/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal.customspi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWiring;

/**
 * The scan runs on a timer for the life of the server, so it must leave nothing behind. A plain
 * {@code ClassGraph.scan()} leaves about 19 {@link ThreadGroup}s per call that nothing ever
 * destroys: 1.1 million after three days on a real server (CLD-2507).
 */
class SpiPackageScannerThreadGroupTest {

	private static final String PACKAGE = "com.cloudempiere.opstest.spi";

	private static SpiPackageScanner scannerOverOneSpiPackage() {
		BundleCapability capability = mock(BundleCapability.class);
		when(capability.getAttributes()).thenReturn(Map.of(BundleRevision.PACKAGE_NAMESPACE, PACKAGE));
		BundleWiring wiring = mock(BundleWiring.class);
		when(wiring.getCapabilities(BundleRevision.PACKAGE_NAMESPACE)).thenReturn(List.of(capability));
		when(wiring.getClassLoader()).thenReturn(SpiPackageScannerThreadGroupTest.class.getClassLoader());
		Bundle bundle = mock(Bundle.class);
		when(bundle.adapt(BundleWiring.class)).thenReturn(wiring);
		when(bundle.getSymbolicName()).thenReturn("com.cloudempiere.opstest");
		BundleContext ctx = mock(BundleContext.class);
		when(ctx.getBundles()).thenReturn(new Bundle[] { bundle });
		return new SpiPackageScanner(ctx);
	}

	private static int threadGroups() {
		ThreadGroup root = Thread.currentThread().getThreadGroup();
		while (root.getParent() != null)
			root = root.getParent();
		return root.activeGroupCount();
	}

	@Test
	void scan_still_finds_the_seam() {
		List<ScannedSpi> found = scannerOverOneSpiPackage().scan();

		assertEquals(1, found.size());
		assertEquals("SampleSeam", found.get(0).name());
	}

	@Test
	void repeated_scans_leave_no_thread_groups_behind() {
		SpiPackageScanner scanner = scannerOverOneSpiPackage();
		scanner.scan();
		int before = threadGroups();

		for (int i = 0; i < 20; i++)
			assertTrue(scanner.scan().size() == 1, "the scan must really run for this to prove anything");

		assertEquals(before, threadGroups());
	}
}
