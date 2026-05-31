/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal;

import java.util.HashMap;
import java.util.Map;

import org.compiere.util.CLogger;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;

/**
 * Live source of bundle + framework lifecycle events, per ADR-001 §4.4.
 * <p>
 * Registers a {@link BundleListener} and {@link FrameworkListener} at activation and
 * forwards normalized events to the {@link EventDispatcher}. Per §4.14 the listener
 * callbacks must not block — this scaffold forwards synchronously to the dispatcher's
 * stdout floor (cheap); the bounded async dispatch queue is a TODO for the full build.
 *
 * @author CloudEmpiere
 */
@Component(immediate = true)
public class BundleLifecycleSource implements BundleListener, FrameworkListener {

	private static final CLogger log = CLogger.getCLogger("com.cloudempiere.ops.collector");

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private EventDispatcher dispatcher;

	private BundleContext ctx;

	@Activate
	void activate(BundleContext ctx) {
		this.ctx = ctx;
		ctx.addBundleListener(this);
		ctx.addFrameworkListener(this);
		log.info("Bundle + framework listeners armed");
	}

	@Deactivate
	void deactivate() {
		if (ctx != null) {
			ctx.removeBundleListener(this);
			ctx.removeFrameworkListener(this);
		}
	}

	@Override
	public void bundleChanged(BundleEvent event) {
		// AUDIT: stop/start during deploy is expected and notable (§5).
		if (event.getType() == BundleEvent.STOPPED || event.getType() == BundleEvent.STARTED) {
			Bundle b = event.getBundle();
			Map<String, Object> subject = new HashMap<>();
			subject.put("kind", "bundle");
			subject.put("bundle", b.getSymbolicName());
			subject.put("bundle_version", String.valueOf(b.getVersion()));
			Map<String, Object> details = new HashMap<>();
			details.put("bundle_event", event.getType() == BundleEvent.STARTED ? "STARTED" : "STOPPED");
			dispatcher.emit("plugin.lifecycle.recovered", subject, details);
		}
	}

	@Override
	public void frameworkEvent(FrameworkEvent event) {
		// PANIC: a framework-level ERROR is whole-container scope (§5, §8).
		if (event.getType() == FrameworkEvent.ERROR) {
			Bundle b = event.getBundle();
			Map<String, Object> subject = new HashMap<>();
			subject.put("kind", "framework");
			subject.put("bundle", b != null ? b.getSymbolicName() : "unknown");
			Map<String, Object> details = new HashMap<>();
			Throwable t = event.getThrowable();
			details.put("failure_message", t != null ? String.valueOf(t.getMessage()) : "FrameworkEvent ERROR");
			dispatcher.emit("plugin.framework.error", subject, details);
		}
	}
}
