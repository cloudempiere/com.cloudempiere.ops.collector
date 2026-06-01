/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.compiere.model.MSysConfig;
import org.compiere.util.CLogger;
import org.compiere.util.Ini;
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
import org.osgi.service.component.runtime.dto.ReferenceDTO;
import org.osgi.service.component.runtime.dto.UnsatisfiedReferenceDTO;

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
	private boolean healthBlocking;

	// Latest computed progress + blockers (for the snapshot). Guarded by 'this'.
	private final Map<String, Object> progress = new LinkedHashMap<>();
	private final List<Map<String, Object>> blockers = new ArrayList<>();
	private final List<Map<String, Object>> history = new ArrayList<>();
	private volatile Instant phaseStartedAt;

	// A1 — native "what matters" inputs for honest blocker classification (ADR-001 §4.4):
	//  • blacklist  = intentionally-disabled components (components.blacklist, IDEMPIERE-6663)  → suppress
	//  • required   = OPS_COLLECTOR_REQUIRED_PLUGINS SysConfig (NF12-shaped)                     → real blocker
	private Set<String> blacklist;                       // file-backed, cached (file is stable)
	private final Set<String> requiredPlugins = new HashSet<>();
	private boolean requiredLoaded;                      // becomes true once MSysConfig is reachable

	@Activate
	void activate(BundleContext ctx) {
		this.ctx = ctx;
		this.startedAt = Instant.now();
		this.phaseStartedAt = startedAt;
		this.timeoutSeconds = envSeconds("OPS_COLLECTOR_BOOT_STARTUP_TIMEOUT_SECONDS", 300L);
		this.healthBlocking = Boolean.getBoolean("ops.collector.health.blocking");
		emitPhaseStarted(Phase.OSGI_FRAMEWORK);
		poller = Executors.newSingleThreadScheduledExecutor(daemonFactory());
		poller.scheduleWithFixedDelay(this::tickQuietly, 2L, 2L, TimeUnit.SECONDS);
		log.info("Boot-phase tracker armed (startup timeout " + timeoutSeconds + "s, health blocking " + healthBlocking + ")");
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

	/** Recompute progress counts/blockers and infer the phase. Updates the snapshot fields.
	 *  A1 (ADR-001 §4.4): classify each broken DS component so readiness gates only on the ones
	 *  that genuinely matter — not the boot-time / tier-irrelevant noise. */
	private synchronized Phase computePhaseAndProgress() {
		int bTotal = 0, bActive = 0, bUnresolved = 0;
		for (Bundle b : ctx.getBundles()) {
			bTotal++;
			if (b.getState() == Bundle.ACTIVE) bActive++;
			else if (b.getState() == Bundle.INSTALLED) bUnresolved++;
		}

		Set<String> blk = blacklist();
		Set<String> req = requiredPlugins();

		// Pass 1: counts + the broken set (and what that set provides, for cascade detection).
		int dsTotal = 0, dsSatisfied = 0, dsPending = 0, dsFailed = 0;
		List<Broken> broken = new ArrayList<>();
		Set<String> brokenProvides = new HashSet<>();
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
						broken.add(new Broken(desc, cc, true));
						addProvides(brokenProvides, desc);
						break;
					default: // UNSATISFIED_*
						dsPending++;
						broken.add(new Broken(desc, cc, false));
						addProvides(brokenProvides, desc);
						break;
				}
			}
		}

		// Pass 2: classify + build the (categorised) blocker list. Only "real" holds readiness.
		blockers.clear();
		int realBlockers = 0;
		for (Broken bk : broken) {
			if (blk.contains(bk.desc.name)) continue;   // SUPPRESS — intentionally disabled (components.blacklist)
			List<Map<String, Object>> missing = missingRefs(bk.desc, bk.cc);
			String category = classify(bk, missing, brokenProvides, req);
			if ("real".equals(category)) realBlockers++;
			addBlocker(bk.desc, bk.failed ? "ds_component_failed" : "ds_component_unsatisfied", category, missing);
		}

		progress.clear();
		progress.put("bundles_total", bTotal);
		progress.put("bundles_active", bActive);
		progress.put("bundles_pending", bUnresolved);
		progress.put("ds_components_total", dsTotal);
		progress.put("ds_components_satisfied", dsSatisfied);
		progress.put("ds_components_pending", dsPending);
		progress.put("ds_components_failed", dsFailed);
		progress.put("ds_components_blocking", realBlockers);   // A1: required-missing or failed_activation only

		if (bUnresolved > 0) {
			if (healthBlocking) return Phase.BUNDLE_RESOLUTION;
			log.warning("BUNDLE_RESOLUTION blockers detected (" + bUnresolved
					+ " unresolved) — continuing to READY (non-blocking mode)");
		}
		if (realBlockers > 0) {
			if (healthBlocking) return Phase.DS_ACTIVATION;
			log.warning("DS_ACTIVATION real blockers detected (" + realBlockers
					+ " required-missing/failed of " + (dsPending + dsFailed)
					+ " unsatisfied) — continuing to READY (non-blocking mode)");
		}
		return Phase.READY;
	}

	/** Classify a broken component (A1). real = failed-activation or a declared-required plugin missing;
	 *  gated = waiting on an {@code osgi.condition}; cascade = waiting on a service another broken
	 *  component should provide; expected = unsatisfied but neither required nor failed (boot/tier noise). */
	private static String classify(Broken bk, List<Map<String, Object>> missing,
			Set<String> brokenProvides, Set<String> required) {
		if (bk.failed) return "real";                                   // @Activate threw — always real
		if (required.contains(bk.desc.name)
				|| (bk.desc.bundle != null && required.contains(bk.desc.bundle.symbolicName)))
			return "real";                                              // declared required → must be up
		for (Map<String, Object> m : missing) {
			String iface = String.valueOf(m.get("interface"));
			String name = String.valueOf(m.get("name"));
			if ((iface != null && iface.endsWith(".Condition"))
					|| (name != null && name.toLowerCase().contains("condition")))
				return "gated";                                         // gated by an osgi.condition (by design)
		}
		for (Map<String, Object> m : missing) {
			Object iface = m.get("interface");
			if (iface != null && brokenProvides.contains(iface))
				return "cascade";                                       // waiting on another broken component
		}
		return "expected";
	}

	/** Missing @Reference names + their declared interface (from the description). */
	private static List<Map<String, Object>> missingRefs(ComponentDescriptionDTO desc, ComponentConfigurationDTO cc) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (cc.unsatisfiedReferences == null) return out;
		for (UnsatisfiedReferenceDTO ur : cc.unsatisfiedReferences) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", ur.name);
			m.put("interface", interfaceForRef(desc, ur.name));
			out.add(m);
		}
		return out;
	}

	private static String interfaceForRef(ComponentDescriptionDTO desc, String refName) {
		if (desc.references != null && refName != null) {
			for (ReferenceDTO r : desc.references) {
				if (refName.equals(r.name)) return r.interfaceName;
			}
		}
		return null;
	}

	private static void addProvides(Set<String> set, ComponentDescriptionDTO desc) {
		if (desc.serviceInterfaces != null) set.addAll(Arrays.asList(desc.serviceInterfaces));
	}

	private void addBlocker(ComponentDescriptionDTO desc, String kind, String category,
			List<Map<String, Object>> missing) {
		if (blockers.size() >= 50) return;   // bound the snapshot
		Map<String, Object> b = new LinkedHashMap<>();
		b.put("kind", kind);
		b.put("category", category);         // real | gated | cascade | expected
		b.put("name", desc.name);
		b.put("bundle", desc.bundle != null ? desc.bundle.symbolicName : "unknown");
		if (!missing.isEmpty()) b.put("missing_references", missing);
		blockers.add(b);
	}

	/** Components intentionally disabled via {@code $IDEMPIERE_HOME/components.blacklist}
	 *  (IDEMPIERE-6663). Read once — the file is static for the JVM's lifetime. */
	private synchronized Set<String> blacklist() {
		if (blacklist == null) {
			Set<String> s = new HashSet<>();
			try {
				String home = Ini.getAdempiereHome();
				if (home != null && !home.isEmpty()) {
					Path f = Paths.get(home, "components.blacklist");
					if (Files.isReadable(f)) {
						for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
							String t = line.trim();
							if (!t.isEmpty() && !t.startsWith("#")) s.add(t);
						}
					}
				}
			} catch (Throwable t) {
				log.warning("components.blacklist read failed (continuing without): " + t);
			}
			blacklist = s;
		}
		return blacklist;
	}

	/** Declared-required plugins ({@code OPS_COLLECTOR_REQUIRED_PLUGINS} SysConfig, comma/space
	 *  separated — NF12-shaped). Loaded once MSysConfig is reachable; retried each tick until then. */
	private synchronized Set<String> requiredPlugins() {
		if (!requiredLoaded) {
			try {
				String v = MSysConfig.getValue("OPS_COLLECTOR_REQUIRED_PLUGINS", "");
				if (v != null) {
					for (String part : v.split("[,\\s]+")) {
						if (!part.trim().isEmpty()) requiredPlugins.add(part.trim());
					}
					requiredLoaded = true;   // MSysConfig reachable → freeze (boot-time config is static)
				}
			} catch (Throwable t) {
				// DB not up yet — retry on the next tick.
			}
		}
		return requiredPlugins;
	}

	/** A broken DS component captured in pass 1 for classification in pass 2. */
	private static final class Broken {
		final ComponentDescriptionDTO desc;
		final ComponentConfigurationDTO cc;
		final boolean failed;
		Broken(ComponentDescriptionDTO desc, ComponentConfigurationDTO cc, boolean failed) {
			this.desc = desc; this.cc = cc; this.failed = failed;
		}
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
