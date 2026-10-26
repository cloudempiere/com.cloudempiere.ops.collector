/******************************************************************************
 * Product: CloudEmpiere Ops REST                                              *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 *****************************************************************************/
package com.cloudempiere.ops.rest;

import java.util.Properties;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.cloudempiere.model.core.AuthorizationTokenContext;
import org.compiere.model.MSysConfig;
import org.compiere.util.Env;
import org.compiere.util.Util;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import com.cloudempiere.ops.collector.api.OpsEvent;
import com.cloudempiere.ops.collector.api.OpsStatusService;

/**
 * The authenticated {@code /api/v1/ops/*} REST surface (ADR-001 §4.7), contributed to the iDempiere
 * REST app via {@link OpsResourceExtension}. Mounted under the REST base, so the effective paths are
 * {@code /api/v1/ops/{startup,plugins,events,warmup}}.
 * <p>
 * <b>Probes live elsewhere.</b> The unauthenticated liveness/readiness probes
 * ({@code livez}/{@code readyz}/{@code startupz}) are <i>not</i> on this resource — they are served
 * by {@code OpsHealthServlet} in the collector core bundle as a Web Application Bundle at
 * {@code /ops/livez}, {@code /ops/readyz}, {@code /ops/startupz}, <i>outside</i> the JAX-RS app so
 * the global auth filter does not apply (the ALB/docker prober cannot send an {@code Authorization}
 * header). Point the ALB target-group check at {@code /ops/readyz}. This resource carries only the
 * data-bearing endpoints that legitimately require identity.
 * <p>
 * Backed by the exported {@link OpsStatusService} (looked up via OSGi — JAX-RS resources are
 * not DS components).
 * <p>
 * <b>Auth — M2M-first.</b> The REST app's global {@code CLDERequestFilter} authenticates every request,
 * including <b>M2M</b> (Cognito {@code client_credentials}) tokens, so this resource adds <i>no</i> filter.
 * An M2M app-client maps to a real {@code (AD_Client_ID, AD_Role_ID, AD_User_ID)} via
 * {@code AD_Role_AppClientAccess} — so a dedicated monitoring M2M client (granted an {@code ops}-scoped
 * role via {@code REST_Resource_Access}) is the intended caller, not a human System Admin.
 * <p>
 * Authorization (policy B): the system-level endpoints {@code startup}/{@code plugins}/{@code warmup}
 * expose server internals or mutate, so they require a <b>System-tenant token</b> ({@code AD_Client_ID 0}) —
 * a System-tenant M2M client satisfies this. {@code events} is tenant-scoped: a System-tenant token sees
 * all events, a tenant-scoped token sees only its own {@code AD_Client_ID} (§4.7). {@code warmup}
 * additionally stays gated by {@code OPS_COLLECTOR_WARMUP_HTTP_ENABLED}.
 *
 * @author CloudEmpiere
 */
@Path("v1/ops")
public class OpsV1Resource {

	// ---- Reads (authenticated; tenant-scoped per §4.7) ----

	@GET @Path("startup") @Produces(MediaType.APPLICATION_JSON)
	public Response startup(@Context UriInfo uriInfo) {
		Caller c = caller();
		if (!c.authenticated) c = pinCaller(uriInfo);  // fall back to REST_HEALTH_MONITORING_KEY
		if (!c.authenticated) return unauthenticated();
		if (!c.systemTenant) return forbidden();   // system-level → System-tenant token only (policy B)
		OpsStatusService s = status();
		if (s == null) return unavailable();
		return json(200, new JSONObject(s.startup()));
	}

	@GET @Path("plugins") @Produces(MediaType.APPLICATION_JSON)
	public Response plugins(@Context UriInfo uriInfo) {
		Caller c = caller();
		if (!c.authenticated) c = pinCaller(uriInfo);  // fall back to REST_HEALTH_MONITORING_KEY
		if (!c.authenticated) return unauthenticated();
		if (!c.systemTenant) return forbidden();   // bundles aren't tenant-owned → System-tenant token only
		OpsStatusService s = status();
		if (s == null) return unavailable();
		return json(200, new JSONObject(s.plugins()));
	}

	@GET @Path("events") @Produces(MediaType.APPLICATION_JSON)
	public Response events(@QueryParam("max") int max, @Context UriInfo uriInfo) {
		Caller c = caller();
		if (!c.authenticated) c = pinCaller(uriInfo);  // fall back to REST_HEALTH_MONITORING_KEY
		if (!c.authenticated) return unauthenticated();
		OpsStatusService s = status();
		if (s == null) return unavailable();
		int limit = max > 0 ? max : 100;
		JSONArray arr = new JSONArray();
		// Server-side tenant scoping (§4.7): a System-tenant token (incl. a System M2M monitor) sees all;
		// a tenant-scoped token sees only events for its own AD_Client_ID. System-level events
		// (no tenant block) are visible to the System tenant only.
		for (OpsEvent e : s.events(limit)) {
			if (c.systemTenant || tenantMatches(e, c.clientId)) arr.put(eventToJson(e));
		}
		return json(200, new JSONObject().put("events", arr));
	}

	// ---- Write (mutating; System-tenant + explicit opt-in) ----

	@POST @Path("warmup") @Produces(MediaType.APPLICATION_JSON)
	public Response warmup() {
		Caller c = caller();
		if (!c.authenticated) return unauthenticated();
		if (!c.systemTenant) return forbidden();   // mutating, system-level → System-tenant token only
		if (!"true".equalsIgnoreCase(System.getenv("OPS_COLLECTOR_WARMUP_HTTP_ENABLED"))) {
			return json(403, new JSONObject()
					.put("status", "disabled")
					.put("reason", "warmup is opt-in; set OPS_COLLECTOR_WARMUP_HTTP_ENABLED=true to allow"));
		}
		OpsStatusService s = status();
		if (s == null) return unavailable();
		return json(200, new JSONObject(s.warmup()));
	}

	// ---- helpers ----

	/** Caller identity from the authed Env (set by the global REST filter before this resource runs). */
	private static final class Caller {
		final boolean authenticated;
		final boolean systemTenant;   // AD_Client_ID 0 — the authority for system-level endpoints (policy B)
		final int clientId;
		Caller(boolean auth, boolean systemTenant, int clientId) {
			this.authenticated = auth; this.systemTenant = systemTenant; this.clientId = clientId;
		}
	}

	/**
	 * Resolve the caller from the authenticated {@code Env} context. M2M-first: the global REST filter
	 * authenticates M2M (Cognito {@code client_credentials}) tokens as well as human/PIN/card tokens,
	 * and an M2M app-client maps to a real {@code (AD_Client_ID, AD_Role_ID, AD_User_ID)} via
	 * {@code AD_Role_AppClientAccess}. So we treat the caller as authenticated when <i>either</i> a user
	 * ({@code #AD_User_ID}) <i>or</i> an app-client ({@code #AD_Authorization_AppClient_ID}) is bound —
	 * requiring a user would wrongly reject pure M2M. The system-level authority is the <b>System tenant</b>
	 * ({@code AD_Client_ID == 0}), satisfied by a System-tenant M2M client just as by a System-Admin user.
	 * <p>
	 * Requiring an explicit principal marker (not merely {@code AD_Client_ID == 0}, which is also the unset
	 * default) keeps this fail-closed if the resource is ever reached without an authenticated context.
	 */
	private static Caller caller() {
		Properties ctx = Env.getCtx();
		String userId = Env.getContext(ctx, "#AD_User_ID");
		String appClientId = Env.getContext(ctx, AuthorizationTokenContext.AD_Authorization_AppClient_ID);
		boolean authenticated = !Util.isEmpty(userId) || !Util.isEmpty(appClientId);
		if (!authenticated) {
			return new Caller(false, false, -1);   // no principal bound (shouldn't happen behind the filter)
		}
		int clientId = Env.getAD_Client_ID(ctx);
		return new Caller(true, clientId == 0, clientId);
	}

	/**
	 * Secondary auth path: validates the {@code REST_OPS_MONITORING_KEY} SysConfig value against
	 * the {@code ?key=} query parameter. Deliberately separate from {@code REST_HEALTH_MONITORING_KEY}
	 * so an operator gating {@code /api/v1/health} does not inadvertently unlock the more sensitive
	 * bundle/DS/event data. If the key is not configured this always returns unauthenticated —
	 * callers must use a JWT token instead. When the key matches the caller is treated as
	 * System-tenant ({@code AD_Client_ID 0}). Read-only endpoints only — {@code warmup} excluded.
	 */
	private static Caller pinCaller(UriInfo uriInfo) {
		String configuredKey = MSysConfig.getValue("REST_OPS_MONITORING_KEY", "");
		if (Util.isEmpty(configuredKey)) return new Caller(false, false, -1);
		String provided = uriInfo.getQueryParameters().getFirst("key");
		if (!configuredKey.equals(provided)) return new Caller(false, false, -1);
		return new Caller(true, true, 0);   // System-tenant scope
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
		return json(403, new JSONObject().put("status", "forbidden").put("reason", "System-tenant scope required"));
	}

	private static Response json(int code, JSONObject body) {
		return Response.status(code).entity(body.toString()).type(MediaType.APPLICATION_JSON).build();
	}
}
