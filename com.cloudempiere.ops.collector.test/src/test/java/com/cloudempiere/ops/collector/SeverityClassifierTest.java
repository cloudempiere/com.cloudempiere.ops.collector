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
		assertEquals(Severity.PANIC, classifier.classify("plugin.component.unsatisfied"));
		assertEquals(Severity.PANIC, classifier.classify("plugin.resolution.failed"));
		assertEquals(Severity.PANIC, classifier.classify("plugin.descriptor.missing"));
		assertEquals(Severity.PANIC, classifier.classify("plugin.framework.error"));
		assertEquals(Severity.PANIC, classifier.classify("ops.jvm.oom"));
		assertEquals(Severity.PANIC, classifier.classify("ops.boot.stalled"));
		assertEquals(Severity.PANIC, classifier.classify("ops.boot.crash_loop_detected"));
	}

	@Test
	void tenant_scope_is_alert() {
		assertEquals(Severity.ALERT, classifier.classify("plugin.2pack.failed"));
		assertEquals(Severity.ALERT, classifier.classify("ops.runtime.component_degraded"));
	}

	@Test
	void expected_lifecycle_is_audit() {
		assertEquals(Severity.AUDIT, classifier.classify("plugin.lifecycle.recovered"));
		assertEquals(Severity.AUDIT, classifier.classify("plugin.2pack.succeeded"));
		assertEquals(Severity.AUDIT, classifier.classify("ops.boot.phase_started"));
		assertEquals(Severity.AUDIT, classifier.classify("ops.boot.completed"));
		assertEquals(Severity.AUDIT, classifier.classify("task.draining"));
		assertEquals(Severity.AUDIT, classifier.classify("plugin.warmup.completed"));
	}

	@Test
	void unknown_and_null_default_to_audit() {
		assertEquals(Severity.AUDIT, classifier.classify("something.totally.unknown"));
		assertEquals(Severity.AUDIT, classifier.classify(null));
	}
}
