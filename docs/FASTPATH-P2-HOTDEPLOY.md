# Fastpath: per-plugin P2 hot-deploy into a running iDempiere container

> **Status:** Design note (2026-05-30). **No base-image change is required** — `clde-infra`
> already ships the canonical installer (`update-prd.sh` / `install-plugins.sh` /
> `resolve-plugins-runtime.sh`) and the image has `awscli`+`bash`. Motivated by live-testing
> `com.cloudempiere.ops.collector` (ADR-001) on `ecs-cluster-idempiere-clde-stg`.
> Implemented in the project skill `llm-osgi-fastpath`.

## Goal

Get a single CloudEmpiere plugin running on a staging iDempiere ECS task — repeatedly,
during a dev/QA session — **without full CI/CD and (for the hot path) without a restart**.
The deploy artifact is the plugin's own P2 on S3
(`s3://cloudempiere-p2/plugins/<plugin>/<env>/latest/`), produced by `mvn verify`. Flow:
**have a plugin → upload to S3 → run install.**

## The three valid paths (and the canonical installer)

| Path | Mechanism | Restart? | SAT-correct for ext. deps | Persists |
|---|---|---|---|---|
| **A — Durable** | one line in `images/idempiere-clde/plugins.list` → image rebuild | new image | yes | yes |
| **B — Runtime** (default) | `versions/<env>.json` + `CE_PLUGINS_AT_RUNTIME=true` → `resolve-plugins-runtime.sh` → `update-prd.sh` at task start | task (re)start | **yes** | no (Fargate) |
| **C — Hot iterate** | OSGi console `install`+`start` | **none** | **no** (low-dep plugins only) | no |

**`update-prd.sh <repos-csv> <ius-csv>`** is iDempiere's canonical installer (wrapped by
`scripts/install-plugins.sh`, driven at boot by `scripts/resolve-plugins-runtime.sh`). It
makes **one director call across all repos** (so cross-plugin `Require-Bundle` deps SAT-
resolve) and runs uninstall→install passes that force the **`bundles.info` rewrite** — the
reason a plain `director.sh -installIU` lands the jar but OSGi `ss` never sees it. Prefer
B for any plugin; C only for the rapid loop on a plugin whose deps are already in the
running framework (e.g. `ops.collector` → only `org.adempiere.base`+`org.eclipse.osgi`).

## What we learned from the live task (facts, not assumptions)

Verified by ECS Exec into a running `idempiere-clde-rest-stg` Fargate task
(image `…/idempiere-clde:20260530-064037`):

| Fact | Value | Implication |
|---|---|---|
| iDempiere home | `/opt/idempiere` | scripts run with this CWD |
| **OSGi console** | **`-Dosgi.console=0.0.0.0:12612`** (already enabled) | a live console exists → true hot-load is possible |
| `director.sh` | `… -install director -configuration director/configuration -application …p2.director $*` | targets an **isolated** `director/` profile, **not** the running instance → does *not* hot-update the live framework |
| `aws` CLI | `/usr/bin/aws` present; task role can read the P2 bucket | S3 is a usable transfer channel from inside the task |
| `java` | `/usr/bin/java` (Corretto 11) | — |
| `bash`, `python3` | present | — |
| `nc`, `telnet`, `socat`, `ncat` | **absent** | **no primitive TCP client** → today the only way to drive `:12612` is an ad-hoc Python/bash-`/dev/tcp` hack |
| ECS Exec | enabled on all `idempiere-clde-*` services | SSM shell works without inbound SSH |
| canonical installer | `update-prd.sh` + `scripts/install-plugins.sh` + `scripts/resolve-plugins-runtime.sh` + `plugins.list` already in image | **the durable/SAT-correct path already exists** — don't reinvent it |

**Conclusion:** the runtime already exposes everything needed. For durable / any-plugin
installs, use the **canonical `update-prd.sh` machinery** (Path A/B) — no new tooling. The
OSGi console (Path C) is only for the no-restart iterate loop on low-dependency plugins;
its `bash /dev/tcp` driver needs no new image packages either. **No base-image change is
required** for the fastpath; baking `expect`/`telnet`/`socat` is optional polish only.

## Mechanism decision

Two ways to land a bundle; they serve different needs — ship the **console** path for the
iterate-and-test loop, keep **director-to-instance** noted for persistent installs.

| | Equinox console (`:12612`) — **recommended** | p2 director into the instance |
|---|---|---|
| Effect | `install`+`start` in the **running JVM**; DS components activate immediately | rewrites the on-disk p2 profile |
| Restart | **none** | needs iDempiere process restart → on Fargate that kills/replaces the task |
| Persistence | lives for the task lifetime (Fargate ephemeral) | persists in the profile (but ephemeral on Fargate anyway) |
| Fit | live smoke test, rapid re-install/update loop | closer to a "real" deploy; pointless on Fargate without image bake |

For Fargate, both are ephemeral, so the console path wins on speed and zero-restart. The
durable production path remains: **bake the bundle into the image** (ADR-001 §10.5).

## Prior art — `chuboe/idempiere-installation-script` (our toolset)

The CloudEmpiere/Logilite deploy flow already solves this with tiny `expect`+`telnet`
primitives driving the same console (`utils/logilite_telnet_*.sh`, `chuboe_osgi_ss.sh`):

| chuboe primitive | console commands | our use |
|---|---|---|
| `logilite_telnet_install.sh` | `install file:///<jar>` → `r` | fresh install |
| `logilite_telnet_update.sh` | `update <id> file:///<jar>` → `bundlelevel -s <lvl> <id>` → `r` | **in-place update** (best for the iterate loop) |
| `logilite_telnet_start.sh` | `start <id>` | start |
| `logilite_telnet_set_bundlelevel.sh` | `bundlelevel -s <lvl> <id>` → `r` | start level |
| `chuboe_osgi_ss.sh` | `ss` | inventory / resolve id |

Their orchestrator (`logilite_deploy_plugins.sh`) targets a VM (sudo, `service idempiere
restart`, `deploy-jar/` folder). We keep the **console command sequence** but drop the
VM-isms (no sudo, no restart, pull from S3) for Fargate + ECS Exec.

**This is implemented** in the project skill `llm-osgi-fastpath`
(`.claude/skills/llm-osgi-fastpath/scripts/clde-osgi-deploy.sh`).

## Proposed base-image change (`clde-infra` → `images/idempiere-clde/`)

### 1. TCP client — decision (resolved empirically 2026-05-30)

The console is a **telnet server** (`-Dosgi.console=0.0.0.0:12612`) — it does IAC option
negotiation. Tested live:

- **bash `/dev/tcp`** — ❌ FAILS. Connects, but doesn't negotiate telnet IAC; commands aren't
  processed and replies arrive as `0xFF…` bytes. (My initial "zero-deps" pick was wrong.)
- **Python raw socket** — ❌ FAILS, same reason.
- **`expect`+`telnet`** — ✅ **WORKS** (what chuboe ships). Validated: installed
  `com.cloudempiere.ops.collector` as bundle 468, `ACTIVE`, all 3 DS components `ACTIVE`.
- **`socat`** — likely works but untested; `expect`+`telnet` is the proven, chuboe-aligned pick.

**Decision: bake `expect`+`telnet` into the image** (the AL2023 base ships neither). Add to
the runtime-stage `dnf install` block in `images/idempiere-clde/Dockerfile`:

```dockerfile
RUN dnf install -y --setopt=install_weak_deps=False \
        tar gzip findutils hostname postgresql15 procps-ng jq shadow-utils \
        awscli redis6 expect telnet \
 && dnf clean all && rm -rf /var/cache/dnf
```

(`aws` + `bash` already present.) A runtime `dnf install -y expect telnet` works for a one-off
test but is lost on task replacement — bake it for repeat use.

### 2. Bake a helper script: `/opt/idempiere/clde-p2-install.sh`

Primitive POSIX `sh`. Pulls the plugin P2 from S3, then drives the console to do a clean
re-install (uninstall existing → install → start) and prints bundle + DS state.

```sh
#!/bin/sh
# clde-p2-install.sh <s3-p2-uri> <bundle-symbolic-name>
# Hot-install/update a CloudEmpiere plugin into the running iDempiere OSGi framework.
# No container restart. Ephemeral (lives for this task's lifetime).
set -eu

S3_URI="${1:?usage: clde-p2-install.sh <s3-p2-uri> <bundle-symbolic-name>}"
SN="${2:?missing bundle symbolic name}"
CONSOLE="${OSGI_CONSOLE:-127.0.0.1:12612}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "[clde-p2] syncing $S3_URI/plugins/ ..."
aws s3 sync "$S3_URI/plugins/" "$WORK/" --only-show-errors
JAR="$(ls "$WORK"/"${SN}"_*.jar 2>/dev/null | head -1)"
[ -n "$JAR" ] || { echo "[clde-p2] no jar for $SN in $S3_URI"; exit 1; }
echo "[clde-p2] jar: $JAR"

# Helper: send console commands, capture reply. crnl = telnet line ending.
console() { printf '%s\n' "$@" | socat -t2 - "TCP:$CONSOLE,crnl"; }

# 1. clean update: if already installed, uninstall by id first
EXISTING="$(console "ss $SN" | awk -v sn="$SN" '$0 ~ sn {print $1; exit}')"
if [ -n "${EXISTING:-}" ]; then
  echo "[clde-p2] uninstalling existing id=$EXISTING"
  console "uninstall $EXISTING" >/dev/null
fi

# 2. install → parse new bundle id → start
ID="$(console "install file:$JAR" | sed -n 's/.*Bundle id is \([0-9]\{1,\}\).*/\1/p')"
[ -n "${ID:-}" ] || { echo "[clde-p2] install failed"; console "ss $SN"; exit 1; }
echo "[clde-p2] installed id=$ID; starting ..."
console "start $ID" >/dev/null

# 3. verify bundle + DS component state (the unique 'ACTIVE but broken' check)
echo "[clde-p2] === bundle state ==="; console "ss $SN"
echo "[clde-p2] === DS components ==="; console "scr:list $ID"
```

### 3. (optional) confirm `scr` gogo commands are wired

The DS state check (`scr:list`/`scr:info`) needs the Felix SCR gogo commands registered
in the console. `org.apache.felix.scr` is present in the platform; verify the console
profile exposes `scr:*` (else fall back to `ss` + log inspection).

## The fastpath workflow (after the image change)

```bash
# 0. build + publish the plugin P2 (dev machine)
mvn clean verify -Pcloudempiere-local
aws s3 sync com.cloudempiere.ops.collector.p2/target/repository/ \
    s3://cloudempiere-p2/plugins/com.cloudempiere.ops.collector/staging/latest/ --region eu-west-1

# 1. ensure a staging task is running (scale 0->1 if needed), then one ECS Exec call:
aws ecs execute-command --cluster ecs-cluster-idempiere-clde-stg --task <id> \
    --container idempiere-clde --region eu-west-1 --interactive \
    --command "/opt/idempiere/clde-p2-install.sh \
       s3://cloudempiere-p2/plugins/com.cloudempiere.ops.collector/staging/latest \
       com.cloudempiere.ops.collector"

# 2. re-iterate: rebuild → re-sync to S3 → re-run the same command (does a clean update).
# 3. observe events on stdout:  aws logs tail /ecs/idempiere-clde-rest-stg --follow
```

No Python, no base64, no nested quoting — `aws s3 sync` + one baked script.

## Open questions

- **Distro of the base image** — determines `socat` install line (yum/apt/apk). Confirm in
  `images/idempiere-clde/Dockerfile`.
- **`scr:*` console availability** — verify the gogo SCR commands are registered; otherwise
  the helper's DS check degrades to `ss` + CloudWatch log scraping.
- **Console exposure** — `:12612` is bound `0.0.0.0` inside the task. Confirm it is *not*
  reachable outside the task's network namespace (security): it should stay loopback-only
  to callers, reached only via ECS Exec. If the SG/ENI could expose it, bind to `127.0.0.1`.
- **Uninstall-then-install vs `update`** — equinox `update <id>` refreshes in place and
  keeps the id; simpler but can leave stale wiring. The helper uses uninstall+install for a
  clean slate; revisit if `update` proves more convenient for rapid loops.
- **Persistent path** — for anything beyond a session smoke test, bake the bundle into the
  image (ADR-001 §10.5); the console fastpath is explicitly ephemeral.

## Note — CLDE-Hub MCP candidate (future, not built)

This fastpath is a strong candidate to expose through **CLDE-Hub** (the Quarkus company-
intelligence layer: LangChain4j + MCP, ADR-001 §H). The skill's deterministic steps map
cleanly to MCP tools an operator/agent could call conversationally:

- `osgi.preflight(plugin, env)`        → `scripts/preflight.sh` (the prerequisite gate)
- `osgi.publish(plugin, env)`          → build check + `aws s3 sync` to the P2 prefix
- `osgi.install(plugin, env, mode)`    → Path B `update-prd.sh` (durable) or Path C console (hot)
- `osgi.status(plugin, task)`          → `ss <SN>` + `scr:list` (DS-state, the ADR-001 signal)
- `osgi.scale(service, n)`             → guarded scale (refuse `*-pr`, require confirm)

This aligns with ADR-001 §6 Phase 6 ("CLDE-Hub MCP plugin-health tools") and Phase 7
(triage agent). Keep the **safety gates server-side** in the MCP layer — never let an agent
scale/deploy without the prod-guard + human confirmation that the skill enforces today.
Deferred to a follow-up ADR; recorded here so the design intent isn't lost.
