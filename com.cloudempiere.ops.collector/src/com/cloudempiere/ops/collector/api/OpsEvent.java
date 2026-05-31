/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.api;

import java.util.Collections;
import java.util.Map;

/**
 * Immutable operational event envelope, per ADR-001 §4.8.
 * <p>
 * This is the stable contract consumers (dashboard-bff, CLDE-Hub, the bridge Lambda)
 * depend on, which is why it lives in the exported {@code com.cloudempiere.ops.collector.api}
 * package. The envelope is schema-versioned ({@link #SCHEMA_VERSION}); evolve via the
 * {@code schema_version} field rather than breaking the shape.
 * <p>
 * Java 11 target → a plain immutable class (not a record). Built via {@link Builder}.
 * Serialization to the §4.8 JSON shape is the dispatcher's concern, not this type's.
 *
 * @author CloudEmpiere
 */
public final class OpsEvent {

	/** Current envelope schema version. Frozen after Phase 2 runs in prod for one month (§7). */
	public static final String SCHEMA_VERSION = "1.0";

	private final String eventId;
	private final String eventType;
	private final Severity severity;
	private final String timestamp;
	private final Map<String, Object> source;
	private final Map<String, Object> tenant;
	private final Map<String, Object> subject;
	private final Map<String, Object> details;
	private final String aggregationHint;
	private final String traceId;
	private final String spanId;
	private final String dedupeHash;

	private OpsEvent(Builder b) {
		this.eventId = b.eventId;
		this.eventType = b.eventType;
		this.severity = b.severity;
		this.timestamp = b.timestamp;
		this.source = immutable(b.source);
		this.tenant = immutable(b.tenant);
		this.subject = immutable(b.subject);
		this.details = immutable(b.details);
		this.aggregationHint = b.aggregationHint;
		this.traceId = b.traceId;
		this.spanId = b.spanId;
		this.dedupeHash = b.dedupeHash;
	}

	private static Map<String, Object> immutable(Map<String, Object> m) {
		return m == null ? Collections.emptyMap() : Collections.unmodifiableMap(m);
	}

	public String getSchemaVersion()      { return SCHEMA_VERSION; }
	public String getEventId()            { return eventId; }
	public String getEventType()          { return eventType; }
	public Severity getSeverity()         { return severity; }
	public String getTimestamp()          { return timestamp; }
	public Map<String, Object> getSource()  { return source; }
	public Map<String, Object> getTenant()  { return tenant; }
	public Map<String, Object> getSubject() { return subject; }
	public Map<String, Object> getDetails() { return details; }
	/** {@code per_task} | {@code per_cluster} | {@code per_tenant} (§4.17.2). */
	public String getAggregationHint()    { return aggregationHint; }
	public String getTraceId()            { return traceId; }
	public String getSpanId()             { return spanId; }
	public String getDedupeHash()         { return dedupeHash; }

	public static Builder builder(String eventType, Severity severity) {
		return new Builder(eventType, severity);
	}

	/** Fluent builder for {@link OpsEvent}. */
	public static final class Builder {
		private String eventId;
		private final String eventType;
		private final Severity severity;
		private String timestamp;
		private Map<String, Object> source;
		private Map<String, Object> tenant;
		private Map<String, Object> subject;
		private Map<String, Object> details;
		private String aggregationHint = "per_task";
		private String traceId;
		private String spanId;
		private String dedupeHash;

		private Builder(String eventType, Severity severity) {
			this.eventType = eventType;
			this.severity = severity;
		}

		public Builder eventId(String v)        { this.eventId = v; return this; }
		public Builder timestamp(String v)      { this.timestamp = v; return this; }
		public Builder source(Map<String, Object> v)  { this.source = v; return this; }
		public Builder tenant(Map<String, Object> v)  { this.tenant = v; return this; }
		public Builder subject(Map<String, Object> v) { this.subject = v; return this; }
		public Builder details(Map<String, Object> v) { this.details = v; return this; }
		public Builder aggregationHint(String v) { this.aggregationHint = v; return this; }
		public Builder traceId(String v)        { this.traceId = v; return this; }
		public Builder spanId(String v)         { this.spanId = v; return this; }
		public Builder dedupeHash(String v)     { this.dedupeHash = v; return this; }

		public OpsEvent build() { return new OpsEvent(this); }
	}
}
