/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal;

import javax.sql.DataSource;

import org.compiere.db.AdempiereDatabase;
import org.compiere.util.DB;

/**
 * Reflection shim over the HikariCP connection pool (SPEC-runtime-health-source §6). iDempiere's
 * PostgreSQL provider wraps a {@code com.zaxxer.hikari.HikariDataSource}, but that package lives
 * embedded inside {@code org.compiere.db.postgresql.provider} and is <b>not</b> on this bundle's
 * target platform. Rather than couple via {@code Import-Package: com.zaxxer.hikari}, we reflect on
 * the {@code getHikariPoolMXBean()} accessor — mirroring the OTel reflection-shim pattern
 * ({@code internal/OtelContext}). On any non-Hikari provider (e.g. Oracle) or if reflection fails,
 * we return {@link Stats#UNKNOWN} and the caller silently skips pool events.
 * <p>
 * Zero DB cost: the pool MXBean is an in-JVM counter read, no SQL.
 *
 * @author CloudEmpiere
 */
final class PoolStatsShim {

	private PoolStatsShim() {}

	/** Immutable snapshot of pool counters. All {@code -1} when the pool can't be read. */
	static final class Stats {
		static final Stats UNKNOWN = new Stats(-1, -1, -1, -1, -1);

		final int active;
		final int idle;
		final int total;
		final int waiting;
		final int max;

		Stats(int active, int idle, int total, int waiting, int max) {
			this.active = active;
			this.idle = idle;
			this.total = total;
			this.waiting = waiting;
			this.max = max;
		}

		boolean known() {
			return max > 0;
		}

		/** active / max in [0,1]; {@code -1} when unknown. */
		double utilization() {
			return (known() && active >= 0) ? ((double) active) / max : -1d;
		}

		/** No headroom and threads queued for a connection → exhaustion. */
		boolean exhausted() {
			return known() && active >= max && waiting > 0;
		}
	}

	/**
	 * Read the live pool counters. Never throws — returns {@link Stats#UNKNOWN} on any failure
	 * (provider not Hikari, pool not yet open, reflection mismatch).
	 */
	static Stats read() {
		try {
			AdempiereDatabase db = DB.getDatabase();
			if (db == null) return Stats.UNKNOWN;

			// PG provider returns its single HikariDataSource regardless of the CConnection arg
			// (DB_PostgreSQL#getDataSource ignores it), so null is safe here.
			DataSource ds = db.getDataSource(null);
			if (ds == null) return Stats.UNKNOWN;

			// Only HikariDataSource exposes getHikariPoolMXBean(); guard by simple-name to avoid
			// a hard type dependency and to no-op cleanly on other providers.
			if (!ds.getClass().getName().contains("Hikari")) return Stats.UNKNOWN;

			Object mx = ds.getClass().getMethod("getHikariPoolMXBean").invoke(ds);
			if (mx == null) return Stats.UNKNOWN;

			int active = (int) mx.getClass().getMethod("getActiveConnections").invoke(mx);
			int idle = (int) mx.getClass().getMethod("getIdleConnections").invoke(mx);
			int total = (int) mx.getClass().getMethod("getTotalConnections").invoke(mx);
			int waiting = (int) mx.getClass().getMethod("getThreadsAwaitingConnection").invoke(mx);
			int max = (int) ds.getClass().getMethod("getMaximumPoolSize").invoke(ds);

			return new Stats(active, idle, total, waiting, max);
		} catch (Throwable t) {
			return Stats.UNKNOWN;
		}
	}
}
