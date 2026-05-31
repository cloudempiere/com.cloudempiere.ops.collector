/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.api;

import java.util.List;
import java.util.Map;

/**
 * Stable read-only facade over the collector's internal state, exported so the REST transport
 * (a separate {@code rest.api}-coupled bundle) and other consumers can serve {@code /ops/v1/*}
 * (ADR-001 §4.7) <b>without</b> coupling the tier-agnostic collector core to the REST plugin.
 * Registered as an OSGi service; consumers look it up.
 *
 * @author CloudEmpiere
 */
public interface OpsStatusService {

	/** Cheap liveness — true if the collector is running ({@code /livez}). No dependency checks. */
	boolean isAlive();

	/** Full readiness ({@code /readyz}): boot reached ready AND not draining (SIGTERM). */
	boolean isReady();

	/** Boot complete ({@code /startupz}): boot reached the ready phase. */
	boolean isStartupComplete();

	/** Rich boot status — the {@code /ops/v1/startup} shape (state/phase/progress/blockers/history). */
	Map<String, Object> startup();

	/** Bundle + DS-component snapshot — the {@code /ops/v1/plugins} shape (the unique signal). */
	Map<String, Object> plugins();

	/** Recent events from the in-memory ring buffer, newest last, capped at {@code max}. */
	List<OpsEvent> events(int max);

	/** Run active warmup validation (Strategy B) and return the result. Mutates the runtime. */
	Map<String, Object> warmup();
}
