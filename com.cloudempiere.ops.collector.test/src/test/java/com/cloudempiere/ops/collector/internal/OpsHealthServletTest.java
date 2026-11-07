/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import com.cloudempiere.ops.collector.api.OpsStatusService;

/**
 * {@link OpsHealthServlet} is what the ALB health check calls once webui moves to {@code /ops/readyz}
 * (CLD-2583). Pins the exact status code + body for each probe, including the "status service not
 * registered yet" window during boot, which must be 503 and never a guessed 200.
 * <p>
 * The servlet looks its {@link OpsStatusService} up through {@code FrameworkUtil.getBundle(...)},
 * a static call — stubbed with Mockito's {@code mockStatic}, scoped to each test.
 */
class OpsHealthServletTest {

	/** Result of one probe call. */
	private record Probe(int code, String body, String contentType) {}

	private static OpsStatusService status(boolean alive, boolean ready, boolean startupComplete) {
		OpsStatusService s = mock(OpsStatusService.class);
		when(s.isAlive()).thenReturn(alive);
		when(s.isReady()).thenReturn(ready);
		when(s.isStartupComplete()).thenReturn(startupComplete);
		return s;
	}

	/** Bundle → context → reference → service, as the live framework would resolve it. */
	@SuppressWarnings("unchecked")
	private static Bundle bundleProviding(OpsStatusService service) {
		Bundle bundle = mock(Bundle.class);
		BundleContext ctx = mock(BundleContext.class);
		when(bundle.getBundleContext()).thenReturn(ctx);
		if (service != null) {
			ServiceReference<OpsStatusService> ref = mock(ServiceReference.class);
			when(ctx.getServiceReference(OpsStatusService.class)).thenReturn(ref);
			when(ctx.getService(ref)).thenReturn(service);
		}
		return bundle;
	}

	private static Probe call(String servletPath, Bundle bundle) throws Exception {
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getServletPath()).thenReturn(servletPath);
		HttpServletResponse resp = mock(HttpServletResponse.class);
		StringWriter body = new StringWriter();
		when(resp.getWriter()).thenReturn(new PrintWriter(body, true));

		try (MockedStatic<FrameworkUtil> fw = mockStatic(FrameworkUtil.class)) {
			fw.when(() -> FrameworkUtil.getBundle(any(Class.class))).thenReturn(bundle);
			new OpsHealthServlet().doGet(req, resp);
		}

		var code = org.mockito.ArgumentCaptor.forClass(Integer.class);
		verify(resp).setStatus(code.capture());
		var type = org.mockito.ArgumentCaptor.forClass(String.class);
		verify(resp).setContentType(type.capture());
		return new Probe(code.getValue(), body.toString(), type.getValue());
	}

	@Test
	void readyz_ready_is_200() throws Exception {
		Probe p = call("/readyz", bundleProviding(status(true, true, true)));
		assertEquals(200, p.code());
		assertEquals("{\"status\":\"ready\"}", p.body());
		assertEquals("application/json", p.contentType());
	}

	@Test
	void readyz_unready_is_503() throws Exception {
		Probe p = call("/readyz", bundleProviding(status(true, false, true)));
		assertEquals(503, p.code());
		assertEquals("{\"status\":\"unready\"}", p.body());
	}

	@Test
	void status_service_not_registered_yet_is_503_unavailable_on_every_probe() throws Exception {
		for (String probe : new String[] { "/readyz", "/livez", "/startupz" }) {
			Probe p = call(probe, bundleProviding(null));
			assertEquals(503, p.code(), probe);
			assertEquals("{\"status\":\"unavailable\"}", p.body(), probe);
		}
	}

	@Test
	void no_bundle_or_no_context_is_503_unavailable() throws Exception {
		Probe noBundle = call("/readyz", null);
		assertEquals(503, noBundle.code());
		assertEquals("{\"status\":\"unavailable\"}", noBundle.body());

		Bundle stopped = mock(Bundle.class); // getBundleContext() → null, as for a stopped bundle
		Probe noCtx = call("/readyz", stopped);
		assertEquals(503, noCtx.code());
		assertEquals("{\"status\":\"unavailable\"}", noCtx.body());
	}

	@Test
	void livez_reflects_isAlive() throws Exception {
		assertEquals(new Probe(200, "{\"status\":\"alive\"}", "application/json"),
				call("/livez", bundleProviding(status(true, false, false))));
		assertEquals(new Probe(503, "{\"status\":\"down\"}", "application/json"),
				call("/livez", bundleProviding(status(false, false, false))));
	}

	@Test
	void startupz_reflects_isStartupComplete() throws Exception {
		assertEquals(new Probe(200, "{\"status\":\"complete\"}", "application/json"),
				call("/startupz", bundleProviding(status(true, false, true))));
		assertEquals(new Probe(503, "{\"status\":\"starting\"}", "application/json"),
				call("/startupz", bundleProviding(status(true, false, false))));
	}

	@Test
	void livez_ignores_readiness() throws Exception {
		// an unready task (e.g. /webui not deployed yet) must stay alive, or ECS would replace it
		Probe p = call("/livez", bundleProviding(status(true, false, false)));
		assertEquals(200, p.code());
	}

	@Test
	void unknown_or_missing_probe_is_404() throws Exception {
		OpsStatusService s = status(true, true, true);
		assertEquals(new Probe(404, "{\"status\":\"unknown_probe\"}", "application/json"),
				call("/healthz", bundleProviding(s)));
		assertEquals(404, call(null, bundleProviding(s)).code());
	}
}
