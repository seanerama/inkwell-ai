#!/usr/bin/env bash
# Inkwell AI Postgres password rotation (Stage 18, ADR-0011 "backup before rotate").
# Rotates POSTGRES_PASSWORD for one environment with a pre-rotate backup, an in-place
# ALTER USER, an .env rewrite of the single key, and a rolling restart of api + worker.
#
#   rotate-db-password.sh <env>
#
# Runs from /srv/inkwell/<env> (overridable for CI via INKWELL_SRV_DIR, like backup.sh).
# The Postgres CONTAINER keeps running; only its role password changes, so no reinit and
# no volume loss. api + worker are recreated so they pick up the new DATABASE_URL.
#
# SECURITY (STATUS.md + ADR-0011): the new password is never echoed, never passed as an
# argv/`-c` literal (psql reads the ALTER from stdin), and never printed. This script
# NEVER runs `docker compose config` and NEVER `cat`s .env; it greps only the single keys
# it needs (API_PORT, AGENT_ENABLED) and rewrites only POSTGRES_PASSWORD.
#
# Exit codes: 0 ok; 1 failure (pre-rotate backup failed, ALTER failed, or unhealthy).
set -euo pipefail

usage() { echo "usage: rotate-db-password.sh <env>" >&2; exit 1; }

ENV_NAME=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help) usage ;;
    -*) echo "unknown option: $1" >&2; usage ;;
    *) if [ -z "$ENV_NAME" ]; then ENV_NAME="$1"; shift; else echo "unexpected arg: $1" >&2; usage; fi ;;
  esac
done
[ -n "$ENV_NAME" ] || usage

# Resolve this script's own directory to an ABSOLUTE path BEFORE we cd into the env dir,
# so we can still find its sibling backup.sh afterwards.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

SRV="${INKWELL_SRV_DIR:-/srv/inkwell}"
ENV_DIR="$SRV/$ENV_NAME"

[ -d "$ENV_DIR" ] || { echo "!! env dir $ENV_DIR does not exist" >&2; exit 1; }
cd "$ENV_DIR"
[ -f compose.yml ] || { echo "!! $ENV_DIR/compose.yml missing" >&2; exit 1; }
[ -f .env ] || { echo "!! $ENV_DIR/.env missing" >&2; exit 1; }

dc() { docker compose -f compose.yml "$@"; }

# 1. Pre-rotate backup (ADR-0011). Only a clean local set (exit 0) or an
#    off-host-copy-only failure (exit 2, local set exists) lets us proceed; ANY other
#    result — a local failure, or the script not being found/executable — aborts.
echo ">> pre-rotate backup (backup.sh --label pre-rotate)"
BK_SCRIPT="$SCRIPT_DIR/backup.sh"
[ -x "$BK_SCRIPT" ] || BK_SCRIPT="/srv/inkwell/bin/backup.sh"
if [ ! -x "$BK_SCRIPT" ]; then
  echo "!! cannot find an executable backup.sh (looked in $SCRIPT_DIR and /srv/inkwell/bin) — aborting" >&2
  exit 1
fi
BK_RC=0
"$BK_SCRIPT" "$ENV_NAME" --label pre-rotate || BK_RC=$?
if [ "$BK_RC" = "2" ]; then
  echo "!! pre-rotate backup: local set ok but off-host copy failed — continuing" >&2
elif [ "$BK_RC" != "0" ]; then
  echo "!! pre-rotate backup failed (exit $BK_RC) — aborting rotation" >&2
  exit 1
fi

# 2. Generate a strong password. Hex only (no shell/SQL metacharacters to quote).
if command -v openssl >/dev/null 2>&1; then
  NEW_PASSWORD="$(openssl rand -hex 24)"
else
  NEW_PASSWORD="$(tr -dc 'a-f0-9' < /dev/urandom | head -c 48)"
fi
[ -n "$NEW_PASSWORD" ] || { echo "!! failed to generate a password" >&2; exit 1; }

# 3. ALTER USER inside the running postgres container. The statement (with the value) is
#    fed on STDIN, never as argv/`-c`, and nothing secret is echoed.
echo ">> ALTER USER inkwell PASSWORD (via psql on stdin)"
if ! printf 'ALTER USER inkwell PASSWORD %s;\n' "'$NEW_PASSWORD'" \
     | dc exec -T postgres psql -U inkwell -d inkwell -v ON_ERROR_STOP=1 >/dev/null; then
  echo "!! ALTER USER failed — .env NOT changed; existing password still valid" >&2
  exit 1
fi

# 4. Rewrite ONLY the POSTGRES_PASSWORD line in .env (never cat the whole file). The `#`
#    delimiter is safe because the value is hex.
echo ">> updating POSTGRES_PASSWORD in .env"
if grep -qE '^POSTGRES_PASSWORD=' .env; then
  sed -i "s#^POSTGRES_PASSWORD=.*#POSTGRES_PASSWORD=${NEW_PASSWORD}#" .env
else
  printf 'POSTGRES_PASSWORD=%s\n' "$NEW_PASSWORD" >> .env
fi

# 5. Recreate api + worker so they read the new DATABASE_URL. Postgres is left running.
echo ">> docker compose up -d api worker"
dc up -d api worker

# 6. Health poll (grep the single API_PORT key; default 8000).
API_PORT="$(grep -E '^API_PORT=' .env | tail -n1 | cut -d= -f2- || true)"
API_PORT="${API_PORT:-8000}"
echo ">> polling /v1/health on 127.0.0.1:${API_PORT}"
HEALTH="fail"
for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${API_PORT}/v1/health" 2>/dev/null | grep -q '"status":"ok"'; then
    HEALTH="ok"
    break
  fi
  sleep 2
done
if [ "$HEALTH" != "ok" ]; then
  echo "!! api did not become healthy after the rotation" >&2
  exit 1
fi
echo "health=ok"

# 7. Canary only when the agent is enabled for this env (grep the single key).
AGENT_ENABLED="$(grep -E '^AGENT_ENABLED=' .env | tail -n1 | cut -d= -f2- || true)"
if [ "$AGENT_ENABLED" = "true" ]; then
  echo ">> running canary (AGENT_ENABLED=true)"
  dc exec -T api inkwell canary
else
  echo ">> skipping canary (AGENT_ENABLED not true)"
fi

echo ">> POSTGRES_PASSWORD rotated for env '$ENV_NAME'"
exit 0
