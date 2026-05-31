/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal;

import java.lang.reflect.Method;

/**
 * Reads the current OpenTelemetry span's trace/span ids for event correlation (ADR-001 §4.8:
 * "every event MUST carry trace_id / span_id so dashboards can jump event → OTel trace → logs").
 * <p>
 * Reflection-only over {@code io.opentelemetry.api.trace.Span} / {@code SpanContext}, for the
 * same reason as the webui {@code OtelSpan} shim: the OTel API is provided <b>at runtime by the
 * Java agent</b> (reachable via the image's {@code osgi.compatibility.bootdelegation=true}), not
 * as an OSGi bundle on the target platform — so we take no compile/Import dependency on it and
 * <b>no-op silently</b> when the agent is absent or there is no active span (background threads).
 * <p>
 * This is the trace-correlation half of the OTel integration; the full OTLP <i>emit</i> sink
 * (events → OTel Logs/Metrics, §4.11) needs the OTel API as a real OSGi contract — an
 * OSGi-wrapped OTel API bundle in the target platform (orbinson) plus
 * {@code org.osgi.framework.system.packages.extra=io.opentelemetry.api,…} in the image config.
 *
 * @author CloudEmpiere
 */
final class OtelContext {

	private static final Method M_CURRENT;
	private static final Method M_GET_SPAN_CONTEXT;
	private static final Method M_IS_VALID;
	private static final Method M_TRACE_ID;
	private static final Method M_SPAN_ID;
	private static final boolean AVAILABLE;

	static {
		Method current = null, getCtx = null, isValid = null, traceId = null, spanId = null;
		boolean ok = false;
		try {
			Class<?> span = Class.forName("io.opentelemetry.api.trace.Span");
			Class<?> spanContext = Class.forName("io.opentelemetry.api.trace.SpanContext");
			current = span.getMethod("current");
			getCtx = span.getMethod("getSpanContext");
			isValid = spanContext.getMethod("isValid");
			traceId = spanContext.getMethod("getTraceId");
			spanId = spanContext.getMethod("getSpanId");
			ok = true;
		} catch (Throwable t) {
			// OTel agent not present — correlation ids simply stay null
		}
		M_CURRENT = current;
		M_GET_SPAN_CONTEXT = getCtx;
		M_IS_VALID = isValid;
		M_TRACE_ID = traceId;
		M_SPAN_ID = spanId;
		AVAILABLE = ok;
	}

	private OtelContext() {}

	/** {@code [traceId, spanId]} of the current valid span, or {@code null} if none / no agent. */
	static String[] currentTraceAndSpan() {
		if (!AVAILABLE) return null;
		try {
			Object span = M_CURRENT.invoke(null);
			if (span == null) return null;
			Object ctx = M_GET_SPAN_CONTEXT.invoke(span);
			if (ctx == null || !((Boolean) M_IS_VALID.invoke(ctx))) return null;
			return new String[] { (String) M_TRACE_ID.invoke(ctx), (String) M_SPAN_ID.invoke(ctx) };
		} catch (Throwable t) {
			return null;
		}
	}
}
