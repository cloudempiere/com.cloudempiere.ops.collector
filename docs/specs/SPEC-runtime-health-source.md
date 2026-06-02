# SPEC — RuntimeHealthSource (long-running transactions + connection-pool + DB session health)

> **Status:** Phase 1 (Trx + pool, zero DB cost) **IMPLEMENTED** 2026-06-02 — `RuntimeHealthSource`,
> `PoolStatsShim`, descriptor, 4 classifier cases, reactor builds green. Phase 2 (flagged
> `pg_stat_activity`/`pg_locks`) still spec-only. Unit tests (`…ops.collector.test`) pending.
> **Scope:** ADR-001 §4 (event sources) / §5 (severity). New event source bundle-internal to
> `com.cloudempiere.ops.collector`. Related: CLD-1912 (A/B/C "don't mark healthy") follow-up.
> **Author:** CloudEmpiere

## 1. Motivation

ALB/ECS health checks (and even our own `/readyz`) see a process that is *accepting connections*.
They do **not** see a JVM that is structurally degrading underneath: a `Trx` wedged open holding a
DB connection + row locks, a HikariCP pool draining toward exhaustion, or a server-side query stuck
in `pg_stat_activity`. This is the runtime analogue of the boot-time "ACTIVE is a lie" thesis —
the bundle/DS layer is fine, but the **data layer is leaking**.

This is also the direct sibling of **Gotcha B**: at boot, `Level.SEVERE` → `MIssue.create` per log
caused a HikariCP "apparent connection leak" on the Start Level thread. The same pool can leak at
runtime from application code that never closes a `Trx`. We have no signal for it today.

**Distinction from business telemetry (validated 2026-06-02):** this source tracks *infrastructure*
liveness (connections, locks, transactions held too long) — **not** business throughput or process
durations. Process-audit duration belongs in OTLP metrics, not here. (See the scoping discussion in
the session log; raw durations → OTel, not the event/PANIC path.)

## 2. Goals / Non-goals

**Goals**
- Detect a **long-running `Trx`** early (well below iDempiere's 120-min hard rollback) with its
  *originating stack trace*, as a structured event — not a buried `WARNING`.
- Detect **connection-pool pressure** (busy/idle/waiting vs. max) → leak-storm / exhaustion.
- (Optional, flagged) Detect **server-side stuck queries / lock waits** via `pg_stat_activity` +
  `pg_locks`.
- Feed severity tiers; **never** wedge the system; **never** add DB pressure by default.

**Non-goals**
- NOT a transaction manager — we observe, we do not roll back (iDempiere's `TrxMonitor` already
  force-rolls-back at the 120-min hard timeout; we surface *earlier*, structured).
- NOT health-gating by default — a single slow trx ≠ unhealthy `/readyz` (see §7).
- NOT business-process duration / audit timing.
- NOT a replacement for APM; raw metrics still go to OTLP.

## 3. What iDempiere already gives us

| Signal | API | Cost | Notes |
|---|---|---|---|
| Open transactions | `org.compiere.util.Trx.getOpenTransactions()` → `Trx[]` | **in-JVM, 0 DB** | `getActiveTransactions()` is a deprecated alias (IDEMPIERE-5355) |
| Trx age | `trx.getStartTime()` → `Date` | in-JVM | `age = now − startTime`; `Trx.java:301` |
| Trx name | `trx.getTrxName()` | in-JVM | originating prefix from `createTrxName` |
| Trx origin stack | `trx.getStrackTrace()` → `String` | in-JVM | `protected Exception trace` captured at register; attribution gold |
| Hard timeout | `trx.getTimeout()` → sec | in-JVM | default `60*120` = **7200s / 120 min** (`Trx.java:199`) |
| Pool counts (string) | `DB.getDatabase().getStatus()` → `String` | in-JVM | human string incl. `# Open Transactions:`; brittle to parse |
| Pool counts (structured) | `DB.getDatabase().getDataSource(conn)` → cast `HikariDataSource` → `getHikariPoolMXBean()` | in-JVM | `getTotalConnections / getActiveConnections / getIdleConnections / getThreadsAwaitingConnection`, `getMaximumPoolSize` (`DB_PostgreSQL.java:298–334`) |
| Server-side sessions | `SELECT … FROM pg_stat_activity` / `pg_locks` | **DB query** | flagged-only; SQL-level view the JVM can't see |

**Existing native behavior we complement, not duplicate:** `Trx.TrxMonitor` (`Trx.java:792`) runs
`scheduleWithFixedDelay(monitor, 5, 5, MINUTES)` and only acts at the **120-min** hard timeout —
one `Level.WARNING "Transaction timeout…"` + `rollbackAndCloseOnTimeout()`. We surface long trx at a
**configurable, much lower** threshold as a structured event, long before damage compounds.

## 4. Component design

```
internal/RuntimeHealthSource          DS @Component(service=RuntimeHealthSource.class, immediate=true)
  @Reference(MANDATORY) EventDispatcher dispatcher
  ScheduledExecutorService poller       daemon, scheduleWithFixedDelay
                                        (NOT one-shot — runtime state is live, unlike MigrationScriptSource)
  poll cadence: OPS_COLLECTOR_RUNTIME_POLL_SECONDS (default 60)
```

Per poll (all wrapped so no probe pass can throw into the framework):
1. **Trx scan** — `Trx.getOpenTransactions()`; for each `age = now − getStartTime()`.
2. **Pool scan** — reflection shim over `getHikariPoolMXBean()` (see §6).
3. **(flagged) DB session scan** — only if `OPS_COLLECTOR_DB_SESSION_PROBE_ENABLED=true`.

State exposed for the readiness gate / status provider:
```java
public boolean   hasTrxLeakStorm();      // count(age>warn) >= LEAK_STORM_COUNT
public int       longRunningTrxCount();
public int       poolWaitingThreads();   // -1 if unknown
public double    poolUtilization();      // active/max, -1 if unknown
```

## 5. Event taxonomy + severity

New event types (additions to `internal/SeverityClassifier.classify`):

| event_type | Severity | Fires when | details |
|---|---|---|---|
| `ops.runtime.trx_long_running` | **ALERT** | a single `Trx` age > `TRX_WARN_SECONDS` (default 300) | `trx_name`, `age_seconds`, truncated `stack` |
| `ops.runtime.trx_leak_storm` | **PANIC** | count(age>warn) ≥ `TRX_LEAK_STORM_COUNT` (default 10) **or** any trx age > `TRX_CRITICAL_SECONDS` (default 3600, i.e. ½ the hard timeout) | `long_running_count`, `oldest_age_seconds`, `samples[]` |
| `ops.runtime.pool_pressure` | **ALERT** | `threadsAwaitingConnection > 0` for ≥ 2 consecutive polls, **or** `active/max ≥ POOL_WARN_RATIO` (default 0.85) | `active`, `idle`, `total`, `waiting`, `max`, `utilization` |
| `ops.runtime.pool_exhausted` | **PANIC** | `active >= max` **and** `waiting > 0` (no headroom + queueing) | same fields |
| `ops.runtime.db_session_stuck` | **ALERT** | (flagged) a `pg_stat_activity` row `state='active'` with `now()-query_start > DB_QUERY_WARN_SECONDS` (default 120) | `pid`, `age_seconds`, `query` (truncated/redacted), `wait_event` |
| `ops.runtime.db_deadlock` | **ALERT** → escalate | (flagged) `pg_locks` shows a blocking chain / deadlock detected | `blocked_pid`, `blocking_pid`, `relation` |
| `ops.runtime.ok` | **AUDIT** | a clean poll after a prior non-clean one (recovery) | — |

Severity rationale (ADR-001 §5):
- A *single* slow trx is recoverable → **ALERT** (page-worthy, not fleet-fatal).
- A leak **storm** / pool **exhaustion** is fleet-level (every request will soon block) → **PANIC**.
- All emit through the dispatcher's stdout floor first (§4.10); the `levelFor()` mapping (Gotcha B
  fix) means ALERT logs at `WARNING`, PANIC at `SEVERE` — so **no AUDIT/ALERT event triggers
  `MIssue.create`** and we don't reintroduce the very leak we're watching for.

## 6. Hikari access — reflection shim (no compile dep)

`com.zaxxer.hikari` lives embedded in `org.compiere.db.postgresql.provider`, not on our target
platform. To avoid an `Import-Package: com.zaxxer.hikari` coupling (and to no-op cleanly on Oracle
or a non-Hikari provider), mirror the existing OTel shim pattern (`internal/OtelContext`):

```
internal/PoolStatsShim
  DataSource ds = DB.getDatabase().getDataSource(CConnection.get());
  if class name ~ "HikariDataSource":
     Object mx = ds.getClass().getMethod("getHikariPoolMXBean").invoke(ds);
     int active  = (int) mx.getClass().getMethod("getActiveConnections").invoke(mx);
     int idle    = ... getIdleConnections
     int total   = ... getTotalConnections
     int waiting = ... getThreadsAwaitingConnection
     int max     = (int) ds.getClass().getMethod("getMaximumPoolSize").invoke(ds);
  else: return UNKNOWN (poolUtilization=-1) — silently skip pool events
```

Fallback if reflection is undesirable: parse `DB.getDatabase().getStatus()` (string) — rejected as
brittle; reflection on the typed MXBean is the primary.

## 7. Readiness (requirement C) interaction — **non-gating by default**

`/readyz` must stay strictly **A/B/C-infra** (OSGi components / migration / 2Pack). A slow trx or
transient pool spike is *not* a reason to 503 and trigger an ECS circuit-breaker rollback.

- Default: this source **pages via severity** (PANIC/ALERT events → EventAdmin/stdout), and exposes
  its state on `GET /api/v1/ops/plugins` (or a new `/api/v1/ops/runtime`) for dashboards — but does
  **not** flip `OpsStatusProvider.isReady()`.
- Optional, behind `OPS_COLLECTOR_RUNTIME_GATES_READY=false` (default false): allow
  `hasTrxLeakStorm() || poolExhausted()` to contribute to readiness. **Off by default** — opt-in
  only, because a flapping pool would flap `/readyz`.

## 8. Env knobs (mirror `envSeconds`/`MSysConfig` pattern in `BootPhaseTracker`)

| Knob | Default | Meaning |
|---|---|---|
| `OPS_COLLECTOR_RUNTIME_POLL_SECONDS` | 60 | poll cadence |
| `OPS_COLLECTOR_TRX_WARN_SECONDS` | 300 | single-trx ALERT threshold |
| `OPS_COLLECTOR_TRX_CRITICAL_SECONDS` | 3600 | single-trx PANIC threshold (½ hard timeout) |
| `OPS_COLLECTOR_TRX_LEAK_STORM_COUNT` | 10 | # long-running trx → PANIC |
| `OPS_COLLECTOR_POOL_WARN_RATIO` | 0.85 | active/max → ALERT |
| `OPS_COLLECTOR_DB_SESSION_PROBE_ENABLED` | false | gate the `pg_stat_activity`/`pg_locks` DB queries |
| `OPS_COLLECTOR_DB_QUERY_WARN_SECONDS` | 120 | server-side stuck-query threshold |
| `OPS_COLLECTOR_RUNTIME_GATES_READY` | false | opt-in: let storm/exhaustion gate `/readyz` |

## 9. Deliverables (when build is approved)

1. `internal/RuntimeHealthSource.java` (+ hand-authored `OSGI-INF/…RuntimeHealthSource.xml` — DS
   descriptors are NOT generated here, §4.5; must be in `bin.includes`).
2. `internal/PoolStatsShim.java` (reflection).
3. `SeverityClassifier` cases for the 7 new event types.
4. (optional) `/api/v1/ops/runtime` read endpoint in `com.cloudempiere.ops.rest` + OpenAPI entry.
5. Env knobs documented in this plugin's `CLAUDE.md` "Runtime knobs" line.
6. `…ops.collector.test` unit tests: age classification, leak-storm threshold, pool-ratio, shim
   no-op when reflection target absent.

## 10. Open questions

- **Stack-trace volume** — `getStrackTrace()` can be large; truncate to N frames and emit once per
  `trx_name` per window (de-dupe), not every poll.
- **`pg_stat_activity` redaction** — queries may contain literals (PII). Redact/parameterize before
  emitting; this is why the DB probe is flagged-off by default.
- **Phasing** — recommend Phase 1 = Trx + pool (zero-DB, ships first); Phase 2 = flagged
  `pg_stat_activity`/`pg_locks` once redaction is settled.
- Does `getDataSource(CConnection.get())` return the live pool on every tier, or null on a
  read-replica config? Verify on staging before relying on pool events there.
