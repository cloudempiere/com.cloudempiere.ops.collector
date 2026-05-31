# Durable deploy — clde-infra bake note (ops collector + rest + webui trace)

> Ready-to-apply spec for a **clde-infra** session. Why a bake and not a hot-deploy: the
> 2026-05-31 console experiment (ADR-001 §11.4) proved a console `install` **cannot** activate
> these — especially the webui fragment, which only registers its ZK listener if it is resolved
> **before** ZK's boot-time `config.xml` scan. Only a `p2.director` / `update-prd.sh` install
> (writes `bundles.info` → boot resolution), made durable in the image lineup, works. Console
> hot-deploy is dev-iteration only and evaporates on every task rollover (§11.2).

## Artifacts (already published to S3)

All three features live under one P2 prefix:

```
s3://cloudempiere-p2/plugins/com.cloudempiere.ops.collector/staging/latest/      (and …/production/latest/)
  features/  com.cloudempiere.ops.collector.feature_*.jar
             com.cloudempiere.ops.rest.feature_*.jar
             com.cloudempiere.ops.trace.webui.feature_*.jar
  plugins/   com.cloudempiere.ops.collector_*.jar   (core; WAB — Web-ContextPath: ops)
             com.cloudempiere.ops.rest_*.jar         (JAX-RS /api/v1/ops/*)
             com.cloudempiere.ops.trace.webui_*.jar  (Fragment-Host: org.adempiere.ui.zk)
```

## Feature IUs and per-tier placement

| Feature group IU | Belongs on tier | Why |
|---|---|---|
| `com.cloudempiere.ops.collector.feature.feature.group` | **all** (rest, scheduler, webui) | tier-agnostic core: DS lifecycle sources + the WAB health probes (`/ops/livez\|readyz\|startupz`). Deps: only `org.adempiere.base` + `org.eclipse.osgi`. |
| `com.cloudempiere.ops.rest.feature.feature.group` | **rest** only | depends on `com.trekglobal.idempiere.rest.api` (present only on the rest tier). Adds `/api/v1/ops/*`. |
| `com.cloudempiere.ops.trace.webui.feature.feature.group` | **webui** only | `Fragment-Host: org.adempiere.ui.zk`; must be baked so it attaches **before** ZK init (§11.4). |

> Note for the resolver: all three IUs resolve from the **same** `com.cloudempiere.ops.collector`
> S3 prefix. Map each to the prefix per `plugins.list`/`versions.json`'s actual multi-IU format
> (one prefix → N feature IUs). Keep `ops.trace.webui` off the rest/scheduler tiers (no ZK there
> ⇒ unresolved host) and `ops.rest` off webui/scheduler (no `rest.api` there).

## Path A — bake into the image lineup (recommended; durable)

Add to `clde-infra/images/idempiere-clde/plugins.list` (or the per-tier equivalent), resolved
from `s3://cloudempiere-p2/plugins/com.cloudempiere.ops.collector/<env>/latest/`:

```
# all tiers
com.cloudempiere.ops.collector   com.cloudempiere.ops.collector.feature.feature.group
# rest tier only
com.cloudempiere.ops.collector   com.cloudempiere.ops.rest.feature.feature.group
# webui tier only  (MUST be baked — §11.4 boot-resolution requirement)
com.cloudempiere.ops.collector   com.cloudempiere.ops.trace.webui.feature.feature.group
```

PR with the `images/idempiere-clde/**` path filter triggers the image rebuild; the features then
install via the canonical `update-prd.sh` (single director call, SAT-correct, `bundles.info`
rewrite) at image build, present at every framework start.

## Path B — runtime pin (staging, no rebuild)

Add the same IUs to `s3://cloudempiere-p2/versions/staging.json` and ensure the staging task-def
sets `CE_PLUGINS_AT_RUNTIME=true` (it currently does **not** on `idempiere-clde-rest-stg` — that
flag is a clde-infra task-def change). `resolve-plugins-runtime.sh` → `update-prd.sh` then installs
at task start. Same boot-resolution guarantee as Path A, ephemeral per task lifecycle.

## Paired infra change (already staged, not applied)

`modules/idempiere-clde-tier/locals.tf` — the ADOT `awsxray` exporter `indexed_attributes` now lists
the 12 webui enrichment keys (promotes them metadata → queryable annotations). Apply **with** the
webui bundle bake (one rollover), not before (nothing to index until the enricher loads).

## Dev-only (Path C) image helper — PR #189

`clde-infra/images/idempiere-clde/Dockerfile`: add `expect telnet` to the runtime-stage `dnf`
block. Needed only for console hot-iteration (the Equinox telnet console on `:12612`); Paths A/B
need nothing extra. Without it, Path C requires a runtime `dnf install expect telnet` (lost on
rollover).

## Verification after bake

- **all tiers:** `curl <task>/ops/readyz` → 200/503 (WAB probe).
- **rest tier:** `curl -H "Authorization: Bearer <System-tenant M2M>" <task>/api/v1/ops/plugins` → 200;
  no token → 401; tenant-scoped M2M → 403 on plugins/startup/warmup (policy B). See
  [`PROVISIONING-ops-monitor-m2m.md`](PROVISIONING-ops-monitor-m2m.md).
- **webui tier:** boot log shows the `OtelSpan` "OpenTelemetry API present — webui span enrichment
  active" marker; `ss com.cloudempiere.ops.trace.webui` → `RESOLVED, Master=<zk-host>`; then an
  authenticated window-open `/zkau` span in X-Ray must carry **both** `enduser.id` **and**
  `idempiere.window_id` (the §11.4 acceptance test for the still-unverified event-thread risk).
