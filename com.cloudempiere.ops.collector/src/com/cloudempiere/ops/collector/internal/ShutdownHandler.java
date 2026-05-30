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
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;

/**
 * Graceful shutdown / drain (ADR-001 §4.4 / §4.7 / §4.17.4). A JVM shutdown hook traps
 * SIGTERM (Fargate task stop), immediately flips {@link #isDraining()} so {@code /readyz}
 * starts returning 503 — the ALB then deregisters this task and stops sending new traffic —
 * waits {@code OPS_COLLECTOR_SHUTDOWN_DRAIN_SECONDS} for in-flight work to finish, and emits
 * the bracketing {@code task.draining} / {@code task.terminating} lifecycle events (§4.17.4)
 * so consumers can deprioritise drain-phase events when reasoning about current fleet health.
 * <p>
 * Ownership (per §10.7): this flips <i>ALB readiness</i>; ZK session continuity stays with
 * {@code ceKillSession} (ADR-007) — they compose. Keep the drain window ≤ the ECS task
 * {@code stopTimeout} or ECS SIGKILLs before the hook finishes.
 *
 * @author CloudEmpiere
 */
@Component(service = ShutdownHandler.class, immediate = true)
public class ShutdownHandler {

	private static final CLogger log = CLogger.getCLogger("com.cloudempiere.ops.collector");

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private EventDispatcher dispatcher;

	private volatile boolean draining;
	private long drainSeconds;
	private Thread hook;

	@Activate
	void activate() {
		drainSeconds = envSeconds("OPS_COLLECTOR_SHUTDOWN_DRAIN_SECONDS", 30L);
		hook = new Thread(this::onShutdown, "clde-ops-collector-shutdown");
		Runtime.getRuntime().addShutdownHook(hook);
		log.info("Shutdown handler armed (drain " + drainSeconds + "s)");
	}

	@Deactivate
	void deactivate() {
		// Remove the hook only if the bundle is stopped without a JVM exit in progress.
		if (hook != null) {
			try { Runtime.getRuntime().removeShutdownHook(hook); }
			catch (IllegalStateException shuttingDown) { /* JVM already exiting — leave it */ }
		}
	}

	/** True once SIGTERM has been received; {@code /readyz} must return 503 while true. */
	public boolean isDraining() {
		return draining;
	}

	/** Runs on JVM shutdown (SIGTERM). Flip readiness, drain, then signal termination. */
	private void onShutdown() {
		if (draining) return;
		draining = true;
		try {
			dispatcher.emit("task.draining", taskSubject(), new HashMap<>());
		} catch (Throwable t) {
			// best-effort — never block shutdown on a telemetry failure
		}
		// Give the ALB time to observe /readyz=503 and stop routing, and in-flight work to drain.
		try {
			Thread.sleep(drainSeconds * 1000L);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		}
		try {
			dispatcher.emit("task.terminating", taskSubject(), new HashMap<>());   // best-effort, last event
		} catch (Throwable t) {
			// ignore
		}
	}

	private Map<String, Object> taskSubject() {
		Map<String, Object> s = new HashMap<>();
		s.put("kind", "task");
		return s;
	}

	private static long envSeconds(String name, long fallback) {
		String v = System.getenv(name);
		if (v == null || v.isEmpty()) return fallback;
		try { long n = Long.parseLong(v.trim()); return n >= 0 ? n : fallback; }
		catch (NumberFormatException e) { return fallback; }
	}
}
