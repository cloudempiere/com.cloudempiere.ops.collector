/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import org.compiere.util.CLogger;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.event.EventAdmin;

import com.cloudempiere.ops.collector.api.OpsEvent;
import com.cloudempiere.ops.collector.api.Severity;

/**
 * Fan-out of operational events to the sinks, per ADR-001 §4.10.
 * <p>
 * <b>The stdout floor rule:</b> every event logs to stdout first — the one egress
 * Docker/ECS guarantees — and only then attempts richer best-effort sinks. No sink
 * failure can pressure another, and no sink failure can wedge the collector.
 * <p>
 * <b>Scaffold status:</b> only sink #1 (stdout, via {@link CLogger} which routes to the
 * iDempiere log stream) is wired. The remaining sinks from §4.10 are TODO:
 * <ul>
 *   <li>#2 OTel API → OTLP — Phase 3, once the OSGi-wrapped OTel SDK bundles are in the
 *       target platform (§4.11).</li>
 *   <li>#3 OSGi {@code EventAdmin} → in-JVM bus for other bundles.</li>
 *   <li>log4j2 JSON layout — replace the hand-built JSON below once the log4j2 fragment
 *       ships (§4.13). All events emit at level {@code ERROR}; severity is a JSON field.</li>
 * </ul>
 *
 * @author CloudEmpiere
 */
@Component(service = EventDispatcher.class, immediate = true)
public class EventDispatcher {

	/** Single logger name for the whole bundle (ADR-001 §4.13). */
	private static final CLogger log = CLogger.getCLogger("com.cloudempiere.ops.collector");

	/** OSGi EventAdmin topic base; per-event type is appended (dots → slashes). */
	private static final String TOPIC_BASE = "com/cloudempiere/ops/collector";

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private SeverityClassifier severityClassifier;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private TenantEnricher tenantEnricher;

	/** Optional in-JVM bus (sink #3). Absent ⇒ stdout floor still works (sink isolation). */
	@Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
	private volatile EventAdmin eventAdmin;

	/** Bounded in-memory ring buffer backing GET /ops/v1/events (§4.7). Guarded by itself. */
	private final Deque<OpsEvent> ring = new ArrayDeque<>();
	private int ringMax = 5000;

	@Activate
	void activate() {
		ringMax = envInt("OPS_COLLECTOR_EVENT_BUFFER_SIZE", 5000);
		log.info("CloudEmpiere ops collector EventDispatcher active (stdout floor armed, ring=" + ringMax + ")");
	}

	/** Most-recent events, newest last, capped at {@code max} (for GET /ops/v1/events). */
	public List<OpsEvent> recentEvents(int max) {
		synchronized (ring) {
			int n = ring.size();
			int from = (max > 0 && max < n) ? n - max : 0;
			List<OpsEvent> out = new ArrayList<>(n - from);
			int i = 0;
			for (OpsEvent e : ring) {
				if (i++ >= from) out.add(e);
			}
			return out;
		}
	}

	private static int envInt(String name, int fallback) {
		String v = System.getenv(name);
		if (v == null || v.isEmpty()) return fallback;
		try { int n = Integer.parseInt(v.trim()); return n > 0 ? n : fallback; }
		catch (NumberFormatException e) { return fallback; }
	}

	/**
	 * Emit by type — the preferred path. Severity is derived by {@link SeverityClassifier} so
	 * emitters never hardcode it, and the tenant block is added by {@link TenantEnricher}.
	 */
	public void emit(String eventType, Map<String, Object> subject, Map<String, Object> details) {
		emit(severityClassifier.classify(eventType), eventType, subject, details);
	}

	/** Build an envelope (with tenant enrichment) and emit it, with an explicit severity. */
	public void emit(Severity severity, String eventType,
			Map<String, Object> subject, Map<String, Object> details) {
		OpsEvent.Builder b = OpsEvent.builder(eventType, severity)
				.eventId(UUID.randomUUID().toString())
				.timestamp(Instant.now().toString())
				.subject(subject)
				.details(details);
		Map<String, Object> tenant = currentTenant();
		if (tenant != null) b.tenant(tenant);
		emit(b.build());
	}

	/** Tenant block ({@code ad_client_id} only) when the current thread carries tenant context. */
	private Map<String, Object> currentTenant() {
		int clientId = tenantEnricher.currentClientId();
		if (clientId < 0) return null;
		Map<String, Object> tenant = new HashMap<>();
		tenant.put("ad_client_id", clientId);
		return tenant;
	}

	/**
	 * Emit an event. Sink isolation: the stdout floor (#1) always runs; every richer sink
	 * is wrapped so its failure is logged and swallowed (never propagated).
	 */
	public void emit(OpsEvent event) {
		// 0. Ring buffer (bounded) for GET /ops/v1/events.
		synchronized (ring) {
			ring.addLast(event);
			while (ring.size() > ringMax) ring.removeFirst();
		}

		// 1. FLOOR — always. Routes to the iDempiere log stream → container stdout.
		log.log(Level.SEVERE, toJson(event));

		// 2. TODO Phase 3: otel.emit(event)  — wrap in try/catch, log.warn on failure.

		// 3. In-JVM bus (best-effort, isolated): post async so the hot path never blocks,
		// and any EventAdmin failure is swallowed — it must never pressure the floor.
		EventAdmin ea = this.eventAdmin;
		if (ea != null) {
			try {
				ea.postEvent(toOsgiEvent(event));
			} catch (Throwable t) {
				log.log(Level.WARNING, "EventAdmin sink down", t);
			}
		}
		// No DB write on the hot path — the DB sink is a separate async EventAdmin consumer.
	}

	/** Build an OSGi event: hierarchical topic per event type + flat properties for filtering,
	 *  plus the typed {@link OpsEvent} under {@code ops_event} for in-JVM consumers. */
	private static org.osgi.service.event.Event toOsgiEvent(OpsEvent e) {
		Map<String, Object> props = new HashMap<>();
		props.put("event_id", e.getEventId());
		props.put("event_type", e.getEventType());
		props.put("severity", e.getSeverity() == null ? null : e.getSeverity().name());
		props.put("timestamp", e.getTimestamp());
		props.put("ops_event", e);
		String topic = TOPIC_BASE + "/" + (e.getEventType() == null ? "unknown" : e.getEventType().replace('.', '/'));
		return new org.osgi.service.event.Event(topic, props);
	}

	/**
	 * Minimal hand-built JSON for the stdout floor. Intentionally dependency-free for the
	 * scaffold; replaced by the log4j2 JSON layout (§4.13) when the fragment ships.
	 */
	private static String toJson(OpsEvent e) {
		StringBuilder sb = new StringBuilder(256);
		sb.append('{');
		field(sb, "schema_version", e.getSchemaVersion()).append(',');
		field(sb, "event_id", e.getEventId()).append(',');
		field(sb, "event_type", e.getEventType()).append(',');
		field(sb, "severity", e.getSeverity() == null ? null : e.getSeverity().name()).append(',');
		field(sb, "timestamp", e.getTimestamp()).append(',');
		field(sb, "aggregation_hint", e.getAggregationHint());
		// subject/details/source intentionally summarized for the scaffold floor.
		sb.append('}');
		return sb.toString();
	}

	private static StringBuilder field(StringBuilder sb, String key, String value) {
		sb.append('"').append(key).append("\":");
		if (value == null) {
			sb.append("null");
		} else {
			sb.append('"').append(escape(value)).append('"');
		}
		return sb;
	}

	private static String escape(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"")
				.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
	}
}
