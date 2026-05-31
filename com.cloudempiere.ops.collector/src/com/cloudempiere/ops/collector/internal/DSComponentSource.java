/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal;

import java.util.Collection;
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
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.runtime.ServiceComponentRuntime;
import org.osgi.service.component.runtime.dto.ComponentConfigurationDTO;
import org.osgi.service.component.runtime.dto.ComponentDescriptionDTO;
import org.osgi.service.component.runtime.dto.UnsatisfiedReferenceDTO;

/**
 * The unique signal of ADR-001 (§4.4 / §4.9 Strategy A): polls {@link ServiceComponentRuntime}
 * for Declarative Services component state and emits when a component is
 * {@code UNSATISFIED_REFERENCE} or {@code FAILED_ACTIVATION} — the "ACTIVE but broken" class
 * that container/orchestrator health (and {@code ss}) cannot see (a bundle is ACTIVE while its
 * DS components silently never fire).
 * <p>
 * <b>Passive only</b> (Strategy A): introspection via {@code getComponentConfigurationDTOs()};
 * it never forces activation (that is {@code WarmupRunner}, Strategy B). Runs on a single daemon
 * scheduled thread ({@code clde-ops-collector-scr-poll}, §4.14). Emits on <i>state transition</i>
 * (component enters / leaves a bad state) rather than every poll, so a persistent failure logs
 * once, not every {@code OPS_COLLECTOR_SCR_POLL_SECONDS}.
 *
 * @author CloudEmpiere
 */
@Component(immediate = true)
public class DSComponentSource {

	private static final CLogger log = CLogger.getCLogger("com.cloudempiere.ops.collector");

	/** First poll shortly after activation (cold-start DS coverage), then every interval. */
	private static final long INITIAL_DELAY_SECONDS = 10L;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private ServiceComponentRuntime scr;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private EventDispatcher dispatcher;

	private ScheduledExecutorService poller;
	/** Component names currently in a bad state, to emit only on transitions. */
	private final Set<String> unhealthy = new HashSet<>();

	@Activate
	void activate() {
		long intervalSeconds = envSeconds("OPS_COLLECTOR_SCR_POLL_SECONDS", 60L);
		poller = Executors.newSingleThreadScheduledExecutor(daemonFactory());
		poller.scheduleWithFixedDelay(this::pollQuietly, INITIAL_DELAY_SECONDS, intervalSeconds, TimeUnit.SECONDS);
		log.info("DS component SCR poll armed (every " + intervalSeconds + "s)");
	}

	@Deactivate
	void deactivate() {
		if (poller != null) poller.shutdownNow();
	}

	/** Poll wrapper: a poll failure must never kill the scheduled task. */
	private void pollQuietly() {
		try {
			poll();
		} catch (Throwable t) {
			log.warning("SCR poll failed: " + t);
		}
	}

	private void poll() {
		Set<String> badNow = new HashSet<>();
		Collection<ComponentDescriptionDTO> descs = scr.getComponentDescriptionDTOs();
		for (ComponentDescriptionDTO desc : descs) {
			for (ComponentConfigurationDTO cc : scr.getComponentConfigurationDTOs(desc)) {
				if (cc.state == ComponentConfigurationDTO.FAILED_ACTIVATION
						|| cc.state == ComponentConfigurationDTO.UNSATISFIED_REFERENCE
						|| cc.state == ComponentConfigurationDTO.UNSATISFIED_CONFIGURATION) {
					String key = desc.name;
					badNow.add(key);
					if (!unhealthy.contains(key)) {
						emitFailure(desc, cc);    // transition healthy -> bad
					}
				}
			}
		}
		// transitions bad -> healthy (recovered)
		for (String key : unhealthy) {
			if (!badNow.contains(key)) emitRecovered(key);
		}
		unhealthy.clear();
		unhealthy.addAll(badNow);
	}

	private void emitFailure(ComponentDescriptionDTO desc, ComponentConfigurationDTO cc) {
		boolean failedActivation = cc.state == ComponentConfigurationDTO.FAILED_ACTIVATION;
		Map<String, Object> subject = new HashMap<>();
		subject.put("kind", "ds_component");
		subject.put("bundle", desc.bundle != null ? desc.bundle.symbolicName : "unknown");
		subject.put("component", desc.name);

		Map<String, Object> details = new HashMap<>();
		details.put("state", stateName(cc.state));
		if (cc.unsatisfiedReferences != null && cc.unsatisfiedReferences.length > 0) {
			Set<String> missing = new HashSet<>();
			for (UnsatisfiedReferenceDTO ref : cc.unsatisfiedReferences) missing.add(ref.name);
			details.put("missing_references", missing);
		}
		if (cc.failure != null) details.put("failure_message", cc.failure);

		dispatcher.emit(
				failedActivation ? "plugin.component.failed_activation" : "plugin.component.unsatisfied",
				subject, details);
	}

	private void emitRecovered(String componentName) {
		Map<String, Object> subject = new HashMap<>();
		subject.put("kind", "ds_component");
		subject.put("component", componentName);
		dispatcher.emit("plugin.lifecycle.recovered", subject, new HashMap<>());
	}

	private static String stateName(int state) {
		switch (state) {
			case ComponentConfigurationDTO.UNSATISFIED_CONFIGURATION: return "UNSATISFIED_CONFIGURATION";
			case ComponentConfigurationDTO.UNSATISFIED_REFERENCE:     return "UNSATISFIED_REFERENCE";
			case ComponentConfigurationDTO.SATISFIED:                 return "SATISFIED";
			case ComponentConfigurationDTO.ACTIVE:                    return "ACTIVE";
			case ComponentConfigurationDTO.FAILED_ACTIVATION:         return "FAILED_ACTIVATION";
			default:                                                  return "UNKNOWN(" + state + ")";
		}
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
			Thread t = new Thread(r, "clde-ops-collector-scr-poll-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		};
	}
}
