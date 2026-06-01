/******************************************************************************
 * Product: CloudEmpiere Webui Trace Enrichment                                *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 *****************************************************************************/
package com.cloudempiere.ops.trace.webui;

import java.util.concurrent.atomic.AtomicInteger;

import org.adempiere.webui.adwindow.ADWindow;
import org.adempiere.webui.adwindow.ADWindowContent;
import org.adempiere.webui.desktop.IDesktop;
import org.adempiere.webui.session.SessionManager;
import org.compiere.model.GridTab;
import org.compiere.util.CLogger;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.util.EventInterceptor;

import com.cloudempiere.ops.trace.webui.internal.OtelSpan;

/**
 * Functional layer of the webui span enrichment (ADR-001 §11.1) — the <i>where</i>: which
 * window / tab / table / record a click is touching. Resolves the active {@code ADWindow}
 * from the ZK desktop's active window and stamps its identifiers onto the current OTel span.
 * <p>
 * <b>Event-thread caveat (§11.1):</b> {@code beforeProcessEvent} runs on ZK's event thread,
 * where {@code Span.current()} may be a no-op if the request span wasn't propagated across the
 * servlet→event-thread handoff. {@link OtelSpan} degrades silently in that case; the reliable
 * identity attributes are set on the servlet thread by {@link WebuiSpanEnricher}. Confirm on a
 * live webui task (set one attribute, verify it lands) before relying on functional filtering.
 *
 * @author CloudEmpiere
 */
public class WebuiEventEnricher implements EventInterceptor {

	private static final CLogger log = CLogger.getCLogger(WebuiEventEnricher.class);
	/** Cap each DIAG to the first dozen events so we don't spam CloudWatch (CLD-1912 §11.4). */
	private static final AtomicInteger DIAG_EVT = new AtomicInteger();
	private static final AtomicInteger DIAG_AFTER = new AtomicInteger();

	@Override
	public Event beforeProcessEvent(Event event) {
		enrichCurrentSpan();
		return event;
	}

	@Override
	public Event beforePostEvent(Event event) {
		return event;
	}

	@Override
	public Event beforeSendEvent(Event event) {
		return event;
	}

	@Override
	public void afterProcessEvent(Event event) {
		// afterProcessEvent runs AFTER the click handler executed its DB queries — and the JDBC child
		// spans proved the server span is current during those queries. So this is the likeliest point
		// where Span.current() is still valid. Probe it, and if valid, stamp here too (best-effort).
		try {
			boolean valid = OtelSpan.currentSpanValid();
			if (DIAG_AFTER.getAndIncrement() < 12) {
				log.warning("[ops.trace.webui] DIAG afterEvent: thread=" + Thread.currentThread().getName()
						+ " spanValid=" + valid);
			}
			if (valid) enrichCurrentSpan();
		} catch (Throwable t) {
			// best-effort
		}
	}

	private void enrichCurrentSpan() {
		try {
			IDesktop appDesktop = SessionManager.getAppDesktop();
			Component active = (appDesktop != null) ? appDesktop.getActiveWindow() : null;
			ADWindow adWindow = (active != null) ? ADWindow.findADWindow(active) : null;

			// DIAG (CLD-1912 §11.4): is the /zkau server span current at the EVENT-processing hook?
			// The JDBC child spans nest under the server span, which means the OTel Context IS active
			// somewhere in the request — this line tells us whether that "somewhere" is the ZK event
			// thread (→ stamp here works) or not (→ neither ZK hook works, need a servlet Filter / the
			// agent's stored span). Also reports whether an AD_Window was resolved, to disambiguate
			// "span invalid" from "no active window".
			if (DIAG_EVT.getAndIncrement() < 12) {
				log.warning("[ops.trace.webui] DIAG event: thread=" + Thread.currentThread().getName()
						+ " spanValid=" + OtelSpan.currentSpanValid()
						+ " window=" + (adWindow != null ? Integer.toString(adWindow.getAD_Window_ID()) : "none"));
			}

			if (adWindow == null) return;   // active component isn't an AD_Window (process/form/info) — see TODO

			OtelSpan.setLong("idempiere.window_id", adWindow.getAD_Window_ID());
			OtelSpan.setStr("idempiere.window_name", adWindow.getTitle());

			ADWindowContent content = adWindow.getADWindowContent();
			if (content != null) {
				GridTab tab = content.getActiveGridTab();
				if (tab != null) {
					OtelSpan.setLong("idempiere.tab_id", tab.getAD_Tab_ID());
					OtelSpan.setStr("idempiere.tab_name", tab.getName());
					OtelSpan.setStr("idempiere.table", tab.getTableName());
					OtelSpan.setLong("idempiere.record_id", tab.getRecord_ID());
				}
			}
			// TODO §11.1: process_id / form_id — when the active component is a process dialog
			//   or form (findADWindow returns null), detect those component types and resolve
			//   AD_Process_ID / AD_Form_ID. Deferred until validated on a live webui task.
		} catch (Throwable t) {
			// best-effort; never break event processing
		}
	}
}
