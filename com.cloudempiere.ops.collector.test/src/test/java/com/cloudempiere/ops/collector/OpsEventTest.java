/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 *****************************************************************************/
package com.cloudempiere.ops.collector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cloudempiere.ops.collector.api.OpsEvent;
import com.cloudempiere.ops.collector.api.Severity;

/** Unit tests for the exported event envelope (ADR-001 §4.8 / §4.15). */
class OpsEventTest {

	@Test
	void builder_sets_fields_and_defaults() {
		Map<String, Object> subject = new HashMap<>();
		subject.put("kind", "ds_component");
		OpsEvent e = OpsEvent.builder("plugin.component.failed_activation", Severity.PANIC)
				.eventId("id-1")
				.timestamp("2026-05-30T00:00:00Z")
				.subject(subject)
				.build();

		assertEquals("1.0", e.getSchemaVersion());
		assertEquals("plugin.component.failed_activation", e.getEventType());
		assertEquals(Severity.PANIC, e.getSeverity());
		assertEquals("id-1", e.getEventId());
		assertEquals("2026-05-30T00:00:00Z", e.getTimestamp());
		assertEquals("ds_component", e.getSubject().get("kind"));
		// default aggregation hint
		assertEquals("per_task", e.getAggregationHint());
	}

	@Test
	void unset_maps_are_empty_not_null() {
		OpsEvent e = OpsEvent.builder("task.ready", Severity.AUDIT).build();
		assertTrue(e.getTenant().isEmpty());
		assertTrue(e.getSubject().isEmpty());
		assertTrue(e.getDetails().isEmpty());
		assertTrue(e.getSource().isEmpty());
	}

	@Test
	void maps_are_immutable() {
		Map<String, Object> details = new HashMap<>();
		details.put("state", "FAILED_ACTIVATION");
		OpsEvent e = OpsEvent.builder("plugin.component.failed_activation", Severity.PANIC)
				.details(details)
				.build();
		assertThrows(UnsupportedOperationException.class, () -> e.getDetails().put("x", "y"));
	}

	@Test
	void aggregation_hint_override() {
		OpsEvent e = OpsEvent.builder("cluster.membership.changed", Severity.AUDIT)
				.aggregationHint("per_cluster")
				.build();
		assertEquals("per_cluster", e.getAggregationHint());
	}
}
