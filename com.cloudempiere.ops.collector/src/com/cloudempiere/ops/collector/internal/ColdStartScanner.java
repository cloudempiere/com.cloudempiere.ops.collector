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
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;

/**
 * One-shot scan of every bundle's state at collector activation, per ADR-001 §4.4.
 * <p>
 * This runs in {@code @Activate}, <i>before</i> {@link BundleLifecycleSource}'s listeners
 * are armed, so it captures bundles that already failed to resolve/start during the first
 * seconds of container boot (the window listeners would otherwise miss). It reports
 * <i>state</i>, not the original exception (§7, accepted limitation for v1).
 * <p>
 * <b>Scaffold status:</b> covers the OSGi bundle dimension. The DS-component dimension
 * (the unique "ACTIVE but broken" signal) is TODO — it needs {@code ServiceComponentRuntime}
 * (§4.6), which {@code DSComponentSource} will own once that service is on the target platform.
 *
 * @author CloudEmpiere
 */
@Component(immediate = true)
public class ColdStartScanner {

	private static final CLogger log = CLogger.getCLogger("com.cloudempiere.ops.collector");

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private EventDispatcher dispatcher;

	@Activate
	void activate(BundleContext ctx) {
		int active = 0, installed = 0, resolved = 0, other = 0;
		for (Bundle b : ctx.getBundles()) {
			switch (b.getState()) {
				case Bundle.ACTIVE:    active++; break;
				case Bundle.INSTALLED: installed++; emitUnresolved(b); break;
				case Bundle.RESOLVED:  resolved++; break;
				default:               other++; break;
			}
		}
		log.info(String.format(
				"Cold-start scan: %d ACTIVE, %d RESOLVED, %d INSTALLED(unresolved), %d other",
				active, resolved, installed, other));
		// TODO §4.4: ServiceComponentRuntime poll for DS component state (UNSATISFIED /
		// FAILED_ACTIVATION) — the part that justifies an in-JVM collector over log parsing.
	}

	/** A bundle stuck in INSTALLED never resolved — PANIC per §5. */
	private void emitUnresolved(Bundle b) {
		Map<String, Object> subject = new HashMap<>();
		subject.put("kind", "bundle");
		subject.put("bundle", b.getSymbolicName());
		subject.put("bundle_version", String.valueOf(b.getVersion()));
		Map<String, Object> details = new HashMap<>();
		details.put("state", "INSTALLED");
		dispatcher.emit("plugin.resolution.failed", subject, details);
	}
}
