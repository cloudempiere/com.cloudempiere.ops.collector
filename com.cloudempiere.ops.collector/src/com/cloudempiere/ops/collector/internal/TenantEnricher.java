/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal;

import org.compiere.util.Env;
import org.osgi.service.component.annotations.Component;

/**
 * Resolves the tenant ({@code AD_Client_ID}) for the current thread, per ADR-001 §4.4.
 * <p>
 * Returns the client id only when a tenant context is actually present on the calling thread
 * (e.g. a model/callout failure on a request thread). Collector events that fire on framework
 * or poll threads have no tenant — those are task-scoped and carry no {@code tenant} block.
 * <p>
 * <b>ID only, never the name</b> (ADR §4.13 / §10.3): the client name is PII and must not enter
 * the event payload; downstream (the bridge / CLDE-Hub) resolves names if needed. We also
 * distinguish "absent" from the System client (0) by inspecting the raw context string rather
 * than {@link Env#getAD_Client_ID} (which returns 0 for both).
 *
 * @author CloudEmpiere
 */
@Component(service = TenantEnricher.class)
public class TenantEnricher {

	/** Negative when no tenant context is on the current thread. */
	public static final int NO_TENANT = -1;

	/**
	 * @return the current {@code AD_Client_ID}, or {@link #NO_TENANT} if the thread carries no
	 *         tenant context. Never throws.
	 */
	public int currentClientId() {
		try {
			String raw = Env.getContext(Env.getCtx(), "#AD_Client_ID");
			if (raw == null || raw.isEmpty()) return NO_TENANT;   // absent (not System=0)
			return Integer.parseInt(raw.trim());
		} catch (Throwable t) {
			return NO_TENANT;
		}
	}
}
