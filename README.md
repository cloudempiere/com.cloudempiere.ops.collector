# com.cloudempiere.ops.collector

In-JVM OSGi observability collector for iDempiere — surfaces bundle and Declarative
Services component lifecycle, 2Pack outcomes, boot phases and tenant context as
structured stdout events. Design: [`docs/ADR-001`](docs/ADR-001-iDempiere-Operational-Observability.md).

## Module layout

```
com.cloudempiere.ops.collector/                 root reactor (aggregator pom)
├── com.cloudempiere.ops.collector.parent/      parent pom (Tycho 2.7.5, JavaSE-11, target platform)
├── com.cloudempiere.ops.collector/             the bundle (eclipse-plugin)
│   ├── META-INF/MANIFEST.MF                     eager activation, version-range deps, exports api
│   ├── OSGI-INF/schemas/event.schema.json       event envelope JSON schema (ADR §4.8)
│   └── src/com/cloudempiere/ops/collector/
│       ├── api/                                 EXPORTED contract: OpsEvent, Severity
│       └── internal/                            DS components (impl detail, not exported)
├── com.cloudempiere.ops.collector.feature/     OSGi feature (eclipse-feature)
└── com.cloudempiere.ops.collector.p2/          P2 repository (eclipse-repository, LAST in build)
```

## Build

Requires **JavaSE-11** (Corretto) + **Maven 3.9+**. Build resolves the iDempiere core
target platform via P2.

The private S3 P2 bucket (`s3://cloudempiere-p2`) has **two** core envs — `staging/latest/`
and `production/latest/` (there is no `develop/` env; the `develop` branch / Docker dev uses
the **staging** P2). Maven's P2 transport does not SigV4-sign, so a `file://` repo synced with
the AWS CLI is the local route:

```bash
# 1. Sync the staging core P2 into the canonical local path (one-time, ~306 MB):
dest=../iDempiereCLDE/core/org.idempiere.p2/target/repository
aws s3 sync s3://cloudempiere-p2/plugins/iDempiereCLDE/staging/latest/ "$dest" \
    --region eu-west-1 --exclude "*.tar.gz"

# 2. Build against it — the cloudempiere-local profile already points here:
mvn clean verify -Pcloudempiere-local

# Or point at any core P2 explicitly (e.g. production, or a local core reactor build):
mvn clean verify -Didempiere.core.repository.url=file:///abs/path/to/core/org.idempiere.p2/target/repository
```

> The parent POM's `idempiere.core.repository.url` default still reads `…/iDempiereCLDE/develop/latest/`,
> which does **not** exist in S3. CI overrides it per-env via `-D`; locally use `-Pcloudempiere-local`
> (above) or override to `staging/latest`. Consider repointing the default to `staging/latest`.

P2 output: `com.cloudempiere.ops.collector.p2/target/repository/`.

### Per-plugin P2 hot deploy

```bash
./idempiere -application org.eclipse.equinox.p2.director \
    -repository <p2-url>/plugins/com.cloudempiere.ops.collector/<env>/latest/ \
    -installIU com.cloudempiere.ops.collector.feature.feature.group
```

### Live test on the ECS staging REST tier (SSM + p2 director)

Once the bundle is established, validate it on a running container rather than only in
the reactor. Target the **staging REST tier** task; reach it over **SSM** (no inbound SSH),
then drive the **p2 director** to install the feature:

```bash
# 1. Publish this plugin's P2 to its own S3 prefix (staging):
aws s3 sync com.cloudempiere.ops.collector.p2/target/repository/ \
    s3://cloudempiere-p2/plugins/com.cloudempiere.ops.collector/staging/latest/ --region eu-west-1

# 2. Find the running staging REST-tier task and open an SSM session (ECS Exec):
aws ecs list-tasks --cluster <staging-cluster> --service-name <rest-tier-service> --region eu-west-1
aws ecs execute-command --cluster <staging-cluster> --task <task-id> \
    --container <idempiere-container> --interactive --command "/bin/sh" --region eu-west-1

# 3. Inside the container: sync the plugin P2 locally (task role has S3 read), then
#    run the director against a file:// repo (the director transport does not sign S3):
aws s3 sync s3://cloudempiere-p2/plugins/com.cloudempiere.ops.collector/staging/latest/ /tmp/ops-p2/
./director -application org.eclipse.equinox.p2.director \
    -repository file:///tmp/ops-p2/ \
    -installIU com.cloudempiere.ops.collector.feature.feature.group

# 4. Verify: the bundle is ACTIVE and its DS components are SATISFIED (not just ACTIVE),
#    and the cold-start scan / lifecycle events appear on stdout → CloudWatch.
#    OSGi console:  ss com.cloudempiere.ops.collector   |   scr:list / scr:info
#    Logs:          aws logs tail /ecs/idempiere-clde-rest-stg --follow --region eu-west-1
```

> The scaffold's `/ops/v1/*` REST surface (§4.7) is a TODO, so for now verification is via
> OSGi console state + the stdout events the lifecycle sources emit. Once `OpsRestResource`
> lands, also curl `/ops/v1/plugins` and `/ops/v1/readyz` from the task.

### Pre-deploy guards (ADR §4.16)

Run against the built JAR in CI:

```bash
JAR=com.cloudempiere.ops.collector/target/*.jar
unzip -l $JAR | grep -q 'OSGI-INF/.*\.xml'                  || { echo FAIL: no DS XML in JAR; exit 1; }
unzip -p $JAR META-INF/MANIFEST.MF | grep -q software.amazon.awssdk && { echo FAIL: AWS SDK in OSGi; exit 1; }
```

The first guard catches the §4.5 silent-failure mode: a DS descriptor listed in
`Service-Component:` but missing from the JAR's binary build → bundle installs `ACTIVE`
while its components do not exist.

## Implementation status

This is the **Phase 2 scaffold** (ADR §6). Buildable, ADR-compliant skeleton with the
always-on lifecycle sources wired to the stdout floor.

**Implemented**
- `api.OpsEvent` / `api.Severity` — exported event-envelope contract (§4.8, §5).
- `internal.EventDispatcher` — `immediate` component; the stdout floor of §4.10 (sink #1).
- `internal.ColdStartScanner` — one-shot bundle-state scan on `@Activate` (§4.4).
- `internal.BundleLifecycleSource` — `BundleListener` + `FrameworkListener` (§4.4).

**TODO** (tracked against ADR §4.4 component table / §6 phases)
- DS-component state poll via `ServiceComponentRuntime` — `DSComponentSource` (the unique
  "ACTIVE but broken" signal); needs the SCR service on the target platform.
- `TwoPackEventSource`, `Log4jStructuredAppender`, `TenantEnricher`, `SeverityClassifier`,
  `BootPhaseTracker`, `ShutdownHandler`, `WarmupRunner`.
- `OpsRestResource` — `/ops/v1/*` via the BX REST extension point (§4.7).
- OTel sink (#2) + EventAdmin sink (#3) in `EventDispatcher` (§4.10, §4.11) — Phase 3.
- log4j2 JSON layout fragment replacing the hand-built JSON floor (§4.13).
- ECS task-identity metadata fetch (§4.17).

> **Java target:** ADR §4.1 specifies `JavaSE-17`; this scaffold uses **`JavaSE-11`** to
> match the iDempiere 10 runtime, the parent POM `executionEnvironment`, and every sibling
> plugin. Bump the MANIFEST BREE and the parent POM `maven.compiler`/`executionEnvironment`
> together once core moves to v13+/Java 17.
