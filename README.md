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
| `*.feature` ×3 · `*.p2` · `*.parent` · `*.test` | one feature per bundle (tier-scoped), P2 repo, Tycho parent, standalone JUnit 5 |

## Build

Tycho 2.7.5, JavaSE-11, Maven 3.9+. The target platform comes from the iDempiere core P2 and the
rest-fork P2; the `cloudempiere-local` profile points at sibling `file://` checkouts:

```bash
mvn clean verify -Pcloudempiere-local
```

Full build/test details (including the out-of-reactor test module) are in [`CLAUDE.md`](CLAUDE.md).

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
