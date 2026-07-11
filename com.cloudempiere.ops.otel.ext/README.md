# com.cloudempiere.ops.otel.ext — SQL fan-out agent extension

An **OpenTelemetry Java-agent extension** that stamps per-request DB fan-out numbers onto every
request's local-root span, **tier-agnostic** — `/zkau`, `/api/v1/*`, and `/graphql` — with **no
iDempiere / GraphQL code change**. Implements ADR-001 §11.5.

## What it produces

The agent already emits every JDBC statement as a raw child span (ADR-065). This extension
aggregates those onto the server span so a slow request self-describes its DB cost:

| Annotation | Meaning | Reads as |
|---|---|---|
| `idempiere.sql.count` | JDBC statements in the request | objective: `< 5` DB queries/request |
| `idempiere.sql.distinct` | distinct sanitized statements | **`count ≫ distinct` ⇒ N+1 / wrong query (FE)** |
| `idempiere.sql.db_ms` | summed JDBC span duration (ms) | **high + low distinct ⇒ slow backend (index)** |
| `idempiere.sql.top_statement` | most-frequent sanitized statement | names the offending SQL |

These attribute names are already declared in the X-Ray `indexed_attributes` list
(`clde-infra/modules/idempiere-clde-tier/locals.tf`), so they filter as annotations, e.g.
`annotation.idempiere_sql_count > 1000`.

## Why an agent extension (not the collector bundle)

The agent builds and **seals** its `SdkTracerProvider` at JVM boot; a `SpanProcessor` cannot be
added afterwards from an OSGi bundle. The only supported path is the autoconfigure SPI
(`AutoConfigurationCustomizerProvider`) loaded from the extension jar at boot — ADR-001 §11.5
Option A. (The stale locals.tf comment "stamped by the collector's SpanProcessor" predates this
correction.)

## Build

```bash
cd com.cloudempiere.ops.otel.ext
mvn -q clean package
# → target/com.cloudempiere.ops.otel.ext-1.0.0.jar   (2 classes + SPI file; deps are `provided`)
```

Align `otel.sdk.version` / `otel.instrumentation.version` in `pom.xml` with the **deployed**
`opentelemetry-javaagent.jar` before building.

## Prerequisite — the agent must be attached on the tier that serves `/graphql`

ADR-065 attaches `-javaagent` **only in the `server` branch** of `docker-entrypoint.sh`
(gated by `CE_OTEL_ENABLED`). This extension only sees spans on a JVM that runs the agent, so
**confirm the tier hosting the `/graphql` WAB has the agent attached** before expecting
`idempiere.sql.*` on GraphQL requests. If `/graphql` runs on a tier without the agent, enable
it there first — no extension change needed.

## Deploy (clde-infra)

Mount the jar and add it to the agent's extension path — alongside the existing `-javaagent`
flag, subject to the **same attach-order rule** (ADR-001 §4.11): prepend to `$JAVA_OPTIONS`
**before `run_silent_setup`**, or it is a silent no-op.

```
-Dotel.javaagent.extensions=/opt/otel/ext/com.cloudempiere.ops.otel.ext-1.0.0.jar
```

Optional per-tier off switch: `-Dcloudempiere.sqlfanout.enabled=false`.

Nothing else — no `indexed_attributes` change (already present), no iDempiere redeploy.

## Validate

Reload a page (e.g. the `/sk/` PLP), open its root span in X-Ray, read `idempiere.sql.*`.
`count ≫ distinct` → N+1 (frontend/resolver shape); high `db_ms` + low `distinct` → backend
index/plan, with `top_statement` naming the query.

For a full analysis workflow (ranking queries, trace drill-down, interpretation rules,
X-Ray CLI gotchas), hand an LLM agent the ready-made prompt in
[`docs/PROMPT-sql-fanout-analysis.md`](docs/PROMPT-sql-fanout-analysis.md).
