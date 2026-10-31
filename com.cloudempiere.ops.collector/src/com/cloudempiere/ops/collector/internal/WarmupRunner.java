/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.compiere.model.MTable;
import org.compiere.util.Env;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;

/**
 * Active warmup validation — Strategy B (ADR-001 §4.4 / §4.9). A one-shot, on-demand deploy
 * gate (invoked by {@code POST /ops/v1/warmup}) that <b>deliberately mutates</b> the runtime:
 * it forces lazy/delayed DS components behind the key iDempiere factory interfaces to
 * {@code @Activate} (so activation exceptions surface here, not on the first user request),
 * and exercises the model-factory lookup for a configured CLDE-table allowlist (to catch
 * factory shadowing). Each target is classified {@code ok} / {@code failed_activation} /
 * {@code timeout} / {@code factory_shadowed}; failures emit PANIC, and a summary emits
 * {@code plugin.warmup.completed}. <b>Never call from a recurring probe</b> — it's a deploy step.
 *
 * @author CloudEmpiere
 */
@Component(service = WarmupRunner.class)
public class WarmupRunner {

	/** Factory service interfaces whose delayed components we force-activate (§4.9). */
	private static final String[] FACTORY_INTERFACES = {
		"org.adempiere.base.IModelFactory",
		"org.adempiere.base.IColumnCalloutFactory",
		"org.adempiere.base.IProcessFactory",
		"org.adempiere.base.IModelValidatorFactory",
		"org.adempiere.base.event.IEventTopicHandler",
	};

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private EventDispatcher dispatcher;

	private BundleContext ctx;

	@Activate
	void activate(BundleContext ctx) {
		this.ctx = ctx;
	}

	/**
	 * Run the warmup. Returns a result payload classifying each target; safe to serialise for
	 * the {@code POST /ops/v1/warmup} response.
	 */
	public Map<String, Object> warmup() {
		long timeoutSeconds = envSeconds("OPS_COLLECTOR_WARMUP_TIMEOUT_SECONDS", 30L);
		List<Map<String, Object>> results = new ArrayList<>();
		int ok = 0, failed = 0;

		ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "clde-ops-collector-warmup");
			t.setDaemon(true);
			return t;
		});
		try {
			for (String iface : FACTORY_INTERFACES) {
				ServiceReference<?>[] refs;
				try {
					refs = ctx.getServiceReferences(iface, null);
				} catch (Throwable t) {
					continue;   // interface not present on this platform/tier
				}
				if (refs == null) continue;
				for (ServiceReference<?> ref : refs) {
					Map<String, Object> r = activateRef(ex, iface, ref, timeoutSeconds);
					results.add(r);
					if ("ok".equals(r.get("status"))) ok++; else failed++;
				}
			}
			// Exercise the model-factory lookup path for the configured table allowlist.
			for (String table : warmupTables()) {
				Map<String, Object> r = exerciseTable(table);
				results.add(r);
				if ("ok".equals(r.get("status"))) ok++; else failed++;
			}
		} finally {
			ex.shutdownNow();
		}

		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("targets", results.size());
		summary.put("ok", ok);
		summary.put("failed", failed);
		summary.put("results", results);
		dispatcher.emit("plugin.warmup.completed", warmupSubject(), summary);
		return summary;
	}

	/** Force the delayed component behind {@code ref} to activate, with a timeout. */
	private Map<String, Object> activateRef(ExecutorService ex, String iface, ServiceReference<?> ref, long timeoutSeconds) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("kind", "ds_component");
		r.put("interface", iface);
		Object bsn = ref.getBundle() != null ? ref.getBundle().getSymbolicName() : "unknown";
		r.put("bundle", bsn);
		r.put("component", String.valueOf(ref.getProperty("component.name")));

		Future<Object> f = ex.submit(() -> ctx.getService(ref));   // getService triggers @Activate
		try {
			Object svc = f.get(timeoutSeconds, TimeUnit.SECONDS);
			r.put("status", svc != null ? "ok" : "factory_shadowed");
			if (svc == null) emitFailure("factory_shadowed", iface, bsn, "service resolved to null");
		} catch (TimeoutException te) {
			f.cancel(true);
			r.put("status", "timeout");
			emitFailure("timeout", iface, bsn, "activation exceeded " + timeoutSeconds + "s");
		} catch (Throwable t) {
			Throwable cause = (t.getCause() != null) ? t.getCause() : t;
			r.put("status", "failed_activation");
			r.put("error", String.valueOf(cause));
			emitFailure("failed_activation", iface, bsn, String.valueOf(cause));
		} finally {
			try { ctx.ungetService(ref); } catch (Throwable ignore) { }
		}
		return r;
	}

	/** Exercise {@code MTable.getClass(table)} — runs the model-factory lookup for one table. */
	private Map<String, Object> exerciseTable(String table) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("kind", "model_factory");
		r.put("table", table);
		try {
			Class<?> clazz = MTable.getClass(table);
			if (clazz != null) {
				r.put("status", "ok");
				r.put("model_class", clazz.getName());
			} else {
				r.put("status", "factory_shadowed");
				emitFailure("factory_shadowed", "IModelFactory", table, "no model class for table");
			}
		} catch (Throwable t) {
			r.put("status", "failed_activation");
			r.put("error", String.valueOf(t));
			emitFailure("failed_activation", "IModelFactory", table, String.valueOf(t));
		}
		return r;
	}

	private void emitFailure(String classification, String iface, Object bundle, String message) {
		Map<String, Object> subject = new HashMap<>();
		subject.put("kind", "ds_component");
		subject.put("interface", iface);
		subject.put("bundle", bundle);
		Map<String, Object> details = new HashMap<>();
		details.put("classification", classification);
		details.put("failure_message", message);
		dispatcher.emit("plugin.component.failed_activation", subject, details);
	}

	private String[] warmupTables() {
		String v = System.getenv("OPS_COLLECTOR_WARMUP_TABLES");
		if (v == null || v.trim().isEmpty()) return new String[0];
		String[] parts = v.split(",");
		List<String> out = new ArrayList<>();
		for (String p : parts) { String s = p.trim(); if (!s.isEmpty()) out.add(s); }
		return out.toArray(new String[0]);
	}

	private Map<String, Object> warmupSubject() {
		Map<String, Object> s = new HashMap<>();
		s.put("kind", "warmup");
		s.put("ad_client_id", Env.getAD_Client_ID(Env.getCtx()));
		return s;
	}

	private static long envSeconds(String name, long fallback) {
		String v = System.getenv(name);
		if (v == null || v.isEmpty()) return fallback;
		try { long n = Long.parseLong(v.trim()); return n > 0 ? n : fallback; }
		catch (NumberFormatException e) { return fallback; }
	}
}
