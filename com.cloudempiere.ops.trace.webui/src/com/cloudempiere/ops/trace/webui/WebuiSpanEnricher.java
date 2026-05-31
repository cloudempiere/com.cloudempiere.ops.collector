/******************************************************************************
 * Product: CloudEmpiere Webui Trace Enrichment                                *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 *****************************************************************************/
package com.cloudempiere.ops.trace.webui;

import java.util.List;
import java.util.Properties;

import org.compiere.util.Env;
import org.zkoss.zk.ui.Desktop;
import org.zkoss.zk.ui.Execution;
import org.zkoss.zk.ui.util.ExecutionCleanup;
import org.zkoss.zk.ui.util.ExecutionInit;

import com.cloudempiere.ops.trace.webui.internal.OtelSpan;

/**
 * Identity layer of the webui span enrichment (ADR-001 §11.1).
 * <p>
 * Runs on the <b>servlet thread</b> in {@link #init} — where the OTel agent's {@code /zkau}
 * server span IS the current span — and stamps <i>who/where</i> business context onto it so a
 * slow click in X-Ray shows the user / client / role / session / desktop, not just the SQL.
 * Registered as a ZK {@code <listener>} via {@code metainfo/zk/config.xml}; this bundle is a
 * fragment of {@code org.adempiere.ui.zk} so ZK discovers that config on the host classpath.
 * <p>
 * Identity is done here (not in the {@link WebuiEventEnricher}) precisely because of the
 * §11.1 event-thread caveat: {@code Span.current()} is reliably the request span on the
 * servlet thread. Everything is fully guarded — a telemetry failure must never break a click.
 *
 * @author CloudEmpiere
 */
public class WebuiSpanEnricher implements ExecutionInit, ExecutionCleanup {

	@Override
	public void init(Execution exec, Execution parent) {
		// Only the outermost execution carries the request span; skip included/forwarded ones.
		// (OTel API is now a real compile dependency — no availability gate; setAttribute no-ops
		// harmlessly when the agent is off.)
		if (parent != null) return;
		try {
			Properties ctx = Env.getCtx();
			OtelSpan.setLong("ad.client_id", Env.getAD_Client_ID(ctx));
			OtelSpan.setLong("ad.user_id", Env.getAD_User_ID(ctx));
			OtelSpan.setLong("ad.role_id", Env.getAD_Role_ID(ctx));
			OtelSpan.setLong("ad.session_id", Env.getContextAsInt(ctx, "#AD_Session_ID"));

			String userName = Env.getContext(ctx, "#AD_User_Name");
			if (userName != null && !userName.isEmpty()) {
				OtelSpan.setStr("enduser.id", userName);            // OTel semconv (queryable in X-Ray)
			} else {
				int uid = Env.getAD_User_ID(ctx);
				if (uid > 0) OtelSpan.setStr("enduser.id", Integer.toString(uid));
			}

			Desktop desktop = exec.getDesktop();
			if (desktop != null) OtelSpan.setStr("zk.desktop_id", desktop.getId());
		} catch (Throwable t) {
			// never propagate — enrichment is best-effort
		}
	}

	@Override
	public void cleanup(Execution exec, Execution parent, List<Throwable> errs) {
		// no-op: attributes were set on the request span during init().
	}
}
