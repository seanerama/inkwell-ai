#!/usr/bin/env bash
# Inkwell AI host-side backup (ADR-0011, Stage 17). Takes a consistent logical dump of
# Postgres and a snapshot of the blobs volume for one environment, writes a
# self-describing manifest, updates the `latest` symlink, prunes old sets, and
# (optionally) copies an encrypted set off-host.
#
#   backup.sh <env> [--label <text>]
#
# Runs from /srv/inkwell/<env> (overridable for CI via INKWELL_SRV_DIR /
# INKWELL_BACKUPS_DIR). NO Postgres client on the host: pg_dump runs INSIDE the
# running postgres container. Blobs are archived by a throwaway alpine container.
#
# SECURITY (STATUS.md + ADR-0011): never `docker compose config` (prints secrets),
# never `cat .env`. Only the single keys this script needs are grepped from .env; the
# blob volume name is derived from the compose project label, never from `config`.
#
# Exit codes: 0 ok; 1 local failure (nothing pruned); 2 local ok but off-host copy failed.
set -euo pipefail

usage() { echo "usage: backup.sh <env> [--label <text>]" >&2; exit 1; }

ENV_NAME=""
LABEL=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --label) LABEL="${2:?--label needs a value}"; shift 2 ;;
    -h|--help) usage ;;
    -*) echo "unknown option: $1" >&2; usage ;;
    *) if [ -z "$ENV_NAME" ]; then ENV_NAME="$1"; shift; else echo "unexpected arg: $1" >&2; usage; fi ;;
  esac
done
[ -n "$ENV_NAME" ] || usage

# A label, if present, must be filename-safe (it becomes part of the set directory).
if [ -n "$LABEL" ] && ! printf '%s' "$LABEL" | grep -qE '^[A-Za-z0-9._-]+$'; then
  echo "!! --label must match [A-Za-z0-9._-]+" >&2; exit 1
fi

SRV="${INKWELL_SRV_DIR:-/srv/inkwell}"
ENV_DIR="$SRV/$ENV_NAME"
BACKUPS_ENV_DIR="${INKWELL_BACKUPS_DIR:-$SRV/backups}/$ENV_NAME"

[ -d "$ENV_DIR" ] || { echo "!! env dir $ENV_DIR does not exist" >&2; exit 1; }
cd "$ENV_DIR"
[ -f compose.yml ] || { echo "!! $ENV_DIR/compose.yml missing" >&2; exit 1; }
[ -f .env ] || { echo "!! $ENV_DIR/.env missing" >&2; exit 1; }

# Compose project name = COMPOSE_PROJECT_NAME if set, else the env dir basename (what
# `docker compose -f compose.yml` uses here, matching remote-deploy.sh and the units).
PROJECT="${COMPOSE_PROJECT_NAME:-$(basename "$ENV_DIR")}"
dc() { docker compose -f compose.yml "$@"; }

TS="$(date -u +%Y%m%dT%H%M%SZ)"
ISO_TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
if [ -n "$LABEL" ]; then SET_NAME="${TS}-${LABEL}"; else SET_NAME="${TS}"; fi
SET_DIR="$BACKUPS_ENV_DIR/$SET_NAME"
START_EPOCH="$(date -u +%s)"

fail_local() {
  echo "!! local backup failed: $*" >&2
  [ -n "${SET_DIR:-}" ] && rm -rf "${SET_DIR:?}"
  exit 1
}

mkdir -p "$BACKUPS_ENV_DIR"
chmod 700 "$BACKUPS_ENV_DIR" 2>/dev/null || true
mkdir -p "$SET_DIR"
chmod 700 "$SET_DIR"

# 1. Postgres: pg_dump -Fc INSIDE the running container (credentials from container env).
echo ">> pg_dump (custom format) from the postgres container"
if ! dc exec -T postgres pg_dump -U inkwell -Fc inkwell > "$SET_DIR/db.dump"; then
  fail_local "pg_dump"
fi
[ -s "$SET_DIR/db.dump" ] || fail_local "db.dump is empty"

# 2. Blobs: derive the volume name from the compose project LABEL (never `config`),
#    then tar it out of a throwaway alpine container and zstd it on the host.
BLOB_VOL="$(docker volume ls --filter "label=com.docker.compose.project=${PROJECT}" \
  --format '{{.Name}}' | grep -E '_blobs$' | head -n1 || true)"
[ -n "$BLOB_VOL" ] || BLOB_VOL="${PROJECT}_blobs"
echo ">> archiving blob volume ${BLOB_VOL}"
if ! docker run --rm -v "${BLOB_VOL}:/data:ro" alpine tar -C /data -cf - . \
     | zstd -q -f -o "$SET_DIR/blobs.tar.zst"; then
  fail_local "blob archive"
fi

# 3. Manifest (self-describing set). Grep the ONE key we need from .env; never cat it.
IMAGE_REF="$(grep -E '^IMAGE_REF=' .env | tail -n1 | cut -d= -f2- || true)"
ALEMBIC_HEAD="$(dc exec -T api alembic current 2>/dev/null | tail -n1 | awk '{print $1}' || true)"
DB_BYTES="$(stat -c %s "$SET_DIR/db.dump")"
BLOB_BYTES="$(stat -c %s "$SET_DIR/blobs.tar.zst")"
DB_SHA="$(sha256sum "$SET_DIR/db.dump" | awk '{print $1}')"
BLOB_SHA="$(sha256sum "$SET_DIR/blobs.tar.zst" | awk '{print $1}')"
DURATION="$(( $(date -u +%s) - START_EPOCH ))"
if [ -n "$LABEL" ]; then LABEL_JSON="\"$LABEL\""; else LABEL_JSON="null"; fi

cat > "$SET_DIR/manifest.json" <<JSON
{
  "env": "$ENV_NAME",
  "timestamp": "$ISO_TS",
  "label": $LABEL_JSON,
  "image_ref": "$IMAGE_REF",
  "alembic_head": "$ALEMBIC_HEAD",
  "db_dump_bytes": $DB_BYTES,
  "db_dump_sha256": "$DB_SHA",
  "blobs_archive_bytes": $BLOB_BYTES,
  "blobs_archive_sha256": "$BLOB_SHA",
  "duration_seconds": $DURATION,
  "remote_copied": false
}
JSON

# Local set is complete: update `latest` and prune (only ever after a successful run).
ln -sfn "$SET_DIR" "$BACKUPS_ENV_DIR/latest"
echo ">> set complete: $SET_DIR (db ${DB_BYTES}B, blobs ${BLOB_BYTES}B, ${DURATION}s)"

# --- Retention ---------------------------------------------------------------
ts_to_epoch() {  # 20260917T033000Z -> epoch
  local t="$1" d hms iso
  d="${t:0:8}"; hms="${t:9:6}"
  iso="${d:0:4}-${d:4:2}-${d:6:2} ${hms:0:2}:${hms:2:2}:${hms:4:2} UTC"
  date -u -d "$iso" +%s 2>/dev/null || echo 0
}
weekday() {  # 1=Mon .. 7=Sun for a 20260917T033000Z stamp
  local t="$1" d hms iso
  d="${t:0:8}"; hms="${t:9:6}"
  iso="${d:0:4}-${d:4:2}-${d:6:2} ${hms:0:2}:${hms:2:2}:${hms:4:2} UTC"
  date -u -d "$iso" +%u 2>/dev/null || echo 0
}

prune_retention() {
  local now thirty name ts ep count scount
  now="$(date -u +%s)"; thirty="$((30 * 86400))"
  declare -A keep
  local nightly=()
  while IFS= read -r name; do
    [[ "$name" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] && nightly+=("$name")
  done < <(find "$BACKUPS_ENV_DIR" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -r)

  count=0
  for name in "${nightly[@]}"; do
    count="$((count + 1))"
    [ "$count" -le 14 ] && keep["$name"]=1
  done
  scount=0
  for name in "${nightly[@]}"; do
    ts="${name:0:16}"
    if [ "$(weekday "$ts")" = "7" ]; then
      scount="$((scount + 1))"
      [ "$scount" -le 8 ] && keep["$name"]=1
    fi
  done

  while IFS= read -r name; do
    [[ "$name" =~ ^[0-9]{8}T[0-9]{6}Z ]] || continue
    [[ "$name" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] && continue   # nightly handled above
    ts="${name:0:16}"; ep="$(ts_to_epoch "$ts")"
    [ "$((now - ep))" -le "$thirty" ] && keep["$name"]=1
  done < <(find "$BACKUPS_ENV_DIR" -mindepth 1 -maxdepth 1 -type d -printf '%f\n')

  while IFS= read -r name; do
    [[ "$name" =~ ^[0-9]{8}T[0-9]{6}Z ]] || continue
    if [ -z "${keep[$name]:-}" ]; then
      echo ">> pruning old set $name"
      rm -rf "${BACKUPS_ENV_DIR:?}/${name:?}"
    fi
  done < <(find "$BACKUPS_ENV_DIR" -mindepth 1 -maxdepth 1 -type d -printf '%f\n')
}
prune_retention

# --- Off-host copy (opt-in) --------------------------------------------------
BACKUP_REMOTE="$(grep -E '^BACKUP_REMOTE=' .env 2>/dev/null | tail -n1 | cut -d= -f2- || true)"
if [ -n "$BACKUP_REMOTE" ]; then
  AGE_RECIPIENT="$(grep -E '^BACKUP_AGE_RECIPIENT=' .env 2>/dev/null | tail -n1 | cut -d= -f2- || true)"
  if [ -z "$AGE_RECIPIENT" ]; then
    echo "!! BACKUP_REMOTE set but BACKUP_AGE_RECIPIENT empty — cannot encrypt off-host copy" >&2
    touch "$SET_DIR/.remote-failed"; exit 2
  fi
  echo ">> off-host: encrypt with age -> rclone copy -> rclone check ($BACKUP_REMOTE)"
  ENC_DIR="$(mktemp -d)"
  ENC_FILE="$ENC_DIR/${SET_NAME}.tar.age"
  if tar -C "$BACKUPS_ENV_DIR" -cf - "$SET_NAME" | age -r "$AGE_RECIPIENT" > "$ENC_FILE" \
     && rclone copy "$ENC_FILE" "${BACKUP_REMOTE%/}/" \
     && rclone check "$ENC_DIR" "${BACKUP_REMOTE%/}/" --include "${SET_NAME}.tar.age" --one-way; then
    rm -rf "$ENC_DIR"
    sed -i 's/"remote_copied": false/"remote_copied": true/' "$SET_DIR/manifest.json"
    echo ">> off-host copy verified"
  else
    rm -rf "$ENC_DIR"
    touch "$SET_DIR/.remote-failed"
    echo "!! off-host copy failed — local set kept, marker written" >&2
    exit 2
  fi
fi

exit 0
