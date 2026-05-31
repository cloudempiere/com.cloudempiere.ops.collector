# CI/CD wiring — register `com.cloudempiere.ops.collector` into the delivery pipeline

> Follow-up on **CLD-1912**. Executable from the `iDempiereCLDE` + `clde-infra` checkouts (not
> from this plugin repo alone). Companion to [`DEPLOY-clde-infra-bake.md`](DEPLOY-clde-infra-bake.md)
> (the lineup/feature-IU details) — this doc is the *pipeline* side.

## Why this is needed (the session lesson)

The `llm-osgi-fastpath` skill is a **dev inner-loop** tool, not delivery. It validated the *code*,
but durable + boot-gated deploys need the real pipeline:
- the **webui fragment is boot-gated** (ZK `config.xml` scanned at framework start) → it only
  activates when **baked into the lineup** (ADR-001 §11.4); no console/hot path can do it;
- anything not baked **evaporates on task rollover** (§11.2) — durability *is* the pipeline.

Most of the pipeline already exists (Jenkins multibranch, S3 P2, clde-infra image bake via
`plugins.list`, `resolve-plugins-runtime.sh`/`update-prd.sh`, the AWX fleet). **The only gap is that
this plugin isn't wired in.** Three wiring steps:

## 1. Build + publish — Jenkins multibranch job (this repo)

New multibranch pipeline for `github.com/cloudempiere/com.cloudempiere.ops.collector`, path-triggered
on the repo. Branch → env (workspace convention): `develop-cloudempiere` → **staging** P2,
`master` → **production** P2.

```groovy
// Jenkinsfile (sketch — align with the shared library other plugin repos use)
pipeline {
  agent { label 'maven-jdk11' }
  environment { ENV = env.BRANCH_NAME == 'master' ? 'production' : 'staging' }
  stages {
    stage('build') { steps { sh 'mvn -B clean verify' } }   // S3 core+rest P2 defaults resolve the target platform
    stage('publish P2') {
      steps {
        sh '''
          DEST=s3://cloudempiere-p2/plugins/com.cloudempiere.ops.collector/${ENV}
          TS=$(date -u +%Y%m%d.%H%M%S)
          aws s3 sync com.cloudempiere.ops.collector.p2/target/repository/ $DEST/$TS/ --delete
          aws s3 sync com.cloudempiere.ops.collector.p2/target/repository/ $DEST/latest/ --delete
        '''
      }
    }
    stage('bump catalog') { steps { sh './ci/bump-catalog.sh ${ENV} ${TS}' } } // see step 2
  }
}
```

Mirror whatever the other CLDE plugin repos already do (most have this exact shape) — don't invent a
new convention.

## 2. Catalog registration — `iDempiereCLDE/ci/plugins.json`

`install-plugins.sh` states the authoritative catalog is `iDempiereCLDE/ci/plugins.json`; the image
buildspec `jq`s it into `PLUGINS_RESOLVED` (`<name> <feature-iu> <ts|latest>`), and the runtime
resolver reads `versions/<env>.json`. Add `com.cloudempiere.ops.collector` with its **three** feature
IUs, tier-scoped (see the bake note for the table):

| feature IU | tiers |
|---|---|
| `com.cloudempiere.ops.collector.feature.feature.group` | all |
| `com.cloudempiere.ops.rest.feature.feature.group` | rest |
| `com.cloudempiere.ops.trace.webui.feature.feature.group` | webui |

> **Confirm in the `iDempiereCLDE` checkout:** the exact `ci/plugins.json` schema and how it expresses
> (a) multiple feature IUs under one plugin prefix and (b) per-tier scoping (the catalog or the per-tier
> `plugins.list` must keep `ops.rest` off webui/scheduler and `ops.trace.webui` off rest/scheduler).

## 3. Lineup bake + runtime pin — `clde-infra`

Per [`DEPLOY-clde-infra-bake.md`](DEPLOY-clde-infra-bake.md):
- **Path A (durable):** add the per-tier feature IUs to `clde-infra/.../plugins.list`; the
  `images/idempiere-clde/**` path filter triggers the image rebuild.
- **Path B (staging, no rebuild):** add to `s3://cloudempiere-p2/versions/staging.json` **and** set
  `CE_PLUGINS_AT_RUNTIME=true` on the staging task-defs (currently **unset** on `rest-stg`/`webui-stg`
  — a clde-infra task-def change).
- Apply the staged `awsxray indexed_attributes` (`modules/idempiere-clde-tier/locals.tf`) **with** the
  webui bake (one rollover).

## End-to-end flow once wired

```
merge → Jenkins (this repo): mvn verify → publish P2 (ts + latest) → bump ci/plugins.json
      → clde-infra image rebuild (plugins.list path trigger) bakes the 3 features per tier
      → next task start: update-prd.sh installs at boot (bundles.info), SAT-correct
      → WAB probes (/ops/readyz), /api/v1/ops M2M API, and the webui enricher all come up
        at framework start — durably, surviving every rollover.
```

## Open confirmations (need the other checkouts)

- exact `ci/plugins.json` schema + per-tier field;
- whether each tier has its own `plugins.list` or one list with tier predicates;
- the shared-library Jenkinsfile conventions used by sibling plugin repos;
- set `CE_PLUGINS_AT_RUNTIME` if Path B on staging is wanted before a full bake.
