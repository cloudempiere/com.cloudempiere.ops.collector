/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 *****************************************************************************/
package com.cloudempiere.ops.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.cloudempiere.ops.collector.api.Severity;
import com.cloudempiere.ops.collector.internal.SeverityClassifier;

/** Unit tests for the event-type → severity mapping (ADR-001 §4.15, §5/§8). */
class SeverityClassifierTest {

	private final SeverityClassifier classifier = new SeverityClassifier();

	@Test
	void framework_scope_is_panic() {
		assertEquals(Severity.PANIC, classifier.classify("plugin.component.failed_activation"));
		assertEquals(Severity.PANIC, classifier.classify("plugin.resolution.failed"));
		assertEquals(Severity.PANIC, classifier.classify("plugin.descriptor.missing"));
		assertEquals(Severity.PANIC, classifier.classify("plugin.framework.error"));
		assertEquals(Severity.PANIC, classifier.classify("ops.jvm.oom"));
		assertEquals(Severity.PANIC, classifier.classify("ops.boot.stalled"));
		assertEquals(Severity.PANIC, classifier.classify("ops.boot.crash_loop_detected"));
		assertEquals(Severity.PANIC, classifier.classify("ops.migration.failed"));
	}

	@Test
	void runtime_data_layer_fleet_failures_are_panic() {
		// SPEC-runtime-health-source §5: a leak storm / pool exhaustion is fleet-level.
		assertEquals(Severity.PANIC, classifier.classify("ops.runtime.trx_leak_storm"));
		assertEquals(Severity.PANIC, classifier.classify("ops.runtime.pool_exhausted"));
	}

	@Test
	void tenant_scope_is_alert() {
		assertEquals(Severity.ALERT, classifier.classify("plugin.2pack.failed"));
		assertEquals(Severity.ALERT, classifier.classify("ops.runtime.component_degraded"));
		assertEquals(Severity.ALERT, classifier.classify("ops.migration.in_progress"));
	}

	@Test
	void runtime_data_layer_recoverable_signals_are_alert() {
		// SPEC-runtime-health-source §5: a single slow trx / transient pool spike is recoverable.
		assertEquals(Severity.ALERT, classifier.classify("ops.runtime.trx_long_running"));
		assertEquals(Severity.ALERT, classifier.classify("ops.runtime.pool_pressure"));
	}

	@Test
	void non_defensive_unsatisfied_is_audit_not_panic() {
		// PR #10 / ADR-001 §5: a DS UNSATISFIED_REFERENCE is too often benign to page on.
		assertEquals(Severity.AUDIT, classifier.classify("plugin.component.unsatisfied"));
	}

	@Test
	void expected_lifecycle_is_audit() {
		assertEquals(Severity.AUDIT, classifier.classify("plugin.lifecycle.recovered"));
		assertEquals(Severity.AUDIT, classifier.classify("plugin.2pack.succeeded"));
		assertEquals(Severity.AUDIT, classifier.classify("ops.boot.phase_started"));
		assertEquals(Severity.AUDIT, classifier.classify("ops.boot.completed"));
		assertEquals(Severity.AUDIT, classifier.classify("task.draining"));
		assertEquals(Severity.AUDIT, classifier.classify("plugin.warmup.completed"));
		assertEquals(Severity.AUDIT, classifier.classify("ops.runtime.ok"));   // recovery
	}

	@Test
	void unknown_and_null_default_to_audit() {
		assertEquals(Severity.AUDIT, classifier.classify("something.totally.unknown"));
		assertEquals(Severity.AUDIT, classifier.classify(null));
	}
}
