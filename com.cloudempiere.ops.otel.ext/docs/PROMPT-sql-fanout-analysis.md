# LLM agent prompt — SQL fan-out analysis via `idempiere.sql.*`

Paste the block below into an LLM agent session (e.g. Claude Code) with AWS CLI
access to run a DB fan-out analysis on any instrumented tier. Validated against
staging rest 2026-07-11 (first run found a 1375-statement /graphql request:
10× N+1 repetition + `SHOW TRANSACTION ISOLATION LEVEL` connection churn).

Gotchas encoded in the prompt (do not relearn):

- X-Ray **filter expressions** use underscores (`annotation.idempiere_sql_count`);
  the **returned JSON** uses dotted keys (`"idempiere.sql.count"`).
- `batch-get-traces` returns each segment as a JSON **string** in `Document` —
  parse each one individually; the whole response is not one JSON document.

---

````markdown
# Task: Analyze iDempiere request DB fan-out via OTel SQL annotations

You are analyzing per-request database cost on CloudEmpiere iDempiere tiers using
AWS X-Ray traces. Region: eu-west-1.

## Background

The OTel Java agent instruments every HTTP server request and every JDBC statement.
A custom agent extension (`com.cloudempiere.ops.otel.ext`, ADR-001 §11.5) aggregates
the JDBC child spans and stamps four indexed annotations onto each request's root
(server) span — tier-agnostic: /zkau (webui), /api/v1/* (REST), /graphql:

| Annotation | Meaning |
|---|---|
| `idempiere.sql.count` | JDBC statements executed in the request (objective: < 5) |
| `idempiere.sql.distinct` | distinct sanitized statement shapes |
| `idempiere.sql.db_ms` | summed JDBC span duration in ms (can exceed wall time — concurrent connections) |
| `idempiere.sql.top_statement` | most frequent sanitized statement |

Deployed on: staging webui + staging rest (service names `idempiere-webui`,
`idempiere-rest`). The rest tier serves both /api/v1/* and /graphql.

## Interpretation rules

- `count ≫ distinct` (ratio > ~3×) ⇒ **N+1 / wrong query shape** — fix the caller
  (GraphQL resolver batching, DataLoader, eager fetch).
- high `db_ms` + low `distinct` + low count ⇒ **slow backend** — index/plan problem;
  `top_statement` names the query to EXPLAIN.
- `top_statement = "SHOW TRANSACTION ISOLATION LEVEL"` ⇒ **connection churn** — the
  pgjdbc driver emits this on connection checkout; the request is acquiring a pool
  connection per statement/transaction instead of holding one per request.
- `db_ms > wall duration` ⇒ statements ran on concurrent connections.

## How to query

Rank worst requests (filter keys use underscores; returned annotation keys use dots):

```bash
aws xray get-trace-summaries --region eu-west-1 \
  --start-time $(($(date +%s) - 3600)) --end-time $(date +%s) \
  --filter-expression 'annotation.idempiere_sql_count > 100 AND http.url CONTAINS "graphql"' \
  --output json
# TraceSummaries[].Annotations["idempiere.sql.count"][0].AnnotationValue.NumberValue
# Drop the http.url clause for all endpoints; use CONTAINS "/api/v1" or "zkau" for other tiers.
```

Drill into one trace — the raw JDBC child spans (subsegments named after the DB) carry
`sql.sanitized_query`; build a frequency table to name the exact N+1 statements:

```bash
aws xray batch-get-traces --region eu-west-1 --trace-ids <ID> --output json
# Parse each Traces[0].Segments[].Document (JSON string). The root segment
# (name idempiere-rest/idempiere-webui) has .annotations with the idempiere.sql.* keys.
# JDBC subsegments live under .subsegments[] (recurse); count identical .sql.sanitized_query values.
```

Health checks if annotations are missing:
1. `aws logs filter-log-events --log-group-name /ecs/idempiere-clde-rest-stg --filter-pattern '"OTel"'`
   must show BOTH "OTel agent attached" AND "OTel SQL fan-out extension attached".
2. The extension only exists on tiers with `otel_enabled = true` (clde-infra ecs-*-<tier>/main.tf)
   and is disabled per-tier by `-Dcloudempiere.sqlfanout.enabled=false`.

## Deliverable

A ranked table of offending requests (count, distinct, db_ms, wall, URL), a diagnosis
per offender using the interpretation rules, and the top 5 repeated sanitized statements
for the worst trace, with a concrete fix recommendation (batching vs index vs
connection scoping). Do not include raw SQL literals — statements are pre-sanitized.
````
