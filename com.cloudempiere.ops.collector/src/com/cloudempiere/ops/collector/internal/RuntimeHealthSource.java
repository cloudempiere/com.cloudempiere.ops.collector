/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.compiere.util.CLogger;
import org.compiere.util.Trx;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.condition.Condition;

/**
 * Runtime data-layer health source (SPEC-runtime-health-source, Phase 1 — zero DB cost). Surfaces
 * the "ACTIVE is a lie" pathology that ALB/ECS — and even our own {@code /readyz} — cannot see: a
 * JVM that is structurally degrading underneath because a {@link Trx} is wedged open holding a DB
 * connection + locks, or the HikariCP pool is draining toward exhaustion.
 * <p>
 * Two in-JVM scans per poll, both zero-SQL:
 * <ol>
 *   <li><b>Long-running transactions</b> — {@link Trx#getOpenTransactions()}; per trx {@code age =
 *       now − getStartTime()}. iDempiere's native {@code TrxMonitor} only acts at the 120-min hard
 *       timeout (one buried WARNING + forced rollback); we surface long trx at a configurable,
 *       far lower threshold as a structured event with the originating stack trace.</li>
 *   <li><b>Connection-pool pressure</b> — {@link PoolStatsShim} (reflection over the Hikari pool
 *       MXBean; no compile dependency).</li>
 * </ol>
 * Severity (ADR-001 §5): a single slow trx / transient pool spike is recoverable → ALERT; a leak
 * <i>storm</i> or pool <i>exhaustion</i> is fleet-level (every request will soon block) → PANIC;
 * recovery → AUDIT. All emit through the dispatcher's stdout floor first, and the Gotcha-B
 * {@code levelFor()} mapping means ALERT/AUDIT log at WARNING/INFO — so no event here triggers
 * {@code MIssue.create} and we never reintroduce the leak we are watching for.
 * <p>
 * <b>Non-gating by default</b> (§7): {@code /readyz} stays strictly A/B/C-infra. The getters below
 * are exposed for an opt-in readiness contribution only.
 *
 * @author CloudEmpiere
 */
@Component(service = RuntimeHealthSource.class, immediate = true)
public class RuntimeHealthSource {

	private static final CLogger log = CLogger.getCLogger("com.cloudempiere.ops.collector");

	private static final long DEFAULT_POLL_SECONDS = 60L;
	private static final long DEFAULT_TRX_WARN_SECONDS = 300L;       // 5 min
	private static final long DEFAULT_TRX_CRITICAL_SECONDS = 3600L;  // 60 min — ½ the 120-min hard timeout
	private static final int DEFAULT_TRX_LEAK_STORM_COUNT = 10;
	private static final double DEFAULT_POOL_WARN_RATIO = 0.85d;
	/** Truncate originating stack traces to keep events small. */
	private static final int STACK_TRACE_MAX_CHARS = 2000;

	// CLD-1955: gate activation until DB is ready — Trx.getOpenTransactions() and PoolStatsShim need DB pool
	@Reference(target = "(osgi.condition.id=idempiere.db.ready)")
	private Condition dbReady;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private EventDispatcher dispatcher;

	private ScheduledExecutorService poller;

	// thresholds (resolved once at activate)
	private long trxWarnSeconds;
	private long trxCriticalSeconds;
	private int trxLeakStormCount;
	private double poolWarnRatio;

	// cross-poll state
	/** trx names already reported long-running this episode (cleared when the trx closes). */
	private final Set<String> reportedTrx = new HashSet<>();
	private int consecutivePoolWaiting;
	private boolean degraded;       // were we in a non-clean state last poll?

	// exposed snapshot (for an opt-in readiness contribution / status surface)
	private volatile int longRunningTrxCount;
	private volatile boolean trxLeakStorm;
	private volatile int poolWaitingThreads = -1;
	private volatile double poolUtilization = -1d;

	@Activate
	void activate() {
		trxWarnSeconds = envSeconds("OPS_COLLECTOR_TRX_WARN_SECONDS", DEFAULT_TRX_WARN_SECONDS);
		trxCriticalSeconds = envSeconds("OPS_COLLECTOR_TRX_CRITICAL_SECONDS", DEFAULT_TRX_CRITICAL_SECONDS);
		trxLeakStormCount = (int) envSeconds("OPS_COLLECTOR_TRX_LEAK_STORM_COUNT", DEFAULT_TRX_LEAK_STORM_COUNT);
		poolWarnRatio = envRatio("OPS_COLLECTOR_POOL_WARN_RATIO", DEFAULT_POOL_WARN_RATIO);

		long poll = envSeconds("OPS_COLLECTOR_RUNTIME_POLL_SECONDS", DEFAULT_POLL_SECONDS);
		poller = Executors.newSingleThreadScheduledExecutor(daemonFactory());
		poller.scheduleWithFixedDelay(this::pollQuietly, poll, poll, TimeUnit.SECONDS);
		log.info("Runtime-health source armed (trx warn=" + trxWarnSeconds + "s crit=" + trxCriticalSeconds
				+ "s storm=" + trxLeakStormCount + ", pool warn-ratio=" + poolWarnRatio + ", poll=" + poll + "s)");
	}

	@Deactivate
	void deactivate() {
		if (poller != null) poller.shutdownNow();
	}

	/** True when a leak storm is in effect (count over threshold or a trx past the critical age). */
	public boolean hasTrxLeakStorm() {
		return trxLeakStorm;
	}

	/** Count of transactions currently open longer than the warn threshold. */
	public int longRunningTrxCount() {
		return longRunningTrxCount;
	}

	/** Threads currently queued for a pool connection; {@code -1} when the pool can't be read. */
	public int poolWaitingThreads() {
		return poolWaitingThreads;
	}

	/** Pool active/max in [0,1]; {@code -1} when the pool can't be read. */
	public double poolUtilization() {
		return poolUtilization;
	}

	private void pollQuietly() {
		try {
			boolean trxBad = scanTransactions();
			boolean poolBad = scanPool();
			boolean clean = !trxBad && !poolBad;
			if (clean && degraded) {
				Map<String, Object> subject = new HashMap<>();
				subject.put("kind", "runtime");
				Map<String, Object> details = new HashMap<>();
				details.put("recovered", true);
				dispatcher.emit("ops.runtime.ok", subject, details);   // AUDIT — recovery
			}
			degraded = !clean;
		} catch (Throwable t) {
			// never let a poll wedge the framework thread
			log.fine("Runtime-health poll skipped: " + t);
		}
	}

	/** @return true if any trx-level problem (long-running or storm) was observed this poll. */
	private boolean scanTransactions() {
		Trx[] open = Trx.getOpenTransactions();
		long now = System.currentTimeMillis();

		int longRunning = 0;
		long oldestAge = 0L;
		boolean anyCritical = false;
		List<Map<String, Object>> samples = new ArrayList<>();
		Set<String> stillLong = new HashSet<>();

		for (Trx trx : open) {
			if (trx == null) continue;
			Date start = trx.getStartTime();
			if (start == null) continue;
			long ageSec = (now - start.getTime()) / 1000L;
			if (ageSec < trxWarnSeconds) continue;

			longRunning++;
			oldestAge = Math.max(oldestAge, ageSec);
			if (ageSec >= trxCriticalSeconds) anyCritical = true;

			String name = trx.getTrxName();
			stillLong.add(name);
			Map<String, Object> sample = new HashMap<>();
			sample.put("trx_name", name);
			sample.put("age_seconds", ageSec);
			samples.add(sample);

			// emit per-trx ALERT once per episode (until this trx closes), with origin stack
			if (reportedTrx.add(name)) {
				Map<String, Object> subject = new HashMap<>();
				subject.put("kind", "transaction");
				subject.put("trx_name", name);
				Map<String, Object> details = new HashMap<>();
				details.put("age_seconds", ageSec);
				String stack = truncate(safeStack(trx));
				if (stack != null) details.put("stack", stack);
				dispatcher.emit("ops.runtime.trx_long_running", subject, details);  // ALERT
				log.warning("Long-running transaction: " + name + " age=" + ageSec + "s");
			}
		}

		// drop names that have closed so a future re-leak re-reports
		reportedTrx.retainAll(stillLong);

		this.longRunningTrxCount = longRunning;
		boolean storm = longRunning >= trxLeakStormCount || anyCritical;
		this.trxLeakStorm = storm;
		if (storm) {
			Map<String, Object> subject = new HashMap<>();
			subject.put("kind", "transaction");
			Map<String, Object> details = new HashMap<>();
			details.put("long_running_count", longRunning);
			details.put("oldest_age_seconds", oldestAge);
			details.put("threshold_count", trxLeakStormCount);
			details.put("samples", samples);
			dispatcher.emit("ops.runtime.trx_leak_storm", subject, details);  // PANIC — fleet-level
			log.severe("Transaction leak storm: " + longRunning + " long-running (oldest " + oldestAge + "s)");
		}
		return longRunning > 0 || storm;
	}

	/** @return true if any pool-level problem (pressure or exhaustion) was observed this poll. */
	private boolean scanPool() {
		PoolStatsShim.Stats s = PoolStatsShim.read();
		if (!s.known()) {
			poolWaitingThreads = -1;
			poolUtilization = -1d;
			return false;   // can't read pool (e.g. non-Hikari provider) — not a problem signal
		}
		poolWaitingThreads = s.waiting;
		poolUtilization = s.utilization();

		if (s.waiting > 0) consecutivePoolWaiting++; else consecutivePoolWaiting = 0;

		Map<String, Object> fields = new HashMap<>();
		fields.put("active", s.active);
		fields.put("idle", s.idle);
		fields.put("total", s.total);
		fields.put("waiting", s.waiting);
		fields.put("max", s.max);
		fields.put("utilization", round2(s.utilization()));

		if (s.exhausted()) {
			Map<String, Object> subject = new HashMap<>();
			subject.put("kind", "pool");
			dispatcher.emit("ops.runtime.pool_exhausted", subject, fields);  // PANIC — no headroom + queued
			log.severe("Connection pool exhausted: active=" + s.active + "/" + s.max + " waiting=" + s.waiting);
			return true;
		}
		boolean pressure = consecutivePoolWaiting >= 2 || s.utilization() >= poolWarnRatio;
		if (pressure) {
			Map<String, Object> subject = new HashMap<>();
			subject.put("kind", "pool");
			dispatcher.emit("ops.runtime.pool_pressure", subject, fields);   // ALERT
			log.warning("Connection pool pressure: active=" + s.active + "/" + s.max + " waiting=" + s.waiting);
			return true;
		}
		return false;
	}

	private static String safeStack(Trx trx) {
		try {
			String st = trx.getStrackTrace();   // iDempiere spelling
			return (st == null || st.isEmpty()) ? null : st;
		} catch (Throwable t) {
			return null;
		}
	}

	private static String truncate(String s) {
		if (s == null) return null;
		return s.length() <= STACK_TRACE_MAX_CHARS ? s : s.substring(0, STACK_TRACE_MAX_CHARS) + "…";
	}

	private static double round2(double v) {
		return v < 0 ? v : Math.round(v * 100d) / 100d;
	}

	private static long envSeconds(String name, long fallback) {
		String v = System.getenv(name);
		if (v == null || v.isEmpty()) return fallback;
		try { long n = Long.parseLong(v.trim()); return n > 0 ? n : fallback; }
		catch (NumberFormatException e) { return fallback; }
	}

	private static double envRatio(String name, double fallback) {
		String v = System.getenv(name);
		if (v == null || v.isEmpty()) return fallback;
		try { double n = Double.parseDouble(v.trim()); return (n > 0 && n <= 1) ? n : fallback; }
		catch (NumberFormatException e) { return fallback; }
	}

	private static ThreadFactory daemonFactory() {
		return r -> {
			Thread t = new Thread(r, "clde-ops-collector-runtime-health");
			t.setDaemon(true);
			return t;
		};
	}
}
