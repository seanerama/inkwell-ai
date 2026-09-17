#!/usr/bin/env bash
# CI gate `backup-restore` (ADR-0011, Stage 17). Proves deploy/backup.sh + deploy/restore.sh
# round-trip data through a real, ephemeral compose stack, judged by exit code only.
#
# Runs from the repo root (like the other gates). Uses the inkwell-ai-server:ci image
# built by the earlier `image-build` gate — it does NOT rebuild it. It generates a
# throwaway .env in a temp dir (never a host .env) and does NOT set BACKUP_REMOTE, so the
# off-host path stays untested here (that is drilled on the host). All compose
# projects/volumes are torn down on exit. Target budget: under three minutes.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

IMAGE_REF="inkwell-ai-server:ci"
TMP="$(mktemp -d)"
SRV="$TMP/srv"
SRC_PROJECT="inkwellbkci$$"
RESTORE_PROJECT="inkwellrestci$$"
mkdir -p "$SRV/staging"

cleanup() {
  set +e
  docker compose -p "$SRC_PROJECT" -f "$SRV/staging/compose.yml" --env-file "$SRV/staging/.env" down -v >/dev/null 2>&1
  docker compose -p "$RESTORE_PROJECT" -f "$SRV/staging/compose.yml" --env-file "$SRV/staging/.env" down -v >/dev/null 2>&1
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

API_PORT="$(free_port)" || fail "no free port for the source stack"

cp deploy/compose.yml "$SRV/staging/compose.yml"
cat > "$SRV/staging/.env" <<ENV
IMAGE_REF=${IMAGE_REF}
INKWELL_ENV=staging
API_PORT=${API_PORT}
POSTGRES_PASSWORD=ci-postgres-pass
INKWELL_TOKEN_PEPPER=ci-token-pepper
INKWELL_BLOB_SIGNING_KEY=ci-blob-signing-key
ANTHROPIC_API_KEY=sk-ant-ci-unused
ENV

dcsrc() { docker compose -p "$SRC_PROJECT" -f "$SRV/staging/compose.yml" --env-file "$SRV/staging/.env" "$@"; }

echo "== bring up ephemeral stack (image ${IMAGE_REF}) =="
dcsrc up -d postgres
for _ in $(seq 1 30); do
  dcsrc exec -T postgres pg_isready -U inkwell >/dev/null 2>&1 && break
  sleep 2
done

echo "== migrate + seed spaces + mint token =="
dcsrc run --rm -T api alembic upgrade head
dcsrc run --rm -T api inkwell db seed
TOKEN="$(dcsrc run --rm -T api inkwell token create --name ci | grep -E '^token:' | awk '{print $2}')"
[ -n "$TOKEN" ] || fail "could not mint a token"

dcsrc up -d api
for _ in $(seq 1 30); do
  curl -fsS "http://127.0.0.1:${API_PORT}/v1/health" 2>/dev/null | grep -q '"status":"ok"' && break
  sleep 2
done

echo "== write exactly one blob via POST /v1/jobs (canvas.annotate persists the image) =="
# Smallest valid PNG (1x1). jobs._store_image decodes + persists it as <uuid>.png.
PNG_B64="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
CODE="$(curl -s -o /dev/null -w '%{http_code}' -X POST \
  -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
  -d "{\"type\":\"canvas.annotate\",\"image\":\"${PNG_B64}\"}" \
  "http://127.0.0.1:${API_PORT}/v1/jobs")"
[ "$CODE" = "202" ] || fail "POST /v1/jobs returned ${CODE}, expected 202"

echo "== backup.sh =="
COMPOSE_PROJECT_NAME="$SRC_PROJECT" INKWELL_SRV_DIR="$SRV" INKWELL_BACKUPS_DIR="$SRV/backups" \
  bash deploy/backup.sh staging
SET_DIR="$SRV/backups/staging/latest"
[ -d "$SET_DIR" ] || fail "backup.sh did not produce a latest set"

echo "== destroy the source stack (prove restore rebuilds from the set) =="
dcsrc down -v

echo "== restore.sh into a fresh project =="
OUT="$TMP/restore.out"
INKWELL_SRV_DIR="$SRV" bash deploy/restore.sh "$SET_DIR" --into "$RESTORE_PROJECT" | tee "$OUT"

echo "== assertions =="
grep -q '^health=ok'               "$OUT" || fail "restored stack not healthy"
grep -qE 'rows: .*spaces=4( |$)'   "$OUT" || fail "expected 4 spaces after restore"
grep -qE 'rows: .*tokens=1( |$)'   "$OUT" || fail "expected 1 token after restore"
grep -q 'blobs: files=1'           "$OUT" || fail "expected exactly 1 blob file after restore"

echo "PASS: backup + restore round-trip reproduced 4 spaces, 1 token, 1 blob, health ok"
