# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> Workspace-wide conventions (layer taxonomy, S3 P2, git/commit rules) live in the parent
> `idempiere-cloudempiere/CLAUDE.md`. This file is specific to the **ops collector** plugin.
> The authoritative design is **`docs/ADR-001-iDempiere-Operational-Observability.md`** — read it
> before non-trivial work; section numbers (§4.x, §11.x) are referenced throughout the code.

## What this is

An in-JVM OSGi **operational-observability collector** for iDempiere (ADR-001): it surfaces what
container/ALB health checks cannot — OSGi bundle + Declarative-Services component state, boot
phases, 2Pack lifecycle, graceful-shutdown drain — as events (stdout floor + EventAdmin), an
exported status service, unauthenticated health probes, and an authenticated REST API. A separate
fragment enriches webui OTel spans.

**Core thesis — "ACTIVE is a lie" (§1):** a bundle can be OSGi `ACTIVE` while its DS components are
`UNSATISFIED_REFERENCE`/`FAILED_ACTIVATION` — invisible to `docker ps`/ECS/ALB. "Healthy" therefore
means **bundle ACTIVE *and* every DS component SATISFIED/ACTIVE** — never bundle state alone. Much of
the code exists to detect exactly this.

## Build & test

Tycho 2.7.5, **JavaSE-11**, Maven 3.9+. Build resolves the target platform from two P2 repos: the
iDempiere **core** P2 and the **rest fork** P2 (for `com.trekglobal.idempiere.rest.api`).

```bash
# Local dev — uses file:// P2 from sibling checkouts (the cloudempiere-local profile):
#   core: iDempiereCLDE/core/org.idempiere.p2/target/repository   rest: ../.p2-cache/rest
mvn clean verify -Pcloudempiere-local                 # full reactor (7 modules)
mvn clean verify -Pcloudempiere-local -DskipTests     # the reactor has no in-tree tests anyway

# CI / explicit repos (S3 default points at develop; pass staging/local as needed):
mvn clean verify -Didempiere.core.repository.url=file:///…/org.idempiere.p2/target/repository \
                 -Didempiere.rest.repository.url=file:///…/.p2-cache/rest
```

P2 output: `com.cloudempiere.ops.collector.p2/target/repository/`.

**Unit tests are a standalone JUnit 5 module (`…ops.collector.test`) that is NOT in the reactor**
(JUnit 5 isn't in the iDempiere P2). It depends on the *installed* bundle jar, so run in order:

```bash
mvn -f com.cloudempiere.ops.collector.parent/pom.xml install -N   # 1. install parent POM
mvn install -Pcloudempiere-local -DskipTests                      # 2. install bundle jars
mvn -f com.cloudempiere.ops.collector.test/pom.xml test           # 3. run tests
```

Artifacts install to a custom local repo (`~/.m2/repository-idempiere-10/`).

## Module layout & the 3-feature rule

```
com.cloudempiere.ops.collector        core bundle — tier-agnostic; also a WAB (Web-ContextPath: ops)
com.cloudempiere.ops.rest             JAX-RS /api/v1/ops/* (Require-Bundle: com.trekglobal…rest.api)
com.cloudempiere.ops.trace.webui      webui OTel-span fragment (Fragment-Host: org.adempiere.ui.zk)
*.feature ×3                          one flat eclipse-feature per bundle
com.cloudempiere.ops.collector.p2     eclipse-repository (MUST be last in reactor order)
*.parent                              Tycho config + the cloudempiere-local profile
*.test                                standalone JUnit 5 (see above)
```

**Why 3 features and not 1 (do not "consolidate"):** the bundles deploy on *different iDempiere
tiers* with mutually-exclusive platform deps — core resolves everywhere, `ops.rest` only where
`rest.api` exists (rest tier), `ops.trace.webui` only where `org.adempiere.ui.zk` exists (webui
tier). No single tier resolves all three, so one feature would be uninstallable everywhere. This is
the multi-tier exception to the community "one feature per plugin" pattern (graphql/trekglobal are
single-tier). See `docs/DEPLOY-clde-infra-bake.md` for per-tier feature IUs.

## Architecture (the parts that span files)

- **`api/` is the only exported package** (`OpsEvent`, `Severity`, `OpsStatusService`). The REST/webui
  bundles and external consumers couple only to this — never to `internal/`.
- **`internal/EventDispatcher`** is the hub (DS `immediate`). Its **stdout floor** rule (§4.10): every
  event logs to stdout *first* (the one egress ECS guarantees) via `CLogger`, then best-effort sinks
  (EventAdmin, future OTLP) run wrapped so no sink failure can pressure another. A bounded ring buffer
  backs `GET /events`. `SeverityClassifier` + `TenantEnricher` are mandatory `@Reference`s.
- **Event sources** (all DS components feeding the dispatcher): `ColdStartScanner` (one-shot bundle
  scan), `BundleLifecycleSource` (Bundle/Framework listeners), `DSComponentSource` (SCR poll — the
  unique "ACTIVE but broken" signal), `TwoPackEventSource` (`AD_Package_Imp` PK_Status),
  `BootPhaseTracker`, `ShutdownHandler` (SIGTERM → drain → flip readiness), `WarmupRunner` (Strategy B).
- **`OpsStatusProvider`** implements the exported `OpsStatusService`, aggregating the above; the REST
  resource and the health servlet look it up via the OSGi registry (they're not DS components).
- **Two HTTP surfaces, deliberately split:**
  - **Probes** — `OpsHealthServlet` is a **WAB** servlet at `/ops/livez|readyz|startupz`,
    *outside* `/api/v1`, **unauthenticated** (the ALB/Docker prober can't send `Authorization`;
    probes read in-memory state only — never the DB). Point ALB health checks at `/ops/readyz`.
  - **Data API** — `OpsV1Resource` at `/api/v1/ops/{startup,plugins,events,warmup}`, registered via
    the rest fork's `ResourceExtension` SPI (`OpsResourceExtension`, a DS `@Component`, *not* BX REST).
    Auth is **M2M-first**: the global `CLDERequestFilter` authenticates (incl. Cognito M2M); this
    bundle adds **no** auth code. **Policy B:** system-level `startup`/`plugins`/`warmup` require a
    System-tenant token (`AD_Client_ID 0`); `events` is tenant-scoped. See `docs/PROVISIONING-ops-monitor-m2m.md`.
- **OTel** is via **reflection shims** (`internal/OtelContext`, webui `internal/OtelSpan`): the OTel
  API is provided by the Java agent *at runtime* (bootdelegation), so there is **no compile/Import
  dependency** on it — code no-ops silently when the agent is absent. A full OTLP *emit* sink is TODO
  (needs an OSGi-wrapped OTel API bundle in the target P2).

## Critical gotchas

- **DS descriptors are hand-authored in `OSGI-INF/*.xml` — tycho-ds does NOT generate them here.**
  The `@Component` annotations are intent; the checked-in XML is what actually registers. If a
  descriptor is missing from the JAR, the bundle installs `ACTIVE` while its component **silently
  does not exist** (§4.5). `build.properties` MUST list `OSGI-INF/` in `bin.includes`; the CI guard
  in `README.md` greps the built JAR for `OSGI-INF/*.xml`.
- **The core bundle is a WAB** (`Web-ContextPath: ops` + `WEB-INF/web.xml`, `metadata-complete="true"`
  to skip the slow Servlet-3 annotation scan). Adding probes ⇒ keep `WEB-INF/` in `bin.includes`.
- **Deploy = durable bake, not console hot-deploy, for anything boot-gated.** The webui fragment's ZK
  `config.xml` is scanned only at framework start, so a console `install`/`refresh` attaches the
  fragment but never activates the enricher — it must be present at boot (baked lineup / `update-prd.sh`
  at task start). Install ≠ activation. Hot-deploy also evaporates on ECS task rollover. See
  `docs/DEPLOY-clde-infra-bake.md`, `docs/CICD-wiring.md`, ADR §11.2/§11.4, and the `llm-osgi-fastpath`
  skill (now in the `idempiere-tools` plugin).
- **Runtime knobs (env vars):** `OPS_COLLECTOR_EVENT_BUFFER_SIZE`, `OPS_COLLECTOR_SCR_POLL_SECONDS`,
  `OPS_COLLECTOR_SHUTDOWN_DRAIN_SECONDS`, `OPS_COLLECTOR_WARMUP_TABLES`,
  `OPS_COLLECTOR_WARMUP_HTTP_ENABLED` (gates the mutating `POST /warmup`).
