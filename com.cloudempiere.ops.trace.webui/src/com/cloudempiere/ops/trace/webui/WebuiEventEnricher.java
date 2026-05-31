/******************************************************************************
 * Product: CloudEmpiere Webui Trace Enrichment                                *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 *****************************************************************************/
package com.cloudempiere.ops.trace.webui;

import org.adempiere.webui.adwindow.ADWindow;
import org.adempiere.webui.adwindow.ADWindowContent;
import org.adempiere.webui.desktop.IDesktop;
import org.adempiere.webui.session.SessionManager;
import org.compiere.model.GridTab;
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
		// no-op
	}

	private void enrichCurrentSpan() {
		try {
			IDesktop appDesktop = SessionManager.getAppDesktop();
			if (appDesktop == null) return;
			Component active = appDesktop.getActiveWindow();
			if (active == null) return;

			ADWindow adWindow = ADWindow.findADWindow(active);
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
