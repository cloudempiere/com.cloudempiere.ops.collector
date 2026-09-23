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
import java.util.Collection;
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
		String[] trace = OtelContext.currentTraceAndSpan();   // §4.8 correlation (no-op without the OTel agent)
		if (trace != null) b.traceId(trace[0]).spanId(trace[1]);
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
		// Log at a level that MATCHES the event severity, NOT a blanket SEVERE. iDempiere's
		// CLogErrorBuffer persists SEVERE records to AD_Issue via MIssue.create — a DB write
		// per event. Blanket-SEVERE meant a boot-time event burst (transiently-UNSATISFIED DS
		// components on the first SCR poll) became an MIssue.create storm that held/leaked
		// HikariCP connections on the bundle-activation threads, starving the REST plugin's
		// own boot-time DB work → /api/v1/health registered past the ALB window → deploy
		// rolled back (CLD-1912 Gotcha B). PANIC stays SEVERE; ALERT/AUDIT drop below it.
		// CloudWatch metric filters key on the JSON $.severity field, not the log level, so
		// alerting is unaffected.
		// Exactly ONE log.log() call here, on purpose -- see the CLD-1912 note above. The line is
		// "<readable summary> | <json>" (2026-09-23), not JSON alone: the summary makes triage
		// possible at a glance in a console/log-tail without reaching for the /events REST
		// endpoint every time, and the JSON after it keeps the line machine-parseable.
		log.log(levelFor(event.getSeverity()), humanReadable(event) + " | " + toJson(event));

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

	/**
	 * Map event severity → JUL log level for the stdout floor (CLD-1912 Gotcha B). Only PANIC
	 * reaches {@code SEVERE} (and thus iDempiere's AD_Issue/{@code MIssue.create} path); ALERT
	 * and AUDIT stay below it so a high-volume event stream never writes to the DB.
	 */
	private static Level levelFor(Severity s) {
		if (s == null) return Level.WARNING;
		switch (s) {
			case PANIC: return Level.SEVERE;
			case ALERT: return Level.WARNING;
			case AUDIT:
			default:    return Level.INFO;
		}
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
	 * One-line, grep-friendly summary: {@code SEVERITY event.type (key=value, key=value, ...)}
	 * from the event's own {@code subject} — generic across every event type this dispatcher
	 * carries, not hand-written per type, so a new event type is readable here for free. Values
	 * are {@code String.valueOf(...)}, not JSON-escaped/quoted — this half of the line is for a
	 * human's eyes, the JSON half after it is for a parser's.
	 */
	private static String humanReadable(OpsEvent e) {
		StringBuilder sb = new StringBuilder();
		sb.append(e.getSeverity() == null ? "UNKNOWN" : e.getSeverity().name());
		sb.append(' ').append(e.getEventType());
		if (e.getSubject() != null && !e.getSubject().isEmpty()) {
			sb.append(" (");
			boolean first = true;
			for (Map.Entry<String, Object> entry : e.getSubject().entrySet()) {
				if (!first) sb.append(", ");
				first = false;
				sb.append(entry.getKey()).append('=').append(entry.getValue());
			}
			sb.append(')');
		}
		return sb.toString();
	}

	/**
	 * Minimal hand-built JSON for the stdout floor. Intentionally dependency-free for the
	 * scaffold; replaced by the log4j2 JSON layout (§4.13) when the fragment ships.
	 * <p>
	 * Includes {@code subject}/{@code details} (fixed 2026-09-23 — a console line reading only
	 * {@code "event_type":"ops.customspi.missing"} with no indication of *which* interface is
	 * useless for triage; the data was always on the event, just never reached the one sink that
	 * exists at PANIC severity before a richer sink is wired up). {@code source} stays out: it's
	 * the {@code OpsEvent} constructor's own fixed metadata (host/pid/tier), not a payload key.
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
		if (e.getTraceId() != null) { sb.append(','); field(sb, "trace_id", e.getTraceId()); }
		if (e.getSpanId() != null) { sb.append(','); field(sb, "span_id", e.getSpanId()); }
		if (e.getSubject() != null && !e.getSubject().isEmpty()) {
			sb.append(",\"subject\":"); writeValue(sb, e.getSubject());
		}
		if (e.getDetails() != null && !e.getDetails().isEmpty()) {
			sb.append(",\"details\":"); writeValue(sb, e.getDetails());
		}
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

	/** Generic JSON serialization for a subject/details value -- these maps carry whatever each
	 * event source puts in them (String, Number, Boolean, nested Map, List/Collection); anything
	 * else falls back to its {@code toString()}, quoted, rather than throwing and losing the
	 * whole floor line over one odd value. */
	@SuppressWarnings("unchecked")
	private static void writeValue(StringBuilder sb, Object v) {
		if (v == null) {
			sb.append("null");
		} else if (v instanceof String) {
			sb.append('"').append(escape((String) v)).append('"');
		} else if (v instanceof Number || v instanceof Boolean) {
			sb.append(v);
		} else if (v instanceof Map) {
			sb.append('{');
			boolean first = true;
			for (Map.Entry<String, Object> entry : ((Map<String, Object>) v).entrySet()) {
				if (!first) sb.append(',');
				first = false;
				sb.append('"').append(escape(String.valueOf(entry.getKey()))).append("\":");
				writeValue(sb, entry.getValue());
			}
			sb.append('}');
		} else if (v instanceof Collection) {
			sb.append('[');
			boolean first = true;
			for (Object item : (Collection<?>) v) {
				if (!first) sb.append(',');
				first = false;
				writeValue(sb, item);
			}
			sb.append(']');
		} else {
			sb.append('"').append(escape(String.valueOf(v))).append('"');
		}
	}

	private static String escape(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"")
				.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
	}
}
