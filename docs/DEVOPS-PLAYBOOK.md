# DevOps Playbook — ship & validate `com.cloudempiere.ops.collector` on staging

> **What tool, what to do, when.** This is the operational runbook for getting a plugin change
> from a merged PR onto the running staging iDempiere and *proving* it works. It captures the
> pipeline, the order, and the hard-won gotchas (CLD-1912 deploy session, 2026-05-31).
>
> Workspace-wide CI/CD wiring lives in `docs/CICD-wiring.md`; per-tier feature IUs in
> `docs/DEPLOY-clde-infra-bake.md`. This doc is the **operator's decision table**.

---

## 0. The pipeline at a glance

```
   merge to master (GitHub)
        │
        ▼
 ① plugin CI  ─cascade(ADR-116)→  ② image bake  ──→  ③ tier deploy  ──→  ④ validate
 (build+publish P2)               (P2→image→ECR)      (roll one tier)     (curl/OSGi)
```

| # | Tool (Jenkins job / CLI) | What it does | When you run it |
|---|---|---|---|
| ① | `cloudempiere/plugins/com.cloudempiere.ops.collector/master` | `mvn verify` → publishes P2 to `s3://cloudempiere-p2/plugins/com.cloudempiere.ops.collector/staging/latest/` | after merge to `master`; or to refresh the S3 P2 |
| ② | `cloudempiere/infra/idempiere-clde-image` | installs plugins **from S3 P2** into the image → Trivy → ECR push (tag `YYYYMMDD-HHMMSS`) | after ① publishes; auto-fired by ①'s cascade |
| ③ | `cloudempiere/infra/idempiere-clde-deploy` | rolls **one tier** to an image tag (ECS task-def revision + service update) | to put a baked image on a tier |
| ③′ | `cloudempiere/infra/idempiere-clde-deploy-all` | orchestrates all tiers in order (staging `webui→rest→scheduler`) | full-env roll |
| ④ | `aws` CLI + `curl` | ECS/ALB state, `bundles.info`, endpoint probes | always — never trust "deployed" without it |

**Cascade note (ADR-116):** ① runs with `TRIGGERED_BY_CASCADE=false`, so on success it **auto-fires ②**.
If you only want a build, that's fine; if you want a *controlled* bake, trigger ② yourself and watch.

---

## 1. Decision table — "I want to…"

| Goal | Tool & action | Notes |
|---|---|---|
| Ship a merged plugin change to staging | ① → (cascade) ② → ③ `SERVICE=<tier>` | confirm the artifact actually has your change (see Gotcha A) |
| Bake an image without deploying | ② with `TRIGGER_AUTO_DEPLOY_STAGING=false` | image sits in ECR; deploy later with ③ |
| Roll one tier to a specific image | ③ `IMAGE_TAG_OVERRIDE=<tag>` `SERVICE=rest\|webui\|scheduler` | circuit breaker + rollback are ON by default |
| Roll back a bad deploy | ③ `IMAGE_TAG_OVERRIDE=<previous-good-tag>` | or just let the circuit breaker do it (see Gotcha D) |
| Get a fix on a tier when CI built the wrong source | **seed S3 → ② → ③** (see §2) | the escape hatch when ① doesn't reflect master |
| Prove a REST resource registered | `curl …/api/v1/ops/startup` → **401** not **404** | 404 = not registered; 401 = registered + auth (Policy B) |
| Check "ACTIVE but broken" DS state | ECS Exec → `bundles.info` / OSGi `scr:list` | bundle ACTIVE ≠ component SATISFIED |

---

## 2. The escape hatch — seed S3 P2 by hand (when CI builds the wrong source)

The plugin multibranch job builds from a **shared source tree** (`/var/jenkins_home/ce-shared-src`,
branch `develop`), **not** the plugin repo's freshly-merged `master`. So a green CI build can publish
a P2 whose jar **does not contain your merged change** (Gotcha A). When that happens, bypass CI:

```bash
# 1. Build locally on master HEAD (qualifier must out-rank the CI jar so director picks yours)
mvn clean verify -Pcloudempiere-local -DskipTests

# 2. Overwrite the staging P2 with your local build (--delete = exact mirror, no stale jars)
aws s3 sync com.cloudempiere.ops.collector.p2/target/repository/ \
    s3://cloudempiere-p2/plugins/com.cloudempiere.ops.collector/staging/latest/ \
    --region eu-west-1 --delete

# 3. Bake from the seeded P2 (no auto-deploy), then deploy that tag with ③
#    Jenkins: idempiere-clde-image  (TRIGGER_AUTO_DEPLOY_STAGING=false)
#    Jenkins: idempiere-clde-deploy (SERVICE=rest, IMAGE_TAG_OVERRIDE=<new tag>)
```

> Verify the jar carries your change **before** baking:
> `unzip -p …/com.cloudempiere.ops.rest_*.jar META-INF/MANIFEST.MF | grep ActivationPolicy`

---

## 3. Validation — never trust "deployed"

```bash
cl=ecs-cluster-idempiere-clde-stg; r=eu-west-1; svc=idempiere-clde-rest-stg

# Which task-def/image is actually PRIMARY + did it stabilize?
aws ecs describe-services --cluster $cl --services $svc --region $r \
  --query 'services[0].{td:taskDefinition,running:runningCount,rollout:deployments[0].rolloutState}'

# Is the new task healthy at the ALB? (unhealthy ⇒ it'll be rolled back)
aws elbv2 describe-target-health --target-group-arn <rest-tg-arn> --region $r \
  --query 'TargetHealthDescriptions[*].{ip:Target.Id,state:TargetHealth.State}'

# What's installed + started? (the last field is markedStarted — see Gotcha C)
aws ecs execute-command --cluster $cl --task <id> --container idempiere-clde --region $r --interactive \
  --command "bash -lc 'grep cloudempiere.ops /opt/idempiere/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info'"

# The endpoints (REST tier host is api.stg, NOT app.stg)
curl -s -o /dev/null -w '%{http_code}\n' https://api.stg.cloudempiere.com/api/v1/health        # 200
curl -s -o /dev/null -w '%{http_code}\n' https://api.stg.cloudempiere.com/api/v1/ops/startup    # 401=registered, 404=not
```

Hosts: **`api.stg.cloudempiere.com`** → rest tier (`/api/v1/*`); **`app.stg.cloudempiere.com`** →
webui tier (`/zkau`, `/webui`). CloudFront fronts both; `app.stg` does **not** route `/api/v1/*`.

---

## 4. Gotchas discovered (CLD-1912, 2026-05-31)

**A. CI builds `ce-shared-src@develop`, not your plugin's `master` HEAD.**
A green ① build can publish a jar lacking your merged change. *Always* verify the built/baked jar
contains your change (manifest header, class, etc.). Use §2 to bypass when needed.

**B. `EventDispatcher` logs every event at `Level.SEVERE` → `MIssue.create` → Hikari connection leak.**
iDempiere's `CLogErrorBuffer` persists `SEVERE` log records to `AD_Issue` (DB writes). The collector
logs *every* event at `SEVERE` regardless of its `Severity` field (`EventDispatcher.emit`, the stdout
floor). A burst of events (e.g. boot-time) ⇒ a burst of `MIssue.create` ⇒ connection-pool pressure ⇒
the app degrades after registering ⇒ ECS replaces it ⇒ deploy can't stabilize. **Fix direction:** log
at a level matching severity (PANIC→SEVERE, ALERT→WARNING, AUDIT→INFO) so AUDIT/ALERT events don't
spawn `MIssue`; and/or an env gate to disable the emit engine. This is the "engine makes the system
unpredictable" mechanism.

**C. `,4,false` in `bundles.info` is NORMAL — it does not mean "dormant".**
The working `com.cloudempiere.rest.cache` is also `…,4,false`. The differentiator for a plain
(non-WAB, non-fragment) DS bundle registering its `immediate` component is **`Bundle-ActivationPolicy:
lazy`** (cache has it; `ops.rest` lacked it). Don't diagnose from the markedStarted flag — verify the
actual OSGi component state (`scr:list`) and the endpoint.

**D. The deploy circuit breaker auto-rolls-back; the health grace is 900s.**
`idempiere-clde-deploy` has `deploymentCircuitBreaker.rollback=true`. If the new task never stabilizes
(boot too slow, or degrades), it reverts to the previous good task-def — *and the endpoint reverts with
it*. A "green" tier after a deploy attempt may mean **rolled back to the old image**, not "your fix is
live". Always check the PRIMARY task-def revision + image tag.

**E. Boot-stall is intermittent, not deterministic.**
The same image booted healthy on one task and stalled on another. Slow boot is dominated by the full
plugin set (and amplified by B). The collector's `@Activate` work is cheap/passive; `WarmupRunner` does
nothing at boot (HTTP-triggered only).

**F. REST `ResourceExtension` is registered purely by OSGi DS — no DB config.**
`ApplicationV1.getClasses()` enumerates `ResourceExtension` services once (first `/api/v1` request). No
`REST_Resource`/AD rows are needed to make `/api/v1/ops/*` exist; they only govern *access* (open when
no matching row). A 404 from `ApplicationV1` = the extension never registered (deployment/activation),
not missing config.

---

## 5. Severity classification principle (non-defensive)

Start non-defensive; escalate with **evidence**, not worst-case assumption. `plugin.component.unsatisfied`
is AUDIT (not PANIC) because it fires transiently at boot and permanently for tier-irrelevant plugins
(orderstatus/wcs/dms model factories on the rest tier). Re-escalate to PANIC only with: boot phase
`COMPLETE` + grace period + a tier-relevance allowlist. Genuinely-catastrophic types stay PANIC
(`failed_activation`, `resolution.failed`, `descriptor.missing`, `framework.error`, `jvm.oom`,
`boot.stalled`, `crash_loop_detected`).

---

## 6. Runtime knobs (env vars)

| Var | Effect |
|---|---|
| `OPS_COLLECTOR_EVENT_BUFFER_SIZE` | ring buffer size backing `GET /events` |
| `OPS_COLLECTOR_SCR_POLL_SECONDS` | DS component poll interval |
| `OPS_COLLECTOR_SHUTDOWN_DRAIN_SECONDS` | SIGTERM drain window |
| `OPS_COLLECTOR_BOOT_STARTUP_TIMEOUT_SECONDS` | boot-stall threshold |
| `OPS_COLLECTOR_WARMUP_TABLES` / `OPS_COLLECTOR_WARMUP_HTTP_ENABLED` | warmup allowlist / gate the mutating `POST /warmup` |

> **TODO (per Gotcha B):** add per-area enable/disable gates (`OPS_COLLECTOR_<AREA>_ENABLED`) so an
> operator can disable the emit engine or a single source via a task-def env var without a rebuild.
