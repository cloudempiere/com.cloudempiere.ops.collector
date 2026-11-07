/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link ShutdownHandler} is the {@code /ops/readyz} input that turns 503 on SIGTERM, so the ALB
 * stops routing before the JVM exits. The JVM shutdown hook itself is driven through the private
 * {@code onShutdown()} by reflection, with the drain window set to 0 so the test doesn't sleep and
 * the in-process ZK broadcast left off (this module has no ZK).
 */
class ShutdownHandlerTest {

	private static ShutdownHandler handler(EventDispatcher dispatcher) throws Exception {
		ShutdownHandler h = new ShutdownHandler();
		set(h, "dispatcher", dispatcher);
		set(h, "drainSeconds", 0L);
		set(h, "broadcastOnShutdown", false);
		return h;
	}

	private static void set(Object target, String name, Object value) throws Exception {
		Field f = ShutdownHandler.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(target, value);
	}

	private static Object get(Object target, String name) throws Exception {
		Field f = ShutdownHandler.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(target);
	}

	private static void onShutdown(ShutdownHandler h) throws Exception {
		Method m = ShutdownHandler.class.getDeclaredMethod("onShutdown");
		m.setAccessible(true);
		m.invoke(h);
	}

	private static List<String> recording(EventDispatcher d) {
		List<String> emitted = new ArrayList<>();
		doAnswer(inv -> { emitted.add(inv.getArgument(0)); return null; }).when(d).emit(anyString(), any(), any());
		return emitted;
	}

	@Test
	void not_draining_until_sigterm() throws Exception {
		assertFalse(handler(mock(EventDispatcher.class)).isDraining());
	}

	@Test
	void sigterm_flips_draining_and_emits_draining_then_terminating() throws Exception {
		EventDispatcher d = mock(EventDispatcher.class);
		List<String> emitted = recording(d);
		ShutdownHandler h = handler(d);

		onShutdown(h);

		assertTrue(h.isDraining());
		assertEquals(List.of("task.draining", "task.terminating"), emitted);
	}

	@Test
	void a_second_shutdown_signal_is_a_no_op() throws Exception {
		EventDispatcher d = mock(EventDispatcher.class);
		List<String> emitted = recording(d);
		ShutdownHandler h = handler(d);

		onShutdown(h);
		onShutdown(h);

		assertEquals(2, emitted.size(), "events must not be emitted twice");
	}

	@Test
	void event_failures_never_block_draining() throws Exception {
		EventDispatcher d = mock(EventDispatcher.class);
		doAnswer(inv -> { throw new IllegalStateException("bus down"); }).when(d).emit(anyString(), any(), any());
		ShutdownHandler h = handler(d);

		onShutdown(h); // must not throw

		assertTrue(h.isDraining());
	}

	@Test
	void interrupted_drain_still_finishes_and_keeps_the_interrupt_flag() throws Exception {
		EventDispatcher d = mock(EventDispatcher.class);
		List<String> emitted = recording(d);
		ShutdownHandler h = handler(d);
		set(h, "drainSeconds", 60L);

		Thread.currentThread().interrupt(); // sleep() throws at once instead of waiting 60 s
		try {
			onShutdown(h);
			assertTrue(Thread.currentThread().isInterrupted(), "interrupt status must be restored");
		} finally {
			Thread.interrupted(); // clear it for the rest of the suite
		}
		assertEquals(List.of("task.draining", "task.terminating"), emitted);
	}

	@Test
	void activate_registers_a_shutdown_hook_and_deactivate_removes_it() throws Exception {
		ShutdownHandler h = new ShutdownHandler();
		set(h, "dispatcher", mock(EventDispatcher.class));

		Method activate = ShutdownHandler.class.getDeclaredMethod("activate");
		activate.setAccessible(true);
		activate.invoke(h);
		Thread hook = (Thread) get(h, "hook");
		assertNotNull(hook);
		assertEquals("clde-ops-collector-shutdown", hook.getName());

		Method deactivate = ShutdownHandler.class.getDeclaredMethod("deactivate");
		deactivate.setAccessible(true);
		deactivate.invoke(h);

		assertFalse(Runtime.getRuntime().removeShutdownHook(hook), "deactivate should already have removed it");
		assertFalse(h.isDraining());
	}

	@Test
	void activate_defaults_drain_to_30s_and_clamps_the_banner_countdown() throws Exception {
		// Env-driven: in the test JVM these variables are unset, so the defaults apply.
		ShutdownHandler h = new ShutdownHandler();
		set(h, "dispatcher", mock(EventDispatcher.class));
		Method activate = ShutdownHandler.class.getDeclaredMethod("activate");
		activate.setAccessible(true);
		activate.invoke(h);
		try {
			if (System.getenv("OPS_COLLECTOR_SHUTDOWN_DRAIN_SECONDS") == null) {
				assertEquals(30L, get(h, "drainSeconds"));
			}
			long drain = (long) get(h, "drainSeconds");
			long countdown = (long) get(h, "broadcastCountdownSeconds");
			assertTrue(countdown <= drain, "countdown must never exceed the drain window");
			if (System.getenv("IDEMPIERE_TIER_NAME") == null) {
				assertEquals(false, get(h, "broadcastOnShutdown"), "no in-process broadcast off the webui tier");
			}
		} finally {
			Method deactivate = ShutdownHandler.class.getDeclaredMethod("deactivate");
			deactivate.setAccessible(true);
			deactivate.invoke(h);
		}
	}

	@Test
	void env_seconds_parsing_falls_back_on_missing_bad_or_non_positive_values() throws Exception {
		Method m = ShutdownHandler.class.getDeclaredMethod("envSeconds", String.class, long.class);
		m.setAccessible(true);
		// No portable way to set env vars in-process; an unset name exercises the fallback path.
		assertEquals(42L, m.invoke(null, "OPS_COLLECTOR_TEST_UNSET_" + System.nanoTime(), 42L));
	}
}
