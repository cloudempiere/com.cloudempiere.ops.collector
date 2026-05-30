---
name: llm-osgi-fastpath
description: >
  LLM dev→OSGi fastpath: take a built CloudEmpiere iDempiere plugin and get it running on a
  staging iDempiere ECS task without full CI/CD. Chains after the idempiere-osgi-p2 skill
  (which builds the bundle/feature/P2). Uses the canonical clde-infra installer (update-prd.sh
  / resolve-plugins-runtime.sh) for durable installs, and the OSGi console only for ephemeral
  live iteration. Use when asked to "deploy the plugin to staging", "test the plugin live",
  "hot-deploy", "install via director", "fastpath deploy", or to iterate build→deploy→test.
---

# LLM dev → OSGi fastpath

Build a plugin (via `idempiere-osgi-p2`) → publish its P2 to S3 → install it onto a staging
iDempiere ECS task → verify → iterate. Three paths, by need:

| Path | Mechanism | Restart? | Correct for | When |
|---|---|---|---|---|
| **A — Durable** | one line in `clde-infra` `plugins.list` → image rebuild bakes it in | new image/task | a real release | production-bound |
| **B — Runtime** | `versions/<env>.json` + `CE_PLUGINS_AT_RUNTIME=true` → `resolve-plugins-runtime.sh` runs `update-prd.sh` at task start | task (re)start | staging without rebuild; **any** plugin (SAT-correct) | most staging tests |
| **C — Hot iterate** | OSGi console `install`+`start` (script below) | **none** | **only** plugins whose deps are already in the running framework | tight build→test loops |

**Default to B.** It uses the canonical installer and is SAT-correct for cross-plugin
deps. Use C only for the rapid loop and only for low-dependency plugins (see its caveat).
Use A when it's going to production.

## The canonical machinery (clde-infra/images/idempiere-clde)

Don't reinvent this — it's in the image:

- **`update-prd.sh <repos-csv> <ius-csv>`** — iDempiere's installer. Runs uninstall then
  install passes in **one director call across all repos**. The single call is mandatory:
  cross-plugin `Require-Bundle` deps only SAT-resolve when director sees every repo. The
  uninstall/install pair is what forces the **`bundles.info` rewrite** — a plain
  `director.sh -installIU` lands the jar but OSGi `ss` never sees it.
- **`scripts/install-plugins.sh`** — wrapper. Reads `PLUGINS_RESOLVED` (lines:
  `<name> <feature-iu> <ts|latest>`), composes the one `update-prd.sh` call, sanity-checks
  `bundles.info`. Env: `IDEMPIERE_HOME`, `CE_ENV`, `CE_P2_BUCKET`, `PLUGINS_RESOLVED`.
- **`scripts/resolve-plugins-runtime.sh`** — runs at task start when
  `CE_PLUGINS_AT_RUNTIME=true`. Pulls `s3://<bucket>/versions/<env>.json` + the iDempiereCLDE
  catalog, jq-filters to `plugins-resolved.txt`, dispatches `install-plugins.sh`. Idempotent.
- **`plugins.list`** — authoritative bake-time composition (`<name> <feature-iu>`), resolved
  from `s3://cloudempiere-p2/plugins/<name>/staging/latest/`.

Image facts: AL2023 + Corretto 11; `awscli`/`bash`/`jq`/`procps` present; **no**
`nc`/`telnet`/`expect`/`socat`. iDempiere home `/opt/idempiere`; console `-Dosgi.console=0.0.0.0:12612`.

## Safety gates (non-negotiable)

1. **Never production.** Live iDempiere tasks are usually the `*-pr` cluster. Refuse `*-pr`
   targets unless the user explicitly overrides. Test tier: `ecs-cluster-idempiere-clde-stg`.
2. **Scaling `0→1` is a mutation** — confirm first; **scale back to 0** when done (staging is
   often at zero off-hours).
3. **Stop and report** on repeated tool failure; don't loop a failing ECS Exec.
4. Paths B/C on Fargate are **ephemeral** (lost on task replacement) — say so. Only A persists.

## Prerequisites — verify FIRST (hard gate)

Run these before anything; do not proceed past a failure — fix the prerequisite instead.

```bash
# 1. AWS CLI + valid creds, correct account + region
aws sts get-caller-identity --query Account --output text     # expect 806902423583
aws configure get region || echo "${AWS_REGION:-UNSET}"       # expect eu-west-1 (else pass --region)

# 2. session-manager-plugin (ECS Exec transport — REQUIRED for paths B-manual/C)
session-manager-plugin --version                              # must print a version
#   missing? macOS: brew install --cask session-manager-plugin

# 3. S3 P2 repo access — READ (core + this plugin) and WRITE (publish target)
aws s3 ls s3://cloudempiere-p2/plugins/iDempiereCLDE/staging/latest/ --region eu-west-1 >/dev/null && echo "S3 read OK"
aws s3 ls s3://cloudempiere-p2/plugins/<plugin>/staging/latest/      --region eu-west-1   # publish prefix
#   NOTE: P2 over plain HTTPS returns 403 (private bucket) — that's expected; the CLI signs with creds.

# 4. Target service: ECS Exec enabled AND not production
aws ecs describe-services --cluster ecs-cluster-idempiere-clde-stg \
  --services idempiere-clde-rest-stg --region eu-west-1 \
  --query 'services[0].{exec:enableExecuteCommand,desired:desiredCount,running:runningCount}'
#   require exec=true; refuse any service whose name ends -pr (production)

# 5. Build toolchain (Step 0 / chains idempiere-osgi-p2)
java -version 2>&1 | grep -q '11\.'   && echo "Java 11 OK"      # JavaSE-11
mvn -version  2>&1 | grep -q 'Apache' && echo "Maven OK"        # 3.9+
```

IAM the **caller** needs: `ecs:DescribeServices`, `ecs:ListTasks`, `ecs:ExecuteCommand`
(+ `ecs:UpdateService` if scaling `0→1`), and `s3:ListBucket`/`s3:GetObject`/`s3:PutObject`
on `cloudempiere-p2`. The **task role** (in-container pull, paths B/C) needs `s3:GetObject`
on `cloudempiere-p2`. ECS Exec requires the task launched with `enableExecuteCommand=true`
(already set on `idempiere-clde-*` services).

## Step 0 — build + publish (chains after idempiere-osgi-p2)
```bash
mvn clean verify -Pcloudempiere-local                       # produces *.p2/target/repository/
aws s3 sync <plugin>.p2/target/repository/ \
    s3://cloudempiere-p2/plugins/<plugin>/staging/latest/ --region eu-west-1
```
P2 envs: `staging/latest/` + `production/latest/` only (no `develop/`; dev uses staging).

## Path A — Durable (real release)
Add to `clde-infra/images/idempiere-clde/plugins.list`:
```
<plugin-name>    <feature-iu>     # e.g. com.cloudempiere.ops.collector  com.cloudempiere.ops.collector.feature.feature.group
```
Open a `clde-infra` PR (path filter on `images/idempiere-clde/**` triggers the image rebuild).
The plugin then bakes in alongside the other externals. This is the only persistent path.

## Path B — Runtime install on staging (recommended for tests)
Add the plugin's pin to `s3://cloudempiere-p2/versions/staging.json` (+ ensure it's in the
catalog), then start a staging task with `CE_PLUGINS_AT_RUNTIME=true` — the entrypoint runs
`resolve-plugins-runtime.sh` → `update-prd.sh`, SAT-correct across all repos, before iDempiere
opens. Verify with `ss`/`scr:list` via the console and `aws logs tail`.

> If editing `versions/staging.json` / the task-def flag is out of scope for the session, you
> can reproduce B manually inside a running task via ECS Exec by invoking
> `/opt/idempiere/scripts/install-plugins.sh` with a hand-built `PLUGINS_RESOLVED` — but that
> still needs an iDempiere restart to load, so for a no-restart loop use Path C.

## Path C — Hot iterate (no restart) — low-dependency plugins only
> **Caveat:** the console `install`+`start` does **not** SAT-resolve across external P2 repos.
> It is safe ONLY when every `Require-Bundle` of your plugin is already resolved in the running
> framework (e.g. `com.cloudempiere.ops.collector` depends only on `org.adempiere.base` +
> `org.eclipse.osgi`). For a plugin with external deps, use Path B (`update-prd.sh`).

1. Discover + env-guard:
   ```bash
   cl=ecs-cluster-idempiere-clde-stg; r=eu-west-1; svc=idempiere-clde-rest-stg
   aws ecs describe-services --cluster $cl --services $svc --region $r \
     --query 'services[0].{running:runningCount,desired:desiredCount,exec:enableExecuteCommand}'
   ```
   Refuse if `svc` ends `-pr`. Confirm `exec=true`.
2. Ensure a task (confirm before scaling): `update-service --desired-count 1`,
   `wait services-stable` (background), then grab the running task id.
3. Hot-deploy via ECS Exec (container `idempiere-clde`), shipping the driver base64 to dodge
   SSM quoting:
   ```bash
   B64=$(base64 < scripts/clde-osgi-deploy.sh | tr -d '\n')
   aws ecs execute-command --cluster $cl --task $task --container idempiere-clde --region $r \
     --interactive --command "bash -c 'echo $B64 | base64 -d > /tmp/d.sh && bash /tmp/d.sh \
       s3://cloudempiere-p2/plugins/<plugin>/staging/latest <plugin-symbolic-name> 5'"
   ```
4. Verify: script prints `ss <SN>` (ACTIVE) + `scr:list <id>` (DS SATISFIED — the
   "ACTIVE but broken" check). Logs: `aws logs tail /ecs/idempiere-clde-rest-stg --follow`.
5. Iterate: rebuild → re-sync to S3 → re-run step 3 (in-place `update`). **Scale back to 0** when done.

## Interpreting OSGi + DS state — the verify step (per ADR-001 §1/§4.7/§5)

`ss <SN>` shows the **bundle** state; `scr:list <id>` / `scr:info <name>` show **DS component**
state. You must check BOTH — see the core rule below.

Bundle states (`ss`):
- `INSTALLED` — present but **unresolved** (missing Require-Bundle/Import-Package). PANIC; never runs.
- `RESOLVED` — wired but **not started** → `start <id>`.
- `ACTIVE` — started (necessary, **not** sufficient).
- `<<LAZY>>` / `STARTING` — lazy activation pending.

DS component states (ServiceComponentRuntime):
- `SATISFIED` / `ACTIVE`(registered) — healthy.
- `UNSATISFIED_REFERENCE` — a mandatory `@Reference` is missing → component never runs.
- `UNSATISFIED_CONFIGURATION` — required config absent.
- `FAILED_ACTIVATION` — `@Activate` threw. PANIC.

**ADR-001 core check ("ACTIVE is a lie"):** a bundle can be `ACTIVE` while its DS components
are `UNSATISFIED_REFERENCE`/`FAILED_ACTIVATION`. So "healthy" = **bundle ACTIVE AND every DS
component SATISFIED/ACTIVE** — never bundle-state alone. For `ops.collector` expect: bundle
`ACTIVE`; `EventDispatcher`/`ColdStartScanner`/`BundleLifecycleSource` SATISFIED; and its log
lines present (`stdout floor armed`, `Cold-start scan:`, `listeners armed`). The log lines are
the **most reliable verification** — they don't depend on parsing the telnet console.
Severity (ADR-001 §5): INSTALLED/FAILED_ACTIVATION → PANIC; tenant-scoped → ALERT; stop/start → AUDIT.

## Assets
- `scripts/clde-osgi-deploy.sh` — Path C console driver, `expect`+`telnet` (chuboe-style).
  **Status (2026-05-30): VALIDATED end-to-end** on `idempiere-clde-rest-stg` — installed
  `com.cloudempiere.ops.collector` as bundle 468, `ACTIVE`, all 3 DS components
  `State:ACTIVE`. **Requires `expect`+`telnet` in the image** — the Equinox console on `:12612`
  is a telnet server (IAC negotiation); raw sockets (bash `/dev/tcp`, Python) do NOT work.
  Bake via the runtime-stage `dnf install` (see below); a runtime `dnf install expect telnet`
  works for a one-off but is lost on task replacement.
- `scripts/preflight.sh` — prerequisite gate (verified working).

### Image requirement for Path C (clde-infra/images/idempiere-clde/Dockerfile)
Add `expect telnet` to the runtime-stage `dnf install` block (next to `awscli redis6 …`):
```dockerfile
RUN dnf install -y --setopt=install_weak_deps=False \
        tar gzip findutils hostname postgresql15 procps-ng jq shadow-utils \
        awscli redis6 expect telnet \
 && dnf clean all && rm -rf /var/cache/dnf
```
Tiny (~a few hundred KB), no runtime behavior change. Required only for Path C; Paths A/B
need nothing extra.

## See also
- **Prerequisites gate** above — run it FIRST.
- `docs/FASTPATH-P2-HOTDEPLOY.md` — design, live-runtime facts, canonical-vs-console rationale.
