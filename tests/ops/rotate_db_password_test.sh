#!/usr/bin/env bash
# CI gate `rotate-db-password` (Stage 18, ADR-0011). Proves deploy/rotate-db-password.sh
# rotates the Postgres role password against a real, ephemeral compose stack and that the
# API still reaches the database (with the NEW password) afterwards. Judged by exit code.
#
# Runs from the repo root. Uses the inkwell-ai-server:ci image built by the earlier
# `image-build` gate (does NOT rebuild it), a throwaway .env in a temp dir (never a host
# .env), and AGENT_ENABLED=false so no live canary/Anthropic call happens. All compose
# projects/volumes are torn down on exit. Target budget: under two minutes.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

# The throwaway .env below must be authoritative. docker compose gives the CALLER's shell
# environment precedence over --env-file when interpolating ${VAR} in compose.yml, and
# the CI gates job exports INKWELL_TOKEN_PEPPER=ci-pepper (for pytest) — which would
# override this stack's pepper (and, since Stage 37, fail the staging config check).
# Drop every compose-interpolated key inherited from the caller; scripts invoked below
# get their INKWELL_SRV_DIR/INKWELL_BACKUPS_DIR/COMPOSE_PROJECT_NAME set inline.
while IFS= read -r inherited; do
  case "$inherited" in
    INKWELL_*|AGENT_*|SPACES_*|PUSH_*|BRAIN_*|IMAGE_REF|API_PORT|POSTGRES_PASSWORD|ANTHROPIC_API_KEY)
      unset "$inherited" ;;
    *) ;;
  esac
done < <(compgen -e)

# Low-entropy, obviously fake throwaway secrets (>= 32 chars, distinct; Stage 37 rule).
CI_TOKEN_PEPPER="ci-token-pepper-$(printf '%032d' 0)"
CI_BLOB_SIGNING_KEY="ci-blob-signing-key-$(printf '%032d' 1)"
cd "$REPO_ROOT"

IMAGE_REF="inkwell-ai-server:ci"
TMP="$(mktemp -d)"
SRV="$TMP/srv"
PROJECT="inkwellrotci$$"
mkdir -p "$SRV/staging"

cleanup() {
  set +e
  docker compose -p "$PROJECT" -f "$SRV/staging/compose.yml" --env-file "$SRV/staging/.env" down -v >/dev/null 2>&1
  rm -rf "$TMP"
}
trap cleanup EXIT

free_port() {
  local p
  for p in $(seq 8100 8999); do
    if ! (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; then echo "$p"; return 0; fi
    exec 3>&- 2>/dev/null || true
  done
  return 1
}

fail() { echo "FAIL: $*" >&2; exit 1; }

API_PORT="$(free_port)" || fail "no free port for the stack"

cp deploy/compose.yml "$SRV/staging/compose.yml"
cat > "$SRV/staging/.env" <<ENV
IMAGE_REF=${IMAGE_REF}
INKWELL_ENV=staging
API_PORT=${API_PORT}
AGENT_ENABLED=false
POSTGRES_PASSWORD=ci-postgres-pass-original
INKWELL_TOKEN_PEPPER=${CI_TOKEN_PEPPER}
INKWELL_BLOB_SIGNING_KEY=${CI_BLOB_SIGNING_KEY}
ANTHROPIC_API_KEY=sk-ant-ci-unused
ENV

dc() { docker compose -p "$PROJECT" -f "$SRV/staging/compose.yml" --env-file "$SRV/staging/.env" "$@"; }

echo "== bring up ephemeral stack (image ${IMAGE_REF}) =="
dc up -d postgres
for _ in $(seq 1 30); do
  dc exec -T postgres pg_isready -U inkwell >/dev/null 2>&1 && break
  sleep 2
done

echo "== inkwell config check (Stage 37): ok on this .env, non-zero on a bad one =="
CFG_OUT="$(dc run --rm -T --no-deps api inkwell config check 2>&1)" \
  || fail "inkwell config check rejected the valid throwaway .env: ${CFG_OUT}"
printf '%s\n' "$CFG_OUT" | grep -q '^config ok (env=staging)$' \
  || fail "inkwell config check did not print 'config ok (env=staging)'"
# A bad .env: the pepper blanked (compose turns it into an EMPTY string in the container).
sed 's/^INKWELL_TOKEN_PEPPER=.*/INKWELL_TOKEN_PEPPER=/' "$SRV/staging/.env" > "$TMP/bad.env"
if docker compose -p "$PROJECT" -f "$SRV/staging/compose.yml" --env-file "$TMP/bad.env" \
     run --rm -T --no-deps api inkwell config check >"$TMP/bad-config.out" 2>&1; then
  fail "inkwell config check exited 0 on a .env with a blank INKWELL_TOKEN_PEPPER"
fi
grep -q 'INKWELL_TOKEN_PEPPER' "$TMP/bad-config.out" \
  || fail "the config-check failure did not name INKWELL_TOKEN_PEPPER"
echo "ok: config check passes the valid .env and rejects the blank pepper (variable named)"

echo "== migrate + seed spaces + mint token =="
dc run --rm -T api alembic upgrade head
dc run --rm -T api inkwell db seed
TOKEN="$(dc run --rm -T api inkwell token create --name ci | grep -E '^token:' | awk '{print $2}')"
[ -n "$TOKEN" ] || fail "could not mint a token"

dc up -d api
for _ in $(seq 1 30); do
  curl -fsS "http://127.0.0.1:${API_PORT}/v1/health" 2>/dev/null | grep -q '"status":"ok"' && break
  sleep 2
done

echo "== sanity: bearer token reaches the DB before rotation =="
CODE="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer ${TOKEN}" \
  "http://127.0.0.1:${API_PORT}/v1/spaces")"
[ "$CODE" = "200" ] || fail "pre-rotate GET /v1/spaces returned ${CODE}, expected 200"

ORIGINAL_PW="$(grep -E '^POSTGRES_PASSWORD=' "$SRV/staging/.env" | cut -d= -f2-)"

echo "== rotate-db-password.sh staging =="
# COMPOSE_PROJECT_NAME lets the rotate script's (and backup.sh's) bare `docker compose`
# find this test's project/volumes; INKWELL_SRV_DIR points both scripts at the temp env.
COMPOSE_PROJECT_NAME="$PROJECT" INKWELL_SRV_DIR="$SRV" \
  bash deploy/rotate-db-password.sh staging || fail "rotate-db-password.sh exited non-zero"

echo "== assertions =="
NEW_PW="$(grep -E '^POSTGRES_PASSWORD=' "$SRV/staging/.env" | cut -d= -f2-)"
[ -n "$NEW_PW" ] || fail "POSTGRES_PASSWORD missing from .env after rotation"
[ "$NEW_PW" != "$ORIGINAL_PW" ] || fail "POSTGRES_PASSWORD unchanged after rotation"

# The api container was recreated with the NEW password; a token-authed DB read proving it
# can still reach Postgres is the real assertion (health alone would not touch the DB).
curl -fsS "http://127.0.0.1:${API_PORT}/v1/health" 2>/dev/null | grep -q '"status":"ok"' \
  || fail "api not healthy after rotation"
CODE="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer ${TOKEN}" \
  "http://127.0.0.1:${API_PORT}/v1/spaces")"
[ "$CODE" = "200" ] || fail "post-rotate GET /v1/spaces returned ${CODE}, expected 200 (DB unreachable with new password?)"

echo "PASS: rotate-db-password rotated the role password; api reaches the DB with the new password, health ok"
