/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.compiere.util.CLogger;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

/**
 * Readiness gate on required <b>web contexts</b> (CLD-2583): {@code /ops/readyz} must not go green
 * on a webui tier until the {@code /webui} web application is actually deployed.
 * <p>
 * Why this exists: the webui ALB health check is moving from {@code /webui/} (which renders the
 * full ZK login page on every probe and pins an HTTP session + ZK desktop for 60 min) to
 * {@code /ops/readyz}. Without this gate, readyz would answer 200 as soon as boot + custom SPIs
 * are fine, even if the ZK WAB has not been deployed yet (prod runs
 * {@code -Dops.collector.health.blocking=false}, so {@link BootPhaseTracker} reports READY even
 * with unresolved bundles).
 * <p>
 * Configuration: env {@code OPS_COLLECTOR_REQUIRED_WEB_CONTEXTS} — comma- and/or space-separated
 * context paths, e.g. {@code /webui}. Env rather than SysConfig on purpose: readiness must be
 * evaluable before the DB is reachable (same as every other {@code OPS_COLLECTOR_*} knob). Empty
 * or unset ⇒ {@link #isAllDeployed()} is always {@code true}, so rest/scheduler tiers see no
 * behaviour change.
 * <p>
 * Mechanism: one {@link ServiceTracker} per required context, on a class-agnostic filter
 * {@code (osgi.web.contextpath=<ctx>)} — no Jetty {@code Import-Package}. The Jetty OSGi
 * integration registers each deployed web app as an OSGi service carrying that property.
 * Probes stay in-memory: {@link #isAllDeployed()} reads a counter map — no DB, no HTTP
 * self-call, no session.
 * <p>
 * <b>Jetty version trap:</b> verified against Jetty 12.0.25 (the prod image:
 * {@code org.eclipse.jetty.osgi_12.0.25}, {@code ee8.osgi.boot_12.0.25}). There,
 * {@code OSGiDeployer.processBinding()} first runs {@code doProcessBinding()} (the context is
 * <i>started</i>), THEN {@code OSGiApp.registerAsOSGiService()} registers
 * {@code org.eclipse.jetty.server.handler.ContextHandler} with {@code osgi.web.contextpath} +
 * {@code osgi.web.symbolicname}, then sends the EventAdmin DEPLOYED event. Undeploy sends
 * UNDEPLOYED, then deregisters. So "service present" ⇔ "context started". On Jetty master this
 * logic moved to {@code OSGiDeploymentListener} behind an {@code if (bundle == null)} guard that
 * looks inverted — it would never publish the service for WABs like {@code org.adempiere.ui.zk}.
 * If that ships in a Jetty we upgrade to, this gate stays red forever on webui tiers (fail-closed,
 * so the ALB marks every task unhealthy). Re-verify the registration on any Jetty bump.
 *
 * @author CloudEmpiere
 */
@Component(service = WebContextReadiness.class, immediate = true)
public class WebContextReadiness {

	private static final CLogger log = CLogger.getCLogger("com.cloudempiere.ops.collector");

	static final String ENV_REQUIRED = "OPS_COLLECTOR_REQUIRED_WEB_CONTEXTS";
	static final String CONTEXT_PATH_PROPERTY = "osgi.web.contextpath";

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private EventDispatcher dispatcher;

	/** Required context → number of currently-registered matching web-app services. */
	private final Map<String, AtomicInteger> deployed = new ConcurrentHashMap<>();
	private final List<ServiceTracker<Object, ServiceReference<Object>>> trackers = new ArrayList<>();
	private volatile List<String> required = List.of();

	@Activate
	void activate(BundleContext ctx) {
		start(ctx, parseContexts(System.getenv(ENV_REQUIRED)));
	}

	/** Package-private for tests: {@link #activate} minus the env read. */
	void start(BundleContext ctx, List<String> contexts) {
		required = List.copyOf(contexts);
		for (String c : required) deployed.put(c, new AtomicInteger());
		for (String c : required) {
			ServiceTracker<Object, ServiceReference<Object>> t =
					new ServiceTracker<>(ctx, filterFor(c), new Customizer(c));
			trackers.add(t);
			t.open(); // already-deployed contexts are delivered synchronously here
		}
		if (required.isEmpty()) {
			log.info("Web-context readiness: no required contexts (" + ENV_REQUIRED + " unset) — not gating");
		} else {
			log.info("Web-context readiness armed for " + required);
		}
	}

	@Deactivate
	void deactivate() {
		for (ServiceTracker<?, ?> t : trackers) {
			try { t.close(); } catch (RuntimeException e) { /* framework stopping; nothing to do */ }
		}
		trackers.clear();
	}

	/** {@code true} when every required context is deployed; always {@code true} if none required. */
	public boolean isAllDeployed() {
		for (String c : required) {
			AtomicInteger n = deployed.get(c);
			if (n == null || n.get() <= 0) return false;
		}
		return true;
	}

	/** Diagnostics for {@code OpsStatusProvider.plugins()}. */
	public Map<String, Object> status() {
		List<String> up = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		for (String c : required) {
			AtomicInteger n = deployed.get(c);
			if (n != null && n.get() > 0) up.add(c); else missing.add(c);
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("required", required);
		m.put("deployed", up);
		m.put("missing", missing);
		m.put("all_deployed", missing.isEmpty());
		return m;
	}

	/**
	 * Splits on commas and whitespace, drops blanks, normalises to a single leading {@code /}
	 * and no trailing {@code /} (Jetty publishes {@code /webui}, an operator may write
	 * {@code webui} or {@code /webui/}). De-duplicated, order preserved.
	 */
	static List<String> parseContexts(String raw) {
		if (raw == null || raw.isBlank()) return List.of();
		Set<String> out = new LinkedHashSet<>();
		for (String token : raw.split("[,\\s]+")) {
			String c = token.trim();
			while (c.endsWith("/")) c = c.substring(0, c.length() - 1);
			while (c.startsWith("/")) c = c.substring(1);
			if (c.isEmpty()) continue;
			out.add("/" + c);
		}
		return Collections.unmodifiableList(new ArrayList<>(out));
	}

	/** Matches the context with or without the leading slash, whatever service class publishes it. */
	static Filter filterFor(String context) {
		String bare = escape(context.substring(1));
		String f = "(|(" + CONTEXT_PATH_PROPERTY + "=/" + bare + ")(" + CONTEXT_PATH_PROPERTY + "=" + bare + "))";
		try {
			return FrameworkUtil.createFilter(f);
		} catch (InvalidSyntaxException e) {
			throw new IllegalArgumentException("invalid web context '" + context + "': " + e.getMessage(), e);
		}
	}

	private static String escape(String v) {
		StringBuilder sb = new StringBuilder(v.length());
		for (char ch : v.toCharArray()) {
			if (ch == '\\' || ch == '(' || ch == ')' || ch == '*') sb.append('\\');
			sb.append(ch);
		}
		return sb.toString();
	}

	private void onChange(String context, ServiceReference<?> ref, boolean nowDeployed) {
		String state = nowDeployed ? "deployed" : "undeployed";
		Object bsn = ref.getProperty("osgi.web.symbolicname");
		log.info("web context " + context + " " + state + (bsn != null ? " (" + bsn + ")" : ""));
		try {
			Map<String, Object> subject = new HashMap<>();
			subject.put("kind", "web_context");
			subject.put("context", context);
			if (bsn != null) subject.put("bundle", String.valueOf(bsn));
			Map<String, Object> details = new HashMap<>();
			details.put("all_deployed", isAllDeployed());
			dispatcher.emit("ops.webcontext." + state, subject, details);
		} catch (Throwable t) {
			log.warning("web-context event emit failed: " + t); // best-effort; never affects readiness
		}
	}

	/** Tracks references only — never calls {@code getService}, so nothing to unget. */
	private final class Customizer implements ServiceTrackerCustomizer<Object, ServiceReference<Object>> {
		private final String context;

		Customizer(String context) {
			this.context = context;
		}

		@Override
		public ServiceReference<Object> addingService(ServiceReference<Object> ref) {
			if (deployed.get(context).incrementAndGet() == 1) onChange(context, ref, true);
			return ref;
		}

		@Override
		public void modifiedService(ServiceReference<Object> ref, ServiceReference<Object> tracked) {
			// still matches the filter (non-matching modifications arrive as removedService)
		}

		@Override
		public void removedService(ServiceReference<Object> ref, ServiceReference<Object> tracked) {
			if (deployed.get(context).decrementAndGet() == 0) onChange(context, ref, false);
		}
	}
}
