#!/usr/bin/env bash
# Inkwell AI restore (ADR-0011, Stage 17). Rebuilds Postgres and the blobs volume from a
# backup set produced by backup.sh, runs migrations to prove the dump is at or behind the
# code, and prints row counts. The regression form of this runs in CI (backup-restore
# gate); a scratch drill on the host is part of every minor release.
#
#   restore.sh <backup-dir> [--into <project>] [--yes]
#
# Default: a SCRATCH compose project `inkwell-restore-<ts>` on a free port with its own
# volumes, built from the source env's compose.yml + .env minus API_PORT — left RUNNING.
# `--into <name>` where /srv/inkwell/<name>/compose.yml exists restores OVER that live
# stack and requires --yes plus a fresh pre-restore backup. `--into <name>` for any other
# name is a scratch restore under that (deterministic) project name.
#
# Overridable for CI: INKWELL_SRV_DIR (source env base, default /srv/inkwell).
set -euo pipefail

usage() { echo "usage: restore.sh <backup-dir> [--into <project>] [--yes]" >&2; exit 1; }

BACKUP_DIR=""
INTO=""
ASSUME_YES=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --into) INTO="${2:?--into needs a value}"; shift 2 ;;
    --yes) ASSUME_YES=1; shift ;;
    -h|--help) usage ;;
    -*) echo "unknown option: $1" >&2; usage ;;
    *) if [ -z "$BACKUP_DIR" ]; then BACKUP_DIR="$1"; shift; else echo "unexpected arg: $1" >&2; usage; fi ;;
  esac
done
[ -n "$BACKUP_DIR" ] || usage
RESOLVED="$(cd "$BACKUP_DIR" 2>/dev/null && pwd)" || RESOLVED=""
if [ -z "$RESOLVED" ] || [ ! -d "$RESOLVED" ]; then
  echo "!! backup dir not found: $BACKUP_DIR" >&2
  exit 1
fi
BACKUP_DIR="$RESOLVED"

MANIFEST="$BACKUP_DIR/manifest.json"
for f in "$MANIFEST" "$BACKUP_DIR/db.dump" "$BACKUP_DIR/blobs.tar.zst"; do
  [ -f "$f" ] || { echo "!! backup set incomplete: missing $(basename "$f")" >&2; exit 1; }
done

manifest_get() {
  grep -oE "\"$1\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" "$MANIFEST" | head -n1 \
    | sed -E 's/.*:[[:space:]]*"([^"]*)"/\1/'
}

SRV="${INKWELL_SRV_DIR:-/srv/inkwell}"
ENV_NAME="$(manifest_get env)"
[ -n "$ENV_NAME" ] || { echo "!! manifest has no env" >&2; exit 1; }
SOURCE_ENV_DIR="$SRV/$ENV_NAME"
[ -f "$SOURCE_ENV_DIR/compose.yml" ] || { echo "!! $SOURCE_ENV_DIR/compose.yml missing (need it to build the restore stack)" >&2; exit 1; }
[ -f "$SOURCE_ENV_DIR/.env" ] || { echo "!! $SOURCE_ENV_DIR/.env missing" >&2; exit 1; }

# 1. Verify integrity against the manifest BEFORE touching any stack.
echo ">> verifying sha256 against manifest"
WANT_DB="$(manifest_get db_dump_sha256)"
WANT_BLOB="$(manifest_get blobs_archive_sha256)"
GOT_DB="$(sha256sum "$BACKUP_DIR/db.dump" | awk '{print $1}')"
GOT_BLOB="$(sha256sum "$BACKUP_DIR/blobs.tar.zst" | awk '{print $1}')"
[ "$WANT_DB" = "$GOT_DB" ] || { echo "!! db.dump sha256 mismatch (want $WANT_DB got $GOT_DB)" >&2; exit 1; }
[ "$WANT_BLOB" = "$GOT_BLOB" ] || { echo "!! blobs.tar.zst sha256 mismatch" >&2; exit 1; }
echo ">> integrity ok"

# 2. Decide target: live (over an existing env dir) vs scratch (own project/volumes/port).
TS="$(date -u +%Y%m%dT%H%M%SZ)"
LIVE=0
if [ -n "$INTO" ] && [ -f "$SRV/$INTO/compose.yml" ]; then
  LIVE=1
  PROJECT="$INTO"
  WORK_DIR="$SRV/$INTO"
  ENV_FILE="$WORK_DIR/.env"
else
  PROJECT="${INTO:-inkwell-restore-$TS}"
  WORK_DIR="$(mktemp -d)"
  cp "$SOURCE_ENV_DIR/compose.yml" "$WORK_DIR/compose.yml"
  # Scratch .env = source .env minus API_PORT, plus a free API_PORT for this project.
  grep -vE '^API_PORT=' "$SOURCE_ENV_DIR/.env" > "$WORK_DIR/.env"
  ENV_FILE="$WORK_DIR/.env"
fi

if [ "$LIVE" -eq 1 ]; then
  if [ "$ASSUME_YES" -ne 1 ]; then
    echo "!! restoring OVER live project '$PROJECT' is destructive — re-run with --yes" >&2
    exit 1
  fi
  echo ">> pre-restore backup of live '$PROJECT' (required before an over-live restore)"
  BK_SCRIPT="$(dirname "$0")/backup.sh"
  [ -x "$BK_SCRIPT" ] || BK_SCRIPT="/srv/inkwell/bin/backup.sh"
  "$BK_SCRIPT" "$PROJECT" --label pre-restore
fi

free_port() {
  local p
  for p in $(seq 8100 8999); do
    if ! (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; then echo "$p"; return 0; fi
    exec 3>&- 2>/dev/null || true
  done
  echo "!! no free port in 8100-8999" >&2; return 1
}

if [ "$LIVE" -eq 1 ]; then
  API_PORT="$(grep -E '^API_PORT=' "$ENV_FILE" | tail -n1 | cut -d= -f2- || true)"
  API_PORT="${API_PORT:-8000}"
else
  API_PORT="$(free_port)"
  echo "API_PORT=$API_PORT" >> "$ENV_FILE"
fi

dc() { docker compose -p "$PROJECT" -f "$WORK_DIR/compose.yml" --env-file "$ENV_FILE" "$@"; }
BLOB_VOL="${PROJECT}_blobs"

echo ">> restore target: project=$PROJECT (live=$LIVE) api_port=$API_PORT"

# 3. Start postgres and wait for it.
dc up -d postgres
for _ in $(seq 1 30); do
  if dc exec -T postgres pg_isready -U inkwell >/dev/null 2>&1; then break; fi
  sleep 2
done

# 4. Restore the database (custom-format dump).
echo ">> pg_restore --clean --if-exists"
dc exec -T postgres pg_restore --clean --if-exists -U inkwell -d inkwell < "$BACKUP_DIR/db.dump" \
  || echo ">> pg_restore reported warnings (expected on a fresh target with --clean)"

# 5. Restore blobs into the project's blob volume. Pre-create it with the compose labels
#    so `dc up api` adopts this (populated) volume silently instead of warning that a
#    volume it did not create already exists.
echo ">> extracting blobs into ${BLOB_VOL}"
docker volume create \
  --label com.docker.compose.project="$PROJECT" \
  --label com.docker.compose.volume=blobs \
  "$BLOB_VOL" >/dev/null
zstd -dc "$BACKUP_DIR/blobs.tar.zst" \
  | docker run --rm -i -v "${BLOB_VOL}:/data" alpine sh -c 'cd /data && tar -xf -'

# 6. Migrate (proves the dump is at or behind the code), then start the API.
echo ">> alembic upgrade head"
dc run --rm -T api alembic upgrade head
echo ">> starting api"
dc up -d api

echo ">> polling /v1/health on 127.0.0.1:${API_PORT}"
HEALTH="fail"
for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${API_PORT}/v1/health" 2>/dev/null | grep -q '"status":"ok"'; then
    HEALTH="ok"; break
  fi
  sleep 2
done

# 7. Report row counts + blob file count.
psql_count() { dc exec -T postgres psql -U inkwell -d inkwell -tAc "select count(*) from $1" 2>/dev/null | tr -d '[:space:]'; }
C_SPACES="$(psql_count spaces)"
C_JOBS="$(psql_count jobs)"
C_CARDS="$(psql_count cards)"
C_CANVASES="$(psql_count canvases)"
C_TOKENS="$(psql_count device_tokens)"
BLOB_FILES="$(docker run --rm -v "${BLOB_VOL}:/data:ro" alpine sh -c 'find /data -type f | wc -l' | tr -d '[:space:]')"

echo "health=${HEALTH}"
echo "project=${PROJECT}"
echo "api_port=${API_PORT}"
echo "rows: spaces=${C_SPACES} jobs=${C_JOBS} cards=${C_CARDS} canvases=${C_CANVASES} tokens=${C_TOKENS}"
echo "blobs: files=${BLOB_FILES}"

if [ "$LIVE" -eq 1 ]; then
  echo ">> live restore complete on project '$PROJECT'"
else
  echo ">> scratch restore left RUNNING as project '$PROJECT' on port $API_PORT"
  echo ">> tear down with: docker compose -p $PROJECT -f $WORK_DIR/compose.yml down -v"
fi

[ "$HEALTH" = "ok" ] || { echo "!! restored stack never became healthy" >&2; exit 1; }
exit 0
