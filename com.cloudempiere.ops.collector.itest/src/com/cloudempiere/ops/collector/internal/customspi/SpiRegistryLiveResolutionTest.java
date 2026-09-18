/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal.customspi;

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
 * Runs {@link SpiRegistry}'s 83-seam catalog against the <b>real, booted OSGi service registry</b>
 * — the thing {@link CustomSpiHealthSourceTest} (in the plain-Maven {@code
 * com.cloudempiere.ops.collector.test} module) cannot do, since it fakes {@link BundleContext}
 * entirely. This is a {@code Fragment-Host: com.cloudempiere.ops.collector} test bundle, so it
 * shares the host's classloader and boots inside the same framework as production code — a green
 * run here means every mandatory seam actually has a plugin behind it in <i>this</i> target
 * platform, not just that {@link CustomSpiHealthSource}'s polling logic is internally consistent.
 * <p>
 * Deliberately NOT run by default (see this module's pom.xml) — like every other CLDE OSGi test
 * fragment, it needs a full local reactor build and a live Equinox launch (Tycho
 * {@code testRuntime=p2Installed}). Run with: {@code mvn verify -DskipTests=false}.
 *
 * @author CloudEmpiere
 */
@Tag("integration")
class SpiRegistryLiveResolutionTest {

	private static BundleContext bundleContext() {
		Bundle bundle = FrameworkUtil.getBundle(SpiRegistryLiveResolutionTest.class);
		assertNotNull(bundle, "FrameworkUtil could not resolve the fragment's own host bundle — not running under OSGi?");
		BundleContext ctx = bundle.getBundleContext();
		assertNotNull(ctx, "host bundle has no BundleContext — is it actually started?");
		return ctx;
	}

	@Test
	void everyRequiredSeamHasARealRegisteredImplementation() throws Exception {
		BundleContext ctx = bundleContext();
		List<String> missing = new ArrayList<>();

		for (RequiredSpi seam : SpiRegistry.all()) {
			if (seam.optional())
				continue;
			ServiceReference<?>[] refs = ctx.getServiceReferences(seam.interfaceFqcn(), null);
			if (refs == null || refs.length == 0)
				missing.add(seam.name() + " (" + seam.interfaceFqcn() + ", via Core." + seam.accessorName() + ")");
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
