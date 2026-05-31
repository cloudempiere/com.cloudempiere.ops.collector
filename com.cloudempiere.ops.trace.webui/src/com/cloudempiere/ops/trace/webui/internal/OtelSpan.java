/******************************************************************************
 * Product: CloudEmpiere Webui Trace Enrichment                                *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 *****************************************************************************/
package com.cloudempiere.ops.trace.webui.internal;

import org.compiere.util.CLogger;

import io.opentelemetry.api.trace.Span;

/**
 * Stamps iDempiere business context onto the current OpenTelemetry span (ADR-001 §11.1).
 * <p>
 * Compiles against the <b>unshaded</b> {@code io.opentelemetry.api}, provided by the
 * {@code com.cloudempiere.ops.lib.opentelemetry} bundle. The OTel Java agent bridges
 * {@link Span#current()} to the live {@code /zkau} server span at runtime; when the agent is off,
 * {@code Span.current()} is the API's no-op span and {@code setAttribute} is a harmless no-op.
 * <p>
 * (The previous reflection shim on {@code Class.forName("io.opentelemetry.api.trace.Span")} could
 * never resolve — the agent ships only a <i>shaded</i> {@code io.opentelemetry.javaagent.shaded.*}
 * copy and expects the app to bring its own unshaded API, which it then bridges; CLD-1912 §11.4.)
 *
 * @author CloudEmpiere
 */
public final class OtelSpan {

	private static final CLogger log = CLogger.getCLogger("com.cloudempiere.ops.trace.webui");

	static {
		// WARNING (not INFO) on purpose: INFO is filtered from CloudWatch and the in-container log
		// on the webui tier, so this boot/first-use marker must be WARNING to confirm — from logs
		// alone — that the unshaded OTel API resolved (i.e. the lib bundle is wired). §11.4.
		log.warning("[ops.trace.webui] OpenTelemetry API linked (com.cloudempiere.ops.lib.opentelemetry) "
				+ "— webui span enrichment active; the agent bridges Span.current() to live /zkau spans");
	}

	private OtelSpan() {}

	/** Set a string attribute on the current span. Null/blank values and any error are ignored. */
	public static void setStr(String key, String value) {
		if (value == null || value.isEmpty()) return;
		try {
			Span.current().setAttribute(key, value);
		} catch (Throwable ignore) {
			// never break the request on a telemetry failure
		}
	}

	/** Set a numeric attribute on the current span. Non-positive ids and any error are ignored. */
	public static void setLong(String key, long value) {
		if (value <= 0) return;
		try {
			Span.current().setAttribute(key, value);
		} catch (Throwable ignore) {
			// never break the request on a telemetry failure
		}
	}

	/**
	 * Diagnostic (CLD-1912 §11.4): is {@link Span#current()} a <b>valid (recording) server span</b>
	 * at the call site? If false on the servlet thread, the agent isn't bridging the span into this
	 * thread/scope, so {@code setAttribute} silently goes nowhere — distinguishes the guard cause
	 * from the thread/scope cause.
	 */
	public static boolean currentSpanValid() {
		try {
			return Span.current().getSpanContext().isValid();
		} catch (Throwable t) {
			return false;
		}
	}
}
