/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal.customspi;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.compiere.util.CLogger;
import org.osgi.framework.BundleContext;
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
 * Checks that every extraction-work SPI seam in {@link SpiRegistry} is backed by a registered OSGi
 * service — the runtime proof that CloudEmpiere business logic, not vanilla iDempiere fallback
 * behavior, is actually in effect (SPEC-custom-spi-health, "the register" + "the health check").
 * <p>
 * Deliberately checks the raw OSGi service registry (via {@code BundleContext.getServiceReferences
 * (String, String)}, class name as a {@code String} — no compile-time {@code Import-Package} on the
 * seam's package needed) rather than calling {@code Core.getXxx()}: several accessors have not yet
 * migrated to the "Option B" null-return convention and still fall back to a {@code Default*}
 * instance, which would mask a missing plugin from a caller of the accessor itself.
 * <p>
 * <b>This is the first readiness-*gating* source in this collector.</b> Every other source here
 * (e.g. {@link com.cloudempiere.ops.collector.internal.RuntimeHealthSource},
 * {@link com.cloudempiere.ops.collector.internal.DSComponentSource}'s generic
 * {@code plugin.component.unsatisfied}) is deliberately non-gating — a slow trx or an
 * unsatisfied-but-tier-irrelevant DS component pages via severity but never flips {@code /readyz}.
 * This source is the deliberate exception: the extraction work's whole premise is that these 83
 * seams are mandatory everywhere, so a missing one means the system should not be considered ready
 * at all, not merely "degraded". See {@code OpsStatusProvider#isReady()}.
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
	private ScheduledExecutorService poller;
	private final SpiTransitionTracker tracker = new SpiTransitionTracker();

	/** Fail-closed: unready until at least one poll has completed. */
	private volatile boolean firstPollDone = false;

	@Activate
	void activate(BundleContext ctx) {
		this.ctx = ctx;
		long intervalSeconds = envSeconds("OPS_COLLECTOR_CUSTOMSPI_POLL_SECONDS", 30L);
		poller = Executors.newSingleThreadScheduledExecutor(daemonFactory());
		poller.scheduleWithFixedDelay(this::pollQuietly, INITIAL_DELAY_SECONDS, intervalSeconds, TimeUnit.SECONDS);
		log.info("Custom-SPI health poll armed (every " + intervalSeconds + "s, " + SpiRegistry.all().size()
				+ " required seams)");
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
		Set<String> missingNow = new HashSet<>();
		for (RequiredSpi seam : SpiRegistry.all()) {
			if (seam.optional()) continue; // verified zero implementers anywhere — never gates
			if (!isRegistered(seam.interfaceFqcn())) missingNow.add(seam.name());
		}

		Transition t = tracker.update(missingNow);
		for (String name : t.newlyMissing()) emitMissing(seamByName(name));
		for (String name : t.newlyRecovered()) emitRecovered(seamByName(name));
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

	private static RequiredSpi seamByName(String name) {
		for (RequiredSpi seam : SpiRegistry.all()) {
			if (seam.name().equals(name)) return seam;
		}
		throw new IllegalStateException("unknown seam name: " + name); // registry is the only source of names
	}

	private void emitMissing(RequiredSpi seam) {
		Map<String, Object> subject = new HashMap<>();
		subject.put("kind", "custom_spi");
		subject.put("interface", seam.interfaceFqcn());
		subject.put("accessor", seam.accessorName());

		Map<String, Object> details = new HashMap<>();
		details.put("core_files", seam.coreFiles());

		dispatcher.emit("ops.customspi.missing", subject, details);
	}

	private void emitRecovered(RequiredSpi seam) {
		Map<String, Object> subject = new HashMap<>();
		subject.put("kind", "custom_spi");
		subject.put("interface", seam.interfaceFqcn());
		subject.put("accessor", seam.accessorName());
		dispatcher.emit("ops.customspi.recovered", subject, new HashMap<>());
	}

	private void emitAllActive() {
		Map<String, Object> subject = new HashMap<>();
		subject.put("kind", "custom_spi");
		Map<String, Object> details = new HashMap<>();
		details.put("required_count", SpiRegistry.all().size());
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
