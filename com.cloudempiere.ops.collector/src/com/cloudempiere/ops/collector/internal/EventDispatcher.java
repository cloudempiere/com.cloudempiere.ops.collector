/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import org.compiere.util.CLogger;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;

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

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private SeverityClassifier severityClassifier;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private TenantEnricher tenantEnricher;

	@Activate
	void activate() {
		log.info("CloudEmpiere ops collector EventDispatcher active (stdout floor armed)");
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
		// 1. FLOOR — always. Routes to the iDempiere log stream → container stdout.
		log.log(Level.SEVERE, toJson(event));

		// 2. TODO Phase 3: otel.emit(event)  — wrap in try/catch, log.warn on failure.
		// 3. TODO: eventAdmin.postEvent(toOsgiEvent(event)) — wrap in try/catch.
		// No DB write on the hot path — the DB sink is a separate async EventAdmin consumer.
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
