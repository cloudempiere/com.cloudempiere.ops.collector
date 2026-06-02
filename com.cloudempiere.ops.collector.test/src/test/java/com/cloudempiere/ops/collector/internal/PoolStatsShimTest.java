/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.cloudempiere.ops.collector.internal.PoolStatsShim.Stats;

/**
 * Unit tests for the HikariCP pool decision boundaries (SPEC-runtime-health-source §6). The Stats
 * value object is the pure logic the {@code RuntimeHealthSource} keys its pool events off — these
 * lock down {@code known()} / {@code utilization()} / {@code exhausted()} without needing a live
 * pool or reflection.
 */
class PoolStatsShimTest {

	@Test
	void unknown_is_not_known_and_never_a_problem() {
		Stats s = Stats.UNKNOWN;
		assertFalse(s.known());
		assertEquals(-1d, s.utilization());
		assertFalse(s.exhausted(), "an unreadable pool must not look exhausted");
	}

	@Test
	void healthy_pool_has_low_utilization_and_no_exhaustion() {
		Stats s = new Stats(5, 15, 20, 0, 20);   // active, idle, total, waiting, max
		assertTrue(s.known());
		assertEquals(0.25d, s.utilization(), 1e-9);
		assertFalse(s.exhausted());
	}

	@Test
	void full_pool_without_waiters_is_not_exhausted() {
		// at-capacity but nobody queued — busy, not failing.
		Stats s = new Stats(20, 0, 20, 0, 20);
		assertEquals(1.0d, s.utilization(), 1e-9);
		assertFalse(s.exhausted(), "no queued threads → not exhausted");
	}

	@Test
	void full_pool_with_waiters_is_exhausted() {
		// no headroom AND threads queued for a connection → every request will soon block.
		Stats s = new Stats(20, 0, 20, 3, 20);
		assertTrue(s.exhausted());
	}

	@Test
	void zero_max_is_unknown() {
		Stats s = new Stats(0, 0, 0, 0, 0);
		assertFalse(s.known());
		assertFalse(s.exhausted());
	}
}
