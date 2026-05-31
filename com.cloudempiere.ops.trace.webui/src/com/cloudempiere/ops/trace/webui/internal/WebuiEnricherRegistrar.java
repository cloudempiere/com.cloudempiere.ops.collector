/******************************************************************************
 * Product: CloudEmpiere Webui Trace Enrichment                                *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 *****************************************************************************/
package com.cloudempiere.ops.trace.webui.internal;

import java.util.logging.Level;

import org.compiere.util.CLogger;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.zkoss.zk.ui.WebApp;
import org.zkoss.zk.ui.WebApps;
import org.zkoss.zk.ui.util.Configuration;

import com.cloudempiere.ops.trace.webui.WebuiEventEnricher;
import com.cloudempiere.ops.trace.webui.WebuiSpanEnricher;

/**
 * Registers the two webui trace enrichers as ZK listeners (ADR-001 §11.1) — programmatically,
 * via the ZK {@link Configuration}, because the earlier {@code metainfo/zk/config.xml} approach
 * does <b>not</b> work here (CLD-1912, §11.4): a fragment-contributed library config is never read
 * by iDempiere's ZK WAB, and iDempiere exposes <b>no OSGi SPI</b> for ZK execution/desktop listeners.
 *
 * <p><b>How this runs despite being in a fragment.</b> A fragment can't have a {@code Bundle-Activator}
 * or an independently-processed DS component — but the host {@code org.adempiere.ui.zk} declares
 * {@code Service-Component: OSGI-INF/*.xml} (a <i>wildcard</i>), and Equinox SCR resolves that against
 * the host's entries <i>including fragment-contributed ones</i>. So this component's descriptor
 * ({@code OSGI-INF/…WebuiEnricherRegistrar.xml}) is picked up and activated as part of the host bundle,
 * with full ZK classpath access. (Same mechanism the host uses for its own ~30 components.)
 *
 * <p><b>Timing.</b> SCR may activate this before ZK's {@link WebApp} (created at servlet init) exists,
 * so {@link WebApps#getCurrent()} can be null at {@code activate()}. We register immediately if we can,
 * otherwise poll briefly on a daemon thread until the {@code WebApp} is up. {@code Configuration}
 * listeners added after init apply to all subsequent executions, so late registration is fine.
 *
 * @author CloudEmpiere
 */
@Component(immediate = true)
public class WebuiEnricherRegistrar {

	private static final CLogger log = CLogger.getCLogger(WebuiEnricherRegistrar.class);
	private static final String MARKER = "[ops.trace.webui]";
	private static final int MAX_WAIT_SECONDS = 120;

	private volatile boolean done = false;

	@Activate
	void activate() {
		if (register()) return;
		// WebApp not ready yet — wait for it without blocking SCR/host activation.
		Thread t = new Thread(this::pollUntilReady, "ops-webui-enricher-registrar");
		t.setDaemon(true);
		t.start();
	}

	private void pollUntilReady() {
		for (int i = 0; i < MAX_WAIT_SECONDS; i++) {
			try {
				Thread.sleep(1000L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			if (register()) return;
		}
		log.log(Level.SEVERE, MARKER + " enricher NOT registered: ZK WebApp unavailable after "
				+ MAX_WAIT_SECONDS + "s — /zkau spans will NOT be enriched (ADR-001 §11.4).");
	}

	private synchronized boolean register() {
		if (done) return true;
		WebApp app = WebApps.getCurrent();
		if (app == null) return false;
		Configuration cfg = app.getConfiguration();
		if (cfg == null) return false;
		// addListener(Class) lets ZK categorize by the listener interfaces each class implements:
		//   WebuiSpanEnricher  -> ExecutionInit + ExecutionCleanup (identity, servlet thread)
		//   WebuiEventEnricher -> EventInterceptor (functional window/tab, ZK event thread)
		try {
			cfg.addListener(WebuiSpanEnricher.class);
			cfg.addListener(WebuiEventEnricher.class);
		} catch (Exception e) {
			done = true; // a thrown addListener won't succeed on retry — stop and surface it
			log.log(Level.SEVERE, MARKER + " enricher registration FAILED — Configuration.addListener threw; "
					+ "/zkau spans will NOT be enriched (ADR-001 §11.4).", e);
			return true;
		}
		done = true;
		// WARNING (not INFO) on purpose: this is the §11.4 deployment-validation checkpoint — it must
		// be greppable in CloudWatch regardless of the configured log threshold, so activation can be
		// confirmed from logs alone, without needing /zkau traffic or an X-Ray query.
		log.log(Level.WARNING, MARKER + " enricher REGISTERED — WebuiSpanEnricher (ExecutionInit) + "
				+ "WebuiEventEnricher (EventInterceptor) added to the ZK WebApp Configuration; /zkau spans will be enriched.");
		return true;
	}
}
