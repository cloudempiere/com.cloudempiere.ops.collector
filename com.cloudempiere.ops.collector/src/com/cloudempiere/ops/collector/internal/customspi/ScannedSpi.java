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

	public ScannedSpi(String name, String interfaceFqcn, String bundleSymbolicName, int bundleState, boolean optional) {
		this.name = name;
		this.interfaceFqcn = interfaceFqcn;
		this.bundleSymbolicName = bundleSymbolicName;
		this.bundleState = bundleState;
		this.optional = optional;
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
	/** True when the interface carries {@code @OptionalSpi} -- never gates readiness. */
	public boolean optional() { return optional; }

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
