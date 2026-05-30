#!/usr/bin/env bash
# preflight.sh — verify all prerequisites for the LLM dev→OSGi fastpath in one shot.
# Run from the DEV machine before attempting a deploy. Exits non-zero on any HARD failure.
#
# Usage: preflight.sh [plugin-symbolic-name]
#   e.g. preflight.sh com.cloudempiere.ops.collector
#
# Env overrides (defaults shown):
#   CE_ENV=staging  REGION=eu-west-1  EXPECT_ACCOUNT=806902423583
#   CLUSTER=ecs-cluster-idempiere-clde-stg  SERVICE=idempiere-clde-rest-stg
#   P2_BUCKET=cloudempiere-p2
set -uo pipefail

PLUGIN="${1:-}"
CE_ENV="${CE_ENV:-staging}"
REGION="${REGION:-eu-west-1}"
EXPECT_ACCOUNT="${EXPECT_ACCOUNT:-806902423583}"
CLUSTER="${CLUSTER:-ecs-cluster-idempiere-clde-stg}"
SERVICE="${SERVICE:-idempiere-clde-rest-stg}"
P2_BUCKET="${P2_BUCKET:-cloudempiere-p2}"

HARD=0; WARN=0
pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; HARD=$((HARD+1)); }
warn() { printf '  \033[33mWARN\033[0m  %s\n' "$1"; WARN=$((WARN+1)); }
have() { command -v "$1" >/dev/null 2>&1; }

echo "== fastpath preflight (env=$CE_ENV region=$REGION) =="

# 1. AWS CLI + creds + account + region
echo "[1] AWS CLI + credentials"
if ! have aws; then fail "aws CLI not installed"; else
  acct="$(aws sts get-caller-identity --query Account --output text 2>/dev/null || true)"
  if [ -z "$acct" ]; then fail "aws creds invalid / sts get-caller-identity failed"
  elif [ "$acct" != "$EXPECT_ACCOUNT" ]; then warn "account $acct (expected $EXPECT_ACCOUNT)"
  else pass "creds OK, account $acct"; fi
  reg="$(aws configure get region 2>/dev/null || echo "${AWS_REGION:-}")"
  [ "$reg" = "$REGION" ] && pass "region $reg" || warn "default region '$reg' != $REGION (pass --region)"
fi

# 2. session-manager-plugin (ECS Exec transport)
echo "[2] session-manager-plugin (ECS Exec)"
if have session-manager-plugin; then pass "$(session-manager-plugin --version 2>/dev/null | head -1)"
else fail "session-manager-plugin missing — macOS: brew install --cask session-manager-plugin"; fi

# 3. S3 P2 repo access
echo "[3] S3 P2 repos ($P2_BUCKET)"
if have aws; then
  if aws s3 ls "s3://$P2_BUCKET/plugins/iDempiereCLDE/$CE_ENV/latest/" --region "$REGION" >/dev/null 2>&1; then
    pass "read core P2 (iDempiereCLDE/$CE_ENV/latest)"
  else fail "cannot read core P2 — check s3:ListBucket/GetObject on $P2_BUCKET"; fi
  if [ -n "$PLUGIN" ]; then
    if aws s3 ls "s3://$P2_BUCKET/plugins/$PLUGIN/$CE_ENV/latest/" --region "$REGION" >/dev/null 2>&1; then
      pass "plugin P2 published ($PLUGIN/$CE_ENV/latest)"
    else warn "plugin P2 not yet at $PLUGIN/$CE_ENV/latest (publish in Step 0)"; fi
    # write capability probe (harmless marker, then delete)
    k="plugins/$PLUGIN/$CE_ENV/.preflight-$$"
    if echo ok | aws s3 cp - "s3://$P2_BUCKET/$k" --region "$REGION" >/dev/null 2>&1; then
      aws s3 rm "s3://$P2_BUCKET/$k" --region "$REGION" >/dev/null 2>&1
      pass "S3 write (publish) OK"
    else warn "no S3 write to $P2_BUCKET (needed for publish step)"; fi
  fi
fi

# 4. Target service: ECS Exec enabled + not production
echo "[4] target service ($CLUSTER / $SERVICE)"
case "$SERVICE" in
  *-pr|*-prod|*-production) fail "REFUSING production-looking service '$SERVICE'";;
  *)
    if have aws; then
      q="$(aws ecs describe-services --cluster "$CLUSTER" --services "$SERVICE" --region "$REGION" \
            --query 'services[0].[enableExecuteCommand,desiredCount,runningCount]' --output text 2>/dev/null || true)"
      if [ -z "$q" ]; then fail "service not found / no ecs:DescribeServices"; else
        set -- $q
        [ "${1:-}" = "True" ] && pass "ECS Exec enabled" || fail "enableExecuteCommand != true"
        echo "       desired=${2:-?} running=${3:-?} (scale 0→1 to test; scale back after)"
      fi
    fi;;
esac

# 5. Build toolchain
echo "[5] build toolchain"
if have java && java -version 2>&1 | grep -q '"11\.'; then pass "Java 11"; else warn "Java 11 not active (BREE JavaSE-11)"; fi
if have mvn && mvn -version 2>&1 | grep -q 'Apache Maven'; then pass "Maven present"; else warn "Maven 3.9+ not found"; fi

echo "== summary: $HARD hard failure(s), $WARN warning(s) =="
[ "$HARD" -eq 0 ] || { echo "ABORT: resolve hard failures before deploying."; exit 1; }
echo "Preflight OK."
