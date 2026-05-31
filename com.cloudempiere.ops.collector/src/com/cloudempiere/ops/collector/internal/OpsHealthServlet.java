/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal;

import java.io.IOException;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import com.cloudempiere.ops.collector.api.OpsStatusService;

/**
 * Unauthenticated liveness/readiness probes served as a Web Application Bundle (ADR-001 §4.7),
 * mounted at {@code /ops/livez}, {@code /ops/readyz}, {@code /ops/startupz} via
 * {@code Web-ContextPath: ops} + {@code WEB-INF/web.xml}.
 * <p>
 * <b>Why a servlet and not the {@code /api/v1/ops} JAX-RS resource:</b> the ALB target-group
 * health check and the Docker {@code HEALTHCHECK} cannot attach an {@code Authorization} header,
 * and the REST app's global {@code CLDERequestFilter} would 401 them. Serving the probes from a
 * WAB on its own context path ({@code /ops/*}) keeps them <i>outside</i> the JAX-RS app entirely,
 * so no auth filter applies — without editing the shared REST plugin. The richer, data-bearing
 * endpoints ({@code startup}/{@code plugins}/{@code events}/{@code warmup}) stay on the
 * authenticated {@code /api/v1/ops/*} JAX-RS resource where the global filter belongs.
 * <p>
 * Co-located in the collector <i>core</i> bundle on purpose: the probe reports the collector's own
 * readiness, so "collector deployed ⇒ probes reachable" with no separate bundle to go missing. The
 * checks read only in-memory collector state (no DB call) — a liveness probe must never depend on
 * the database, or a DB hiccup would get the task killed instead of merely marked unready.
 *
 * @author CloudEmpiere
 */
public class OpsHealthServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		// With Web-ContextPath "ops" and url-patterns /livez|/readyz|/startupz, the probe name
		// is the servlet path. pathInfo is null for an exact mapping.
		String probe = req.getServletPath();
		if (probe == null) probe = "";

		OpsStatusService s = status();
		if (s == null) {
			// Collector core present (this servlet is running) but the status service is not yet
			// registered — treat as not-ready/unavailable rather than guessing alive.
			write(resp, 503, "unavailable");
			return;
		}

		switch (probe) {
			case "/livez":
				if (s.isAlive()) write(resp, 200, "alive");
				else write(resp, 503, "down");
				break;
			case "/readyz":
				if (s.isReady()) write(resp, 200, "ready");
				else write(resp, 503, "unready");
				break;
			case "/startupz":
				if (s.isStartupComplete()) write(resp, 200, "complete");
				else write(resp, 503, "starting");
				break;
			default:
				write(resp, 404, "unknown_probe");
		}
	}

	/** Look up the live DS-managed {@link OpsStatusService} from this (the core) bundle. */
	private static OpsStatusService status() {
		Bundle b = FrameworkUtil.getBundle(OpsHealthServlet.class);
		if (b == null) return null;
		BundleContext ctx = b.getBundleContext();
		if (ctx == null) return null;
		ServiceReference<OpsStatusService> ref = ctx.getServiceReference(OpsStatusService.class);
		return ref == null ? null : ctx.getService(ref);
	}

	/** Minimal dependency-free JSON body, matching the JAX-RS probe shape ({@code {"status":"…"}}). */
	private static void write(HttpServletResponse resp, int code, String status) throws IOException {
		resp.setStatus(code);
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.getWriter().write("{\"status\":\"" + status + "\"}");
	}
}
