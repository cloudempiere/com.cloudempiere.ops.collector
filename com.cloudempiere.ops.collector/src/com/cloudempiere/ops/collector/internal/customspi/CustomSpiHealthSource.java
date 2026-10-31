/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal.customspi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.compiere.util.CLogger;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;

import com.cloudempiere.ops.collector.internal.EventDispatcher;
import com.cloudempiere.ops.collector.internal.customspi.SpiTransitionTracker.Transition;

/**
 * Checks that every live CLDE SPI interface (found by {@link SpiPackageScanner}, ADR-123's
 * {@code com.cloudempiere.*.spi} bundle-qualified packages) is backed by a registered OSGi
 * service — the runtime proof that CloudEmpiere business logic, not vanilla iDempiere fallback
 * behavior, is actually in effect (SPEC-custom-spi-health, "the register" + "the health check").
 * <p>
 * Through 2026-09-23 this catalog was a hand-maintained {@code SpiRegistry} (83, later 213,
 * entries). Deleted per ADR-123: {@code seam-inventory.py} found it permanently drifted behind
 * {@code Core.java} and the actual SPI packages, because nothing kept it in sync automatically.
 * The catalog is now derived live, every poll, from the packages themselves — it cannot drift.
 * <p>
 * Deliberately checks the raw OSGi service registry (via {@code BundleContext.getServiceReferences
 * (String, String)}, class name as a {@code String} — no compile-time {@code Import-Package} on any
 * seam's package needed, same reasoning {@link SpiPackageScanner} uses for discovery) rather than
 * calling a {@code Core.getXxx()} accessor: several have not migrated to a null-return convention
 * and still fall back to a {@code Default*} instance, which would mask a missing plugin from a
 * caller of the accessor itself.
 * <p>
 * <b>This is the first readiness-*gating* source in this collector.</b> Every other source here
 * (e.g. {@link com.cloudempiere.ops.collector.internal.RuntimeHealthSource},
 * {@link com.cloudempiere.ops.collector.internal.DSComponentSource}'s generic
 * {@code plugin.component.unsatisfied}) is deliberately non-gating — a slow trx or an
 * unsatisfied-but-tier-irrelevant DS component pages via severity but never flips {@code /readyz}.
 * This source is the deliberate exception: the extraction work's whole premise is that these seams
 * are mandatory everywhere, so a missing one means the system should not be considered ready at
 * all, not merely "degraded". See {@code OpsStatusProvider#isReady()}.
 * <p>
 * Transition-only emission (delegated to {@link SpiTransitionTracker}, kept pure/testable): a
 * persistently-missing SPI logs once, not every poll — same spirit as
 * {@link com.cloudempiere.ops.collector.internal.DSComponentSource}.
 *
 * @author CloudEmpiere
 */
@Component(service = CustomSpiHealthSource.class, immediate = true)
public class CustomSpiHealthSource {

	private static final CLogger log = CLogger.getCLogger("com.cloudempiere.ops.collector");

	/** First poll shortly after activation, then every interval — mirrors DSComponentSource. */
	private static final long INITIAL_DELAY_SECONDS = 10L;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private EventDispatcher dispatcher;

	private BundleContext ctx;
	private SpiScanner scanner;
	private ScheduledExecutorService poller;
	private final SpiTransitionTracker tracker = new SpiTransitionTracker();

	/** The most recent poll's full catalog (diagnostics — {@code OpsStatusProvider}). Empty (never
	 * null) before the first poll completes. */
	private volatile List<ScannedSpi> lastScan = List.of();

	/** Fail-closed: unready until at least one poll has completed. */
	private volatile boolean firstPollDone = false;

	@Activate
	void activate(BundleContext ctx) {
		this.ctx = ctx;
		this.scanner = new SpiPackageScanner(ctx);
		long intervalSeconds = envSeconds("OPS_COLLECTOR_CUSTOMSPI_POLL_SECONDS", 30L);
		poller = Executors.newSingleThreadScheduledExecutor(daemonFactory());
		poller.scheduleWithFixedDelay(this::pollQuietly, INITIAL_DELAY_SECONDS, intervalSeconds, TimeUnit.SECONDS);
		log.info("Custom-SPI health poll armed (every " + intervalSeconds + "s, catalog scanned live)");
	}

	@Deactivate
	void deactivate() {
		if (poller != null) poller.shutdownNow();
	}

	/** {@code false} until every required seam has a registered implementation. Fail-closed. */
	public boolean isAllRequiredActive() {
		return firstPollDone && tracker.currentMissing().isEmpty();
	}

	/** Names of currently-missing required seams, sorted. Empty (never null) once armed. */
	public Set<String> missingSpiNames() {
		return tracker.currentMissing();
	}

	/** The full catalog as of the most recent poll (diagnostics). Empty before the first poll. */
	public List<ScannedSpi> lastScan() {
		return lastScan;
	}

	/** Poll wrapper: a poll failure must never kill the scheduled task, nor flip us to "ready". */
	private void pollQuietly() {
		try {
			poll();
		} catch (Throwable t) {
			log.warning("Custom-SPI poll failed: " + t);
		} finally {
			firstPollDone = true;
		}
	}

	private void poll() {
		List<ScannedSpi> seams = scanner.scan();
		lastScan = seams;

		Set<String> missingNow = new HashSet<>();
		for (ScannedSpi seam : seams) {
			if (seam.optional()) continue; // @OptionalSpi or a factory product (ScannedSpi.producedBy) — never gates
			if (!isRegistered(seam.interfaceFqcn())) missingNow.add(seam.name());
		}

		Transition t = tracker.update(missingNow);
		for (String name : t.newlyMissing()) emitMissing(seamByName(seams, name));
		for (String name : t.newlyRecovered()) emitRecovered(seamByName(seams, name));
		if (t.becameAllActive()) emitAllActive();
	}

	private boolean isRegistered(String interfaceFqcn) {
		try {
			ServiceReference<?>[] refs = ctx.getServiceReferences(interfaceFqcn, null);
			return refs != null && refs.length > 0;
		} catch (InvalidSyntaxException e) {
			// null filter never throws; defensive only.
			log.warning("Custom-SPI lookup failed for " + interfaceFqcn + ": " + e);
			return false;
		}
	}

	/**
	 * Every currently-registered OSGi service for {@code interfaceFqcn}, one entry per
	 * {@code {bundle, bundleState, ranking}} — empty (not absent) when nothing is registered, so
	 * a missing seam's event explicitly shows "zero services" rather than leaving the reader to
	 * infer it from silence. For a missing seam this is always empty by construction (that's the
	 * definition of missing); the useful case is a recovered seam, or ad-hoc diagnosis of "is this
	 * really zero, or is the providing bundle just not ACTIVE yet". Public — callable for any
	 * interface FQCN on demand, not only from {@link #poll()}'s own transition events (e.g. a
	 * future {@code /api/v1/ops/spi/{name}} diagnostic endpoint).
	 */
	public List<Map<String, Object>> describeServices(String interfaceFqcn) {
		List<Map<String, Object>> out = new ArrayList<>();
		try {
			ServiceReference<?>[] refs = ctx.getServiceReferences(interfaceFqcn, null);
			if (refs == null) return out;
			for (ServiceReference<?> ref : refs) {
				Bundle providingBundle = ref.getBundle();
				Map<String, Object> m = new HashMap<>();
				m.put("bundle", providingBundle == null ? null : providingBundle.getSymbolicName());
				m.put("bundleState", providingBundle == null ? "UNREGISTERED" : ScannedSpi.stateName(providingBundle.getState()));
				Object ranking = ref.getProperty(Constants.SERVICE_RANKING);
				m.put("ranking", ranking == null ? 0 : ranking);
				out.add(m);
			}
		} catch (InvalidSyntaxException e) {
			// null filter never throws; defensive only.
		}
		return out;
	}

	private static ScannedSpi seamByName(List<ScannedSpi> seams, String name) {
		for (ScannedSpi seam : seams) {
			if (seam.name().equals(name)) return seam;
		}
		throw new IllegalStateException("unknown seam name: " + name); // this poll's own scan is the only source of names
	}

	private void emitMissing(ScannedSpi seam) {
		Map<String, Object> subject = new HashMap<>();
		subject.put("kind", "custom_spi");
		subject.put("interface", seam.interfaceFqcn());
		subject.put("bundle", seam.bundleSymbolicName());
		subject.put("bundleState", seam.bundleStateName());

		Map<String, Object> details = new HashMap<>();
		details.put("services", describeServices(seam.interfaceFqcn())); // always [] here by definition

		dispatcher.emit("ops.customspi.missing", subject, details);
	}

	private void emitRecovered(ScannedSpi seam) {
		Map<String, Object> subject = new HashMap<>();
		subject.put("kind", "custom_spi");
		subject.put("interface", seam.interfaceFqcn());
		subject.put("bundle", seam.bundleSymbolicName());
		subject.put("bundleState", seam.bundleStateName());

		Map<String, Object> details = new HashMap<>();
		details.put("services", describeServices(seam.interfaceFqcn()));

		dispatcher.emit("ops.customspi.recovered", subject, details);
	}

	private void emitAllActive() {
		Map<String, Object> subject = new HashMap<>();
		subject.put("kind", "custom_spi");
		Map<String, Object> details = new HashMap<>();
		details.put("required_count", lastScan.size());
		dispatcher.emit("ops.customspi.all_active", subject, details);
	}

	private static long envSeconds(String name, long fallback) {
		String v = System.getenv(name);
		if (v == null || v.isEmpty()) return fallback;
		try { long n = Long.parseLong(v.trim()); return n > 0 ? n : fallback; }
		catch (NumberFormatException e) { return fallback; }
	}

	private static ThreadFactory daemonFactory() {
		final AtomicLong n = new AtomicLong();
		return r -> {
			Thread t = new Thread(r, "clde-ops-collector-customspi-poll-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		};
	}
}
