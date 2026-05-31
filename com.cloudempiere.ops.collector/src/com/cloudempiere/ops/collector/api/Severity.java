/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.api;

/**
 * Severity tier of an operational event, per ADR-001 §5.
 * <p>
 * The tier is carried as a JSON field on the {@link OpsEvent} envelope, never as a
 * log level — every event logs at {@code ERROR} so CloudWatch metric filters can
 * target {@code { $.event_type = "plugin.*" && $.severity = "PANIC" }} (§4.13).
 *
 * @author CloudEmpiere
 */
public enum Severity {

	/** Framework-level / whole-container failure. CloudWatch alarm → page.
	 *  e.g. bundle fails to resolve, DS {@code FAILED_ACTIVATION}, OOM, boot stalled. */
	PANIC,

	/** Tenant-scoped, blocks business. DB + Slack.
	 *  e.g. 2Pack import failed, validator throws, scheduled process keeps failing. */
	ALERT,

	/** Notable but expected. DB only, for dashboard.
	 *  e.g. bundle stop-start (deploy), doc-state errors, login failures. */
	AUDIT;
}
