# ADR-001: iDempiere Operational Observability & Plugin Lifecycle Monitoring

- **Status:** Proposed
- **Date:** 2026-05-30 (rev. 7 — OTel agent/sidecar **validated live** on staging webui via ADR-065; adds §11 webui span-enrichment requirement + validated deployment requirements)
- **Deciders:** Norbert (CEO / Architecture)
- **Technical area:** iDempiere / OSGi runtime (Equinox), Docker / ECS Fargate, AWS observability (CloudWatch + EventBridge), CLDE-Hub intelligence layer (Quarkus)
- **Sequence:** First ADR of the `com.cloudempiere.ops.collector` bundle (idempiere-cloudempiere fork) — numbering starts at 001.
- **Related (platform design context):** agentic platform, ephemeral Fargate, `cloudempiere_ops` DB, CLDE-Hub (prior design notes, not yet ADR-numbered in this sequence).
- **Related (clde-infra sequence):** **ADR-064** — iDempiere telemetry sampler (the *external* per-task half of this design; live in prod 2026-05-30); **ADR-065** — distributed tracing (OTel Java agent + ADOT sidecar → X-Ray), **validated live on staging webui 2026-05-30**: the agent auto-instruments `/zkau` into HTTP+JDBC spans (one slow click = 3,374 SQL spans, naming the exact lookup-SQL fan-out). ADR-065 is the *infra* half (image/task-def/collector); **this ADR's collector is the iDempiere-signal enrichment layer on top — see §11**; **ADR-024/025/026** — the `cloudempiere-events` substrate (EventBridge bus + DDB sink + projections + Slack relay) this collector wires into; ADR-007 (drain-first); ADR-049/020 (operational/log observability)
- **Companion docs:** `idempiere-observability-deep-dive.md` (OSGi internals, lazy activation, code sketches); `idempiere-deploy-checklist.docx`. **See §10** for how this bundle wires into the existing CloudEmpiere AWS substrate — validated against the live external sampler.

---

## 1. Problem

CloudEmpiere runs iDempiere as multi-tenant hosted SaaS — each instance a **Docker container** (local Docker Compose; prod ECS Fargate). Inside that container is an OSGi/Equinox runtime hosting ~813 CLDE-marked customizations as plugins. Today we have **no structured, proactive signal when something in that runtime goes wrong**.

Real problems we hit:

- **A plugin fails to start and nobody notices** until a user reports broken behaviour. A bundle stuck in `INSTALLED` (unresolved) or a DS component in `FAILED_ACTIVATION` produces no alert — the container is "up", the process is running, the ALB health check is green, but a callout or model factory silently never fires.
- **`ACTIVE` is a lie we trust.** A bundle can be `ACTIVE` while its Declarative Services components are `UNSATISFIED`. Container-level health (`docker ps`, ECS task health, ALB) cannot see this.
- **In Docker the failure is invisible from the outside.** Resolution failures, activation exceptions, 2Pack errors all scroll past in the first seconds of container logs and are never surfaced as actionable events.
- **Tool-fragmented detection.** Answering "is this instance healthy?" today means exec into the container, telnet the OSGi console, parse `ss`, check the iDempiere Monitor servlet, grep logs, cross-reference Linear.
- **Doesn't scale across N tenants.** Manual inspection per container does not survive a growing customer base.

Trigger question: *get CloudWatch-style notification — "plugin X failed to start in container Y for tenant Z" — without exec-ing into containers and grepping logs.*

---

## 2. Hypotheses

Each evaluated against the Docker reality (ephemeral containers, logs as primary egress, no stable shell access in prod).

### H1 — "Container/orchestrator health is enough."
ECS task health, `docker healthcheck`, ALB probes catch only process liveness + port reachability. A container with a dead DS component is 100% healthy by every orchestrator signal. **Rejected** — container health and plugin health are different things.

### H2 — "Poll the OSGi console from outside."
Equinox console is a command shell, not an event stream — it cannot push. It cannot see DS component state. Output parsing is brittle across versions. Exposing it cross-container is a security liability. **Rejected.**

### H3 — "Regex-parse container logs, no in-JVM code."
Genuinely catches resolution failures, framework errors, 2Pack failures (~70%) — they *do* print to stdout. But unstructured, version-fragile, no tenant context, **cannot see DS component state**. **Partially accepted as Phase 0** — useful interim, insufficient end-state.

### H4 — "In-JVM collector emitting structured events to stdout."
A small OSGi bundle inside the container subscribes to lifecycle events, polls DS component state, enriches with tenant context, writes structured JSON to stdout (which Docker/ECS already ship to CloudWatch). **Accepted as the core.** Information-theoretic argument: everything observable about the runtime is observable from inside the JVM first; anything reachable externally has already passed through (or could pass through) stdout. In-JVM = highest fidelity (sees DS state); stdout = lowest fragility in a Docker world.

### Supporting hypothesis — "Intelligence belongs outside iDempiere."
The Quarkus **CLDE-Hub** already has LangChain4j, MCP server, role-based access, information-sensitivity controls. Collector stays dumb (detect + emit); CLDE-Hub reasons. Keeps each iDempiere container narrow and AWS-agnostic; centralizes multi-tenant logic.

### Supporting hypothesis — "Reuse the BX REST community contract."
BX Service's `idempiere-rest` (active community fork of trekglobal's REST) already exposes `/api/v1/health`, `/api/v1/nodes`, `/api/v1/nodes/{id}` (JVM/GC/heap/DB pool/sessions/threads/uptime), `/api/v1/caches`, `/api/v1/server` (scheduler). **Adopt as platform base.** The collector then implements only what BX REST does not: OSGi bundle + DS component state, 2Pack lifecycle, tenant-aware aggregation, boot phases, severity classification, OTel emission, event egress.

### Supporting hypothesis — "OpenTelemetry as the telemetry standard."
OpenTelemetry is the CNCF-graduated de facto standard for Java telemetry in 2026. Traces/metrics/logs stable across all major SDKs; OTLP wire protocol accepted by every backend (CloudWatch, X-Ray, AMP, Grafana, Datadog). OSGi-wrapped SDK bundles exist (`orbinson/opentelemetry-osgi-wrappers`); Adobe AEM uses the same pattern. **Adopt OTel as signal egress format.** Auto-instrumentation via Java agent covers HTTP/JDBC/JVM/messaging for free; the collector adds the iDempiere-specific signals on top.

---

## 3. Decision

A layered observability architecture with seven explicit principles. The collector bundle is the source of truth for plugin lifecycle; BX REST is the source of truth for snapshot operational data; OTel is the signal format; EventBridge is the domain-event transport.

### Principles

1. **iDempiere produces events; CLDE-Hub reasons about them.** Same pattern as the existing business-data chatbot, applied to operational data.
2. **stdout (log4j2 JSON) is the floor, never the ceiling.** Every event logs to stdout first — the one egress Docker/ECS guarantees. Richer sinks attempted after; if they fail, alerting still works through the log stream.
3. **One collector, many consumers.** Bundle exposes one stable `/ops/v1` contract. Dashboard, MCP tools, alarms are consumers; never touch raw sources.
4. **No AWS SDK in the OSGi runtime.** Events egress via stdout + OTLP; translation to AWS-native constructs (EventBridge, CloudWatch metrics) happens downstream. Same image runs identically under Docker Compose and ECS.
5. **Two-pipe egress.** *Signals* (metrics/traces/structured logs) → OTLP → OTel Collector → backends. *Domain events* (plugin failed, 2Pack failed, boot stalled) → stdout JSON → bridge Lambda → EventBridge `cloudempiere-ops` bus. Pub/sub at the AWS layer, not inside the JVM.
6. **Boot-phase observability.** Collector exposes phase-level boot status as both probe endpoints (`/livez`/`/readyz`/`/startupz`) and a rich introspection endpoint (`/ops/v1/startup`). Each phase transition emits an event onto the bus.
7. **Lifecycle parity, not just boot.** Same machinery handles boot, mid-life degradation, graceful shutdown. `/readyz` is a continuous contract. Collector traps `SIGTERM`, flips `/readyz` to 503, drains traffic before iDempiere terminates.
8. **Read-only by default.** Plugin write operations (restart/redeploy) stay behind explicit human action in deploy-dashboard-bff; never freely-callable from MCP/agent tools.
9. **Passive against published iDempiere APIs only; never patch the fork.** Collector ships as an independent CLDE bundle. No patches to `org.adempiere.base` or `com.trekglobal.idempiere.rest.api`.

### Architecture

```
+- Docker container (local Compose OR ECS Fargate task) ----------------+
|  iDempiere JVM / Equinox OSGi                                         |
|                                                                       |
|  OpenTelemetry Java agent (-javaagent:opentelemetry-javaagent.jar)    |
|    auto-instruments: Servlet (ZK), JDBC, HTTP client, JMX             |
|    emits OTLP: traces + metrics + logs                                |
|                                                                       |
|  com.cloudempiere.ops.collector  (OUR bundle — unique value only)     |
|    Sources (all in-JVM, no extra ports):                              |
|      - BundleListener + FrameworkListener                             |
|      - ServiceComponentRuntime poll (DS component state) *            |
|      - one-shot scan on activate (pre-listener startup) *             |
|      - custom log4j2 appender (structured app ERRORs)                 |
|      - 2Pack hook (AdempiereActivator outcomes)                       |
|    Pipeline: normalize -> dedupe -> enrich(AD_Client) -> classify     |
|    Outputs:                                                           |
|      1. log4j2 JSON -> stdout       (FLOOR; always works)             |
|      2. OTel API -> OTLP            (signals: metrics/logs/spans)     |
|      3. /ops/v1/* REST              (stable query contract)           |
|      4. OSGi EventAdmin             (in-JVM bus for other bundles)    |
|                                                                       |
|  BX REST bundle (community, adopted as base)                          |
|    /api/v1/health  /api/v1/nodes[/{id}]  /api/v1/caches               |
|    /api/v1/server  - JVM/GC/heap/DB pool/sessions/threads             |
+-----------------------------+-----------------------------------------+
                              | OTLP (4318)                  | stdout
                              v                              v
              OTel Collector (sidecar)                  ECS awslogs
                              |                              |
        +-----+---------------+                              v
        v     v               v                       CloudWatch Logs
   CloudWatch X-Ray         AMP                              |
   (logs+metrics)                              +-------------+--------+
                                               |                      |
                                       Metric Filter        Subscription Filter
                                               |                      v
                                         Alarm -> SNS         Bridge Lambda
                                               |                      v
                                            Slack         EventBridge bus
                                                          cloudempiere-ops
                                                                  |
                          +-----------+-----------+----------------+
                          v           v           v                v
                       Lambda       SQS       Step Func        Archive
                       (triage)    (n8n)    (orchestration)   (replay)
                          |
                          v
                    CLDE-Hub (Quarkus)
                    LangChain4j + MCP
                          |
                          v
                Linear MCP / n8n MCP

  deploy-dashboard-bff <----- /ops/v1/* + /api/v1/* -----  (one human dashboard)
```

`*` = the parts that justify an in-JVM collector over external log parsing.

---

## 4. Plugin development requirements

This section is the developer-facing spec for `com.cloudempiere.ops.collector`. Everything below is a build requirement, not commentary.

### 4.1 Bundle identity & versioning

```
Bundle-SymbolicName: com.cloudempiere.ops.collector;singleton:=true
Bundle-Version: 1.0.0.qualifier
Bundle-Vendor: CloudEmpiere
Bundle-Name: CloudEmpiere Operational Observability Collector
Bundle-RequiredExecutionEnvironment: JavaSE-17
Bundle-ActivationPolicy: lazy   # NO — see 4.3
```

- Use semantic versioning. `1.x` requires no API breaks within the major.
- Vendor namespace `com.cloudempiere.ops.*` (not `org.idempiere.*`) — this is a CLDE-internal bundle for now; reconsider open-sourcing under `org.idempiere.ops.collector` in a follow-up ADR.

### 4.2 Dependency constraints (MANIFEST)

```
Require-Bundle:
  org.adempiere.base;bundle-version="[10.0.0,15.0.0)",
  org.eclipse.osgi;bundle-version="[3.18.0,4.0.0)"

Import-Package:
  org.osgi.framework;version="[1.10,2)",
  org.osgi.service.component.annotations;version="[1.5,2)",
  org.osgi.service.component.runtime;version="[1.5,2)",
  org.osgi.service.component.runtime.dto;version="[1.5,2)",
  org.osgi.service.event;version="[1.4,2)",
  org.osgi.service.log;version="[1.4,2)",
  org.compiere.model,
  org.compiere.util,
  io.opentelemetry.api;version="[1.40,2)",
  io.opentelemetry.api.metrics;version="[1.40,2)",
  io.opentelemetry.api.trace;version="[1.40,2)",
  io.opentelemetry.context;version="[1.40,2)",
  io.opentelemetry.semconv;version="[1.25,2)",
  org.apache.logging.log4j;version="[2.20,3)"

Service-Component: OSGI-INF/*.xml
Export-Package: com.cloudempiere.ops.collector.api
```

**Hard rules.**

- **Use version *ranges*, never pinned versions.** Pinning to `13.0.0` locks us to one iDempiere release; we must support v10 today and v13/v14 going forward.
- **No AWS SDK imports.** No `software.amazon.awssdk.*`. Egress is OTLP + stdout only.
- **No reflective access to iDempiere internals.** Only published `org.compiere.model` / `org.compiere.util` APIs. If something requires reflection, raise it as a question rather than do it.
- **OTel SDK comes via OSGi-wrapped bundles.** Use `orbinson/opentelemetry-osgi-wrappers` or equivalent — raw OTel jars fail OSGi resolution.

### 4.3 Activation policy

`Bundle-ActivationPolicy: lazy` is the iDempiere community default but is **wrong for this bundle**. The collector must start before user-facing bundles so its listeners attach before lifecycle events fire.

```
# DO:    omit Bundle-ActivationPolicy  (= eager / "started" on framework start)
# DON'T: Bundle-ActivationPolicy: lazy
```

Equivalently, in `server.product` (or in a startup config the base image controls):

```xml
<plugin id="com.cloudempiere.ops.collector" autoStart="true" startLevel="2" />
```

`startLevel=2` puts the collector before CLDE business bundles (typically startLevel=4–6) so its listeners are armed when those bundles resolve and activate. Confirm actual start levels in `server.product` before deciding.

### 4.4 Declarative Services (component declarations)

Use DS annotations (OSGi R7 / `@Component`) — no `BundleActivator`. The DS framework handles lifecycle correctly, including ordering.

Required components, each with its own XML descriptor under `OSGI-INF/`:

| Component | Role | Activation |
|---|---|---|
| `BundleLifecycleSource` | Registers `BundleListener` + `FrameworkListener` | `immediate=true` |
| `DSComponentSource` | Polls `ServiceComponentRuntime` on interval | `immediate=true` |
| `ColdStartScanner` | One-shot scan on `@Activate`, before listeners go live | `immediate=true` |
| `TwoPackEventSource` | Hooks `AdempiereActivator` outcomes | `immediate=true` |
| `Log4jStructuredAppender` | Captures app-level ERRORs structurally | `immediate=true` |
| `TenantEnricher` | Adds `AD_Client_ID` resource attribute | service, lookup-time |
| `SeverityClassifier` | PANIC/ALERT/AUDIT classification | service, lookup-time |
| `EventDispatcher` | Fanout to stdout + OTel + EventAdmin + DB | `immediate=true` |
| `OpsRestResource` | JAX-RS resource: `/ops/v1/*` | registered with BX REST extension point |
| `BootPhaseTracker` | Tracks Phase 0–6; serves `/ops/v1/startup` | `immediate=true` |
| `ShutdownHandler` | `SIGTERM` → flip `/readyz` → drain | `immediate=true` |
| `WarmupRunner` | Active validation, invoked by `POST /ops/v1/warmup` | service, on-demand |

**`immediate=true`** on every always-on component. Lazy DS components don't activate until referenced, which is wrong for things that must observe lifecycle events from the moment OSGi starts.

### 4.5 Critical: include every DS descriptor in build.properties

The single most common "silent failure" failure mode is a DS XML file listed in `Service-Component:` not being included in the JAR's Binary Build:

```properties
# build.properties — Binary Build MUST list every OSGI-INF descriptor
bin.includes = META-INF/,\
               OSGI-INF/,\
               OSGI-INF/*.xml,\
               .
```

**Pre-deploy guard (Jenkins, mandatory):**
```bash
unzip -l target/com.cloudempiere.ops.collector-*.jar | grep OSGI-INF/ \
  || { echo "FAIL: no OSGI-INF in JAR"; exit 1; }
```

If this fails, the bundle will install `ACTIVE` and the components will simply not exist (`Component descriptor entry … not found`).

### 4.6 OSGi services consumed

Read-only access only. Bind via DS `@Reference`:

```java
@Reference(cardinality = ReferenceCardinality.MANDATORY)
ServiceComponentRuntime scr;          // DS state introspection

@Reference(cardinality = ReferenceCardinality.MANDATORY)
EventAdmin eventAdmin;                 // OSGi pub/sub for in-JVM consumers

@Reference(cardinality = ReferenceCardinality.OPTIONAL)
IClusterService clusterService;        // node identity if Hazelcast configured

// BundleContext injected via @Activate parameter
@Activate
void activate(BundleContext ctx, Map<String, Object> config) { ... }
```

`IClusterService` is `OPTIONAL` because single-instance deployments don't have Hazelcast bundles loaded — the collector must work without it.

### 4.7 The `/ops/v1` REST contract

Register the JAX-RS resource through BX REST's extension mechanism (consult `com.trekglobal.idempiere.rest.api` / BX REST extension points; same way `CacheResource` is registered). Do **not** stand up an independent HTTP server.

Endpoints, with required response shapes:

#### `GET /ops/v1/livez` — process liveness
- Cheap, no dependency checks, no DB calls.
- Returns `200 {"status":"alive"}` if the collector bundle is running.
- Wires to `docker HEALTHCHECK`.

#### `GET /ops/v1/readyz` — full readiness
- Checks: collector internal state OK, no DS components `FAILED_ACTIVATION`, DB reachable (via pool), no `SIGTERM` in progress.
- Returns `200 {"status":"ready"}` or `503 {"status":"unready","reasons":[…]}`.
- Wires to ALB target group for ZK/app traffic.

#### `GET /ops/v1/startupz` — boot complete
- Returns `200` once boot Phase 4+ reached (bundles ACTIVE, DS satisfied).
- Returns `503 {"status":"starting","phase":"…","elapsed_seconds":N}` while booting.
- Wires to Kubernetes `startupProbe` if you go there; ECS uses long `startPeriod`.

#### `GET /ops/v1/startup` — rich boot status
```json
{
  "state": "starting",
  "phase": "ds_activation",
  "phase_number": 3,
  "phase_total": 6,
  "started_at": "2026-05-30T12:00:00Z",
  "elapsed_seconds": 47,
  "progress": {
    "bundles_total": 230, "bundles_active": 218, "bundles_pending": 12,
    "ds_components_total": 412, "ds_components_satisfied": 389,
    "ds_components_pending": 23, "ds_components_failed": 0
  },
  "blockers": [
    {"kind": "ds_component_unsatisfied",
     "name": "com.cloudempiere.tms.event.handler",
     "bundle": "com.cloudempiere.tms",
     "missing_references": ["IEventManager"]}
  ],
  "history": [
    {"phase":"osgi_framework","completed_at":"…","duration_seconds":8},
    {"phase":"bundle_resolution","completed_at":"…","duration_seconds":13}
  ]
}
```

#### `GET /ops/v1/plugins` — bundle + DS snapshot (the unique signal)
```json
{
  "bundles": [
    {"symbolicName":"com.cloudempiere.tms","version":"2.4.7","state":"ACTIVE","since":"…"}
  ],
  "components": [
    {"name":"com.cloudempiere.tms.event.handler",
     "bundle":"com.cloudempiere.tms",
     "state":"UNSATISFIED_REFERENCE",
     "unsatisfiedReferences":["IEventManager"],
     "failure": null}
  ],
  "summary": {"bundles_active":813,"components_unhealthy":1}
}
```

Query params: `?bundle=<symbolic-name>`, `?state=<state>`, `?cldeOnly=true`.

#### `GET /ops/v1/events?since=&severity=&tenant=&bundle=` — recent events
In-memory ring buffer + DB-backed history fallback. Bounded (default 5000 events in memory). Returns severity-classified events for dashboards and agents.

#### `POST /ops/v1/warmup` — active validation, deploy gate
- Mutates the runtime intentionally (forces lazy DS components to activate).
- Returns when complete; result payload classifies each component as `ok` / `failed_activation` / `factory_shadowed` / `timeout`.
- See 4.9 below for the two-strategy validation model.

#### Auth & gating — four-tier model

Different callers deserve different treatment. One blanket "Cognito JWT for everything" either over-secures the probes (probes can't carry JWTs cleanly) or under-secures writes (a stolen long-lived JWT shouldn't be able to mutate the runtime). The collector implements four tiers explicitly.

| Tier | Endpoints | Auth | Authorization |
|------|-----------|------|---------------|
| **0** Probes | `/livez`, `/readyz`, `/startupz` | None (or shared static `OPS_COLLECTOR_PROBE_KEY` if probe surface is reachable outside the VPC) | None. Response bodies reveal up/down only — no bundle names, tenants, versions. |
| **1** Service-to-service | `/plugins`, `/events`, `/startup` (machine callers: dashboard-bff, CLDE-Hub, bridge Lambda) | **AWS SigV4** signed request | Mapped from the caller's IAM principal ARN to either System-Admin equivalence or a tenant scope (configured policy). |
| **2** Human reads | `/plugins`, `/events`, `/startup` (interactive operator) | **Cognito JWT** — reuse BX REST's validation library; same JWKS, same audience claim | `AD_Role`: System Admin → all tenants; tenant admin → own `AD_Client_ID` only. Server-side filtering before serialization. |
| **3** Writes | `POST /ops/v1/warmup` (and any future `/restart`, `/redeploy`) | **Cognito JWT + deliberate confirmation token** (short-lived 15-min HMAC nonce issued by the dashboard at deploy session start) | System Admin only. Both conditions required. |

**Authentication is one of {SigV4, JWT, probe-key, none}; authorization is the same `AuthContext` regardless:**

```java
record AuthContext(
    Tier tier,                       // 0..3
    Optional<String> principalArn,   // SigV4
    Optional<Integer> adUserId,      // JWT
    Optional<Integer> adRoleId,
    Optional<Integer> adClientId,    // tenant scope
    boolean isSystemAdmin
) {}
```

**Hard rules.**

- **Server-side tenant filtering**, always. A tenant admin querying `/ops/v1/plugins` gets results pre-filtered to their `AD_Client_ID`; query parameters cannot widen scope. Plugin payloads can leak tenant existence in subtle ways (bundle names that encode customer identity, stack traces with table prefixes) — filter before serialize, never after.
- **Reuse BX REST's JWT validation library** verbatim. Same JWKS endpoint, same audience claim, same role-mapping logic. Key rotation updates both at once.
- **Writes are never JWT-only.** Tier 3 requires the confirmation token *in addition to* the JWT. The token is issued by the dashboard on deliberate operator action (start of a deploy session); it expires in 15 minutes; one nonce, one use. Protects against stolen-JWT replay and against an MCP agent triggering writes as a side effect of autonomous reasoning. ~30 lines of code; eliminates a whole class of incident.
- **Probe bodies are minimal.** 503 means "not ready"; it does not say "TMS plugin failed on tenant 1000031." That detail belongs to Tier 1/2 callers.

### 4.8 Event schema (stdout JSON + EventBridge)

All events conform to one envelope. **Define this in `com.cloudempiere.ops.collector.api`** so consumers can depend on it.

```json
{
  "schema_version": "1.0",
  "event_id": "uuid-v4",
  "event_type": "plugin.component.failed_activation",
  "severity": "PANIC",
  "timestamp": "2026-05-30T14:21:03.123Z",
  "source": {
    "service": "idempiere",
    "ecs_task_arn": "arn:aws:ecs:eu-central-1:123456789012:task/cluster-name/abc123def456",
    "ecs_task_id": "abc123def456",
    "container_id": "abc123def456-collector",
    "private_ip": "10.8.0.42",
    "started_at": "2026-05-30T11:30:00Z",
    "iDempiere_version": "13.0.1",
    "collector_version": "1.0.0",
    "bundle_version_hash": "sha256:...",
    "deployment_color": "blue",
    "deployment_environment": "prod",
    "ad_client_scope": [1000026, 1000031],
    "cluster_id": "hazelcast-prod-eu-central-1"
  },
  "aggregation_hint": "per_task",
  "tenant": {
    "ad_client_id": 1000026,
    "ad_client_name": "Acme"
  },
  "subject": {
    "kind": "ds_component",
    "bundle": "com.cloudempiere.tms",
    "bundle_version": "2.4.7",
    "component": "com.cloudempiere.tms.event.handler"
  },
  "details": {
    "state": "FAILED_ACTIVATION",
    "missing_references": ["IEventManager"],
    "failure_message": "...",
    "stack_trace": "..."
  },
  "trace_id": "0123456789abcdef0123456789abcdef",
  "span_id": "0123456789abcdef",
  "dedupe_hash": "sha256:..."
}
```

**Required event types (initial set):**

- `plugin.lifecycle.failed` / `plugin.lifecycle.recovered`
- `plugin.component.unsatisfied` / `plugin.component.failed_activation`
- `plugin.resolution.failed`
- `plugin.descriptor.missing`
- `plugin.2pack.failed` / `plugin.2pack.succeeded`
- `plugin.warmup.completed`
- `ops.boot.phase_started` / `ops.boot.phase_completed` / `ops.boot.completed` / `ops.boot.stalled` / `ops.boot.crash_loop_detected`
- `ops.deploy.started` / `ops.deploy.completed`
- `ops.jvm.oom`
- `ops.runtime.component_degraded`
- `task.starting` / `task.ready` / `task.draining` / `task.terminating` — task lifecycle from collector's perspective (see 4.17)
- `cluster.membership.changed` — Hazelcast join/leave/split detected via `IClusterService`

**Dedupe rule:** `dedupe_hash = sha256(event_type + bundle + component + failure_message)`. If the same hash fires more than once per minute, suppress and increment an internal counter; emit a `plugin.event_suppressed` summary every 5 minutes.

**Trace correlation:** every event MUST carry `trace_id` / `span_id` so dashboards can jump from event → OTel trace → logs.

### 4.9 Lazy plugins: two-strategy validation

Three kinds of lazy stack: lazy bundle activation (L1), delayed DS components (L2 — the default), on-demand factory lookup (L3). Forcing activation to validate **mutates** the runtime. Split validation:

**Strategy A — Passive (recurring, non-mutating).** SCR introspection only via `ServiceComponentRuntime.getComponentConfigurationDTOs()`. `SATISFIED` ⇒ "will activate cleanly when first requested." Used by `/ops/v1/health`, the SCR poll (default interval 60s), and the cold-start scan. Safe forever. Catches unsatisfied references + missing descriptors (~90%).

**Strategy B — Active warmup (one-shot, post-deploy gate).** `POST /ops/v1/warmup` deliberately resolves service references (forces `@Activate` to run) for `IModelFactory`, `ICalloutFactory`, `IEventTopicHandler`, etc., and exercises the factory lookup path for a configured CLDE-table allowlist. Catches `@Activate` exceptions and service-ranking shadowing. **Never call from a recurring probe** — it's a deploy step, run once before reopening traffic.

```java
// Strategy B sketch — forces delayed-component activation
ServiceReference<IModelFactory>[] refs =
    ctx.getServiceReferences(IModelFactory.class, null);
for (ServiceReference<IModelFactory> ref : refs) {
    try {
        IModelFactory f = ctx.getService(ref);   // triggers @Activate
        record(OK, ref);
    } catch (Throwable t) {
        emit(PANIC, "component.failed_activation", ref, t);
    } finally {
        ctx.ungetService(ref);
    }
}
```

**Architectural guidance** (for the wider team, not just this bundle): prefer eager activation (`@Component(immediate = true)`, no `Bundle-ActivationPolicy: lazy`) for user-facing CLDE factories/handlers. Lazy made sense for desktop-era iDempiere; in long-lived multi-tenant containers it just means "first user pays the latency and finds the failure." Reserve lazy for genuinely expensive, rarely-used, side-effect-free components.

### 4.10 The "stdout floor" rule in code

```java
void emit(Severity sev, String type, Subject subj, Details details) {
    String json = envelope(sev, type, subj, details, currentTenant(), traceContext());
    log.error(json);                                 // 1. FLOOR — always
    try { otel.emit(sev, type, subj); }              // 2. OTel signal
    catch (Throwable t) { log.warn("otel sink down", t); }
    try { eventAdmin.postEvent(toOsgiEvent(json)); } // 3. in-JVM bus
    catch (Throwable t) { log.warn("event admin down", t); }
    // No DB write on hot path — DB sink is a separate async consumer
    // of OSGi EventAdmin, or downstream via Lambda from CloudWatch
}
```

**Sink isolation.** No sink failure can pressure another. No sink failure can wedge the collector. Log first, everything else best-effort.

### 4.11 OTel integration

- The Java agent is added at JVM start via `-javaagent` in the iDempiere container ENTRYPOINT. Handles auto-instrumentation (Servlet, JDBC, HTTP client, JMX). **VALIDATED 2026-05-30 (ADR-065)** — also auto-instruments **Redis** (Redisson) for free.
  - **CRITICAL attach gotcha (validated):** `idempiere-server.sh` launches the runtime JVM with **`$IDEMPIERE_JAVA_OPTIONS`**, not `$JAVA_OPTIONS`; `silent-setup` *bakes* the current `$JAVA_OPTIONS` into `utils/myEnvironment.sh` as `IDEMPIERE_JAVA_OPTIONS`. The `-javaagent` MUST therefore be prepended to `$JAVA_OPTIONS` **before `run_silent_setup`** in the entrypoint. Attaching *after* silent-setup is a silent no-op (heap/-D flags load, agent does not — confirmed by reading the live JVM cmdline; 0 traces). silent-setup's own equinox JVM ignores `JAVA_OPTIONS` (`-vmargs` launcher), so it stays un-instrumented.
- The collector bundle uses the OTel API (OSGi-wrapped) for **manual** instrumentation of iDempiere-specific concerns.
- Resource attributes set once at bundle activation: `service.name=idempiere`, `service.version=<bundle-version>`, `service.instance.id=<container-id>`, `deployment.environment=<prod|staging|dev>`.
- Tenant context attached **per signal** via Baggage / Span attributes: `tenant.id=<AD_Client_ID>`. **Never** as a static resource attribute (would collapse multi-tenant signals).
- OTel endpoint: `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317` (ADOT sidecar, gRPC — validated 2026-05-30; HTTP 4318 also works). Configurable via env (12-Factor III). The sidecar is the `aws-otel-collector` image (digest-pinned), `essential=false`, exporting to AWS X-Ray; the ZK `/zkau` comet long-poll is dropped **collector-side** (it shares the `/zkau` URL with interactive AU — can't be excluded at the agent).

### 4.12 Configuration

All config via environment variables (12-Factor III). Defaults shipped; nothing hardcoded.

| Env var | Default | Meaning |
|---|---|---|
| `OPS_COLLECTOR_SCR_POLL_SECONDS` | `60` | DS state poll interval |
| `OPS_COLLECTOR_EVENT_BUFFER_SIZE` | `5000` | In-memory ring buffer for /events |
| `OPS_COLLECTOR_DEDUPE_WINDOW_SECONDS` | `60` | Same-hash suppression window |
| `OPS_COLLECTOR_WARMUP_TABLES` | (empty) | Comma-separated CLDE tables for Strategy B |
| `OPS_COLLECTOR_BOOT_STARTUP_TIMEOUT_SECONDS` | `300` | Phase 4 not reached → emit `boot.stalled` |
| `OPS_COLLECTOR_SHUTDOWN_DRAIN_SECONDS` | `30` | SIGTERM → ready=false → wait → terminate |
| `OPS_COLLECTOR_HEALTH_PUBLIC` | `true` | /livez/readyz/startupz public, like BX REST |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | OTel Collector endpoint |
| `OTEL_SERVICE_NAME` | `idempiere` | OTel resource attribute |

### 4.13 Logging

- Use log4j2 (already in iDempiere). Configure JSON layout via `log4j2.xml` — the deploy of the collector ships a log4j2 fragment.
- Single log4j logger name: `com.cloudempiere.ops.collector`.
- All emitted events use level `ERROR` regardless of severity tier — the severity tier is a JSON field, not a log level. This makes CloudWatch metric filters cleanly target `{ $.event_type = "plugin.*" && $.severity = "PANIC" }`.
- Never log secrets, JWTs, DB connection strings, or user PII. Tenant ID is OK (it's an integer, not sensitive).

### 4.14 Threading & resource model

- SCR poll runs on a single scheduled executor (`Executors.newSingleThreadScheduledExecutor`). Daemon thread. Named `clde-ops-collector-scr-poll`.
- Event dispatch is async via a bounded queue (size 10_000). If queue saturates, drop oldest + emit `ops.collector.queue_overflow`.
- No blocking I/O on the listener callback thread. Listeners enqueue, dispatcher drains.
- Total memory budget: <50 MB heap (event buffer + DTOs). Profile before merging.

### 4.15 Testing requirements

- **Unit tests:** dedupe, severity classification, tenant enrichment, event envelope schema validation, ring buffer overflow.
- **OSGi integration tests:** Pax Exam or equivalent. Spin up an Equinox runtime, install the collector + a deliberately-broken test bundle (missing reference, throwing `@Activate`, missing DS descriptor), assert correct events fire.
- **Probe tests:** boot-phase ordering, `/readyz` flips on `SIGTERM`, `/startupz` returns 503 → 200 transition.
- **Warmup test:** Strategy B against a known-broken component, assert `failed_activation` event emitted with correct payload.
- **Schema tests:** every emitted event validates against a JSON schema kept in the bundle (`OSGI-INF/schemas/event.schema.json`).

### 4.16 Operational guards (mandatory, in Jenkins)

```bash
# 1. DS descriptors present in JAR
unzip -l target/*.jar | grep -q OSGI-INF/.*\.xml || fail "missing DS XML"

# 2. MANIFEST has no AWS SDK imports
unzip -p target/*.jar META-INF/MANIFEST.MF | grep -q "software.amazon.awssdk" && fail "no AWS SDK in OSGi"

# 3. No pinned bundle versions
unzip -p target/*.jar META-INF/MANIFEST.MF | grep -Eq 'bundle-version="[0-9]+\.[0-9]+\.[0-9]+"' && fail "use version ranges, not pinned"

# 4. OTel agent attached in container ENTRYPOINT
grep -q opentelemetry-javaagent Dockerfile || fail "OTel agent missing"

# 5. Event schema validates
java -jar event-schema-validator.jar OSGI-INF/schemas/event.schema.json
```

### 4.17 Multi-task semantics (fleet, not cluster)

iDempiere runs as N ECS Fargate tasks. They are **nominally** clustered via Hazelcast but cannot be assumed coordinated at every moment. Real ways this breaks: Hazelcast join can fail or be delayed; rolling deploys leave old-version + new-version tasks running simultaneously; a task in `STARTING` emits boot events while peers emit steady-state events; draining tasks keep emitting AUDIT events for in-flight work. Consumers that treat task events as interchangeable draw wrong conclusions.

**Operational principle: treat the collection of tasks as a *fleet*, not a *cluster*.** "Cluster" implies coordination (Hazelcast, leader election); "fleet" implies independent units that report individually and may or may not be coordinated. Most operations reason about the fleet; cluster-specific signals are an explicit separate thing the collector also surfaces.

**1. Task identity is mandatory on every event.** The `source` block in 4.8 carries `ecs_task_arn`, `ecs_task_id`, `container_id`, `started_at`, `iDempiere_version`, `collector_version`, `bundle_version_hash`, `deployment_color`, `ad_client_scope`, and `cluster_id`. The collector populates these once at bundle activation by reading **ECS task metadata** at the `ECS_CONTAINER_METADATA_URI_V4` env-injected URL (Fargate provides this automatically; one GET, cache the result for the bundle's lifetime). If `ECS_CONTAINER_METADATA_URI_V4` is unset (local Docker Compose), fall back to `hostname` + a generated UUID and set `deployment_environment=local`. **Failure to read task metadata is not fatal** — log a WARN, populate with `unknown`, continue.

**2. `aggregation_hint` is required on every event.** Tells consumers how to combine across tasks:

- `per_task` — value is for this task only; summing across tasks is wrong (heap, sessions, thread count).
- `per_cluster` — value reflects shared state already; one task's view is the cluster's view (`cluster.membership.changed`, distributed cache size).
- `per_tenant` — value is scoped to a tenant; consumers can sum within a tenant across tasks if needed (`per_tenant_session_count`).

This is a small field that prevents large mistakes downstream. A naive consumer summing `sessions_count` across 4 tasks gets 4× the real number; with `aggregation_hint=per_task` and `ecs_task_id`, the consumer can de-duplicate or aggregate correctly.

**3. Cluster membership is its own signal.** The collector emits `cluster.membership.changed` (`aggregation_hint: per_cluster`) when `IClusterService` reports a join/leave/split. Without this signal, a brief Hazelcast split-brain appears as "half my tasks went silent" — alarming but wrong. Consumers track which task is in/out and reason accordingly.

**4. Task lifecycle events bracket every other event.** The collector emits:

- `task.starting` — at collector `@Activate`, before any other events.
- `task.ready` — when `/ops/v1/readyz` first returns 200.
- `task.draining` — on `SIGTERM`, immediately. Tells consumers "everything from this `ecs_task_id` after this point is drain-phase; may be safely deprioritized for fleet-health reasoning."
- `task.terminating` — last event from this task ARN; emitted right before JVM exit. Best-effort (no guarantee, but typical).

EventBridge consumers can filter on `task.draining` to skip drain-phase events when reasoning about current health, while still keeping them for the audit trail.

**5. Cross-task deduplication lives in the bridge, not the collector.** The dedupe rule in 4.8 deduplicates *within* a task; *across* tasks, the bus correctly sees N copies (losing one is worse than counting N). The **bridge Lambda** between CloudWatch Logs and EventBridge applies a second dedupe pass: hash `(event_type, bundle, component, tenant)` *ignoring* `source.ecs_task_id`. If N events with the same cross-task hash arrive within 60s, the bridge creates **one** Linear ticket listing all N task ARNs, not N tickets. The aggregated ticket lists which tasks fired, when, and any unique payload elements. Far more useful than four separate tickets a human must manually correlate.

The collector's job is to emit faithfully per task. The bridge's job is to summarize for human consumers. Don't conflate.

**6. Two consumer patterns; two right answers.**

- *Operational view ("is anything wrong right now?").* The consumer cares about *any task* reporting a problem, not aggregation. EventBridge rule: "if any event with `severity=PANIC` arrives, alarm." Over-firing on a single bad task is what you want — investigate task by task.
- *Fleet view ("are 80% of tasks healthy?").* Requires aggregation. Use OTel metrics with `ecs_task_id` as a dimension. CloudWatch alarm: "alert when fewer than 75% of tasks have `bundle_active_ratio = 1.0`." Reserve a custom aggregator (Lambda → DynamoDB / `cloudempiere_ops`) for genuinely complex correlations that CloudWatch math expressions cannot express.

**7. ECS metadata fetch — implementation note.**

```java
@Activate
void activate(BundleContext ctx) {
    String metadataUrl = System.getenv("ECS_CONTAINER_METADATA_URI_V4");
    if (metadataUrl != null) {
        try {
            // GET metadataUrl    → container info (taskArn parent, image, etc.)
            // GET metadataUrl + "/task" → task info (taskArn, family, revision, cluster)
            // Cache for bundle lifetime; refresh only on bundle restart
            this.taskIdentity = TaskIdentity.fromEcsMetadata(...);
        } catch (Throwable t) {
            log.warn("ECS metadata unreachable; falling back to local identity", t);
            this.taskIdentity = TaskIdentity.local();
        }
    } else {
        this.taskIdentity = TaskIdentity.local();   // Docker Compose, dev
    }
}
```

No AWS SDK required — the metadata endpoint is plain HTTP from inside the task; a `java.net.http.HttpClient` GET suffices. Keeps the no-AWS-SDK rule intact.

---

## 5. Severity tiers

| Tier | Scope | Action | Examples |
|------|-------|--------|----------|
| **PANIC** | Framework-level / whole container | CloudWatch alarm → page | Bundle fails to resolve; DS `FAILED_ACTIVATION`; `OutOfMemoryError`; boot stalled |
| **ALERT** | Tenant-scoped, blocks business | DB + Slack | 2Pack import failed; validator throws; scheduled process keeps failing |
| **AUDIT** | Notable, expected | DB only, for dashboard | Bundle stop-start (deploy); doc-state errors; login failures |

CloudWatch metric filters alarm only on PANIC + ALERT. AUDIT events stay out of EventBridge to control cost.

---

## 6. Phased rollout

| Phase | Scope | Effort | Value |
|-------|-------|--------|-------|
| **0** | CloudWatch metric filters on existing container stdout (5–6 regex → SNS → Slack) | ~½ day | ~70% of failures, zero code |
| **1** | Adopt BX REST in the base image (`/api/v1/health` etc.) if not present | ~1 day | Snapshot ops data, community contract |
| **2** | Collector bundle in base image — DS state, 2Pack, tenant enrichment, /ops/v1, stdout JSON | ~1 week | Structured, tenant-aware, DS coverage |
| **3** | OTel Java agent + sidecar Collector wired into container | ~2 days | Auto-instrumentation, correlated signals |
| **4** | EventBridge `cloudempiere-ops` bus + bridge Lambda | ~2 days | Domain-event fan-out |
| **5** | deploy-dashboard-bff tile consuming /ops/v1 + /api/v1 | ~3 days | One human dashboard |
| **6** | CLDE-Hub MCP plugin-health tools | ~3 days | Conversational ops |
| **7** | Triage agent — EventBridge → ephemeral Quarkus Fargate task → MCP context + Linear/n8n | ~1 week | Automated incident response |

Each phase ships independent value. Stop at any phase and what's there is still useful.

---

## 7. Consequences

### Positive
- Detects the "ACTIVE but broken" DS-component failure class invisible to container/orchestrator health.
- Two-pipe egress (OTel signals + EventBridge events) aligns with 2026 industry standard; no vendor lock-in.
- Single stable `/ops/v1` contract absorbs iDempiere version volatility; consumers don't break on upgrade.
- Identical container image runs locally and in prod — portability preserved by the no-AWS-SDK rule.
- Reuses BX REST community contract for snapshot ops data; collector focuses on unique signals.
- Establishes a reusable "operational data plane" template for Camel integrations, Aquaseed, warehouse scanner.

### Negative / risks
- **Collector is a single integration point inside iDempiere.** *Mitigation:* stdout floor keeps alerting independent; treated as critical infra — read-only, fail-soft per sub-source.
- **One-shot scan loses pre-listener error stacks** (reports state, not original exception). *Mitigation:* accept for v1; escalate to Equinox `BundleWatcher` only if pre-startup stacks prove painful.
- **OTel agent adds container start time** (~2–5s typical). *Mitigation:* tune `OPS_COLLECTOR_BOOT_STARTUP_TIMEOUT_SECONDS` and ECS `startPeriod` based on measured P99 boot.
- **EventBridge schema design before abundant failure data.** *Mitigation:* freeze v1.0 schema after Phase 2 runs in prod for one month; evolve via `schema_version` field.

### Explicitly rejected
- **Container/orchestrator health alone** — blind to plugin/DS state.
- **External OSGi-console poller** — not an event stream; no DS state; brittle; security/port liability in Docker.
- **Regex-only log parsing as end state** — accepted as Phase 0 only.
- **Custom log4j2 → CloudWatch appender library** — stdout shipping is current best practice; AWS SDK risks OSGi conflicts and breaks image portability.
- **MCP server inside iDempiere** — intelligence belongs in CLDE-Hub.
- **EventBridge as primary egress from the JVM** — domain-event bus, not a JVM egress; events flow stdout → CloudWatch → bridge → bus.
- **Patching the iDempiere fork to add `/api/v1/bundles`** — sets a precedent we'll regret. Collector provides `/ops/v1/plugins` instead.
- **Extending `idempiereMonitor`** — wrong shape (HTML, interactive admin); the collector + Felix Web Console + BX REST cover everything it offers in better shapes.

---

## 8. Validation hooks

Operationalized in `idempiere-deploy-checklist.docx`. Patterns the collector and CloudWatch filters watch (Phase 0):

- `BundleException` / `Could not resolve module` / uses-constraint → `plugin.resolution.failed`
- `[SCR] Error while trying to bind reference` → `plugin.component.failed`
- `Component descriptor entry ... not found` → `plugin.descriptor.missing`
- `AdempiereActivator.installPackage ... fail` → `plugin.2pack.failed`
- `FrameworkEvent ERROR` → `plugin.framework.error`
- `OutOfMemoryError` → `jvm.oom` (PANIC)

Pre-deploy guards: see 4.16. Container guard: wire `docker HEALTHCHECK` to `/ops/v1/readyz`, ECS task health check to `/ops/v1/readyz` with `startPeriod >= 180s`.

Real-world regression case: the `org.idempiere.webservices` uses-constraint violation reported on the iDempiere Google Group is the canonical Phase 0 test case. The metric filter must fire on it before any code is written.

---

## 9. Open questions

- **SigV4 IAM policy shape:** which IAM actions does the collector consider equivalent to "System Admin"? Lean: an explicit `cloudempiere:ops:read-all` custom action, mapped from the principal ARN at the collector's auth filter. Easier to audit than role-name matching.
- **Confirmation-token issuer:** deploy-dashboard-bff issues, signs with HMAC, hands to operator at deploy session start, operator passes through to CLDE-Hub or invokes `/ops/v1/warmup` directly. Key rotation story for the HMAC secret needs pinning.
- **Fleet-aggregation tooling:** CloudWatch metric math (lean) vs custom aggregator Lambda (heavier). Decide after Phase 4 reveals what queries operators actually want.
- **OTel exporter:** AWS Distro for OpenTelemetry (ADOT) Collector sidecar vs upstream OTel Collector? Lean ADOT (native AWS auth, X-Ray + CloudWatch + AMP exporters bundled).
- **Phase-1 audit log store:** `cloudempiere_ops.plugin_events` (chosen) vs CloudWatch Logs Insights as primary query target — revisit if DB write coupling proves painful.
- **`/ops/v1/metrics` Prometheus surface:** the OTel collector already exposes Prometheus if needed; do we still want a direct Prometheus endpoint from the collector bundle? Lean no.
- **Warmup table allowlist (Strategy B):** which customized tables to exercise at deploy time? Start with the top 10 highest-traffic CLDE-customized windows.
- **`BundleWatcher` adaptor hook:** build it only if losing pre-listener activation stack traces becomes a real operational pain.
- **Open-source the collector under `org.idempiere.ops.collector`?** Defer to a follow-up ADR after Phase 2 stabilizes. The collector is 90% non-CLDE-specific code; community contribution is plausible. Intelligence (CLDE-Hub agent + Linear/n8n workflows) stays proprietary regardless.
- **Boot baseline data:** before tuning timeouts, collect actual boot duration per phase across tenants for one week. The defaults in 4.12 are estimates.

---

## 10. Wiring into the CloudEmpiere AWS substrate (validated context)

This section resolves the doc's open egress decisions against the **substrate that
already exists** in `clde-infra`, and validates the design against the **live
external sampler** (clde-infra **ADR-064**, `modules/idempiere-telemetry-sampler`,
in prod since 2026-05-30). The two are deliberate complements:

| | **This collector (ADR-001)** | **Telemetry sampler (ADR-064)** |
|---|---|---|
| Vantage | **in-JVM** (Equinox) | **external** Lambda, polls each task |
| Owns | OSGi bundle/DS state, 2Pack, boot phases, tenant — *"ACTIVE but broken"* | caches, sessions, JVM, per-task liveness snapshot |
| Egress | stdout JSON -> CloudWatch -> **bridge -> EventBridge** + OTLP | direct REST -> S3/Athena + CloudWatch/Timestream + EventBridge |
| Repo | `idempiere-cloudempiere/com.cloudempiere.ops.collector` (the bundle) | `clde-infra` (the AWS plumbing) |

The bundle lives in the fork; **all AWS-side wiring below is `clde-infra`'s job.**
The fork emits faithful stdout; clde-infra turns it into metrics/events/alarms.

### 10.1 Bus decision -- use `cloudempiere-events`, not a new `cloudempiere-ops` bus

§3 (principle 5) and §4.8 referenced a `cloudempiere-ops` bus. **Resolved: emit onto
the existing `cloudempiere-events` substrate** (ADR-024/025/026). Reasons:

- It already has the full read-side: EventBridge bus -> DDB sink (`release_events_store`)
  -> projections -> **Slack relay** -> 90-day archive (`event-bus/` in clde-infra).
  A second bus duplicates all of that.
- The standing rule (clde-infra CLAUDE.md, "substrate events") is *every platform
  lifecycle action emits on `cloudempiere-events`* -- operational lifecycle (plugin
  failed, boot stalled, OOM) is exactly that.
- The external sampler **already emits `telemetry.anomaly.detected` on this bus** -- so
  collector events and sampler anomalies land in one place, **joinable on
  `ecs_task_id`/`task_arn`** (the collector's §4.8 `source` and the sampler's
  `task_health` share that key).

Keep `cloudempiere_ops` (the Postgres DB) as the **Phase-1 audit store** -- that's a
DB, not the bus. Only the *bus name* changes from the draft.

### 10.2 The egress path (concrete, all clde-infra)

```
[ JVM ] collector.emit()  -- log4j2 JSON (FLOOR) -->  stdout
                                                        |
                                ECS awslogs driver (already configured)
                                                        v
                          CloudWatch Logs  /ecs/idempiere-clde-<tier>-<env>
                                                        |
                         +------------------------------+------------------------------+
                         | (Phase 0, interim)                                          | (Phase 4)
                         v                                                             v
                 Metric Filter                                          Subscription Filter
            { $.event_type="plugin.*" && $.severity="PANIC" }          (severity in PANIC,ALERT)
                         |                                                             v
                    Alarm -> SNS -> Slack                                   ops-bridge Lambda
                                                                  - parse collector JSON
                                                                  - wrap in CloudEvents v1.0 (10.3)
                                                                  - cross-task dedupe (10.4)
                                                                  - PutEvents
                                                                             v
                                                          EventBridge  cloudempiere-events
                                                                             |
                                          (existing) DDB sink + projections + Slack relay + archive
                                                                             |
                                          rules -> CLDE-Hub (triage) / n8n / Linear MCP
```

OTLP signals (metrics/traces) take the **separate pipe** (§4.11) -- OTel agent + ADOT
sidecar -> CloudWatch/AMP/X-Ray. EventBridge carries **domain events only**, never the
JVM signal firehose. (Two pipes, as §3 principle 5 states -- now mapped to real sinks.)

### 10.3 Envelope: wrap the collector schema in CloudEvents v1.0 at the bridge

The substrate's contract (ADR-024, `ce-jenkins/vars/ceEvent.groovy`) is **CloudEvents
v1.0**. The collector keeps emitting its rich §4.8 envelope to stdout; the **bridge
Lambda wraps it** -- no change to the bundle:

```jsonc
{ "specversion":"1.0",
  "id":        "<collector event_id>",
  "source":    "idempiere/ops-collector",            // substrate <engine>/<emitter-id>
  "type":      "plugin.component.failed_activation",  // == collector event_type
  "time":      "<collector timestamp>",
  "datacontenttype":"application/json",
  "data":      { /* the entire collector §4.8 payload: severity, source{ecs_task_id,
                   ad_client_scope, ...}, subject, details, trace_id, dedupe_hash */ } }
```

`source = idempiere/ops-collector` follows the substrate convention (cf.
`jenkins/ce-kill-session`, `lambda/idempiere-telemetry-sampler`). No PII/secrets in
`data` (ADR-024 rule) -- tenant **ID** is fine, names already excluded by §4.13.

### 10.4 Cross-task dedupe + catalog + IAM (the substrate obligations)

- **Cross-task dedupe lives in the bridge** (matches §4.17.5): hash
  `(event_type, bundle, component, ad_client_id)` *ignoring* `ecs_task_id`; N matches
  in 60s -> **one** CloudEvent (and one Linear ticket) listing all task ARNs. Per-task
  fidelity stays on stdout; the bus carries the summarized fact.
- **Catalog every event type first.** All §4.8 types (`plugin.*`, `ops.boot.*`,
  `ops.jvm.oom`, `task.*`, `cluster.membership.changed`) must be added to
  `ce-jenkins/catalog.yaml` "Outcome types" **before first emit** -- the pre-merge
  validator enforces it (same gate the sampler's `telemetry.anomaly.detected` passed).
- **IAM:** the ops-bridge Lambda role needs `events:PutEvents` on
  `arn:aws:events:eu-west-1:806902423583:event-bus/cloudempiere-events` (mirror
  `aws_iam_role_policy.jenkins_substrate_emit` in `ci-jenkins/main.tf`).

### 10.5 clde-infra ownership map (who builds what)

| Piece | clde-infra home | Phase |
|---|---|---|
| Metric filters + PANIC/ALERT alarm -> SNS/Slack | `monitoring-prod/` | **0** (now, zero collector code) |
| Subscription filter + **ops-bridge Lambda** (wrap + dedupe + PutEvents) | `event-bus/` (beside the existing relay/projector Lambdas) | 4 |
| Catalog entries for the new types | `ce-jenkins/catalog.yaml` | 2 |
| EventBridge rules -> CLDE-Hub triage / n8n / Linear | `event-bus/` | 4-7 |
| Bundle in the base image + OTel agent in ENTRYPOINT | the iDempiere image build (`images/idempiere-clde/`) | 2-3 |

### 10.6 Validation against the live sampler (why the aim holds)

The external sampler already **proved the hardest assumptions** this collector rests on:

- **Fleet-not-cluster (§4.17) is empirically correct.** We observed exactly 4 cluster
  nodes from a single REST call, the cluster-aggregated `/caches` view needing
  **dedup**, off-hours scale-down, and rolling mixed-version tasks. The collector's
  `aggregation_hint` + per-task `source` is the clean in-JVM version of what the
  sampler hand-derived; the sampler's `cluster_view_mismatch` ~= the collector's
  `cluster.membership.changed`.
- **BX REST base (Phase 1) works in prod** -- `/api/v1/caches` + `/api/v1/nodes/{id}`
  verified via the Cognito M2M `notifier` client. The collector adopting BX REST as
  its snapshot base is validated; the collector's job is the **DS-component state**
  BX REST can't expose (`/ops/v1/plugins`).
- **stdout -> CloudWatch is real** -- the §8 patterns (`AdempiereActivator.installPackage`,
  `FrameworkEvent ERROR`, `container.startup.milestone`) were read live from
  `/ecs/idempiere-clde-webui-pr`. **Phase 0 is buildable today against real logs.**
- **`/ops/v1/plugins` supersedes a fork `/api/v1/bundles` patch** -- both this ADR (§7
  Rejected) and ADR-064 land on the same conclusion: don't patch the REST fork for
  bundle state; expose it from an independent bundle. The aims agree.

### 10.7 Two reconciliations to pin (cross-ADR)

- **Health/drain vs ADR-007.** The DS-aware `/ops/v1/readyz` should become the ALB
  target-group check (it catches *"ACTIVE but DS-dead"* that today's `/webui/` check
  misses). But its `SIGTERM -> readyz=503 -> drain` (§4.17.4) overlaps **drain-first /
  kill-session (ADR-007)**. Pin ownership: the collector flips ALB readiness;
  `ceKillSession` still owns ZK session continuity. They compose (readiness drains new
  traffic; kill-session drains sticky desktops) -- document the order in the deploy runbook.
- **JVM metrics path.** The OTel agent + ADOT sidecar (§4.11) is the *native* JVM-metrics
  route -- it supersedes both the sampler's `/nodes` heap-string read and the
  Container-Insights Java/JMX option. Same **cost class** as flagged in ADR-064 (a sidecar
  per task ~= Fargate $); curate the exported metric set to avoid the cardinality bill.

---

## 11. Webui span enrichment + validated deployment requirements (rev 7, 2026-05-30, ADR-065)

The OTel agent + ADOT sidecar is now **validated live** (ADR-065, staging webui): a
slow `/zkau` decomposes into nested **JDBC spans** — one 10.8 s click = **3,374 SQL
calls** across 15 statements, dominated by a `SUM(WeightTotal,...)` aggregate run 30×
(7.4 s) and `SHOW TRANSACTION ISOLATION LEVEL` run 1,812× (JDBC driver chatter). The
agent gives the *what* (which SQL); this section adds the requirement for the *who/where*
(which user, window, tab, process) and pins the deployment constraints learned in testing.

### 11.1 REQUIREMENT — webui AU span enrichment (Phase 2.5, NEW)

The agent's `/zkau` server span is **opaque** — it cannot see ZK's protocol, so it
cannot say *which user on which window/tab/process* a slow click came from. Requirement:
enrich the **current server span** with iDempiere business context per AU request, via
the OTel API (`Span.current().setAttribute(...)`; `opentelemetry-api` is compile-only,
the agent provides the runtime).

**Packaging decision — SEPARATE bundle, NOT the core collector.** The enrichment needs
the **webui bundle** (`org.adempiere.ui.zk`); the `ops.collector` core is **tier-agnostic**
(runs on webui *and* rest *and* scheduler, which have no ZK). Adding a webui dependency to
the core would break resolution on rest/scheduler. → new sibling bundle
`com.cloudempiere.ops.trace.webui`, in this repo/feature, deployed only where ZK exists.

**Attribute schema** (set on the current span):

| Layer | Hook point (thread) | Attributes |
|---|---|---|
| Identity | `SessionContextListener` (`ExecutionInit`, servlet thread — span IS current) | `enduser.id`, `ad.user_id`, `ad.client_id`, `ad.role_id`, `ad.session_id`, `zk.desktop_id` |
| Functional | ZK `EventInterceptor.beforeProcessEvent` + `SessionManager` active window | `idempiere.window_id`/`window_name`, `idempiere.tab_name`, `idempiere.table`, `idempiere.record_id`, `idempiere.process_id`, `idempiere.form_id` |

- **Event-thread caveat:** if iDempiere runs events on a separate ZK event thread,
  `Span.current()` there may be a no-op (context not propagated across the handoff). Do
  identity in `ExecutionInit` (servlet thread, span present); if functional attrs resolve
  on the event thread, capture the span at request entry or set them back during
  `ExecutionCleanup`. **Test:** set one attribute in `ExecutionInit` and confirm it lands.
- **X-Ray filterability:** custom attributes default to X-Ray *metadata* (not queryable).
  To filter like `annotation.idempiere_window_name = "Sales Order"`, the ADOT collector's
  `awsxray` exporter MUST list these keys in `indexed_attributes` (→ X-Ray annotations).
  This is an **infra-side change in clde-infra** (the tier module's collector config),
  paired with this bundle.

### 11.2 REQUIREMENT — validated deployment constraints

- **Attach order** (§4.11): `-javaagent` on `$JAVA_OPTIONS` **before `run_silent_setup`**.
  Non-negotiable; the alternative is a silent no-op.
- **Persistence — bake into the plugin lineup.** Hot-deploy (Equinox telnet console,
  `clde-osgi-deploy.sh`) is **ephemeral**: validated 2026-05-30 that the bundle is lost on
  every task replacement (schedule cycle, image redeploy, `-replace`). For a durable
  collector it MUST be in the **baked plugin lineup** (image), not hot-deployed. Hot-deploy
  stays a dev/iteration tool only.
- **Sidecar + comet:** ADOT `aws-otel-collector` (digest-pinned), OTLP gRPC `:4317`,
  `essential=false`; comet dropped collector-side (shared `/zkau` URL).

### 11.3 Phase rollout — status update

- **Phase 3 (OTel agent + sidecar): DONE / validated** on staging webui (ADR-065). The
  infra half (image jar, entrypoint attach, sidecar, X-Ray IAM, collector config) is live
  and proven.
- **New Phase 2.5 — webui span enrichment** (§11.1): the `…ops.trace.webui` bundle. Slots
  between Phase 2 (core collector) and Phase 3; depends on the agent already attaching.
- **`EventDispatcher` Phase 3 TODO** (`otel.emit(event)`): now concrete — emit `OpsEvent`s
  as OTel span events / logs so bundle-lifecycle + cold-start land in the same X-Ray
  backend as the traces, correlated by `trace_id`/`span_id` (already in the §4.8 schema).
