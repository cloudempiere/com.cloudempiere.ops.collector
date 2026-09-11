/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal.customspi;

import java.util.List;

/**
 * One required extraction-work SPI seam: a {@code Core.getXxx()} accessor
 * (org.cloudempiere.base.Core) resolved via {@code Service.locator()} — an OSGi service lookup.
 * See {@link SpiRegistry} for the full catalog and its provenance.
 * <p>
 * Plain immutable holder, not a {@code record} — this bundle targets JavaSE-11.
 *
 * @author CloudEmpiere
 */
public final class RequiredSpi {

	/** The seam interface's simple name (e.g. {@code IWmsStorageOwnerPolicy}). */
	private final String name;

	/**
	 * The interface's fully-qualified class name, stored as a {@code String} on purpose — checked
	 * against the OSGi service registry via {@code BundleContext.getServiceReferences(String,
	 * String)}, so this module never needs a compile-time {@code Import-Package} on the seam's
	 * package.
	 */
	private final String interfaceFqcn;

	/** The {@code Core} accessor method name (diagnostics only). */
	private final String accessorName;

	/**
	 * {@code core/} files that call this seam (diagnostics only, from
	 * SPI-CALL-SITE-CONVENTION.md §5; empty for a seam with no core/ caller).
	 */
	private final List<String> coreFiles;

	/**
	 * True for a seam confirmed to have zero implementations anywhere in the codebase (verified by
	 * grepping every repo for {@code implements <Interface>}, not just this deployment) — tracked in
	 * the registry for visibility, but excluded from {@link CustomSpiHealthSource}'s missing-set /
	 * gating. As of 2026-09-11, {@code IDunningPersonResolver} is the only such entry among the 83;
	 * see SPEC-custom-spi-health.md for how it was distinguished from a genuine deployment gap (the
	 * `org.idempiere.banking` seams, which DO have real implementations, just weren't wired into one
	 * local instance's launch config).
	 */
	private final boolean optional;

	public RequiredSpi(String name, String interfaceFqcn, String accessorName, List<String> coreFiles) {
		this(name, interfaceFqcn, accessorName, coreFiles, false);
	}

	private RequiredSpi(String name, String interfaceFqcn, String accessorName, List<String> coreFiles, boolean optional) {
		this.name = name;
		this.interfaceFqcn = interfaceFqcn;
		this.accessorName = accessorName;
		this.coreFiles = coreFiles;
		this.optional = optional;
	}

	/** Same fields, but confirmed to have zero implementations anywhere — never gates readiness. */
	public static RequiredSpi optional(String name, String interfaceFqcn, String accessorName, List<String> coreFiles) {
		return new RequiredSpi(name, interfaceFqcn, accessorName, coreFiles, true);
	}

	public String name() { return name; }
	public String interfaceFqcn() { return interfaceFqcn; }
	public String accessorName() { return accessorName; }
	public List<String> coreFiles() { return coreFiles; }
	public boolean optional() { return optional; }
}
