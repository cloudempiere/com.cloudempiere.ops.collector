/******************************************************************************
 * Product: CloudEmpiere OTel Agent Extension — SQL fan-out                     *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                        *
 * This program is free software; you can redistribute it and/or modify it      *
 * under the terms version 2 of the GNU General Public License as published     *
 * by the Free Software Foundation.                                             *
 *****************************************************************************/
package com.cloudempiere.ops.otel.ext;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.instrumenter.LocalRootSpan;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * Aggregates the per-request JDBC fan-out that the OTel Java agent already emits as raw
 * child spans, and stamps four queryable annotations onto the request's local-root
 * (server) span — <b>tier-agnostic</b>, so it covers {@code /zkau}, {@code /api/v1/*} and
 * {@code /graphql} with no iDempiere code change (ADR-001 §11.5).
 *
 * <p>The raw data (one slow click = thousands of JDBC spans, ADR-065) already exists in the
 * trace pipeline; this processor turns it into a single filterable number per request:
 * <pre>
 *   idempiere.sql.count          = JDBC statements executed
 *   idempiere.sql.distinct       = distinct sanitized statements  (count ≫ distinct = N+1)
 *   idempiere.sql.db_ms          = summed JDBC span duration (ms)
 *   idempiere.sql.top_statement  = most-frequent sanitized statement
 * </pre>
 *
 * <p><b>How attribution works.</b> {@code count ≫ distinct} ⇒ N+1 / a wrong query shape
 * (frontend / resolver); high {@code db_ms} with low {@code distinct} ⇒ a slow backend
 * (indexing / plan), and {@code top_statement} names the offender.
 *
 * <p><b>Why an agent extension, not the collector OSGi bundle.</b> The agent builds and
 * seals its {@code SdkTracerProvider} at JVM boot; a {@code SpanProcessor} cannot be added
 * to it afterwards from a bundle. It must be contributed through the autoconfigure SPI at
 * boot — see {@link SqlFanoutExtension} — which the agent loads from the extension jar named
 * by {@code -Dotel.javaagent.extensions=} (ADR-001 §11.5, Option A).
 *
 * <p><b>Correctness constraints (ADR-001 §11.5 caveats):</b>
 * <ul>
 * <li>State is keyed strictly by the <b>local-root {@link SpanContext}</b> ({@link LocalRootSpan}),
 *     never a thread-local — the processor sees spans from every thread/request, and JDBC may run
 *     on a different thread than the server span started on (async handoff). {@code LocalRootSpan}
 *     follows agent context propagation, so the key is stable across that handoff.</li>
 * <li>Aggregates are written onto the root <b>while it is still live</b> (child spans end before
 *     their parent, so on each JDBC {@code onEnd} the root is still open and writable). Writing in
 *     the root's own {@code onEnd} would be a no-op — an ended span rejects {@code setAttribute}.</li>
 * <li>The per-root map is bounded: entries evict on root end, plus a TTL sweep guards a dropped
 *     {@code onEnd} from leaking.</li>
 * <li>Only the agent-<b>sanitized</b> {@code db.statement} (literals already stripped) is read;
 *     no raw statement values are copied.</li>
 * </ul>
 *
 * @author CloudEmpiere
 */
public final class SqlFanoutSpanProcessor implements SpanProcessor {

    // db.* attribute keys — referenced by name to avoid coupling to a semconv artifact version.
    private static final AttributeKey<String> DB_SYSTEM    = AttributeKey.stringKey("db.system");
    private static final AttributeKey<String> DB_STATEMENT = AttributeKey.stringKey("db.statement");

    // Emitted annotations (must match clde-infra indexed_attributes, ADR-001 §11.5).
    private static final AttributeKey<Long>   SQL_COUNT    = AttributeKey.longKey("idempiere.sql.count");
    private static final AttributeKey<Long>   SQL_DISTINCT = AttributeKey.longKey("idempiere.sql.distinct");
    private static final AttributeKey<Long>   SQL_DB_MS    = AttributeKey.longKey("idempiere.sql.db_ms");
    private static final AttributeKey<String> SQL_TOP      = AttributeKey.stringKey("idempiere.sql.top_statement");

    /** Evict a root/pending entry whose owner never delivered an onEnd after this long. */
    private static final long TTL_NANOS = TimeUnit.MINUTES.toNanos(2);

    /** Live requests: local-root SpanContext → running aggregate. */
    private final ConcurrentHashMap<SpanContext, RootAgg> roots = new ConcurrentHashMap<>();

    /** In-flight JDBC child span-id → its root aggregate (onStart has context, onEnd does not). */
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    private final ScheduledExecutorService reaper;

    public SqlFanoutSpanProcessor() {
        this.reaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ops-otel-sqlfanout-reaper");
            t.setDaemon(true);
            return t;
        });
        this.reaper.scheduleAtFixedRate(this::sweep, 60, 60, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ onStart

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        try {
            if (span.getKind() == SpanKind.SERVER) {
                // A request root: register it so children can find it and we can stamp it.
                roots.put(span.getSpanContext(), new RootAgg(span));
                return;
            }
            if (span.getAttribute(DB_SYSTEM) == null) {
                return; // not a JDBC/DB client span
            }
            Span root = LocalRootSpan.fromContextOrNull(parentContext);
            if (root == null) {
                return;
            }
            RootAgg agg = roots.get(root.getSpanContext());
            if (agg != null) {
                // Bridge start→end: onEnd(child) has no context, so remember the target now.
                pending.put(span.getSpanContext().getSpanId(), new Pending(agg));
            }
        } catch (Throwable ignore) {
            // telemetry must never break a request
        }
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    // -------------------------------------------------------------------- onEnd

    @Override
    public void onEnd(ReadableSpan span) {
        try {
            if (span.getAttribute(DB_SYSTEM) != null) {
                Pending p = pending.remove(span.getSpanContext().getSpanId());
                if (p != null) {
                    p.agg.record(span);   // accumulate this statement
                    p.agg.stamp();        // refresh annotations on the still-live root
                }
                return;
            }
            if (span.getKind() == SpanKind.SERVER) {
                // Root ending: annotations already stamped by the last child; just release.
                roots.remove(span.getSpanContext());
            }
        } catch (Throwable ignore) {
            // telemetry must never break a request
        }
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    // ------------------------------------------------------------------- sweeper

    private void sweep() {
        long now = System.nanoTime();
        roots.values().removeIf(a -> now - a.createdNanos > TTL_NANOS);
        pending.values().removeIf(p -> now - p.createdNanos > TTL_NANOS);
    }

    @Override
    public io.opentelemetry.sdk.common.CompletableResultCode shutdown() {
        reaper.shutdownNow();
        roots.clear();
        pending.clear();
        return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
    }

    // ------------------------------------------------------------- inner state

    /** In-flight child → root bridge with a creation stamp for TTL eviction. */
    private static final class Pending {
        final RootAgg agg;
        final long createdNanos = System.nanoTime();
        Pending(RootAgg agg) { this.agg = agg; }
    }

    /** Running per-request aggregate; written onto the live root span on every child end. */
    private static final class RootAgg {
        final ReadWriteSpan root;
        final long createdNanos = System.nanoTime();
        final AtomicLong count  = new AtomicLong();
        final AtomicLong dbNanos = new AtomicLong();
        // Sanitized statement → frequency; size() is the distinct count.
        final ConcurrentHashMap<String, Long> freq = new ConcurrentHashMap<>();
        volatile String top;
        volatile long topFreq;

        RootAgg(ReadWriteSpan root) { this.root = root; }

        void record(ReadableSpan child) {
            count.incrementAndGet();
            long lat = child.getLatencyNanos();
            if (lat > 0) {
                dbNanos.addAndGet(lat);
            }
            String stmt = child.getAttribute(DB_STATEMENT);
            if (stmt == null || stmt.isEmpty()) {
                return;
            }
            // merge + running-max under a short lock: keeps distinct/top mutually consistent
            // and O(1) amortized (vs. rescanning the freq map on every one of thousands of ends).
            synchronized (this) {
                long f = freq.merge(stmt, 1L, Long::sum);
                if (f > topFreq) {
                    topFreq = f;
                    top = stmt;
                }
            }
        }

        void stamp() {
            root.setAttribute(SQL_COUNT, count.get());
            root.setAttribute(SQL_DISTINCT, (long) freq.size());
            root.setAttribute(SQL_DB_MS, dbNanos.get() / 1_000_000L);
            String t = top;
            if (t != null) {
                root.setAttribute(SQL_TOP, t);
            }
        }
    }
}
