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

import org.compiere.model.MSysConfig;
import org.compiere.util.CLogger;
import org.compiere.util.WebUtil;
import org.idempiere.broadcast.BroadCastMsg;
import org.idempiere.broadcast.BroadCastUtil;
import org.idempiere.broadcast.BroadcastMsgUtil;
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
 * Ownership (per §10.7): this flips <i>ALB readiness</i> AND — when
 * {@code OPS_COLLECTOR_BROADCAST_ON_SHUTDOWN} is on (default) — broadcasts the ZK
 * session-timeout banner to <b>this node's own desktops, in-process</b>, the instant
 * SIGTERM arrives (see {@link #broadcastLocalSessionTimeout()}). This supersedes the
 * external {@code ceKillSession}/drain-first call (ADR-007) for the deploy/scale path:
 * the broadcast now originates <i>where the session lives</i>, so there is no external
 * wiring, no Cognito token, and no serverName/FQDN targeting to get right — the node
 * warns its own users as it goes down. The external REST {@code /kill-current-session}
 * path stays for ad-hoc/admin kicks. Keep the drain window ≤ the ECS task
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
	private long broadcastCountdownSeconds;
	private boolean broadcastOnShutdown;
	private Thread hook;

	@Activate
	void activate() {
		// Drain window: how long the task lingers after SIGTERM before the JVM exits.
		// Must stay ≤ the ECS task stopTimeout (120s on Fargate) or ECS SIGKILLs first.
		// Resolution order: AD_SysConfig (UI-editable, read at boot) → env var → default.
		drainSeconds = cfgSeconds("OPS_COLLECTOR_SHUTDOWN_DRAIN_SECONDS", 30L);
		// Banner countdown: the TimeoutPanel "save your work" timer shown to users
		// (msg.intData). Independent of the drain window, but CLAMPED to ≤ drainSeconds —
		// a countdown longer than the drain window never completes (the JVM exits first),
		// so the user gets an abrupt cutoff anyway. Defaults to the drain window.
		broadcastCountdownSeconds = cfgSeconds("OPS_COLLECTOR_BROADCAST_COUNTDOWN_SECONDS", drainSeconds);
		if (broadcastCountdownSeconds > drainSeconds)
			broadcastCountdownSeconds = drainSeconds;
		// In-process session-timeout broadcast is WEBUI-ONLY — only the webui tier holds ZK
		// desktops to warn. rest/scheduler/preview have no sessions, so the broadcast is a
		// no-op there; gate it out explicitly (also avoids touching ZK on non-webui tiers).
		// Default ON for webui; OPS_COLLECTOR_BROADCAST_ON_SHUTDOWN=false forces fallback to
		// the external ceKillSession/drain-first path (ADR-007).
		broadcastOnShutdown = isWebuiTier()
				&& !"false".equalsIgnoreCase(System.getenv("OPS_COLLECTOR_BROADCAST_ON_SHUTDOWN"));
		hook = new Thread(this::onShutdown, "clde-ops-collector-shutdown");
		Runtime.getRuntime().addShutdownHook(hook);
		log.info("Shutdown handler armed (drain " + drainSeconds + "s, banner countdown "
				+ broadcastCountdownSeconds + "s, webui=" + isWebuiTier()
				+ ", in-process broadcast=" + broadcastOnShutdown + ")");
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
		// Warn this node's ZK desktops NOW (in-process), so users get the countdown
		// banner during the drain window instead of an abrupt sessiontimeout.zul.
		if (broadcastOnShutdown) {
			broadcastLocalSessionTimeout();
		}
		// Give the ALB time to observe /readyz=503 and stop routing, the banner to
		// play out, and in-flight work to drain.
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

	/**
	 * Warn THIS node's ZK desktops in-process, the instant SIGTERM arrives, so users see
	 * the {@code TimeoutPanel} countdown during the drain window instead of an abrupt
	 * {@code sessiontimeout.zul} on their next click.
	 * <p>
	 * Local-only ({@code pushToQueue(msg, true)}) — no cluster publish, no REST, no
	 * Cognito token, and no serverName/FQDN matching to get right: the node broadcasts to
	 * its own desktops as it goes down. {@code EVENT_SESSION_ONNODE_TIMEOUT} with
	 * {@code target = WebUtil.getServerName()} pops the panel on every local desktop
	 * (DefaultDesktop sets {@code currSession = WebUtil.getServerName()} for this event,
	 * which trivially equals our own target). {@code intData = broadcastCountdownSeconds}
	 * (≤ drain window) so the TimeoutPanel timer fires before the JVM exits.
	 * Best-effort — never blocks shutdown.
	 */
	private void broadcastLocalSessionTimeout() {
		try {
			BroadCastMsg msg = new BroadCastMsg();
			msg.setEventId(BroadCastUtil.EVENT_SESSION_ONNODE_TIMEOUT);
			msg.setTarget(WebUtil.getServerName());
			msg.setIntData((int) broadcastCountdownSeconds);
			BroadcastMsgUtil.pushToQueue(msg, true); // local-only — this node's desktops
			log.info("In-process session-timeout broadcast to local ZK desktops (countdown " + broadcastCountdownSeconds + "s)");
		} catch (Throwable t) {
			log.warning("Local session-timeout broadcast failed (non-fatal): " + t);
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

	/**
	 * Resolution order: AD_SysConfig (same key, UI-editable) → env var → built-in default.
	 * Read once at {@code activate()} (boot) — the DB is up then; we cannot read AD_SysConfig
	 * during SIGTERM (the pool is closing), so a UI change applies on the task's NEXT start.
	 * Best-effort: a missing key / not-yet-ready DB falls through to the env/default.
	 */
	private static long cfgSeconds(String key, long fallback) {
		try {
			int v = MSysConfig.getIntValue(key, Integer.MIN_VALUE);
			if (v != Integer.MIN_VALUE && v >= 0) return v;
		} catch (Throwable t) {
			// DB not ready / key absent — fall through to env, then default
		}
		return envSeconds(key, fallback);
	}

	/** True only on the webui tier (the only tier with ZK desktops to warn). Reads the
	 *  task-def {@code IDEMPIERE_TIER_NAME} (e.g. webui-tier-stg / rest-tier-stg). */
	private static boolean isWebuiTier() {
		String tier = System.getenv("IDEMPIERE_TIER_NAME");
		return tier != null && tier.toLowerCase().contains("webui");
	}
}
