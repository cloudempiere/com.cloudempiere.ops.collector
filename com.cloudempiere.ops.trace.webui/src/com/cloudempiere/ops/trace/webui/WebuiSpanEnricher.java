/******************************************************************************
 * Product: CloudEmpiere Webui Trace Enrichment                                *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 *****************************************************************************/
package com.cloudempiere.ops.trace.webui;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.compiere.util.CLogger;
import org.compiere.util.Env;
import org.zkoss.zk.ui.Desktop;
import org.zkoss.zk.ui.Execution;
import org.zkoss.zk.ui.util.ExecutionCleanup;
import org.zkoss.zk.ui.util.ExecutionInit;

import com.cloudempiere.ops.trace.webui.internal.OtelSpan;

/**
 * Identity layer of the webui span enrichment (ADR-001 §11.1).
 * <p>
 * <b>Capture at init, stamp at cleanup (CLD-1912 §11.4 verdict).</b> The diagnostic proved
 * {@code Span.current()} is the <i>no-op</i> span at {@link #init} (servlet/QTP thread,
 * {@code spanValid=false}) — the OTel agent records/exports the {@code /zkau} server span but does not
 * make it current in the {@code Context} that early. The span <i>does</i> become current deeper in the
 * request (the agent's nested JDBC child spans prove it) and is still current at request end. So we:
 * <ol>
 *   <li>capture the iDempiere identity at {@link #init} — where {@code Env} is populated on this thread; and</li>
 *   <li>stamp it onto the span at {@link #cleanup} — where the agent's span is current.</li>
 * </ol>
 * A best-effort stamp also runs at init in case the span is already current there. {@code cleanup} logs
 * its own {@code spanValid} (bounded) so we can confirm from logs that the span is current at that point.
 *
 * @author CloudEmpiere
 */
public class WebuiSpanEnricher implements ExecutionInit, ExecutionCleanup {

	private static final CLogger log = CLogger.getCLogger(WebuiSpanEnricher.class);
	private static final String STASH = "ops.trace.identity";   // exec-scoped captured identity
	private static final AtomicInteger DIAG_INIT = new AtomicInteger(0);
	private static final AtomicInteger DIAG_CLEAN = new AtomicInteger(0);

	@Override
	public void init(Execution exec, Execution parent) {
		// Only the outermost execution carries the request span; skip included/forwarded ones.
		if (parent != null) return;
		try {
			Properties ctx = Env.getCtx();
			Identity id = new Identity();
			id.clientId = Env.getAD_Client_ID(ctx);
			id.userId = Env.getAD_User_ID(ctx);
			id.roleId = Env.getAD_Role_ID(ctx);
			id.sessionId = Env.getContextAsInt(ctx, "#AD_Session_ID");
			id.userName = Env.getContext(ctx, "#AD_User_Name");
			Desktop desktop = exec.getDesktop();
			id.desktopId = desktop != null ? desktop.getId() : null;
			// Stash now (Env is populated on this servlet thread); stamp the span at cleanup (span current there).
			exec.setAttribute(STASH, id);
			// Best-effort: if the span happens to be current already at init, stamp now too.
			if (OtelSpan.currentSpanValid()) stamp(id);

			if (id.userId > 0 && DIAG_INIT.getAndIncrement() < 12) {
				log.warning("[ops.trace.webui] DIAG init: parentNull=true thread="
						+ Thread.currentThread().getName() + " spanValid=" + OtelSpan.currentSpanValid()
						+ " uid=" + id.userId);
			}
		} catch (Throwable t) {
			// never propagate — enrichment is best-effort
		}
	}

	@Override
	public void cleanup(Execution exec, Execution parent, List<Throwable> errs) {
		if (parent != null) return;
		try {
			Object stashed = exec.getAttribute(STASH);
			if (!(stashed instanceof Identity)) return;
			Identity id = (Identity) stashed;
			if (id.userId > 0 && DIAG_CLEAN.getAndIncrement() < 12) {
				log.warning("[ops.trace.webui] DIAG cleanup: thread=" + Thread.currentThread().getName()
						+ " spanValid=" + OtelSpan.currentSpanValid() + " uid=" + id.userId);
			}
			// Span is current at request end (within the agent's still-open server-span scope) → attrs land.
			stamp(id);
		} catch (Throwable t) {
			// never propagate
		}
	}

	/** Stamp the captured identity onto whatever span is current now (no-op if invalid). */
	private static void stamp(Identity id) {
		OtelSpan.setLong("ad.client_id", id.clientId);
		OtelSpan.setLong("ad.user_id", id.userId);
		OtelSpan.setLong("ad.role_id", id.roleId);
		OtelSpan.setLong("ad.session_id", id.sessionId);
		if (id.userName != null && !id.userName.isEmpty()) {
			OtelSpan.setStr("enduser.id", id.userName);            // OTel semconv (queryable in X-Ray)
		} else if (id.userId > 0) {
			OtelSpan.setStr("enduser.id", Integer.toString(id.userId));
		}
		if (id.desktopId != null) OtelSpan.setStr("zk.desktop_id", id.desktopId);
	}

	/** Request-scoped capture of the iDempiere identity, computed at init, stamped at cleanup. */
	private static final class Identity {
		int clientId, userId, roleId, sessionId;
		String userName, desktopId;
	}
}
