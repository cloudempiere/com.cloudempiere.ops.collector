/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;

/**
 * Tests {@link WebContextReadiness} through a real {@link org.osgi.util.tracker.ServiceTracker}
 * against a Mockito {@link BundleContext}: the tracker's {@link ServiceListener} is captured from
 * {@code addServiceListener} and fed {@link ServiceEvent}s, which is exactly what the framework
 * does when Jetty registers/unregisters a web app's {@code ContextHandler} service.
 */
class WebContextReadinessTest {

	/** A fake framework: records listeners, can pre-seed "already registered" references. */
	private static final class FakeFramework {
		final BundleContext ctx = mock(BundleContext.class);
		final List<ServiceListener> listeners = new ArrayList<>();
		final Map<ServiceListener, Filter> filters = new HashMap<>();
		final List<String> emitted = new ArrayList<>();
		final EventDispatcher dispatcher = mock(EventDispatcher.class);

		FakeFramework(ServiceReference<?>... preRegistered) throws Exception {
			doAnswer(inv -> {
				ServiceListener l = inv.getArgument(0);
				listeners.add(l);
				filters.put(l, FrameworkUtil.createFilter(inv.<String>getArgument(1)));
				return null;
			})
					.when(ctx).addServiceListener(any(ServiceListener.class), anyString());
			doAnswer(inv -> { listeners.remove(inv.<ServiceListener>getArgument(0)); return null; })
					.when(ctx).removeServiceListener(any(ServiceListener.class));
			when(ctx.getServiceReferences((String) isNull(), anyString())).thenAnswer(inv -> {
				Filter f = FrameworkUtil.createFilter(inv.<String>getArgument(1));
				List<ServiceReference<?>> hits = new ArrayList<>();
				for (ServiceReference<?> r : preRegistered) if (f.match(r)) hits.add(r);
				return hits.isEmpty() ? null : hits.toArray(new ServiceReference<?>[0]);
			});
			doAnswer(inv -> { emitted.add(inv.getArgument(0)); return null; })
					.when(dispatcher).emit(anyString(), any(), any());
		}

		/** Delivers the event only to listeners whose filter matches, like the real framework. */
		void fire(int type, ServiceReference<?> ref) {
			for (ServiceListener l : new ArrayList<>(listeners)) {
				if (filters.get(l).match(ref)) l.serviceChanged(new ServiceEvent(type, ref));
			}
		}

		WebContextReadiness start(String rawConfig) throws Exception {
			WebContextReadiness w = new WebContextReadiness();
			Field f = WebContextReadiness.class.getDeclaredField("dispatcher");
			f.setAccessible(true);
			f.set(w, dispatcher);
			w.start(ctx, WebContextReadiness.parseContexts(rawConfig));
			return w;
		}
	}

	/** A web-app service reference as Jetty 12.0.25's OSGiApp.registerAsOSGiService publishes it. */
	@SuppressWarnings("unchecked")
	private static ServiceReference<Object> webApp(String contextPath, String bsn) {
		ServiceReference<Object> ref = mock(ServiceReference.class);
		Dictionary<String, Object> props = new Hashtable<>();
		props.put("osgi.web.contextpath", contextPath);
		if (bsn != null) props.put("osgi.web.symbolicname", bsn);
		when(ref.getProperty(anyString())).thenAnswer(inv -> props.get(inv.<String>getArgument(0)));
		when(ref.getPropertyKeys()).thenReturn(new String[] { "osgi.web.contextpath", "osgi.web.symbolicname" });
		return ref;
	}

	private static void register(FakeFramework fw, ServiceReference<?> ref) {
		fw.fire(ServiceEvent.REGISTERED, ref);
	}

	@Test
	void not_ready_before_registration_then_ready_after() throws Exception {
		FakeFramework fw = new FakeFramework();
		WebContextReadiness w = fw.start("/webui");

		assertFalse(w.isAllDeployed());
		assertEquals(List.of("/webui"), w.status().get("missing"));

		register(fw, webApp("/webui", "org.adempiere.ui.zk"));

		assertTrue(w.isAllDeployed());
		assertEquals(List.of("/webui"), w.status().get("deployed"));
		assertEquals(List.of("ops.webcontext.deployed"), fw.emitted);
	}

	@Test
	void not_ready_after_removal() throws Exception {
		FakeFramework fw = new FakeFramework();
		WebContextReadiness w = fw.start("/webui");
		ServiceReference<Object> zk = webApp("/webui", "org.adempiere.ui.zk");
		register(fw, zk);
		assertTrue(w.isAllDeployed());

		fw.fire(ServiceEvent.UNREGISTERING, zk);

		assertFalse(w.isAllDeployed());
		assertEquals(List.of("ops.webcontext.deployed", "ops.webcontext.undeployed"), fw.emitted);
	}

	@Test
	void already_deployed_at_activation_is_ready_immediately() throws Exception {
		FakeFramework fw = new FakeFramework(webApp("/webui", "org.adempiere.ui.zk"));
		WebContextReadiness w = fw.start("/webui");

		assertTrue(w.isAllDeployed());
	}

	@Test
	void empty_or_unset_config_is_a_no_op() throws Exception {
		for (String raw : new String[] { null, "", "   ", " , " }) {
			FakeFramework fw = new FakeFramework();
			WebContextReadiness w = fw.start(raw);
			assertTrue(w.isAllDeployed(), "config=" + raw);
			assertTrue(fw.listeners.isEmpty(), "no tracker should be opened for config=" + raw);
		}
	}

	@Test
	void multiple_contexts_all_required() throws Exception {
		FakeFramework fw = new FakeFramework();
		WebContextReadiness w = fw.start("/webui, /preview");

		register(fw, webApp("/webui", "org.adempiere.ui.zk"));
		assertFalse(w.isAllDeployed());
		assertEquals(List.of("/preview"), w.status().get("missing"));

		register(fw, webApp("/preview", "some.preview.bundle"));
		assertTrue(w.isAllDeployed());
		assertEquals(Boolean.TRUE, w.status().get("all_deployed"));
	}

	@Test
	void unrelated_context_does_not_satisfy_gate() throws Exception {
		FakeFramework fw = new FakeFramework();
		WebContextReadiness w = fw.start("/webui");

		register(fw, webApp("/ops", "com.cloudempiere.ops.collector"));
		register(fw, webApp("/webuiX", "other"));

		assertFalse(w.isAllDeployed());
		assertTrue(fw.emitted.isEmpty());
	}

	@Test
	void context_published_without_leading_slash_still_matches() throws Exception {
		FakeFramework fw = new FakeFramework();
		WebContextReadiness w = fw.start("webui/");

		register(fw, webApp("webui", "org.adempiere.ui.zk"));

		assertTrue(w.isAllDeployed());
	}

	@Test
	void deactivate_closes_trackers() throws Exception {
		FakeFramework fw = new FakeFramework();
		WebContextReadiness w = fw.start("/webui /preview");
		assertEquals(2, fw.listeners.size());

		w.deactivate();

		assertTrue(fw.listeners.isEmpty());
	}

	@Test
	void emit_failure_never_affects_readiness() throws Exception {
		FakeFramework fw = new FakeFramework();
		doAnswer(inv -> { throw new IllegalStateException("bus down"); })
				.when(fw.dispatcher).emit(anyString(), any(), any());
		WebContextReadiness w = fw.start("/webui");
		ServiceReference<Object> zk = webApp("/webui", "org.adempiere.ui.zk");

		register(fw, zk);
		assertTrue(w.isAllDeployed());

		fw.fire(ServiceEvent.UNREGISTERING, zk);
		assertFalse(w.isAllDeployed());
	}

	@Test
	void second_service_on_same_context_keeps_it_deployed_until_both_gone() throws Exception {
		FakeFramework fw = new FakeFramework();
		WebContextReadiness w = fw.start("/webui");
		ServiceReference<Object> a = webApp("/webui", "org.adempiere.ui.zk");
		ServiceReference<Object> b = webApp("/webui", "org.adempiere.ui.zk");

		register(fw, a);
		register(fw, b);
		fw.fire(ServiceEvent.UNREGISTERING, a);
		assertTrue(w.isAllDeployed(), "one of two registrations still present");

		fw.fire(ServiceEvent.UNREGISTERING, b);
		assertFalse(w.isAllDeployed());
		// transitions only: one deployed, one undeployed — not one per service
		assertEquals(List.of("ops.webcontext.deployed", "ops.webcontext.undeployed"), fw.emitted);
	}

	@Test
	void service_without_symbolic_name_still_counts() throws Exception {
		FakeFramework fw = new FakeFramework();
		WebContextReadiness w = fw.start("/webui");

		register(fw, webApp("/webui", null));

		assertTrue(w.isAllDeployed());
		assertEquals(List.of("ops.webcontext.deployed"), fw.emitted);
	}

	@Test
	void filter_metacharacters_are_escaped_and_match_literally() throws Exception {
		Filter f = WebContextReadiness.filterFor("/a(b)*c\\d");

		assertTrue(f.match(webApp("/a(b)*c\\d", "x")));
		assertFalse(f.match(webApp("/aXbYc", "x")), "'*' must not act as a wildcard");
	}

	@Test
	void parse_contexts_normalises_and_dedupes() {
		assertEquals(List.of("/webui", "/preview"),
				WebContextReadiness.parseContexts(" webui,/webui/ ,\t/preview  /webui"));
	}
}
