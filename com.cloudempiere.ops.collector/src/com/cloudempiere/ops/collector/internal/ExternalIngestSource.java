/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal;

import java.util.Collections;
import java.util.Map;

import org.compiere.util.CLogger;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;

/**
 * Inbound EventAdmin ingest for operational events produced by <b>other in-JVM bundles</b> that
 * must NOT compile-depend on this collector (boundary rule #3). External producers post a plain
 * OSGi {@link Event} on {@link #TOPIC_INGEST} carrying the property contract below; this handler
 * maps it to {@link EventDispatcher#emit(String, Map, Map)} so it flows through the normal sinks
 * (stdout floor, ring buffer, DB, outbound EventAdmin) and severity classification.
 *
 * <p>First producer: {@code com.cloudempiere.identity}'s {@code ErpContextGuard}
 * ({@code auth.erp_context.rejected}, ADR-0037). The topic and property keys are a string
 * contract duplicated on the producer side ({@code ErpContextGuard}) — keep them in sync.
 *
 * <p>Best-effort and defensive: a malformed inbound event is logged and dropped, never
 * propagated (an external producer must not be able to destabilise the collector).
 *
 * @author CloudEmpiere
 */
@Component(
		service = EventHandler.class,
		immediate = true,
		property = { EventConstants.EVENT_TOPIC + "=" + ExternalIngestSource.TOPIC_INGEST })
public class ExternalIngestSource implements EventHandler {

	private static final CLogger log = CLogger.getCLogger("com.cloudempiere.ops.collector");

	/** Shared ingest topic. MUST match the producer contract (identity {@code ErpContextGuard}). */
	public static final String TOPIC_INGEST = "com/cloudempiere/ops/ingest";

	// Property keys — MUST match the producer contract.
	private static final String PROP_EVENT_TYPE = "ops.event.type";
	private static final String PROP_SUBJECT = "ops.subject";
	private static final String PROP_DETAILS = "ops.details";

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	private EventDispatcher dispatcher;

	@Override
	public void handleEvent(Event event) {
		try {
			Object typeObj = event.getProperty(PROP_EVENT_TYPE);
			if (!(typeObj instanceof String) || ((String) typeObj).isEmpty()) {
				log.warning("ExternalIngestSource: dropping event with missing/blank " + PROP_EVENT_TYPE);
				return;
			}
			String eventType = (String) typeObj;
			Map<String, Object> subject = asMap(event.getProperty(PROP_SUBJECT));
			Map<String, Object> details = asMap(event.getProperty(PROP_DETAILS));
			dispatcher.emit(eventType, subject, details);
		} catch (Throwable t) {
			// An external producer must never be able to destabilise the collector.
			log.warning("ExternalIngestSource: ingest failed, dropping event: " + t.getMessage());
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object o) {
		return (o instanceof Map) ? (Map<String, Object>) o : Collections.emptyMap();
	}
}
