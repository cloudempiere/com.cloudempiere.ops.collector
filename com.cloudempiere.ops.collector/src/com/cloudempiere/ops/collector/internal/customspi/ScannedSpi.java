/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal.customspi;

import org.osgi.framework.Bundle;

/**
 * One SPI interface found live by {@link SpiPackageScanner} -- replaces the hand-maintained
 * {@code SpiRegistry}/{@code RequiredSpi} pair (deleted 2026-09-23, see ADR-123 and
 * {@code cloudempiere-workspace/scripts/seam-inventory.py}).
 * <p>
 * Plain immutable holder, not a {@code record} -- this bundle targets JavaSE-11.
 *
 * @author CloudEmpiere
 */
public final class ScannedSpi {

	private final String name;
	private final String interfaceFqcn;
	private final String bundleSymbolicName;
	private final int bundleState;
	private final boolean optional;
	private final String producedBy;

	public ScannedSpi(String name, String interfaceFqcn, String bundleSymbolicName, int bundleState, boolean optional) {
		this(name, interfaceFqcn, bundleSymbolicName, bundleState, optional, null);
	}

	public ScannedSpi(String name, String interfaceFqcn, String bundleSymbolicName, int bundleState, boolean optional,
			String producedBy) {
		this.name = name;
		this.interfaceFqcn = interfaceFqcn;
		this.bundleSymbolicName = bundleSymbolicName;
		this.bundleState = bundleState;
		this.optional = optional;
		this.producedBy = producedBy;
	}

	/** This seam, marked as the product of {@code factoryFqcn} -- see {@link #producedBy()}. */
	ScannedSpi withProducedBy(String factoryFqcn) {
		return new ScannedSpi(name, interfaceFqcn, bundleSymbolicName, bundleState, optional, factoryFqcn);
	}

	public String name() { return name; }
	public String interfaceFqcn() { return interfaceFqcn; }
	/** Symbolic name of the bundle the interface's own .class is on (diagnostics). This is the
	 * seam-*defining* bundle, not necessarily the implementer's bundle -- see
	 * {@link CustomSpiHealthSource}'s service lookup for who's actually providing it. */
	public String bundleSymbolicName() { return bundleSymbolicName; }
	/** That bundle's {@link Bundle#getState()} as of this scan (diagnostics). */
	public int bundleState() { return bundleState; }
	public String bundleStateName() { return stateName(bundleState); }
	/** True when this seam never gates readiness: it carries {@code @OptionalSpi}, or it is a
	 * factory product ({@link #producedBy()} is set). */
	public boolean optional() { return optional || producedBy != null; }
	/** FQCN of another scanned interface with a method that returns this one (e.g. {@code IDMS},
	 * returned by {@code IDMSFactory.get()}), or {@code null}. A factory product reaches callers
	 * through its factory and is never itself registered as a service, so the factory -- which
	 * is scanned and gates readiness on its own -- carries the check. */
	public String producedBy() { return producedBy; }

	/** Shared with {@link CustomSpiHealthSource}'s service-reference diagnostics so both report
	 * bundle state the same way. */
	static String stateName(int state) {
		switch (state) {
			case Bundle.UNINSTALLED: return "UNINSTALLED";
			case Bundle.INSTALLED:   return "INSTALLED";
			case Bundle.RESOLVED:    return "RESOLVED";
			case Bundle.STARTING:    return "STARTING";
			case Bundle.STOPPING:    return "STOPPING";
			case Bundle.ACTIVE:      return "ACTIVE";
			default:                 return "UNKNOWN(" + state + ")";
		}
	}
}
