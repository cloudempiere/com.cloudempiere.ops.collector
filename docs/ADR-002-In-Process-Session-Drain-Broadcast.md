# ADR-002: In-Process Session-Drain Broadcast (webui SIGTERM banner)

- **Status:** Accepted (implementation in `ShutdownHandler`; build + staging soak pending)
- **Date:** 2026-06-06
- **Deciders:** Norbert (CEO / Architecture)
- **Technical area:** iDempiere ZK webui (`org.adempiere.ui.zk`), `com.cloudempiere.ops.collector` (OSGi/Equinox), ECS Fargate task lifecycle, ALB target-group draining
- **Sequence:** Second ADR of the `com.cloudempiere.ops.collector` bundle (idempiere-cloudempiere fork).
- **Related:** ADR-001 §4.17.4 / §10.7 (this bundle's `ShutdownHandler` / drain), **ADR-007** (clde-infra — drain-first + kill-session), **CLD-1906** (`com.cloudempiere.session.broadcast` — `CEKillServerSessions` + hardened `TimeoutPanel`), **CLD-1845 / CLD-1880** (the external-kill serverName/FQDN saga), ADR-024/025/026 (`cloudempiere-events` substrate).

---

## Context

When a webui Fargate task is replaced (rolling deploy, scale-in, graceful stop), its ZK
`Desktop` objects live **only in that JVM's heap**. The task gets SIGTERM; on the user's next
click the request lands on a different task that has no `Desktop` for them → upstream ZK throws
`Desktop not registered` → `sessiontimeout.zul` ("page no longer available — Continue"). The
user is logged out with **no warning** (ADR-007 §Context).

ADR-007's mitigation is **drain-first + kill-session**: deregister the old task, then
`POST /processes/kill-current-session` so iDempiere broadcasts a `TimeoutPanel` "save your work"
countdown before the stop. That call is made **externally** — historically Jenkins, migrating to
an EventBridge → Lambda path (ADR-055 §Phase 2.5). Operating it has been costly:

1. **serverName/FQDN matching is fragile.** The broadcast targets `AD_Session.ServerName` and
   must byte-match `WebUtil.getServerName()` of the node. Fargate's hostname handling broke this
   repeatedly (CLD-1845 / CLD-1880) → silent no-ops (kicked users, no banner, no audit row).
2. **The wiring is too long for a time-critical broadcast.** SIGTERM and ALB-deregister fire
   back-to-back; an external `ECS event → bus → Lambda → mint token → POST` chain arrives **after**
   the JVM has already started shutting down. You cannot react your way to "before SIGTERM."
3. **It needs an HA pair.** Drain-first only works at ≥2 tasks (one serves while the other drains);
   at desired=1 it is impossible, so it can't even be tested on staging.
4. **It conflicts with the Jenkins-decoupling direction** (ADR-055): Jenkins should stop calling
   AWS/iDempiere directly, but a *short, ordered* call is exactly what the banner needs.

Meanwhile `ShutdownHandler` (ADR-001 §4.17.4) **already** traps SIGTERM in-process, flips
`/readyz`→503, waits `OPS_COLLECTOR_SHUTDOWN_DRAIN_SECONDS`, and emits `task.draining` /
`task.terminating`. The timing-critical scaffolding is already where the session lives.

## Decision

**Broadcast the session-timeout banner in-process, from `ShutdownHandler`, on SIGTERM** — to
**this node's own ZK desktops** — superseding the external `ceKillSession` call for the
deploy/scale path. webui-only.

```java
// ShutdownHandler.onShutdown(), after draining=true + emit task.draining, before the drain sleep:
BroadCastMsg msg = new BroadCastMsg();
msg.setEventId(BroadCastUtil.EVENT_SESSION_ONNODE_TIMEOUT);
msg.setTarget(WebUtil.getServerName());          // our OWN name → trivially self-matches
msg.setIntData((int) broadcastCountdownSeconds);
BroadcastMsgUtil.pushToQueue(msg, /*isLocalOnly=*/ true);   // no cluster, no REST, no token
```

`DefaultDesktop` handles `EVENT_SESSION_ONNODE_TIMEOUT` by comparing `currSession =
WebUtil.getServerName()` to `msg.getTarget()`; since target **is** our own serverName, **every
local desktop** pops the (CLD-1906-hardened, non-dismissable) `TimeoutPanel`.

### Why this is strictly better for the deploy/scale path
- **No serverName/FQDN matching** — target is *self*; the entire CLD-1845/1880 bug class is gone.
- **No external wiring / token / Lambda / bus hop** — the broadcast originates where the session is.
- **No race** — fires the instant SIGTERM arrives, inside the existing drain window.
- **Works at any task count**, including desired=1 (testable on staging).
- **Fires for ANY SIGTERM** — deploy, scale-in, graceful crash — not just Jenkins-orchestrated drains.

### Scope & configuration
- **webui-only.** Gated on `IDEMPIERE_TIER_NAME` containing `webui`. rest/scheduler/preview hold
  no ZK desktops; the drain/readiness half of `ShutdownHandler` still runs on every tier.
- **Two independent timings**, each resolving **AD_SysConfig → env var → built-in default**
  (read at boot — the DB can't be read mid-SIGTERM, so a UI change applies on the next task start):
  - `OPS_COLLECTOR_SHUTDOWN_DRAIN_SECONDS` (default 30) — how long the task lingers.
  - `OPS_COLLECTOR_BROADCAST_COUNTDOWN_SECONDS` (default = drain) — the `TimeoutPanel` countdown,
    **clamped ≤ drain** (a longer countdown never completes — the JVM exits first).
  - `OPS_COLLECTOR_BROADCAST_ON_SHUTDOWN=false` → fall back to the external path only.

### Cross-knob coupling (must hold)
```
  countdown ≤ drain ≤ stopTimeout(120, Fargate-fixed)
  (ALB health-check interval × unhealthy-threshold) + ALB deregistration_delay ≤ stopTimeout
```
`stopTimeout`, `deregistration_delay`, and the health-check are **clde-infra**
(`modules/idempiere-clde-tier`). The app timings must fit inside them, or ECS SIGKILLs before the
ALB finishes draining in-flight (held-open ZK comet) or before the banner completes.

## Setup

The two timing knobs resolve **AD_SysConfig → env var → built-in default**. You may set them
either way; AD_SysConfig wins and is UI-editable (System Admin → *System Configurator*).

### Option A — AD_SysConfig (UI-editable, recommended)

Idempotent SQL (PostgreSQL, System tenant, System level). Safe to re-run — `NOT EXISTS` guards
prevent duplicates. `generate_uuid()` is the iDempiere PG function; `CreatedBy/UpdatedBy = 100`
is `SuperUser`.

```sql
-- ADR-002 — webui in-process SIGTERM session-drain broadcast knobs.
-- AD_Client_ID=0 (System), ConfigurationLevel='S' (System), read at task boot.

INSERT INTO AD_SysConfig
  (AD_SysConfig_ID, AD_SysConfig_UU, AD_Client_ID, AD_Org_ID, IsActive,
   Created, CreatedBy, Updated, UpdatedBy, Name, Value, Description, EntityType, ConfigurationLevel)
SELECT (SELECT COALESCE(MAX(AD_SysConfig_ID), 1000000) + 1 FROM AD_SysConfig),
       generate_uuid(), 0, 0, 'Y', now(), 100, now(), 100,
       'OPS_COLLECTOR_SHUTDOWN_DRAIN_SECONDS', '30',
       'webui in-process SIGTERM drain window (seconds): how long the task lingers after '
       || 'SIGTERM before the JVM exits. Must be <= ECS stopTimeout (120). Read at boot. ADR-002.',
       'D', 'S'
WHERE NOT EXISTS (SELECT 1 FROM AD_SysConfig WHERE Name = 'OPS_COLLECTOR_SHUTDOWN_DRAIN_SECONDS');

INSERT INTO AD_SysConfig
  (AD_SysConfig_ID, AD_SysConfig_UU, AD_Client_ID, AD_Org_ID, IsActive,
   Created, CreatedBy, Updated, UpdatedBy, Name, Value, Description, EntityType, ConfigurationLevel)
SELECT (SELECT COALESCE(MAX(AD_SysConfig_ID), 1000000) + 1 FROM AD_SysConfig),
       generate_uuid(), 0, 0, 'Y', now(), 100, now(), 100,
       'OPS_COLLECTOR_BROADCAST_COUNTDOWN_SECONDS', '30',
       'webui TimeoutPanel "save your work" countdown (seconds) shown on SIGTERM. Clamped <= '
       || 'OPS_COLLECTOR_SHUTDOWN_DRAIN_SECONDS (a longer countdown never completes). Read at boot. ADR-002.',
       'D', 'S'
WHERE NOT EXISTS (SELECT 1 FROM AD_SysConfig WHERE Name = 'OPS_COLLECTOR_BROADCAST_COUNTDOWN_SECONDS');
```

Verify:

```sql
SELECT Name, Value, ConfigurationLevel, EntityType
FROM   AD_SysConfig
WHERE  Name LIKE 'OPS_COLLECTOR_%'
ORDER  BY Name;
```

> A change here applies on the task's **next start** — `ShutdownHandler` reads at boot (the DB
> can't be read mid-SIGTERM). For a packaged install, ship these as a 2Pack instead of raw SQL.

### Option B — task-def env vars (clde-infra)

Set on the webui task definition (`modules/idempiere-clde-tier`) if you prefer infra-managed config:

```
OPS_COLLECTOR_SHUTDOWN_DRAIN_SECONDS     = 30      # drain window (<= stopTimeout 120)
OPS_COLLECTOR_BROADCAST_COUNTDOWN_SECONDS = 25     # banner countdown (<= drain)
OPS_COLLECTOR_BROADCAST_ON_SHUTDOWN      = true    # on/off — ENV ONLY (not AD_SysConfig-backed)
```

`OPS_COLLECTOR_BROADCAST_ON_SHUTDOWN` is read from the environment only (the on/off kill-switch),
and the broadcast is **webui-only** regardless (gated on `IDEMPIERE_TIER_NAME`).

### Sanity check (must hold — see "Cross-knob coupling" above)
```
  countdown ≤ drain ≤ stopTimeout(120)
  (ALB HC interval × unhealthy-threshold) + ALB deregistration_delay ≤ stopTimeout
```

## Consequences

- **`ceKillSession` / external kill-session is no longer on the deploy/scale critical path.** The
  REST `/processes/kill-current-session` (CEKillServerSessions, CLD-1906) **stays** for ad-hoc /
  admin kicks with a per-request `timeoutinseconds`.
- **EventBridge keeps the audit only** (`task.draining` / `task.terminating`) — no timing pressure;
  it records what happened, it does not deliver the banner.
- **Jenkins/orchestrator becomes thin** for this concern — it just stops the task; it never calls
  kill-session for a normal deploy.
- **The FQDN master-key alignment (2026-06-05)** remains correct and unblocks the *legacy external*
  path during transition, but is **irrelevant** to the in-process path (self-target).

## Risks / open items

- **ZK push mid-teardown.** The broadcast rides Atmosphere/`EventManager`; on SIGTERM the JVM is up
  (within `stopTimeout`) but the push infra may be tearing down. The broadcast fires **early** in
  `onShutdown()` (before the drain sleep) to maximise the window. **Requires a staging soak**:
  2 webui tasks → deploy → confirm a logged-in desktop pops the `TimeoutPanel`.
- **Boot-time config read.** AD_SysConfig is read at `activate()`; if the bundle activates before
  the DB is ready, it falls through to env/default. Acceptable; documented.
- **Build/deploy.** Tycho/OSGi bundle change → Maven build + image bake; new `org.idempiere.broadcast`
  `Import-Package`.

## Alternatives considered

- **Keep external kill-session (status quo).** Rejected: the serverName matching + long wiring +
  HA-pair requirement are the recurring failure modes this ADR removes.
- **React to raw ECS Task State Change in the Lambda (pure EventBridge).** Rejected for the *banner*:
  the event arrives at/after SIGTERM — too late to warn before shutdown. Retained for **audit** only
  (`release.task.dropped` is the abrupt-path peer of `release.task.draining`).
- **ZK desktop replication across JVMs.** Would let the session *survive* a task swap (not just warn).
  Out of scope — large, not in upstream iDempiere.
