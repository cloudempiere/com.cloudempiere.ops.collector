/******************************************************************************
 * Product: CloudEmpiere Webui Trace Enrichment                                *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 *****************************************************************************/
package com.cloudempiere.ops.trace.webui.internal;

import java.util.EnumSet;
import java.util.Set;

import javax.servlet.DispatcherType;
import javax.servlet.FilterRegistration;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.compiere.util.CLogger;

/**
 * Registers {@link WebuiTraceFilter} on the webui WAB's {@code /zkau} path at servlet-context startup,
 * <b>without</b> touching the iDempiere core {@code web.xml}.
 * <p>
 * The webui ({@code org.adempiere.ui.zk}) is a WAB on Jetty 10 OSGi with {@code org.eclipse.jetty.annotations}
 * active, so Jetty scans {@code META-INF/services/javax.servlet.ServletContainerInitializer} on the host
 * bundle classloader — which includes this fragment's resources (we are {@code Fragment-Host:
 * org.adempiere.ui.zk}). This SCI is discovered there and runs at WAB init, where
 * {@link ServletContext#addFilter} is still legal.
 * <p>
 * Self-diagnosing: emits a bounded WARNING on {@link #onStartup}. If that line never appears in the webui
 * log on boot, Jetty did not scan this fragment's SCI and we fall back to the core {@code web.xml}
 * registration (ADR-001 §11.4). Best-effort throughout — never breaks WAB startup.
 *
 * @author CloudEmpiere
 */
public class WebuiTraceFilterInitializer implements ServletContainerInitializer {

	private static final CLogger log = CLogger.getCLogger("com.cloudempiere.ops.trace.webui");

	@Override
	public void onStartup(Set<Class<?>> c, ServletContext ctx) throws ServletException {
		try {
			// Guard against double-registration if Jetty invokes the SCI more than once.
			if (ctx.getFilterRegistration("opsTraceWebuiFilter") != null) {
				log.warning("[ops.trace.webui] SCI onStartup: opsTraceWebuiFilter already present on context="
						+ ctx.getContextPath());
				return;
			}
			FilterRegistration.Dynamic reg = ctx.addFilter("opsTraceWebuiFilter", new WebuiTraceFilter());
			if (reg == null) {
				log.warning("[ops.trace.webui] SCI onStartup: addFilter returned null on context="
						+ ctx.getContextPath() + " — filter NOT registered");
				return;
			}
			reg.setAsyncSupported(true);
			reg.addMappingForUrlPatterns(
					EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC),
					false,                       // isMatchAfter=false → run before the app's own filters
					"/zkau", "/zkau/*");
			log.warning("[ops.trace.webui] SCI onStartup: registered opsTraceWebuiFilter on /zkau, /zkau/* (context="
					+ ctx.getContextPath() + ")");
		} catch (Throwable t) {
			// never break WAB startup
			log.log(java.util.logging.Level.WARNING,
					"[ops.trace.webui] SCI onStartup failed (filter not registered): " + t, t);
		}
	}
}
