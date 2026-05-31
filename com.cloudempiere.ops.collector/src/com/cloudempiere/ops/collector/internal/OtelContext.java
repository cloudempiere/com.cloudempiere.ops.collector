/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

/**
 * Reads the current OpenTelemetry span's trace/span ids for event correlation (ADR-001 §4.8:
 * "every event MUST carry trace_id / span_id so dashboards can jump event → OTel trace → logs").
 * <p>
 * Compiles against the <b>unshaded</b> {@code io.opentelemetry.api}, provided by the
 * {@code com.cloudempiere.ops.lib.opentelemetry} bundle. The OTel Java agent bridges
 * {@link Span#current()} to live spans at runtime; when the agent is off (or this runs on a
 * background thread with no active span) {@code getSpanContext().isValid()} is {@code false} and
 * we return {@code null}. (The earlier reflection shim on {@code Class.forName("io.opentelemetry…")}
 * could never work — the agent only carries a <i>shaded</i> copy; CLD-1912 §11.4.)
 *
 * @author CloudEmpiere
 */
final class OtelContext {

	private OtelContext() {}

	/** {@code [traceId, spanId]} of the current valid span, or {@code null} if none / no agent. */
	static String[] currentTraceAndSpan() {
		try {
			SpanContext ctx = Span.current().getSpanContext();
			if (ctx == null || !ctx.isValid()) return null;
			return new String[] { ctx.getTraceId(), ctx.getSpanId() };
		} catch (Throwable t) {
			return null; // correlation is best-effort — never let it disrupt event emission
		}
	}
}
