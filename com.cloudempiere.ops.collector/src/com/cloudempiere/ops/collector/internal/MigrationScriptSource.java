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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;

/**
 * Requirement <b>B</b> (CLD-1912 — "do not mark the server healthy on a migration-script issue"):
 * a failed DB migration must be a first-class signal. iDempiere records SQL migration application in
 * {@code AD_MigrationScript.Status} (reference {@code MigrationScriptStatus}: {@code CO}=Completed,
 * {@code ER}=Error, {@code IP}=In Progress). The entrypoint's {@code RUN_SyncDB} applies pending
 * scripts <i>before</i> the JVM opens, so by the time this DS component activates the outcome is
 * already queryable.
 * <p>
 * One-shot check once the DB is reachable (retried until then): emits {@code ops.migration.failed}
 * (PANIC) when any script is in {@code ER}, {@code ops.migration.in_progress} (ALERT) for a lingering
 * {@code IP}, else {@code ops.migration.ok} (AUDIT). Exposes {@link #hasFailure()} for the readiness
 * gate (requirement C) so {@code /readyz} can fail on a broken DB build. Migrations don't change at
 * runtime, so the poller stops after the first successful check.
 *
 * @author CloudEmpiere
 */
@Component(service = MigrationScriptSource.class, immediate = true)
public class MigrationScriptSource {

	private static final CLogger log = CLogger.getCLogger("com.cloudempiere.ops.collector");

	/** First check after a short delay (let iDempiere finish opening the DB connection). */
	private static final long INITIAL_DELAY_SECONDS = 15L;
	private static final long RETRY_SECONDS = 30L;
	/** ~10 min of DB-not-ready retries, then give up quietly (never wedge). */
	private static final int MAX_ATTEMPTS = 20;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private EventDispatcher dispatcher;

	private ScheduledExecutorService poller;
	private final AtomicInteger attempts = new AtomicInteger();
	private volatile int failedCount = -1;       // -1 = not yet checked
	private volatile int inProgressCount = -1;

	@Activate
	void activate() {
		poller = Executors.newSingleThreadScheduledExecutor(daemonFactory());
		poller.scheduleWithFixedDelay(this::checkQuietly, INITIAL_DELAY_SECONDS, RETRY_SECONDS, TimeUnit.SECONDS);
		log.info("Migration-script source armed (AD_MigrationScript Status check)");
	}

	@Deactivate
	void deactivate() {
		if (poller != null) poller.shutdownNow();
	}

	/** True once a check completed AND found a script in {@code ER}. False while unchecked (fail-open). */
	public boolean hasFailure() {
		return failedCount > 0;
	}

	/** {@code -1} until the first successful check; then the count of {@code AD_MigrationScript} rows in Error. */
	public int failedCount() {
		return failedCount;
	}

	private void checkQuietly() {
		try {
			check();
		} catch (Throwable t) {
			// DB not open yet (or transient) — retry until MAX_ATTEMPTS, then stop quietly.
			if (attempts.incrementAndGet() >= MAX_ATTEMPTS) {
				log.warning("Migration-script check gave up after " + MAX_ATTEMPTS + " attempts: " + t);
				if (poller != null) poller.shutdown();
			}
		}
	}

	private void check() {
		int errors = DB.getSQLValueEx(null, "SELECT COUNT(*) FROM AD_MigrationScript WHERE Status='ER'");
		if (errors < 0) throw new IllegalStateException("AD_MigrationScript not queryable yet");
		int inProgress = DB.getSQLValueEx(null, "SELECT COUNT(*) FROM AD_MigrationScript WHERE Status='IP'");
		this.failedCount = errors;
		this.inProgressCount = Math.max(inProgress, 0);

		Map<String, Object> subject = new HashMap<>();
		subject.put("kind", "migration");

		if (errors > 0) {
			String names = DB.getSQLValueString(null,
					"SELECT string_agg(name, ', ') FROM AD_MigrationScript WHERE Status='ER'");
			Map<String, Object> details = new HashMap<>();
			details.put("failed_count", errors);
			details.put("in_progress_count", inProgressCount);
			if (names != null) details.put("scripts", names);
			dispatcher.emit("ops.migration.failed", subject, details);   // PANIC — do not mark healthy (req B)
			log.warning("Migration scripts in ERROR: " + errors + (names != null ? " [" + names + "]" : ""));
		} else if (inProgressCount > 0) {
			Map<String, Object> details = new HashMap<>();
			details.put("in_progress_count", inProgressCount);
			dispatcher.emit("ops.migration.in_progress", subject, details);
		} else {
			Map<String, Object> details = new HashMap<>();
			details.put("completed_check", true);
			dispatcher.emit("ops.migration.ok", subject, details);
		}

		// One successful check is enough — migrations are applied at boot, not at runtime.
		if (poller != null) poller.shutdown();
	}

	private static ThreadFactory daemonFactory() {
		return r -> {
			Thread t = new Thread(r, "clde-ops-collector-migration-check");
			t.setDaemon(true);
			return t;
		};
	}
}
