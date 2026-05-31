# Provisioning — `ops-monitor` M2M access to `/api/v1/ops` (policy B)

> Companion to **ADR-001 §4.7** and **CLD-1912**. This is the *data/runtime* half of the
> M2M-first authorization landed in `OpsV1Resource` (commit "WAB health probes + M2M-first
> /api/v1/ops auth, policy B"). The code is deployed with the plugin; the rows below are
> provisioned per environment.

## Why this is a runbook, not a committed 2Pack

Two reasons the grants can't all ship as one portable `META-INF/2Pack_x.y.z.zip`:

1. **The app-client is environment-specific.** `AD_Authorization_AppClient` carries the
   environment's **Cognito `client_id`** (staging vs production differ), so it cannot be a
   single portable artifact.
2. **2Pack zips are PackOut artifacts**, exported from a configured System tenant — not
   hand-authored. The *portable* parts below (the `ops-monitor` role, optional `REST_Resource`
   definitions) can be created once and PackOut'd into a zip; the app-client row is created
   per-env from this spec.

So: create the objects below in each environment's **System tenant** (UI or the hub MCP),
then optionally PackOut the portable subset for repeatable redeploy.

## The authorization model (recap)

- **Authentication** is the global REST filter (`CLDERequestFilter`) — it accepts Cognito
  **M2M `client_credentials`** tokens. No change needed.
- An M2M token maps to a real `(AD_Client_ID, AD_Role_ID, AD_User_ID)` via
  `AD_Role_AppClientAccess` (app-client → role + `Mapped_AD_User_ID`).
- **Policy B (in `OpsV1Resource`):**
  - `startup` / `plugins` / `warmup` (system-level) require a **System-tenant** token
    (`AD_Client_ID == 0`).
  - `events` is **tenant-scoped**: System-tenant token sees all; a tenant-scoped token sees
    only its own `AD_Client_ID`.
- `REST_Resource` / `REST_Resource_Access` are **optional hardening**: `hasAccess(path,…)`
  returns *open* for any path with no matching `REST_Resource`, so policy B is sufficient on
  its own. Register the resources below only if you also want to restrict *which role* may
  reach `v1/ops/*` at the path layer.

---

## Step 1 — `ops-monitor` System role  *(portable)*

`AD_Role` in the **System tenant**:

| Column | Value |
|---|---|
| `AD_Client_ID` | `0` (System) |
| `AD_Org_ID` | `0` |
| `Name` | `ops-monitor` |
| `Description` | `Read-only operational observability (M2M monitor). CLD-1912.` |
| `UserLevel` | `S` (System) — so the resolved token is System-tenant for policy B |
| `IsAccessAllOrgs` | `Y` |
| `IsManual` | `Y` |

Give it the minimum org/window access it needs to resolve a session; it needs **no** UI
window access — it only fronts the REST `/api/v1/ops` reads.

## Step 2 *(optional hardening)* — restrict `v1/ops/*` to `ops-monitor`  *(portable)*

Only if `REST_RESOURCE_ACCESS_CONTROL` SysConfig is `Y` (default) **and** you want path-layer
role restriction in addition to policy B.

`REST_Resource` rows (`REST_ResourcePath` is a **regex** matched against the request path):

| Name | `REST_ResourcePath` (regex) |
|---|---|
| Ops status read | `v1/ops/(startup\|plugins\|events)` |
| Ops warmup | `v1/ops/warmup` |

`REST_Resource_Access` rows:

| `AD_Role_ID` | `REST_Resource_ID` | `REST_HttpMethods` |
|---|---|---|
| `ops-monitor` | Ops status read | `GET` |
| `ops-monitor` | Ops warmup | `POST` |

> Note: registering these makes the paths role-restricted for **all** callers (human included),
> not just M2M. Skip Step 2 if policy B alone is the intended gate.

## Step 3 — per-environment M2M app-client  *(NOT portable — set per env)*

1. **AWS Cognito:** create an app-client with the `client_credentials` grant; record its
   `client_id`. Ensure a matching `REST_OIDCService` row exists for the issuer (already present
   on CLDE staging/prod for the Cognito user pool).
2. **`AD_Authorization_AppClient`** (System tenant):

   | Column | Value |
   |---|---|
   | `AD_Client_ID` / `AD_Org_ID` | `0` / `0` |
   | `Name` | `ops-monitor (<env>)` |
   | `OauthFlow` | client-credentials / M2M (per the reference list value used by other M2M clients) |
   | `ProvisioningMode` | as used by existing M2M app-clients (no user provisioning) |
   | *(Cognito `client_id`)* | the value from step 3.1 — the `OIDC_Audience`/`client_id` the token carries |

3. **`AD_Role_AppClientAccess`** — the linkage that makes the token System-tenant:

   | Column | Value |
   |---|---|
   | `AD_Authorization_AppClient_ID` | the row from 3.2 |
   | `AD_Role_ID` | `ops-monitor` (Step 1) |
   | `Mapped_AD_User_ID` | a **System-tenant** service user (`AD_Client_ID 0`) |
   | `IsDefault` | `Y` |

   The mapped user + System role is what resolves the M2M token to `AD_Client_ID == 0`, which
   policy B requires for `startup`/`plugins`/`warmup`. A *tenant-scoped* monitor would instead
   map to a tenant user/role and would then only see its own `events`.

## Step 4 — SysConfig

- `REST_RESOURCE_ACCESS_CONTROL` — default `Y`. Leave as-is unless Step 2 is used and you need
  to toggle path-layer control.
- `OPS_COLLECTOR_WARMUP_HTTP_ENABLED` — must be `true` (env var) for `POST /api/v1/ops/warmup`
  to be allowed at all; it is off by default (mutating).

## Step 5 — package the portable subset (optional)

To redeploy Steps 1–2 repeatably: PackOut (`ApplicationDictionary → Export`) the `ops-monitor`
role and the `REST_Resource`/`REST_Resource_Access` rows from a configured System tenant into a
`2Pack_x.y.z.zip`, drop it under `com.cloudempiere.ops.collector/META-INF/`, and add an
`Incremental2PackActivator` as the bundle `Bundle-Activator` (same pattern as
`com.cloudempiere.identity.core`). The app-client (Step 3) stays a per-env manual step.

## Verification

```bash
# 1. Obtain an M2M token from the env's Cognito app-client (client_credentials grant)
TOKEN=$(curl -s -X POST "$COGNITO_TOKEN_URL" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "grant_type=client_credentials&client_id=$OPS_MONITOR_CLIENT_ID&client_secret=$SECRET" \
  | jq -r .access_token)

# 2. System-tenant M2M → 200 on system-level endpoints
curl -s -H "Authorization: Bearer $TOKEN" https://<host>/api/v1/ops/plugins   # expect 200
curl -s -H "Authorization: Bearer $TOKEN" https://<host>/api/v1/ops/events    # expect 200, all tenants

# 3. A tenant-scoped M2M token → 403 on plugins/startup/warmup, events scoped to its AD_Client_ID
```

Probes are unaffected — they are the unauthenticated WAB at `/ops/livez|readyz|startupz`
(no token), which is what the ALB target-group check should poll.
