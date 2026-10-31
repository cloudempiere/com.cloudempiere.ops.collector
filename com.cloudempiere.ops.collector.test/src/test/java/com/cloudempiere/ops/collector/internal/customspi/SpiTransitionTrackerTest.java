/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal.customspi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.cloudempiere.ops.collector.internal.customspi.SpiTransitionTracker.Transition;

/**
 * Pure-logic tests for the emit-once-per-transition bookkeeping
 * {@link CustomSpiHealthSource} delegates to (SPEC-custom-spi-health) — no {@code BundleContext}
 * or OSGi runtime needed, same spirit as {@code PoolStatsShimTest}.
 */
class SpiTransitionTrackerTest {

	@Test
	void first_poll_with_nothing_missing_reports_all_active_once() {
		SpiTransitionTracker tracker = new SpiTransitionTracker();
		Transition t = tracker.update(Set.of());
		assertTrue(t.newlyMissing().isEmpty());
		assertTrue(t.newlyRecovered().isEmpty());
		assertTrue(t.becameAllActive(), "a clean first poll should fire all_active once");
	}

	@Test
	void a_missing_seam_is_reported_once_not_every_poll() {
		SpiTransitionTracker tracker = new SpiTransitionTracker();

		Transition first = tracker.update(Set.of("IWmsStorageOwnerPolicy"));
		assertEquals(Set.of("IWmsStorageOwnerPolicy"), first.newlyMissing());
		assertFalse(first.becameAllActive());

		// steady-state missing: no repeat "newlyMissing" event on subsequent polls
		Transition second = tracker.update(Set.of("IWmsStorageOwnerPolicy"));
		assertTrue(second.newlyMissing().isEmpty(), "must not re-report an already-missing seam");
		assertTrue(second.newlyRecovered().isEmpty());
		assertFalse(second.becameAllActive());
	}

	@Test
	void recovery_is_reported_once_and_flips_all_active() {
		SpiTransitionTracker tracker = new SpiTransitionTracker();
		tracker.update(Set.of("IWmsStorageOwnerPolicy"));

		Transition recovered = tracker.update(Set.of());
		assertEquals(Set.of("IWmsStorageOwnerPolicy"), recovered.newlyRecovered());
		assertTrue(recovered.becameAllActive());

		// steady-state clean: no repeat all_active on subsequent clean polls
		Transition stillClean = tracker.update(Set.of());
		assertFalse(stillClean.becameAllActive(), "must not re-report all_active every clean poll");
		assertTrue(stillClean.newlyRecovered().isEmpty());
	}

	@Test
	void independent_seams_transition_independently() {
		SpiTransitionTracker tracker = new SpiTransitionTracker();
		tracker.update(Set.of("A", "B"));

		Transition t = tracker.update(Set.of("A", "C")); // B recovered, C newly missing, A steady
		assertEquals(Set.of("C"), t.newlyMissing());
		assertEquals(Set.of("B"), t.newlyRecovered());
		assertFalse(t.becameAllActive());
		assertEquals(Set.of("A", "C"), tracker.currentMissing());
	}

	@Test
	void current_missing_is_sorted_and_reflects_last_update() {
		SpiTransitionTracker tracker = new SpiTransitionTracker();
		tracker.update(Set.of("IZzz", "IAaa", "IMmm"));
		assertEquals(java.util.List.of("IAaa", "IMmm", "IZzz"), java.util.List.copyOf(tracker.currentMissing()));
	}
}
