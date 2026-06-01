/******************************************************************************
 * Product: CloudEmpiere Webui Trace Enrichment                                *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 *****************************************************************************/
package com.cloudempiere.ops.trace.webui.internal;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.compiere.util.CLogger;

import io.opentelemetry.api.trace.Span;

/**
 * Servlet {@code Filter} on the {@code /zkau} path — the fix for the span-scope dead end
 * (ADR-001 §11.4). The 4-point DIAG proved {@code Span.current()} is the no-op span at <b>every</b>
 * ZK callback (init / cleanup / beforeProcessEvent / afterProcessEvent all {@code spanValid=false}):
 * the OTel agent records the {@code /zkau} server span (X-Ray has it, with nested JDBC children) but
 * does not make it <i>current</i> in the {@code Context} that ZK's AU engine runs under.
 * <p>
 * A servlet Filter runs <b>inside the agent's instrumented servlet/filter scope</b>, before ZK's
 * execution dispatch loses the context. So this filter:
 * <ol>
 *   <li>reads {@code Span.current()} at the servlet entry and <b>captures the span object</b> onto the
 *       request ({@link #SPAN_ATTR}); and</li>
 *   <li>logs a bounded {@code DIAG filter: spanValid=…} line so we can confirm — from logs — whether
 *       the span really is current here (if it is, the capture works; if not, the agent isn't making it
 *       current at the servlet level either and we move to context-propagation config).</li>
 * </ol>
 * {@code WebuiSpanEnricher#init} then reads {@link #SPAN_ATTR} and stamps that span <b>directly</b>
 * ({@code span.setAttribute(...)}) — never {@code Span.current()}, which is the no-op span there.
 * <p>
 * Registered as an OSGi HTTP Whiteboard {@code javax.servlet.Filter} via the hand-authored
 * {@code OSGI-INF/…WebuiTraceFilter.xml} (picked up by the host {@code org.adempiere.ui.zk}'s wildcard
 * {@code Service-Component}, same mechanism as {@link WebuiEnricherRegistrar}).
 *
 * @author CloudEmpiere
 */
public class WebuiTraceFilter implements Filter {

	private static final CLogger log = CLogger.getCLogger("com.cloudempiere.ops.trace.webui");

	/** Request attribute carrying the agent's {@code /zkau} server span, captured where it IS current. */
	public static final String SPAN_ATTR = "ops.trace.webui.span";

	/** Bound the DIAG to the first dozen requests so we don't spam CloudWatch (§11.4). */
	private static final AtomicInteger DIAG = new AtomicInteger(0);

	@Override
	public void init(FilterConfig filterConfig) {
		log.warning("[ops.trace.webui] WebuiTraceFilter registered on /zkau — capturing the agent span at the servlet scope");
	}

	@Override
	public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
			throws IOException, ServletException {
		try {
			Span span = Span.current();
			boolean valid = span.getSpanContext().isValid();
			if (DIAG.getAndIncrement() < 12) {
				log.warning("[ops.trace.webui] DIAG filter: thread=" + Thread.currentThread().getName()
						+ " spanValid=" + valid);
			}
			// Capture the live span object now — the ZK ExecutionInit reads it later and stamps it
			// DIRECTLY, bypassing Span.current() (the no-op span at every ZK callback).
			if (valid) req.setAttribute(SPAN_ATTR, span);
		} catch (Throwable t) {
			// best-effort — telemetry must never break the request
		}
		chain.doFilter(req, res);
	}

	@Override
	public void destroy() {
		// no-op
	}
}
