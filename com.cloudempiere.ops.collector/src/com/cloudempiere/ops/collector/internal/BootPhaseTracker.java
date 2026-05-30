/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.compiere.util.CLogger;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.runtime.ServiceComponentRuntime;
import org.osgi.service.component.runtime.dto.ComponentConfigurationDTO;
import org.osgi.service.component.runtime.dto.ComponentDescriptionDTO;

/**
 * Tracks the container boot through ordered phases and serves the {@code /ops/v1/startup}
 * model (ADR-001 §4.4 / §4.7). The phase is inferred from live bundle + DS state on a short
 * poll: unresolved bundles ⇒ {@code bundle_resolution}; bundles up but DS not all satisfied ⇒
 * {@code ds_activation}; all resolved + DS satisfied ⇒ {@code ready}. Each transition emits an
 * {@code ops.boot.*} event; failing to reach {@code ready} within
 * {@code OPS_COLLECTOR_BOOT_STARTUP_TIMEOUT_SECONDS} emits PANIC {@code ops.boot.stalled}.
 * <p>
 * Exposed as a service so the future {@code OpsRestResource} can serve {@link #snapshot()}
 * for {@code /ops/v1/startup} and derive {@code /startupz} (200 once phase ≥ {@code ready}).
 *
 * @author CloudEmpiere
 */
@Component(service = BootPhaseTracker.class, immediate = true)
public class BootPhaseTracker {

	private static final CLogger log = CLogger.getCLogger("com.cloudempiere.ops.collector");
	private static final int PHASE_TOTAL = 6;

	/** Ordered boot phases (number is 1-based, matching the §4.7 example where ds_activation=3/6). */
	enum Phase {
		OSGI_FRAMEWORK(1, "osgi_framework"),
		BUNDLE_RESOLUTION(2, "bundle_resolution"),
		DS_ACTIVATION(3, "ds_activation"),
		READY(4, "ready"),
		WARMUP(5, "warmup"),      // driven externally by WarmupRunner (Strategy B)
		COMPLETE(6, "complete");
		final int number; final String id;
		Phase(int n, String id) { this.number = n; this.id = id; }
	}

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private EventDispatcher dispatcher;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private ServiceComponentRuntime scr;

	private BundleContext ctx;
	private ScheduledExecutorService poller;

	private volatile Instant startedAt;
	private volatile Phase phase = Phase.OSGI_FRAMEWORK;
	private volatile boolean stalledEmitted;
	private int readyStableTicks;
	private long timeoutSeconds;

	// Latest computed progress + blockers (for the snapshot). Guarded by 'this'.
	private final Map<String, Object> progress = new LinkedHashMap<>();
	private final List<Map<String, Object>> blockers = new ArrayList<>();
	private final List<Map<String, Object>> history = new ArrayList<>();
	private volatile Instant phaseStartedAt;

	@Activate
	void activate(BundleContext ctx) {
		this.ctx = ctx;
		this.startedAt = Instant.now();
		this.phaseStartedAt = startedAt;
		this.timeoutSeconds = envSeconds("OPS_COLLECTOR_BOOT_STARTUP_TIMEOUT_SECONDS", 300L);
		emitPhaseStarted(Phase.OSGI_FRAMEWORK);
		poller = Executors.newSingleThreadScheduledExecutor(daemonFactory());
		poller.scheduleWithFixedDelay(this::tickQuietly, 2L, 2L, TimeUnit.SECONDS);
		log.info("Boot-phase tracker armed (startup timeout " + timeoutSeconds + "s)");
	}

	@Deactivate
	void deactivate() {
		if (poller != null) poller.shutdownNow();
	}

	private void tickQuietly() {
		try { tick(); } catch (Throwable t) { log.warning("boot tick failed: " + t); }
	}

	private void tick() {
		Phase computed = computePhaseAndProgress();

		if (computed.number > phase.number || (computed == Phase.READY && phase != Phase.READY)) {
			transitionTo(computed);
		}

		if (phase == Phase.READY) {
			// Require a couple of stable ready ticks before declaring COMPLETE.
			if (++readyStableTicks >= 2) {
				transitionTo(Phase.COMPLETE);
				dispatcher.emit("ops.boot.completed", bootSubject(), snapshot());
				if (poller != null) poller.shutdown();   // boot done; stop polling
			}
		} else {
			readyStableTicks = 0;
			// Stall detection: never reached ready within the budget.
			if (!stalledEmitted && Duration.between(startedAt, Instant.now()).getSeconds() > timeoutSeconds) {
				stalledEmitted = true;
				dispatcher.emit("ops.boot.stalled", bootSubject(), snapshot());
			}
		}
	}

	/** Recompute progress counts/blockers and infer the phase. Updates the snapshot fields. */
	private synchronized Phase computePhaseAndProgress() {
		int bTotal = 0, bActive = 0, bUnresolved = 0;
		for (Bundle b : ctx.getBundles()) {
			bTotal++;
			if (b.getState() == Bundle.ACTIVE) bActive++;
			else if (b.getState() == Bundle.INSTALLED) bUnresolved++;
		}

		int dsTotal = 0, dsSatisfied = 0, dsPending = 0, dsFailed = 0;
		blockers.clear();
		Collection<ComponentDescriptionDTO> descs = scr.getComponentDescriptionDTOs();
		for (ComponentDescriptionDTO desc : descs) {
			for (ComponentConfigurationDTO cc : scr.getComponentConfigurationDTOs(desc)) {
				dsTotal++;
				switch (cc.state) {
					case ComponentConfigurationDTO.SATISFIED:
					case ComponentConfigurationDTO.ACTIVE:
						dsSatisfied++;
						break;
					case ComponentConfigurationDTO.FAILED_ACTIVATION:
						dsFailed++;
						addBlocker(desc, "ds_component_failed");
						break;
					default: // UNSATISFIED_*
						dsPending++;
						addBlocker(desc, "ds_component_unsatisfied");
						break;
				}
			}
		}

		progress.clear();
		progress.put("bundles_total", bTotal);
		progress.put("bundles_active", bActive);
		progress.put("bundles_pending", bUnresolved);
		progress.put("ds_components_total", dsTotal);
		progress.put("ds_components_satisfied", dsSatisfied);
		progress.put("ds_components_pending", dsPending);
		progress.put("ds_components_failed", dsFailed);

		if (bUnresolved > 0) return Phase.BUNDLE_RESOLUTION;
		if (dsPending > 0 || dsFailed > 0) return Phase.DS_ACTIVATION;
		return Phase.READY;
	}

	private void addBlocker(ComponentDescriptionDTO desc, String kind) {
		if (blockers.size() >= 50) return;   // bound the snapshot
		Map<String, Object> b = new LinkedHashMap<>();
		b.put("kind", kind);
		b.put("name", desc.name);
		b.put("bundle", desc.bundle != null ? desc.bundle.symbolicName : "unknown");
		blockers.add(b);
	}

	private synchronized void transitionTo(Phase next) {
		if (next == phase) return;
		long durationSec = Duration.between(phaseStartedAt, Instant.now()).getSeconds();
		Map<String, Object> hist = new LinkedHashMap<>();
		hist.put("phase", phase.id);
		hist.put("duration_seconds", durationSec);
		history.add(hist);
		// completed previous phase
		Map<String, Object> details = new HashMap<>(snapshotProgress());
		details.put("duration_seconds", durationSec);
		dispatcher.emit("ops.boot.phase_completed", phaseSubject(phase), details);

		phase = next;
		phaseStartedAt = Instant.now();
		emitPhaseStarted(next);
	}

	private void emitPhaseStarted(Phase p) {
		dispatcher.emit("ops.boot.phase_started", phaseSubject(p), new HashMap<>(snapshotProgress()));
	}

	// ---- snapshot (the /ops/v1/startup model) ----

	/** @return true once the boot has reached the {@code ready} phase (for {@code /startupz}). */
	public boolean isReady() {
		return phase == Phase.READY || phase == Phase.COMPLETE;
	}

	/** Rich boot status, the §4.7 {@code /ops/v1/startup} shape. */
	public synchronized Map<String, Object> snapshot() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("state", phase == Phase.COMPLETE ? "complete" : (phase == Phase.READY ? "ready" : "starting"));
		m.put("phase", phase.id);
		m.put("phase_number", phase.number);
		m.put("phase_total", PHASE_TOTAL);
		m.put("started_at", startedAt != null ? startedAt.toString() : null);
		m.put("elapsed_seconds", startedAt == null ? 0 : Duration.between(startedAt, Instant.now()).getSeconds());
		m.put("progress", new LinkedHashMap<>(progress));
		m.put("blockers", new ArrayList<>(blockers));
		m.put("history", new ArrayList<>(history));
		return m;
	}

	private synchronized Map<String, Object> snapshotProgress() {
		return new LinkedHashMap<>(progress);
	}

	private Map<String, Object> phaseSubject(Phase p) {
		Map<String, Object> s = new HashMap<>();
		s.put("kind", "boot_phase");
		s.put("phase", p.id);
		s.put("phase_number", p.number);
		return s;
	}

	private Map<String, Object> bootSubject() {
		Map<String, Object> s = new HashMap<>();
		s.put("kind", "boot");
		return s;
	}

	private static long envSeconds(String name, long fallback) {
		String v = System.getenv(name);
		if (v == null || v.isEmpty()) return fallback;
		try { long n = Long.parseLong(v.trim()); return n > 0 ? n : fallback; }
		catch (NumberFormatException e) { return fallback; }
	}

	private static ThreadFactory daemonFactory() {
		return r -> {
			Thread t = new Thread(r, "clde-ops-collector-boot-tracker");
			t.setDaemon(true);
			return t;
		};
	}
}
