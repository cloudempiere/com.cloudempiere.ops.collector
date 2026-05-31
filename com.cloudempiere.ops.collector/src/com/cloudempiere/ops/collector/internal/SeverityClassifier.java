/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal;

import org.osgi.service.component.annotations.Component;

import com.cloudempiere.ops.collector.api.Severity;

/**
 * Single source of truth for event severity (ADR-001 §4.4 / §5). Maps an {@code event_type}
 * to its {@link Severity} tier so emitters don't each hardcode it — the dispatcher classifies
 * once on the way out.
 * <ul>
 *   <li><b>PANIC</b> — framework/container scope: resolution/activation failures, framework
 *       errors, OOM, boot stalled/crash-loop.</li>
 *   <li><b>ALERT</b> — tenant-scoped, blocks business: 2Pack import failed, runtime degradation.</li>
 *   <li><b>AUDIT</b> — notable but expected: recoveries, deploys, boot phases, task/cluster
 *       lifecycle, warmup completion.</li>
 * </ul>
 *
 * @author CloudEmpiere
 */
@Component(service = SeverityClassifier.class)
public class SeverityClassifier {

	/** Classify an event type. Unknown types default to AUDIT (never silently PANIC the fleet). */
	public Severity classify(String eventType) {
		if (eventType == null) return Severity.AUDIT;

		switch (eventType) {
			// framework / container scope
			case "plugin.lifecycle.failed":
			case "plugin.component.failed_activation":
			case "plugin.component.failed":
			case "plugin.resolution.failed":
			case "plugin.descriptor.missing":
			case "plugin.framework.error":
			case "ops.jvm.oom":
			case "ops.boot.stalled":
			case "ops.boot.crash_loop_detected":
				return Severity.PANIC;

			// tenant-scoped, blocks business
			case "plugin.2pack.failed":
			case "ops.runtime.component_degraded":
				return Severity.ALERT;

			// NON-DEFENSIVE (temporary): a DS component reporting UNSATISFIED_REFERENCE is too
			// often benign to page on — it fires transiently during boot, and permanently for
			// plugins that are simply not relevant on this tier (e.g. orderstatus/wcs/dms model
			// factories on the rest tier). Blanket-PANIC here caused an alert flood, so we record
			// it at AUDIT for the dashboard instead of paging. TODO (ADR-001 §5): re-escalate with
			// EVIDENCE only — boot phase COMPLETE + a grace period + a tier-relevance allowlist —
			// rather than the defensive "any unsatisfied ⇒ PANIC" rule.
			case "plugin.component.unsatisfied":
				return Severity.AUDIT;

			default:
				// notable-but-expected: recoveries, deploys, boot phases, task/cluster lifecycle…
				return Severity.AUDIT;
		}
	}
}
