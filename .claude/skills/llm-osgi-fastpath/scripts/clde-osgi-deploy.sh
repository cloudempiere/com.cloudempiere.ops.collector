#!/usr/bin/env bash
# clde-osgi-deploy.sh — hot install/update a CloudEmpiere plugin into the RUNNING
# iDempiere OSGi framework via the Equinox telnet console. No container restart.
#
# Runs INSIDE an iDempiere ECS task (invoke via `aws ecs execute-command`).
#
# Adapted from chuboe/idempiere-installation-script (utils/logilite_telnet_install.sh,
# logilite_telnet_start.sh, logilite_telnet_update.sh, chuboe_osgi_ss.sh). Same telnet
# console command sequence; changed for our context: pull the artifact from the plugin's
# own P2 on S3, no sudo, no `service idempiere restart`.
#
# REQUIRES: expect + telnet + aws. expect+telnet MUST be in the image (bake them):
#   AL2023 Dockerfile runtime stage:  dnf install -y ... expect telnet
# The Equinox console (-Dosgi.console=0.0.0.0:12612) is a TELNET server; a raw socket
# (bash /dev/tcp / python) does NOT negotiate telnet IAC and fails — use expect+telnet,
# exactly as chuboe does.
#
# VALIDATED 2026-05-30 on idempiere-clde-rest-stg: com.cloudempiere.ops.collector installed
# as bundle 468, ACTIVE, all 3 DS components (ColdStartScanner/EventDispatcher/
# BundleLifecycleSource) State:ACTIVE. (Validation used a runtime `dnf install expect telnet`;
# bake it into the image for repeat use.)
#
# Usage: clde-osgi-deploy.sh <s3-p2-uri> <bundle-symbolic-name> [startlevel]
set -euo pipefail

S3_URI="${1:?usage: clde-osgi-deploy.sh <s3-p2-uri> <bundle-symbolic-name> [startlevel]}"
SN="${2:?missing bundle symbolic name}"
LEVEL="${3:-5}"
PORT="${OSGI_CONSOLE_PORT:-12612}"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

command -v expect >/dev/null || { echo "[fastpath] ERROR: 'expect' not installed (dnf install -y expect telnet)"; exit 3; }
command -v telnet >/dev/null || { echo "[fastpath] ERROR: 'telnet' not installed (dnf install -y expect telnet)"; exit 3; }

echo "[fastpath] syncing $S3_URI/plugins/ ..."
aws s3 sync "$S3_URI/plugins/" "$WORK/" --only-show-errors
JAR="$(ls "$WORK/${SN}"_*.jar 2>/dev/null | head -1 || true)"
[ -n "$JAR" ] || { echo "[fastpath] ERROR: no jar matching ${SN}_*.jar in $S3_URI"; exit 1; }
echo "[fastpath] artifact: $JAR"

# Drive the telnet console with expect (chuboe pattern): spawn telnet, send each command
# waiting on the "osgi>" prompt, then disconnect. All console output goes to stdout.
osgi() {
  expect -f - "$@" <<'EXP'
set timeout 10
spawn telnet 127.0.0.1 $env(OSGI_PORT)
expect "osgi>"
foreach c $argv { send -- "$c\r"; expect "osgi>" }
send -- "disconnect\r"
expect "osgi>" { send -- "y\r" } eof
expect eof
EXP
}
export OSGI_PORT="$PORT"

esc_sn="$(printf '%s' "$SN" | sed 's/\./\\./g')"
EXISTING="$(osgi "ss $SN" | grep -E "^[0-9]+[[:space:]].*${esc_sn}" | awk '{print $1}' | head -1 || true)"

if [ -n "${EXISTING:-}" ]; then
  echo "[fastpath] updating existing bundle id=$EXISTING in place"
  osgi "update $EXISTING file:///$JAR" "refresh $EXISTING" "bundlelevel -s $LEVEL $EXISTING" "start $EXISTING"
  BID="$EXISTING"
else
  echo "[fastpath] fresh install"
  OUT="$(osgi "install file:///$JAR" "refresh")"; echo "$OUT"
  BID="$(printf '%s\n' "$OUT" | sed -n 's/.*[Bb]undle id is \([0-9]\{1,\}\).*/\1/p' | head -1 || true)"
  [ -n "${BID:-}" ] || BID="$(osgi "ss $SN" | grep -E "^[0-9]+[[:space:]].*${esc_sn}" | awk '{print $1}' | head -1 || true)"
  [ -n "${BID:-}" ] || { echo "[fastpath] ERROR: could not resolve bundle id"; osgi "ss $SN"; exit 1; }
  echo "[fastpath] bundle id=$BID; starting ..."
  osgi "bundlelevel -s $LEVEL $BID" "start $BID"
fi

echo "[fastpath] === bundle state ($SN) ==="
osgi "ss $SN"
echo "[fastpath] === DS components (the 'ACTIVE but broken' check) ==="
osgi "scr:list $BID" || echo "[fastpath] (scr:* unavailable — verify via the bundle's log lines)"
echo "[fastpath] done. Ephemeral (Fargate). Verify activation in the iDempiere log:"
echo "[fastpath]   grep -E 'stdout floor armed|Cold-start scan|listeners armed' /opt/idempiere/log/*.log"
