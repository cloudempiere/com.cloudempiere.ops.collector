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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.dto.BundleDTO;
import org.osgi.service.component.runtime.ServiceComponentRuntime;
import org.osgi.service.component.runtime.dto.ComponentConfigurationDTO;
import org.osgi.service.component.runtime.dto.ComponentDescriptionDTO;
import org.osgi.service.component.runtime.dto.ReferenceDTO;
import org.osgi.service.component.runtime.dto.UnsatisfiedReferenceDTO;

/**
 * {@link BootPhaseTracker} is one of the four {@code /ops/readyz} inputs. Covers phase inference,
 * the blocking vs non-blocking modes (prod runs {@code ops.collector.health.blocking=false}), the
 * A1 blocker classification (real / gated / cascade / expected / blacklisted), COMPLETE after two
 * stable ready ticks, stall detection and the {@code /startup} snapshot shape.
 * <p>
 * Driven through the private {@code tick()} by reflection, NOT {@code activate()}: activation arms
 * a real background poller. {@code blacklist} and {@code requiredLoaded} are pre-set so the tick
 * never reaches iDempiere core ({@code Ini}, {@code DB}, {@code MSysConfig}), which this OSGi-free
 * module doesn't have.
 */
class BootPhaseTrackerTest {

	/** A fake OSGi/SCR runtime whose bundle states and DS components a test can change. */
	private static final class Fake {
		final BundleContext ctx = mock(BundleContext.class);
		final ServiceComponentRuntime scr = mock(ServiceComponentRuntime.class);
		final EventDispatcher dispatcher = mock(EventDispatcher.class);
		final List<String> emitted = new ArrayList<>();
		final List<Integer> bundleStates = new ArrayList<>();
		final Map<ComponentDescriptionDTO, ComponentConfigurationDTO> components = new LinkedHashMap<>();

		Fake() {
			when(ctx.getBundles()).thenAnswer(inv -> {
				Bundle[] out = new Bundle[bundleStates.size()];
				for (int i = 0; i < out.length; i++) {
					Bundle b = mock(Bundle.class);
					when(b.getState()).thenReturn(bundleStates.get(i));
					out[i] = b;
				}
				return out;
			});
			when(scr.getComponentDescriptionDTOs()).thenAnswer(inv -> new ArrayList<>(components.keySet()));
			when(scr.getComponentConfigurationDTOs(any())).thenAnswer(inv -> List.of(components.get(inv.<ComponentDescriptionDTO>getArgument(0))));
			doAnswer(inv -> { emitted.add(inv.getArgument(0)); return null; })
					.when(dispatcher).emit(anyString(), any(), any());
		}

		BootPhaseTracker tracker(boolean blocking, Set<String> blacklist, Set<String> required) throws Exception {
			BootPhaseTracker t = new BootPhaseTracker();
			set(t, "dispatcher", dispatcher);
			set(t, "scr", scr);
			set(t, "ctx", ctx);
			Instant now = Instant.now();
			set(t, "startedAt", now);
			set(t, "phaseStartedAt", now);
			set(t, "timeoutSeconds", 300L);
			set(t, "healthBlocking", blocking);
			set(t, "blacklist", new HashSet<>(blacklist));
			set(t, "requiredLoaded", true);
			@SuppressWarnings("unchecked")
			Set<String> req = (Set<String>) get(t, "requiredPlugins");
			req.addAll(required);
			return t;
		}
	}

	private static void set(Object target, String name, Object value) throws Exception {
		Field f = BootPhaseTracker.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(target, value);
	}

	private static Object get(Object target, String name) throws Exception {
		Field f = BootPhaseTracker.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(target);
	}

	private static void tick(BootPhaseTracker t) throws Exception {
		Method m = BootPhaseTracker.class.getDeclaredMethod("tick");
		m.setAccessible(true);
		m.invoke(t);
	}

	/** A DS component in {@code state}, optionally providing interfaces and missing references. */
	private static void component(Fake f, String name, String bundle, int state, String[] provides,
			String[][] missingRefs) {
		ComponentDescriptionDTO d = new ComponentDescriptionDTO();
		d.name = name;
		d.bundle = new BundleDTO();
		d.bundle.symbolicName = bundle;
		d.serviceInterfaces = provides;
		List<ReferenceDTO> refs = new ArrayList<>();
		List<UnsatisfiedReferenceDTO> unsat = new ArrayList<>();
		for (String[] mr : missingRefs) {
			ReferenceDTO r = new ReferenceDTO();
			r.name = mr[0];
			r.interfaceName = mr[1];
			refs.add(r);
			UnsatisfiedReferenceDTO u = new UnsatisfiedReferenceDTO();
			u.name = mr[0];
			unsat.add(u);
		}
		d.references = refs.toArray(new ReferenceDTO[0]);
		ComponentConfigurationDTO c = new ComponentConfigurationDTO();
		c.state = state;
		c.unsatisfiedReferences = unsat.toArray(new UnsatisfiedReferenceDTO[0]);
		f.components.put(d, c);
	}

	private static void healthy(Fake f, String name) {
		component(f, name, "b." + name, ComponentConfigurationDTO.ACTIVE, null, new String[0][]);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, String> categories(BootPhaseTracker t) {
		Map<String, String> out = new LinkedHashMap<>();
		for (Map<String, Object> b : (List<Map<String, Object>>) t.snapshot().get("blockers")) {
			out.put((String) b.get("name"), (String) b.get("category"));
		}
		return out;
	}

	@Test
	void not_ready_before_the_first_tick() throws Exception {
		BootPhaseTracker t = new Fake().tracker(true, Set.of(), Set.of());
		assertFalse(t.isReady());
		assertEquals("starting", t.snapshot().get("state"));
		assertEquals("osgi_framework", t.snapshot().get("phase"));
	}

	@Test
	void all_healthy_goes_ready_then_complete_after_two_stable_ticks() throws Exception {
		Fake f = new Fake();
		f.bundleStates.add(Bundle.ACTIVE);
		healthy(f, "a");
		BootPhaseTracker t = f.tracker(true, Set.of(), Set.of());

		tick(t);
		assertTrue(t.isReady());
		assertEquals("ready", t.snapshot().get("state"));
		assertFalse(f.emitted.contains("ops.boot.completed"));

		tick(t);
		assertTrue(t.isReady(), "COMPLETE still counts as ready");
		assertEquals("complete", t.snapshot().get("state"));
		assertTrue(f.emitted.contains("ops.boot.completed"));
	}

	@Test
	void blocking_mode_holds_on_unresolved_bundle_until_it_resolves() throws Exception {
		Fake f = new Fake();
		f.bundleStates.add(Bundle.INSTALLED);
		BootPhaseTracker t = f.tracker(true, Set.of(), Set.of());

		tick(t);
		assertFalse(t.isReady());
		assertEquals("bundle_resolution", t.snapshot().get("phase"));

		f.bundleStates.set(0, Bundle.ACTIVE);
		tick(t);
		assertTrue(t.isReady());
	}

	@Test
	void prod_non_blocking_mode_reports_ready_despite_unresolved_bundle_and_failed_component() throws Exception {
		Fake f = new Fake();
		f.bundleStates.add(Bundle.INSTALLED);
		component(f, "broken", "b.broken", ComponentConfigurationDTO.FAILED_ACTIVATION, null, new String[0][]);
		BootPhaseTracker t = f.tracker(false, Set.of(), Set.of());

		tick(t);

		// This is why CLD-2583 needed WebContextReadiness: on prod this input alone proves nothing.
		assertTrue(t.isReady());
		assertEquals("real", categories(t).get("broken"), "still reported as a real blocker in the snapshot");
	}

	@Test
	void failed_activation_is_a_real_blocker_in_blocking_mode() throws Exception {
		Fake f = new Fake();
		f.bundleStates.add(Bundle.ACTIVE);
		component(f, "broken", "b.broken", ComponentConfigurationDTO.FAILED_ACTIVATION, null, new String[0][]);
		BootPhaseTracker t = f.tracker(true, Set.of(), Set.of());

		tick(t);

		assertFalse(t.isReady());
		assertEquals("ds_activation", t.snapshot().get("phase"));
		assertEquals(Map.of("broken", "real"), categories(t));
	}

	@Test
	void unsatisfied_but_not_required_is_expected_and_does_not_block() throws Exception {
		Fake f = new Fake();
		f.bundleStates.add(Bundle.ACTIVE);
		component(f, "tierNoise", "b.wcs", ComponentConfigurationDTO.UNSATISFIED_REFERENCE, null,
				new String[][] { { "factory", "org.example.IFactory" } });
		BootPhaseTracker t = f.tracker(true, Set.of(), Set.of());

		tick(t);

		assertTrue(t.isReady());
		assertEquals(Map.of("tierNoise", "expected"), categories(t));
	}

	@Test
	void unsatisfied_required_plugin_is_real_by_component_or_bundle_name() throws Exception {
		Fake f = new Fake();
		f.bundleStates.add(Bundle.ACTIVE);
		component(f, "byName", "b.x", ComponentConfigurationDTO.UNSATISFIED_REFERENCE, null, new String[0][]);
		component(f, "byBundle", "com.cloudempiere.required", ComponentConfigurationDTO.UNSATISFIED_REFERENCE, null, new String[0][]);
		BootPhaseTracker t = f.tracker(true, Set.of(), Set.of("byName", "com.cloudempiere.required"));

		tick(t);

		assertFalse(t.isReady());
		assertEquals(Map.of("byName", "real", "byBundle", "real"), categories(t));
	}

	@Test
	void condition_gated_and_cascade_components_are_classified_and_do_not_block() throws Exception {
		Fake f = new Fake();
		f.bundleStates.add(Bundle.ACTIVE);
		component(f, "gatedByCondition", "b.g", ComponentConfigurationDTO.UNSATISFIED_REFERENCE, null,
				new String[][] { { "ready", "org.osgi.service.condition.Condition" } });
		component(f, "upstream", "b.u", ComponentConfigurationDTO.UNSATISFIED_REFERENCE,
				new String[] { "org.example.IUpstream" }, new String[][] { { "db", "org.example.IDb" } });
		component(f, "downstream", "b.d", ComponentConfigurationDTO.UNSATISFIED_REFERENCE, null,
				new String[][] { { "up", "org.example.IUpstream" } });
		BootPhaseTracker t = f.tracker(true, Set.of(), Set.of());

		tick(t);

		assertTrue(t.isReady());
		Map<String, String> c = categories(t);
		assertEquals("gated", c.get("gatedByCondition"));
		assertEquals("cascade", c.get("downstream"));
		assertEquals("expected", c.get("upstream"));
	}

	@Test
	void blacklisted_component_is_suppressed_entirely() throws Exception {
		Fake f = new Fake();
		f.bundleStates.add(Bundle.ACTIVE);
		component(f, "disabledOnPurpose", "b.x", ComponentConfigurationDTO.FAILED_ACTIVATION, null, new String[0][]);
		BootPhaseTracker t = f.tracker(true, Set.of("disabledOnPurpose"), Set.of());

		tick(t);

		assertTrue(t.isReady(), "a blacklisted FAILED component must not hold readiness");
		assertTrue(categories(t).isEmpty());
	}

	@Test
	void stall_is_emitted_once_after_the_timeout() throws Exception {
		Fake f = new Fake();
		f.bundleStates.add(Bundle.INSTALLED);
		BootPhaseTracker t = f.tracker(true, Set.of(), Set.of());
		set(t, "startedAt", Instant.now().minusSeconds(600));
		set(t, "timeoutSeconds", 60L);

		tick(t);
		tick(t);

		assertEquals(1, f.emitted.stream().filter("ops.boot.stalled"::equals).count());
		assertFalse(t.isReady());
	}

	@Test
	@SuppressWarnings("unchecked")
	void snapshot_reports_progress_counts_and_phase_history() throws Exception {
		Fake f = new Fake();
		f.bundleStates.add(Bundle.ACTIVE);
		f.bundleStates.add(Bundle.ACTIVE);
		f.bundleStates.add(Bundle.INSTALLED);
		healthy(f, "ok");
		component(f, "pending", "b.p", ComponentConfigurationDTO.UNSATISFIED_REFERENCE, null, new String[0][]);
		component(f, "failed", "b.f", ComponentConfigurationDTO.FAILED_ACTIVATION, null, new String[0][]);
		BootPhaseTracker t = f.tracker(true, Set.of(), Set.of());

		tick(t);

		Map<String, Object> s = t.snapshot();
		Map<String, Object> p = (Map<String, Object>) s.get("progress");
		assertEquals(3, p.get("bundles_total"));
		assertEquals(2, p.get("bundles_active"));
		assertEquals(1, p.get("bundles_pending"));
		assertEquals(3, p.get("ds_components_total"));
		assertEquals(1, p.get("ds_components_satisfied"));
		assertEquals(1, p.get("ds_components_pending"));
		assertEquals(1, p.get("ds_components_failed"));
		assertEquals(1, p.get("ds_components_blocking"));
		assertEquals(6, s.get("phase_total"));
		assertEquals(2, s.get("phase_number"));
		List<Map<String, Object>> history = (List<Map<String, Object>>) s.get("history");
		assertEquals("osgi_framework", history.get(0).get("phase"));
		assertTrue(f.emitted.contains("ops.boot.phase_completed"));
		assertTrue(f.emitted.contains("ops.boot.phase_started"));
	}

	@Test
	void a_failing_tick_is_swallowed() throws Exception {
		Fake f = new Fake();
		when(f.ctx.getBundles()).thenThrow(new IllegalStateException("framework stopping"));
		BootPhaseTracker t = f.tracker(true, Set.of(), Set.of());

		Method m = BootPhaseTracker.class.getDeclaredMethod("tickQuietly");
		m.setAccessible(true);
		m.invoke(t); // must not throw

		assertFalse(t.isReady());
	}
}
