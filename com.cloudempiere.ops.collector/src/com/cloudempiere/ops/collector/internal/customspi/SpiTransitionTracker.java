/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal.customspi;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure transition bookkeeping for {@link CustomSpiHealthSource} — deliberately split out from the
 * DS component (same spirit as {@code PoolStatsShim.Stats}) so the emit-once-per-transition logic
 * is testable without a live {@code BundleContext}.
 * <p>
 * {@link #update(Set)} is called from the single scheduled poller thread, but
 * {@link #currentMissing()} is read from HTTP request threads via {@code CustomSpiHealthSource
 * .isAllRequiredActive()}/{@code missingSpiNames()} (ultimately {@code /readyz}). Both methods are
 * {@code synchronized} on this instance so a request thread can never observe a half-updated set.
 * Contention is a non-issue: the poller fires every ~30s, reads are occasional HTTP requests.
 *
 * @author CloudEmpiere
 */
public final class SpiTransitionTracker {

	/** Result of one {@link #update(Set)} call: what changed since the previous poll. */
	public static final class Transition {
		private final Set<String> newlyMissing;
		private final Set<String> newlyRecovered;
		private final boolean becameAllActive;

		Transition(Set<String> newlyMissing, Set<String> newlyRecovered, boolean becameAllActive) {
			this.newlyMissing = Collections.unmodifiableSet(newlyMissing);
			this.newlyRecovered = Collections.unmodifiableSet(newlyRecovered);
			this.becameAllActive = becameAllActive;
		}

		/** Seam names that were present/unarmed last poll and are missing now. */
		public Set<String> newlyMissing() { return newlyMissing; }

		/** Seam names that were missing last poll and are present now. */
		public Set<String> newlyRecovered() { return newlyRecovered; }

		/** True exactly once, the poll where the missing set first became empty. */
		public boolean becameAllActive() { return becameAllActive; }
	}

	private final Set<String> missing = new HashSet<>();
	private boolean previouslyClean = false;

	/**
	 * Feed one poll's result (the set of seam names currently unregistered) and get back what
	 * changed since the previous call.
	 */
	public synchronized Transition update(Set<String> missingNow) {
		Set<String> newlyMissing = new HashSet<>();
		for (String name : missingNow) {
			if (!missing.contains(name)) newlyMissing.add(name);
		}
		Set<String> newlyRecovered = new HashSet<>();
		for (String name : missing) {
			if (!missingNow.contains(name)) newlyRecovered.add(name);
		}

		missing.clear();
		missing.addAll(missingNow);

		boolean cleanNow = missingNow.isEmpty();
		boolean becameAllActive = cleanNow && !previouslyClean;
		previouslyClean = cleanNow;

		return new Transition(newlyMissing, newlyRecovered, becameAllActive);
	}

	/** The current missing set, sorted, as of the last {@link #update(Set)} call. */
	public synchronized Set<String> currentMissing() {
		return new TreeSet<>(missing);
	}
}
