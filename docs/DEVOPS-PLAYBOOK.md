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

| # | Tool | What it does | When you run it |
|---|---|---|---|
| ① | **AWS CodeBuild `cloudempiere-plugin-build`** (the REAL plugin CI) | builds the plugin repo's **`master` HEAD** → publishes versioned P2 to `s3://…/plugins/<plugin>/staging/<ts>/` (+ `latest/`) + `build.json` (`git_sha`) → **bumps the BoDM ledger** `s3://cloudempiere-p2/versions/staging.json` `ts` | on merge to `master` (auto) |
| ② | `cloudempiere/infra/idempiere-clde-image` | installs each plugin by resolving the **BoDM `ts`** → `staging/<ts>/` → Trivy → ECR push (tag `YYYYMMDD-HHMMSS`) | **after** ① bumped the BoDM `ts` (timing matters — see Gotcha A) |
| ③ | `cloudempiere/infra/idempiere-clde-deploy` | rolls **one tier** to an image tag (ECS task-def revision + service update) | to put a baked image on a tier |
| ③′ | `cloudempiere/infra/idempiere-clde-deploy-all` | orchestrates all tiers in order (staging `webui→rest→scheduler`) | full-env roll |
| ④ | `aws` CLI + `curl` | ECS/ALB state, `bundles.info`, endpoint probes | always — never trust "deployed" without it |

> ⚠️ **NOT the CI:** the Jenkins job `cloudempiere/plugins/com.cloudempiere.ops.collector/master`
> builds a shared tree `ce-shared-src@develop` (not `master`) and does **not** feed the BoDM.
> It is a red herring — triggering it does nothing for delivery.

**To ship a merged change:** confirm CodeBuild published (`versions/staging.json` `ts` advanced **and**
`staging/<ts>/build.json` `git_sha` == your `master` HEAD) → **then** bake ② → deploy ③. No local
seed/override needed when CodeBuild + BoDM are healthy.

---

## 1. Decision table — "I want to…"

| Goal | Tool & action | Notes |
|---|---|---|
| Ship a merged plugin change to staging | ① → (cascade) ② → ③ `SERVICE=<tier>` | confirm the artifact actually has your change (see Gotcha A) |
| Bake an image without deploying | ② with `TRIGGER_AUTO_DEPLOY_STAGING=false` | image sits in ECR; deploy later with ③ |
| Roll one tier to a specific image | ③ `IMAGE_TAG_OVERRIDE=<tag>` `SERVICE=rest\|webui\|scheduler` | circuit breaker + rollback are ON by default |
| Roll back a bad deploy | ③ `IMAGE_TAG_OVERRIDE=<previous-good-tag>` | or just let the circuit breaker do it (see Gotcha D) |
| Force a specific build onto a tier | publish versioned `staging/<ts>/` + bump `versions/staging.json` `ts` → ② → ③ (see §2) | seeding `latest/` alone does NOT work — bake reads the BoDM `ts` |
| Prove a REST resource registered | `curl …/api/v1/ops/startup` → **401** not **404** | 404 = not registered; 401 = registered + auth (Policy B) |
| Check "ACTIVE but broken" DS state | ECS Exec → `bundles.info` / OSGi `scr:list` | bundle ACTIVE ≠ component SATISFIED |

---

## 2. Forcing a specific build (rarely needed — CodeBuild + BoDM normally handle it)

**First check whether you even need this.** If CodeBuild already published your `master`, the BoDM
already points at the right build — just bake ② + deploy ③:

```bash
# Does the BoDM already point at your fix? (ts + the build's git_sha)
aws s3 cp s3://cloudempiere-p2/versions/staging.json - --region eu-west-1 | python3 -m json.tool | grep -A2 ops.collector
TS=<the ts from above>
aws s3 cp s3://cloudempiere-p2/plugins/com.cloudempiere.ops.collector/staging/$TS/build.json - --region eu-west-1
#   git_sha == your master HEAD?  → just bake ② + deploy ③. Done.
```

**Only if CodeBuild has NOT produced your build** (and you can't wait for it): publish a versioned
build by hand and bump the ledger. **Seeding `staging/latest/` alone does nothing** — the bake resolves
the BoDM `ts` directory, not `latest/`, and a CodeBuild publish will overwrite `latest/` anyway.

```bash
TS=$(date -u +%Y%m%d.%H%M%S)          # new versioned ts
aws s3 sync com.cloudempiere.ops.collector.p2/target/repository/ \
    s3://cloudempiere-p2/plugins/com.cloudempiere.ops.collector/staging/$TS/ --region eu-west-1
# then set versions/staging.json -> "com.cloudempiere.ops.collector": { "ts": "$TS", "install_iu": … }
#   (this mutates the BoDM ledger — get owner sign-off), then bake ② + deploy ③.
```

> Verify the BoDM-pinned jar carries your change **before** baking:
> `aws s3 cp s3://…/staging/<ts>/plugins/com.cloudempiere.ops.rest_*.jar - | unzip -p /dev/stdin META-INF/MANIFEST.MF | grep ActivationPolicy`

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

## 4. Gotchas discovered (CLD-1912, 2026-05-31 → 06-01)

**A. Two build systems — use the right one; mind bake timing.**
The **real** plugin CI is **AWS CodeBuild `cloudempiere-plugin-build`** — it builds `master` HEAD,
publishes `staging/<ts>/`, and bumps the BoDM. The Jenkins job `…/plugins/…/master` builds
`ce-shared-src@develop` and is a **red herring** (does not feed the BoDM). A bake reflects the BoDM
`ts` *at bake time*, so after a merge you must bake **after** CodeBuild bumps the `ts` — a bake started
before the bump ships the **previous** build (exactly how a stale `0913` shipped while `master` was
already correct as `1143`). Verify `staging/<ts>/build.json` `git_sha` == your `master` HEAD.

**G. The image bake resolves the BoDM `ts` dir, NOT `staging/latest/`.**
`idempiere-clde-image` reads `versions/staging.json` → installs `staging/<ts>/`. Hand-seeding
`staging/latest/` does nothing (and CodeBuild overwrites it). To force a build you must publish a
versioned `staging/<ts>/` **and** bump the ledger `ts` (§2).

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
