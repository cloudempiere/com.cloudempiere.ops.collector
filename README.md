# com.cloudempiere.ops.collector

In-JVM OSGi **operational-observability collector** for iDempiere. It surfaces what container/ALB
health checks can't see — OSGi bundle and Declarative-Services **component** state, boot phases,
2Pack lifecycle, and graceful-shutdown drain — as structured stdout events, an exported status
service, unauthenticated health probes, and an M2M-authenticated REST API. A companion fragment
enriches webui OpenTelemetry spans.

> **Why:** a bundle can be OSGi `ACTIVE` while its DS components are unsatisfied — invisible to
> `docker ps`/ECS/ALB. "Healthy" means bundle ACTIVE **and** every component SATISFIED **and**, for
> the 83 extraction-work SPI seams cataloged in `docs/specs/SPEC-custom-spi-health.md`, every one
> registered — the one condition that gates `/readyz` outright.

Design: [`docs/ADR-001`](docs/ADR-001-iDempiere-Operational-Observability.md). Working notes for
contributors: [`CLAUDE.md`](CLAUDE.md).

## Modules

| Module | What | Deploys on |
|---|---|---|
| `com.cloudempiere.ops.collector` | core bundle (events, status service) + WAB health probes | all tiers |
| `com.cloudempiere.ops.rest` | `/api/v1/ops/*` REST API | rest tier |
| `com.cloudempiere.ops.trace.webui` | webui OTel span-enrichment fragment | webui tier |
| `*.feature` ×3 · `*.p2` · `*.parent` | one feature per bundle (tier-scoped), P2 repo, Tycho parent |
| `com.cloudempiere.ops.collector.test` | standalone JUnit 5, pure-logic + fakes (Mockito), no OSGi — NOT in the Tycho reactor |
| `com.cloudempiere.ops.collector.itest` | Tycho `eclipse-test-plugin`, `Fragment-Host: com.cloudempiere.ops.collector` — runs against a real, booted OSGi framework |

## Build

Tycho 4.0.8, JavaSE-17, Maven 3.9.10 (`./mvnw`). The target platform comes from the iDempiere core P2 and the
rest-fork P2; the `cloudempiere-local` profile points at sibling `file://` checkouts:

```bash
mvn clean verify -Pcloudempiere-local
```

Full build/test details (including the two out-of-reactor/fragment test modules) are in
[`CLAUDE.md`](CLAUDE.md).

## Testing

- **Fast, isolated** (fakes `BundleContext`/`EventDispatcher` with Mockito, no OSGi runtime):
  ```bash
  mvn -f com.cloudempiere.ops.collector.parent/pom.xml install -N
  mvn install -Pcloudempiere-local -DskipTests
  mvn -f com.cloudempiere.ops.collector.test/pom.xml test
  ```
- **Real OSGi, real service registry** (boots an actual Equinox framework with every seam-providing
  plugin installed; needs `iDempiereCLDE`, `org.idempiere.banking`, `com.cloudempiere.core`, and
  `com.cloudempiere.vocabulary` already built locally — see the comment above `<repositories>` in
  `com.cloudempiere.ops.collector.itest/pom.xml` for exactly which P2s and why):
  ```bash
  mvn install -Pcloudempiere-local -DskipTests
  mvn -f com.cloudempiere.ops.collector.itest/pom.xml clean verify -Pcloudempiere-local -DskipTests=false
  ```
  Use `clean verify` (not just `verify`) — a stale `target/work` p2 profile from a previous run will
  conflict with a freshly rebuilt `com.cloudempiere.ops.collector` qualifier otherwise.

## HTTP surface

- **Probes** (unauthenticated, outside `/api/v1`): `GET /ops/livez | /ops/readyz | /ops/startupz`
  — point ALB/Docker health checks at `/ops/readyz`.
  See [Readiness](#readiness-opsreadyz) for exactly what `/ops/readyz` checks.
- **Data API** (Bearer, M2M-capable): `GET /api/v1/ops/{startup,plugins,events}`, `POST /api/v1/ops/warmup`.
  System-tenant token required for `startup`/`plugins`/`warmup`; `events` is tenant-scoped. See
  [`docs/PROVISIONING-ops-monitor-m2m.md`](docs/PROVISIONING-ops-monitor-m2m.md).

## Readiness (`/ops/readyz`)

`/ops/readyz` answers `200 {"status":"ready"}` only when **all** of these hold, otherwise
`503 {"status":"unready"}` (`503 {"status":"unavailable"}` while the collector's status service is
not registered yet):

| Gate | Source | Configure |
|---|---|---|
| Boot reached ready | `BootPhaseTracker` | `OPS_COLLECTOR_BOOT_STARTUP_TIMEOUT_SECONDS` |
| Not draining (no SIGTERM) | `ShutdownHandler` | `OPS_COLLECTOR_SHUTDOWN_DRAIN_SECONDS` |
| Every required extraction-work SPI registered | `customspi/CustomSpiHealthSource` | `OPS_COLLECTOR_CUSTOMSPI_POLL_SECONDS` |
| Every required web context deployed | `WebContextReadiness` (CLD-2583) | `OPS_COLLECTOR_REQUIRED_WEB_CONTEXTS` |

The check is in-memory only: no DB query, no HTTP self-call, no session — safe for the ALB to call
every few seconds. `/ops/livez` is **not** affected by any of these gates, so an unready task is
not replaced by ECS.

### Required web contexts (webui / preview tiers)

On a webui tier, "ready" must mean the ZK web app is actually serving. Set this **environment
variable on the iDempiere container** (the ECS task definition's `environment`, or
`docker run -e` / docker-compose locally) — it is not an ALB, Terraform or AD_SysConfig setting:

```
OPS_COLLECTOR_REQUIRED_WEB_CONTEXTS=/webui
```

- Comma- and/or space-separated; `webui`, `/webui` and `/webui/` are equivalent. Several contexts
  must **all** be deployed.
- **Unset or empty = not gating** — rest and scheduler tiers leave it unset.
- Read once at bundle activation; a change needs a task restart.
- Env, not SysConfig, on purpose: readiness must be decidable before the DB is reachable.

How it works: Jetty's OSGi integration registers each started web app as an OSGi service carrying
`osgi.web.contextpath=/webui`. The collector tracks that service (class-agnostic filter, no Jetty
dependency) and counts registrations per context.

What you see on stdout:

```
Web-context readiness armed for [/webui]
web context /webui deployed (org.adempiere.ui.zk)
```

plus `ops.webcontext.deployed` / `ops.webcontext.undeployed` events (AUDIT). An "undeployed" line on
shutdown is expected. Current state: `GET /api/v1/ops/plugins` → `web_contexts`:

```json
{ "required": ["/webui"], "deployed": ["/webui"], "missing": [], "all_deployed": true }
```

**Troubleshooting — readyz stuck at 503 on webui:** if the log shows `armed for [/webui]` but never
`deployed`, Jetty is not publishing the service. Verified on Jetty 12.0.25; on Jetty master the
registration moved to `OSGiDeploymentListener` behind an `if (bundle == null)` guard that would
skip WABs such as `org.adempiere.ui.zk`. Re-verify after any Jetty upgrade. To unblock, unset the
variable (readyz falls back to the other gates) and keep the ALB on `/webui/` until fixed.

**Rollout order:** ship this plugin version → set the variable → confirm on staging that
`/ops/readyz` never returns 200 before `/webui/` does → only then point the ALB health check at
`/ops/readyz`.

## Deploy

Durable install via the clde-infra image lineup (`update-prd.sh` / `plugins.list`) — **not** a
console hot-deploy (the webui fragment is boot-gated). See
[`docs/DEPLOY-clde-infra-bake.md`](docs/DEPLOY-clde-infra-bake.md) and
[`docs/CICD-wiring.md`](docs/CICD-wiring.md).
