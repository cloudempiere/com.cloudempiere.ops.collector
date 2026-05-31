/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal;

import java.util.HashMap;
import java.util.Map;

import org.adempiere.base.event.AbstractEventHandler;
import org.adempiere.base.event.IEventTopics;
import org.compiere.model.PO;
import org.compiere.util.CLogger;
import org.osgi.service.event.Event;

/**
 * 2Pack import outcomes (ADR-001 §4.4). Rather than intercept {@code AdempiereActivator}
 * (a per-plugin base class we can't hook without patching), this listens — via iDempiere's
 * published model-event framework — to {@code AD_Package_Imp} rows: every pack-in (auto on
 * plugin start, or manual) writes one, and {@code PK_Status} records the outcome. Emits an
 * ALERT {@code plugin.2pack.failed} on failure (tenant-scoped, blocks business) or an AUDIT
 * {@code plugin.2pack.succeeded} otherwise.
 * <p>
 * Registered as an iDempiere event handler (extends {@link AbstractEventHandler}); the DS
 * descriptor binds {@code IEventManager} (per the core pattern) plus our {@link EventDispatcher}.
 *
 * @author CloudEmpiere
 */
public class TwoPackEventSource extends AbstractEventHandler {

	private static final CLogger log = CLogger.getCLogger("com.cloudempiere.ops.collector");
	private static final String TABLE = "AD_Package_Imp";

	private volatile EventDispatcher dispatcher;

	@Override
	protected void initialize() {
		registerTableEvent(IEventTopics.PO_AFTER_NEW, TABLE);
		registerTableEvent(IEventTopics.PO_AFTER_CHANGE, TABLE);
		log.info("2Pack outcome listener armed on " + TABLE);
	}

	@Override
	protected void doHandleEvent(Event event) {
		if (dispatcher == null) return;
		try {
			PO po = getPO(event);
			if (po == null) return;

			Object statusObj = po.get_Value("PK_Status");
			String status = statusObj == null ? null : statusObj.toString().trim();
			if (status == null || status.isEmpty()) return;   // not a terminal state yet

			Object nameObj = po.get_Value("Name");
			String pkg = nameObj == null ? "unknown" : nameObj.toString();

			Map<String, Object> subject = new HashMap<>();
			subject.put("kind", "2pack");
			subject.put("package", pkg);
			Map<String, Object> details = new HashMap<>();
			details.put("status", status);

			dispatcher.emit(isFailure(status) ? "plugin.2pack.failed" : "plugin.2pack.succeeded",
					subject, details);
		} catch (Throwable t) {
			log.warning("2Pack event handling failed: " + t);
		}
	}

	/** Tolerant match — PK_Status is free-ish text across versions. */
	private static boolean isFailure(String status) {
		String s = status.toLowerCase();
		return s.contains("fail") || s.contains("error") || s.contains("incomplet");
	}

	// DS bind/unbind for the EventDispatcher (IEventManager bind is on AbstractEventHandler).
	public void bindEventDispatcher(EventDispatcher d) { this.dispatcher = d; }
	public void unbindEventDispatcher(EventDispatcher d) { if (this.dispatcher == d) this.dispatcher = null; }
}
