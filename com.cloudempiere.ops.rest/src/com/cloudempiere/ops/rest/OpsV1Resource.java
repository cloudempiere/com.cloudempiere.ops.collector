/******************************************************************************
 * Product: CloudEmpiere Ops REST                                              *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 *****************************************************************************/
package com.cloudempiere.ops.rest;

import java.util.List;
import java.util.Properties;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.compiere.util.Env;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import com.cloudempiere.ops.collector.api.OpsEvent;
import com.cloudempiere.ops.collector.api.OpsStatusService;

/**
 * The {@code /ops/v1/*} REST surface (ADR-001 §4.7), contributed to the iDempiere REST app via
 * {@link OpsResourceExtension}. Mounted under the REST base, so the effective paths are
 * {@code /api/v1/ops/*} (the ADR's {@code /ops/v1} assumed a standalone base; extending the
 * REST app mounts under {@code /api/v1/} — wire docker/ALB health checks accordingly).
 * <p>
 * Backed by the exported {@link OpsStatusService} (looked up via OSGi — JAX-RS resources are
 * not DS components).
 * <p>
 * <b>Auth.</b> The REST app's global {@code CLDERequestFilter} already enforces identity on every
 * resource, so {@code startup}/{@code plugins}/{@code events}/{@code warmup} are protected by the
 * M2M / PIN / usercard Bearer JWT automatically — this resource adds <i>no</i> filter. The probes
 * ({@code livez}/{@code readyz}/{@code startupz}) are intercepted by that filter too, but must be
 * reachable by the ALB/docker health prober, which cannot send an {@code Authorization} header.
 * They therefore need a dedicated low-privilege monitoring credential — a provisioned PIN/usercard
 * value, or a new {@code AD_AuthorizationToken} type — passed as a query param and validated
 * directly in {@code CLDERequestFilter} (the {@code v1/health ?key=} pattern, backed by
 * {@code AD_AuthorizationToken}). <b>That probe-credential exemption is a deferred follow-up
 * (CLD-1912); until it lands the probes will 401 behind the global filter, so do NOT point the ALB
 * target-group check at these paths yet.</b> {@code warmup} additionally stays gated by
 * {@code OPS_COLLECTOR_WARMUP_HTTP_ENABLED}. Server-side tenant filtering of plugins/events (§4.7)
 * is also TODO.
 *
 * @author CloudEmpiere
 */
@Path("v1/ops")
public class OpsV1Resource {

	// ---- Tier 0 probes (open) ----

	@GET @Path("livez") @Produces(MediaType.APPLICATION_JSON)
	public Response livez() {
		OpsStatusService s = status();
		return (s != null && s.isAlive())
				? json(200, new JSONObject().put("status", "alive"))
				: json(503, new JSONObject().put("status", "down"));
	}

	@GET @Path("readyz") @Produces(MediaType.APPLICATION_JSON)
	public Response readyz() {
		OpsStatusService s = status();
		if (s == null) return json(503, new JSONObject().put("status", "unavailable"));
		return s.isReady()
				? json(200, new JSONObject().put("status", "ready"))
				: json(503, new JSONObject().put("status", "unready"));
	}

	@GET @Path("startupz") @Produces(MediaType.APPLICATION_JSON)
	public Response startupz() {
		OpsStatusService s = status();
		if (s == null) return json(503, new JSONObject().put("status", "unavailable"));
		return s.isStartupComplete()
				? json(200, new JSONObject().put("status", "complete"))
				: json(503, new JSONObject(s.startup()).put("status", "starting"));
	}

	// ---- Reads (open for now; §4.7 auth + tenant filtering = B2 TODO) ----

	@GET @Path("startup") @Produces(MediaType.APPLICATION_JSON)
	public Response startup() {
		Caller c = caller();                       // boot status leaks bundle/component names
		if (!c.authenticated) return unauthenticated();
		if (!c.systemAdmin) return forbidden();    // System Admin only
		OpsStatusService s = status();
		if (s == null) return unavailable();
		return json(200, new JSONObject(s.startup()));
	}

	@GET @Path("plugins") @Produces(MediaType.APPLICATION_JSON)
	public Response plugins() {
		Caller c = caller();                       // full bundle/DS list is system-level (§4.7 leak risk)
		if (!c.authenticated) return unauthenticated();
		if (!c.systemAdmin) return forbidden();    // System Admin only — bundles aren't tenant-owned
		OpsStatusService s = status();
		if (s == null) return unavailable();
		return json(200, new JSONObject(s.plugins()));
	}

	@GET @Path("events") @Produces(MediaType.APPLICATION_JSON)
	public Response events(@QueryParam("max") int max) {
		Caller c = caller();
		if (!c.authenticated) return unauthenticated();
		OpsStatusService s = status();
		if (s == null) return unavailable();
		int limit = max > 0 ? max : 100;
		JSONArray arr = new JSONArray();
		// Server-side tenant filtering (§4.7): System Admin sees all; a tenant admin sees only
		// events scoped to its own AD_Client_ID. System-level events (no tenant) → System Admin only.
		for (OpsEvent e : s.events(limit)) {
			if (c.systemAdmin || tenantMatches(e, c.clientId)) arr.put(eventToJson(e));
		}
		return json(200, new JSONObject().put("events", arr));
	}

	// ---- Tier 3 write (disabled by default pending identity confirm-token) ----

	@POST @Path("warmup") @Produces(MediaType.APPLICATION_JSON)
	public Response warmup() {
		Caller c = caller();
		if (!c.authenticated) return unauthenticated();
		if (!c.systemAdmin) return forbidden();    // mutating; System Admin only
		if (!"true".equalsIgnoreCase(System.getenv("OPS_COLLECTOR_WARMUP_HTTP_ENABLED"))) {
			return json(403, new JSONObject()
					.put("status", "disabled")
					.put("reason", "warmup requires identity Tier-3 auth (B2); set OPS_COLLECTOR_WARMUP_HTTP_ENABLED=true to allow"));
		}
		OpsStatusService s = status();
		if (s == null) return unavailable();
		return json(200, new JSONObject(s.warmup()));
	}

	// ---- helpers ----

	/** Caller identity from the authed Env (set by CLDERequestFilter before this resource runs). */
	private static final class Caller {
		final boolean authenticated;
		final boolean systemAdmin;
		final int clientId;
		Caller(boolean auth, boolean sysAdmin, int clientId) {
			this.authenticated = auth; this.systemAdmin = sysAdmin; this.clientId = clientId;
		}
	}

	private static Caller caller() {
		Properties ctx = Env.getCtx();
		String userId = Env.getContext(ctx, "#AD_User_ID");
		if (userId == null || userId.isEmpty()) {
			return new Caller(false, false, -1);   // not authenticated (shouldn't happen behind the filter)
		}
		int clientId = Env.getAD_Client_ID(ctx);
		// rac-as-authority: AD_Client_ID 0 = System client ⇒ System Admin (sees all tenants).
		return new Caller(true, clientId == 0, clientId);
	}

	private static boolean tenantMatches(OpsEvent e, int clientId) {
		Object tc = e.getTenant().get("ad_client_id");
		return tc instanceof Number && ((Number) tc).intValue() == clientId;
	}

	private static OpsStatusService status() {
		Bundle b = FrameworkUtil.getBundle(OpsV1Resource.class);
		if (b == null) return null;
		BundleContext ctx = b.getBundleContext();
		if (ctx == null) return null;
		ServiceReference<OpsStatusService> ref = ctx.getServiceReference(OpsStatusService.class);
		return ref == null ? null : ctx.getService(ref);
	}

	private static JSONObject eventToJson(OpsEvent e) {
		JSONObject o = new JSONObject();
		o.put("schema_version", e.getSchemaVersion());
		putOpt(o, "event_id", e.getEventId());
		putOpt(o, "event_type", e.getEventType());
		putOpt(o, "severity", e.getSeverity() == null ? null : e.getSeverity().name());
		putOpt(o, "timestamp", e.getTimestamp());
		putOpt(o, "aggregation_hint", e.getAggregationHint());
		if (!e.getTenant().isEmpty()) o.put("tenant", new JSONObject(e.getTenant()));
		if (!e.getSubject().isEmpty()) o.put("subject", new JSONObject(e.getSubject()));
		if (!e.getDetails().isEmpty()) o.put("details", new JSONObject(e.getDetails()));
		putOpt(o, "trace_id", e.getTraceId());
		putOpt(o, "span_id", e.getSpanId());
		putOpt(o, "dedupe_hash", e.getDedupeHash());
		return o;
	}

	private static void putOpt(JSONObject o, String key, String value) {
		if (value != null) o.put(key, value);
	}

	private static Response unavailable() {
		return json(503, new JSONObject().put("status", "unavailable"));
	}

	private static Response unauthenticated() {
		return json(401, new JSONObject().put("status", "unauthenticated"));
	}

	private static Response forbidden() {
		return json(403, new JSONObject().put("status", "forbidden").put("reason", "System Admin scope required"));
	}

	private static Response json(int code, JSONObject body) {
		return Response.status(code).entity(body.toString()).type(MediaType.APPLICATION_JSON).build();
	}
}
