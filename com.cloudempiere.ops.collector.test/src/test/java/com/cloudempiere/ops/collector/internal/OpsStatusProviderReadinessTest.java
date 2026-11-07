/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

import com.cloudempiere.ops.collector.internal.customspi.CustomSpiHealthSource;

/** CLD-2583: {@code isReady()} must AND in {@link WebContextReadiness#isAllDeployed()}. */
class OpsStatusProviderReadinessTest {

	private static OpsStatusProvider provider(boolean webDeployed) throws Exception {
		return provider(true, false, true, webDeployed);
	}

	private static OpsStatusProvider provider(boolean booted, boolean draining, boolean spiActive,
			boolean webDeployed) throws Exception {
		BootPhaseTracker boot = mock(BootPhaseTracker.class);
		when(boot.isReady()).thenReturn(booted);
		ShutdownHandler shutdown = mock(ShutdownHandler.class);
		when(shutdown.isDraining()).thenReturn(draining);
		CustomSpiHealthSource customSpi = mock(CustomSpiHealthSource.class);
		when(customSpi.isAllRequiredActive()).thenReturn(spiActive);
		WebContextReadiness web = mock(WebContextReadiness.class);
		when(web.isAllDeployed()).thenReturn(webDeployed);

		OpsStatusProvider p = new OpsStatusProvider();
		set(p, "boot", boot);
		set(p, "shutdown", shutdown);
		set(p, "customSpi", customSpi);
		set(p, "web", web);
		return p;
	}

	private static void set(Object target, String name, Object value) throws Exception {
		Field f = OpsStatusProvider.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(target, value);
	}

	@Test
	void ready_only_when_web_contexts_deployed() throws Exception {
		assertTrue(provider(true).isReady());
		assertFalse(provider(false).isReady());
	}

	@Test
	void web_deployed_does_not_override_the_other_gates() throws Exception {
		assertFalse(provider(false, false, true, true).isReady(), "still booting");
		assertFalse(provider(true, true, true, true).isReady(), "draining");
		assertFalse(provider(true, false, false, true).isReady(), "custom SPI missing");
	}

	@Test
	void startup_and_liveness_are_not_gated_by_web_contexts() throws Exception {
		OpsStatusProvider p = provider(false);
		assertTrue(p.isAlive());
		assertTrue(p.isStartupComplete());
	}
}
