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
- **Data API** (Bearer, M2M-capable): `GET /api/v1/ops/{startup,plugins,events}`, `POST /api/v1/ops/warmup`.
  System-tenant token required for `startup`/`plugins`/`warmup`; `events` is tenant-scoped. See
  [`docs/PROVISIONING-ops-monitor-m2m.md`](docs/PROVISIONING-ops-monitor-m2m.md).

## Deploy

Durable install via the clde-infra image lineup (`update-prd.sh` / `plugins.list`) — **not** a
console hot-deploy (the webui fragment is boot-gated). See
[`docs/DEPLOY-clde-infra-bake.md`](docs/DEPLOY-clde-infra-bake.md) and
[`docs/CICD-wiring.md`](docs/CICD-wiring.md).
