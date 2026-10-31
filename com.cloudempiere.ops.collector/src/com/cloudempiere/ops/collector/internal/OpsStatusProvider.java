/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.runtime.ServiceComponentRuntime;
import org.osgi.service.component.runtime.dto.ComponentConfigurationDTO;
import org.osgi.service.component.runtime.dto.ComponentDescriptionDTO;
import org.osgi.service.component.runtime.dto.UnsatisfiedReferenceDTO;

import com.cloudempiere.ops.collector.api.OpsEvent;
import com.cloudempiere.ops.collector.api.OpsStatusService;
import com.cloudempiere.ops.collector.internal.customspi.CustomSpiHealthSource;
import com.cloudempiere.ops.collector.internal.customspi.ScannedSpi;

/**
 * Aggregates the collector's internal state behind the exported {@link OpsStatusService}
 * (ADR-001 §4.7). The REST transport bundle looks this service up and serves {@code /ops/v1/*}
 * from it — keeping the REST/JAX-RS coupling out of the tier-agnostic collector core.
 *
 * @author CloudEmpiere
 */
@Component(service = OpsStatusService.class)
public class OpsStatusProvider implements OpsStatusService {

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private BootPhaseTracker boot;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private ShutdownHandler shutdown;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private WarmupRunner warmupRunner;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private EventDispatcher dispatcher;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private ServiceComponentRuntime scr;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private CustomSpiHealthSource customSpi;

	private BundleContext ctx;

	@Activate
	void activate(BundleContext ctx) {
		this.ctx = ctx;
	}

	@Override
	public boolean isAlive() {
		return true;
	}

	@Override
	public boolean isReady() {
		// customSpi.isAllRequiredActive() is the one deliberately gating source in this collector
		// (SPEC-custom-spi-health) — a missing extraction-work SPI means the system should not be
		// considered ready, not merely "degraded". Every other source here is non-gating by policy.
		return boot.isReady() && !shutdown.isDraining() && customSpi.isAllRequiredActive();
	}

	@Override
	public boolean isStartupComplete() {
		return boot.isReady();
	}

	@Override
	public Map<String, Object> startup() {
		return boot.snapshot();
	}

	@Override
	public List<OpsEvent> events(int max) {
		return dispatcher.recentEvents(max);
	}

	@Override
	public Map<String, Object> warmup() {
		return warmupRunner.warmup();
	}

	@Override
	public Map<String, Object> plugins() {
		List<Map<String, Object>> bundles = new ArrayList<>();
		int bundlesActive = 0;
		for (Bundle b : ctx.getBundles()) {
			Map<String, Object> bm = new LinkedHashMap<>();
			bm.put("symbolicName", b.getSymbolicName());
			bm.put("version", String.valueOf(b.getVersion()));
			bm.put("state", bundleState(b.getState()));
			bundles.add(bm);
			if (b.getState() == Bundle.ACTIVE) bundlesActive++;
		}

		List<Map<String, Object>> components = new ArrayList<>();
		int unhealthy = 0;
		Collection<ComponentDescriptionDTO> descs = scr.getComponentDescriptionDTOs();
		for (ComponentDescriptionDTO desc : descs) {
			for (ComponentConfigurationDTO cc : scr.getComponentConfigurationDTOs(desc)) {
				Map<String, Object> cm = new LinkedHashMap<>();
				cm.put("name", desc.name);
				cm.put("bundle", desc.bundle != null ? desc.bundle.symbolicName : "unknown");
				cm.put("state", componentState(cc.state));
				if (cc.unsatisfiedReferences != null && cc.unsatisfiedReferences.length > 0) {
					List<String> missing = new ArrayList<>();
					for (UnsatisfiedReferenceDTO r : cc.unsatisfiedReferences) missing.add(r.name);
					cm.put("unsatisfiedReferences", missing);
				}
				if (cc.failure != null) cm.put("failure", cc.failure);
				components.add(cm);
				if (cc.state != ComponentConfigurationDTO.SATISFIED && cc.state != ComponentConfigurationDTO.ACTIVE) {
					unhealthy++;
				}
			}
		}

		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("bundles_active", bundlesActive);
		summary.put("components_unhealthy", unhealthy);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bundles", bundles);
		out.put("components", components);
		out.put("summary", summary);
		out.put("custom_spi", customSpiStatus());
		return out;
	}

	/** Extraction-work SPI live-scan status (SPEC-custom-spi-health) — the one gating source.
	 * Catalog is the most recent poll's live scan (ADR-123), not a hand-maintained registry. */
	private Map<String, Object> customSpiStatus() {
		List<ScannedSpi> catalog = customSpi.lastScan();
		List<String> missingNow = new ArrayList<>(customSpi.missingSpiNames());
		int optionalCount = 0;
		for (ScannedSpi seam : catalog) {
			if (seam.optional()) optionalCount++;
		}
		int required = catalog.size() - optionalCount;

		Map<String, Object> m = new LinkedHashMap<>();
		m.put("required", required);
		m.put("optional", optionalCount);
		m.put("active", required - missingNow.size());
		m.put("missing", missingNow);
		m.put("all_active", customSpi.isAllRequiredActive());
		return m;
	}

	private static String bundleState(int s) {
		switch (s) {
			case Bundle.ACTIVE:      return "ACTIVE";
			case Bundle.RESOLVED:    return "RESOLVED";
			case Bundle.INSTALLED:   return "INSTALLED";
			case Bundle.STARTING:    return "STARTING";
			case Bundle.STOPPING:    return "STOPPING";
			case Bundle.UNINSTALLED: return "UNINSTALLED";
			default:                 return "UNKNOWN(" + s + ")";
		}
	}

	private static String componentState(int s) {
		switch (s) {
			case ComponentConfigurationDTO.UNSATISFIED_CONFIGURATION: return "UNSATISFIED_CONFIGURATION";
			case ComponentConfigurationDTO.UNSATISFIED_REFERENCE:     return "UNSATISFIED_REFERENCE";
			case ComponentConfigurationDTO.SATISFIED:                 return "SATISFIED";
			case ComponentConfigurationDTO.ACTIVE:                    return "ACTIVE";
			case ComponentConfigurationDTO.FAILED_ACTIVATION:         return "FAILED_ACTIVATION";
			default:                                                  return "UNKNOWN(" + s + ")";
		}
	}
}
