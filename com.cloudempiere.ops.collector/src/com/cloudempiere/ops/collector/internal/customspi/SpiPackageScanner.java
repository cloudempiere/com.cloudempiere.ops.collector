/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal.customspi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWiring;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.ClassRefTypeSignature;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.ScanResult;
import io.github.classgraph.TypeSignature;

/**
 * Live discovery of every CLDE SPI interface -- replaces the hand-maintained {@code SpiRegistry}
 * (213 entries, deleted 2026-09-23; see ADR-123 "Bundle-Qualified SPI Packages" and
 * {@code cloudempiere-workspace/scripts/seam-inventory.py}, which found the hand-maintained
 * version was permanently ~40% behind reality).
 * <p>
 * Method: every installed bundle's {@link BundleWiring} is asked which packages it exports
 * (standard OSGi capability introspection, no classloading involved); any package matching the
 * {@code com.cloudempiere.<bundle>.spi} convention (ADR-123 -- one such package per SPI-owning
 * bundle, e.g. {@code com.cloudempiere.base.spi}) is then scanned with ClassGraph, scoped to that
 * bundle's own classloader and that single package non-recursively, for every public interface
 * physically there. A newly-added SPI-owning bundle is picked up automatically, with zero code
 * change here, as long as it follows the naming convention.
 * <p>
 * An interface another scanned interface returns from a method (e.g. {@code IDMS} from
 * {@code IDMSFactory.get()}) is a factory product, not a seam: it is listed but never gates
 * readiness -- see {@link FactoryProducts}.
 * <p>
 * This bundle takes a compile-time {@code Import-Package} on {@code io.github.classgraph} only --
 * never on any of the {@code com.cloudempiere.*.spi} packages themselves, so adding a new one
 * never means touching this bundle's MANIFEST.MF.
 *
 * @author CloudEmpiere
 */
final class SpiPackageScanner implements SpiScanner {

	/** ADR-123: one .spi package per SPI-owning bundle, named after that bundle. */
	private static final Pattern SPI_PACKAGE = Pattern.compile("^com\\.cloudempiere\\.[a-z0-9]+(?:\\.[a-z0-9]+)*\\.spi$");

	/**
	 * Checked by simple annotation name only (ClassGraph reads it straight from the bytecode) so
	 * this bundle never needs {@code com.cloudempiere.base.spi} on its own classpath just to
	 * detect it. The annotation was deleted from core 2026-10-12 (applied nowhere); the check
	 * stays so a confirmed zero-implementer seam can be marked again without touching this bundle.
	 */
	private static final String OPTIONAL_ANNOTATION = "com.cloudempiere.base.spi.OptionalSpi";

	private final BundleContext ctx;

	SpiPackageScanner(BundleContext ctx) {
		this.ctx = ctx;
	}

	@Override
	public List<ScannedSpi> scan() {
		List<ScannedSpi> found = new ArrayList<>();
		Map<String, Set<String>> returnTypesByFqcn = new HashMap<>();
		for (Bundle bundle : ctx.getBundles()) {
			BundleWiring wiring = bundle.adapt(BundleWiring.class);
			if (wiring == null) continue; // not resolved (yet) -- nothing to scan
			for (BundleCapability cap : wiring.getCapabilities(BundleRevision.PACKAGE_NAMESPACE)) {
				Object pkgAttr = cap.getAttributes().get(BundleRevision.PACKAGE_NAMESPACE);
				if (!(pkgAttr instanceof String)) continue;
				String pkg = (String) pkgAttr;
				if (!SPI_PACKAGE.matcher(pkg).matches()) continue;
				found.addAll(scanPackage(bundle, wiring, pkg, returnTypesByFqcn));
			}
		}
		return FactoryProducts.mark(found, returnTypesByFqcn);
	}

	private static List<ScannedSpi> scanPackage(Bundle bundle, BundleWiring wiring, String pkg,
			Map<String, Set<String>> returnTypesByFqcn) {
		List<ScannedSpi> found = new ArrayList<>();
		ClassLoader classLoader = wiring.getClassLoader();
		if (classLoader == null) return found;

		try (ScanResult scanResult = new ClassGraph()
				.overrideClassLoaders(classLoader)
				.enableClassInfo()
				.enableAnnotationInfo()
				.enableMethodInfo()
				.disableNestedJarScanning()
				.disableModuleScanning()
				.acceptPackagesNonRecursive(pkg)
				.scan()) {
			ClassInfoList interfaces = scanResult.getAllInterfaces();
			for (ClassInfo ci : interfaces) {
				if (!pkg.equals(ci.getPackageName())) continue; // non-recursive, but be explicit
				if (ci.isInnerClass()) continue; // e.g. IExportExtraFieldFactory.ExtraField --
					// a companion type nested inside a real seam, not an independent one itself
				boolean optional = ci.hasAnnotation(OPTIONAL_ANNOTATION);
				returnTypesByFqcn.put(ci.getName(), returnTypes(ci));
				found.add(new ScannedSpi(ci.getSimpleName(), ci.getName(), bundle.getSymbolicName(),
						bundle.getState(), optional));
			}
		} catch (Exception e) {
			// A single misbehaving bundle's classloader must never take the whole scan down.
			return found;
		}
		return found;
	}

	/** Classes the interface's declared methods return directly (no generics, no arrays). */
	private static Set<String> returnTypes(ClassInfo ci) {
		Set<String> types = new HashSet<>();
		for (MethodInfo method : ci.getDeclaredMethodInfo()) {
			TypeSignature result = method.getTypeSignatureOrTypeDescriptor().getResultType();
			if (result instanceof ClassRefTypeSignature)
				types.add(((ClassRefTypeSignature) result).getFullyQualifiedClassName());
		}
		return types;
	}
}
