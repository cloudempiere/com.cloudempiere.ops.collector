/******************************************************************************
 * Product: CloudEmpiere Webui Trace Enrichment                                *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 *****************************************************************************/
package com.cloudempiere.ops.trace.webui.internal;

import java.lang.reflect.Method;

import org.compiere.util.CLogger;

/**
 * Tiny reflection shim over the OpenTelemetry API's {@code Span} (ADR-001 §11.1).
 * <p>
 * The OTel API is provided <b>at runtime by the Java agent</b> ({@code -javaagent}), not as
 * an OSGi bundle on the target platform. Rather than take a compile/Import-Package dependency
 * on {@code io.opentelemetry.api} (which would force an OSGi-wrapped OTel bundle into the
 * platform), this shim resolves {@code Span.current().setAttribute(...)} reflectively:
 * <ul>
 *   <li>compiles + resolves against the stock iDempiere platform (no OTel dependency);</li>
 *   <li>binds at runtime when the agent is present — reachable from this OSGi bundle via the
 *       image's {@code -Dosgi.compatibility.bootdelegation=true} (app-classloader fallback);</li>
 *   <li><b>no-ops silently</b> when the agent is absent (e.g. {@code CE_OTEL_ENABLED=false}).</li>
 * </ul>
 * Swap for a direct {@code io.opentelemetry.api} compile dependency once an OSGi-wrapped OTel
 * API bundle (e.g. orbinson/opentelemetry-osgi-wrappers) is in the target platform.
 *
 * @author CloudEmpiere
 */
public final class OtelSpan {

	private static final CLogger log = CLogger.getCLogger("com.cloudempiere.ops.trace.webui");

	private static final Method M_CURRENT;
	private static final Method M_SET_STRING;
	private static final Method M_SET_LONG;
	private static final boolean AVAILABLE;

	static {
		Method current = null, setString = null, setLong = null;
		boolean ok = false;
		try {
			Class<?> span = Class.forName("io.opentelemetry.api.trace.Span");
			current = span.getMethod("current");
			setString = span.getMethod("setAttribute", String.class, String.class);
			setLong = span.getMethod("setAttribute", String.class, long.class);
			ok = true;
			log.info("OpenTelemetry API present — webui span enrichment active");
		} catch (Throwable t) {
			log.info("OpenTelemetry API not present (agent off) — webui span enrichment is a no-op");
		}
		M_CURRENT = current;
		M_SET_STRING = setString;
		M_SET_LONG = setLong;
		AVAILABLE = ok;
	}

	private OtelSpan() {}

	/** True when the OTel agent's API is reachable; lets callers skip work entirely. */
	public static boolean isAvailable() {
		return AVAILABLE;
	}

	/** Set a string attribute on the current span. Null/blank values and any error are ignored. */
	public static void setStr(String key, String value) {
		if (!AVAILABLE || value == null || value.isEmpty()) return;
		try {
			Object span = M_CURRENT.invoke(null);
			if (span != null) M_SET_STRING.invoke(span, key, value);
		} catch (Throwable ignore) {
			// never break the request on a telemetry failure
		}
	}

	/** Set a numeric attribute on the current span. Non-positive ids and any error are ignored. */
	public static void setLong(String key, long value) {
		if (!AVAILABLE || value <= 0) return;
		try {
			Object span = M_CURRENT.invoke(null);
			if (span != null) M_SET_LONG.invoke(span, key, value);
		} catch (Throwable ignore) {
			// never break the request on a telemetry failure
		}
	}
}
