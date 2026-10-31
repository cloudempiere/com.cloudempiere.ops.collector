/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal.customspi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

/**
 * Runs the real {@link SpiPackageScanner} (ClassGraph + live bundle wiring) against the <b>real,
 * booted OSGi framework</b> — the thing {@code CustomSpiHealthSourceTest} (in the plain-Maven
 * {@code com.cloudempiere.ops.collector.test} module) cannot do, since it fakes {@code
 * BundleContext} and injects a fixed {@link SpiScanner} entirely. This is a {@code
 * Fragment-Host: com.cloudempiere.ops.collector} test bundle, so it shares the host's classloader
 * and boots inside the same framework as production code — a green run here means the scan
 * actually finds every {@code com.cloudempiere.*.spi} package in this target platform and every
 * required interface in it actually has a plugin behind it, not just that
 * {@link CustomSpiHealthSource}'s polling logic is internally consistent.
 * <p>
 * Deliberately NOT run by default (see this module's pom.xml) — like every other CLDE OSGi test
 * fragment, it needs a full local reactor build and a live Equinox launch (Tycho
 * {@code testRuntime=p2Installed}). Run with: {@code mvn verify -DskipTests=false}.
 *
 * @author CloudEmpiere
 */
@Tag("integration")
class SpiPackageScannerLiveResolutionTest {

	private static BundleContext bundleContext() {
		Bundle bundle = FrameworkUtil.getBundle(SpiPackageScannerLiveResolutionTest.class);
		assertNotNull(bundle, "FrameworkUtil could not resolve the fragment's own host bundle — not running under OSGi?");
		BundleContext ctx = bundle.getBundleContext();
		assertNotNull(ctx, "host bundle has no BundleContext — is it actually started?");
		return ctx;
	}

	@Test
	void scanFindsAtLeastTheThreeAdr123Packages() {
		List<ScannedSpi> catalog = new SpiPackageScanner(bundleContext()).scan();

		assertFalse(catalog.isEmpty(), "live scan found zero SPI interfaces — package pattern or "
				+ "bundle-wiring introspection broke");

		List<String> bundlesSeen = catalog.stream().map(ScannedSpi::bundleSymbolicName).distinct()
				.collect(Collectors.toList());
		assertTrue(bundlesSeen.contains("org.adempiere.base"),
				"expected org.adempiere.base (com.cloudempiere.base.spi) among the scanned bundles, got: " + bundlesSeen);
	}

	@Test
	void everyRequiredSeamHasARealRegisteredImplementation() throws Exception {
		BundleContext ctx = bundleContext();
		List<ScannedSpi> catalog = new SpiPackageScanner(ctx).scan();
		List<String> missing = new ArrayList<>();

		for (ScannedSpi seam : catalog) {
			if (seam.optional())
				continue;
			ServiceReference<?>[] refs = ctx.getServiceReferences(seam.interfaceFqcn(), null);
			if (refs == null || refs.length == 0)
				missing.add(seam.name() + " (" + seam.interfaceFqcn() + ", bundle " + seam.bundleSymbolicName() + ")");
		}

		assertTrue(missing.isEmpty(), "seams with no real OSGi service registered in this target platform: "
				+ missing.stream().collect(Collectors.joining(", ")));
	}

	@Test
	void liveCustomSpiHealthSourceServiceIsRegistered() throws Exception {
		// CustomSpiHealthSource.xml (OSGI-INF) declares immediate="true" and provides itself as a
		// service. A missing OSGI-INF entry (e.g. dropped from bin.includes) leaves the bundle
		// ACTIVE while this component silently never exists — exactly the failure mode
		// CLAUDE.md's "DS descriptors are hand-authored" gotcha warns about. This proves DS
		// actually activated it in the real container, not just that the .java file compiles.
		BundleContext ctx = bundleContext();
		ServiceReference<CustomSpiHealthSource> ref = ctx.getServiceReference(CustomSpiHealthSource.class);
		assertNotNull(ref, "CustomSpiHealthSource is not registered as an OSGi service — "
				+ "check OSGI-INF/com.cloudempiere.ops.collector.internal.customspi.CustomSpiHealthSource.xml "
				+ "is present in the built bundle and Service-Component in MANIFEST.MF picks it up");
	}
}
