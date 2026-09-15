#!/usr/bin/env bash
# Inkwell AI deploy (Release/Deploy Operator, ADR-0005). Runs from the workstation and
# targets mini-hp01 over the tailnet. No sudo: host-setup.sh has already prepared the
# host, and a deploy is just `compose pull/migrate/up` as the operator user.
#
#   ./deploy/deploy.sh <staging|prod> <IMAGE_REF>
#
# IMAGE_REF is a tag (v0.0.1) or, preferably, the digest from the GitHub Release's
# server-image.digest asset (ghcr.io/seanerama/inkwell-ai-server@sha256:...), so
# staging and prod run byte-identical images.
#
# Steps: back up current ref -> sync compose -> pin ref in .env -> pull ->
# alembic upgrade head (additive) -> seed -> compose up -d -> health poll.
# Rollback: re-run with the previous ref printed at the start (also saved on the host
# in .env.previous).
set -euo pipefail

ENVIRONMENT="${1:?usage: deploy.sh <staging|prod> <IMAGE_REF>}"
IMAGE_REF_IN="${2:?usage: deploy.sh <staging|prod> <IMAGE_REF>}"

case "$ENVIRONMENT" in
  staging) HEALTH_PORT=8444 ;;
  prod)    HEALTH_PORT=8443 ;;
  *) echo "environment must be 'staging' or 'prod'" >&2; exit 2 ;;
esac

# Accept a bare tag, a bare digest, or a full reference.
case "$IMAGE_REF_IN" in
  ghcr.io/*) IMAGE_REF="$IMAGE_REF_IN" ;;
  sha256:*)  IMAGE_REF="ghcr.io/seanerama/inkwell-ai-server@${IMAGE_REF_IN}" ;;
  *)         IMAGE_REF="ghcr.io/seanerama/inkwell-ai-server:${IMAGE_REF_IN}" ;;
esac

HOST="mini-hp01.taile0ffc4.ts.net"
USER_AT_HOST="smahoney@${HOST}"
REMOTE_DIR="/srv/inkwell/${ENVIRONMENT}"
HEALTH_URL="https://${HOST}:${HEALTH_PORT}/v1/health"

echo ">> deploying ${IMAGE_REF} to ${ENVIRONMENT} (${REMOTE_DIR}) on ${HOST}"

# 1. Ship compose (the .env already exists on the host, created by the operator).
scp -q deploy/compose.yml "${USER_AT_HOST}:${REMOTE_DIR}/compose.yml"

# 2. On the host: record the previous ref, pin the new one, pull, migrate, seed, up, poll.
ssh "${USER_AT_HOST}" IMAGE_REF="${IMAGE_REF}" REMOTE_DIR="${REMOTE_DIR}" \
    HEALTH_URL="${HEALTH_URL}" 'bash -s' <<'REMOTE'
set -euo pipefail
cd "${REMOTE_DIR}"
test -f .env || { echo "!! ${REMOTE_DIR}/.env missing — create it from deploy/.env.example" >&2; exit 3; }

PREVIOUS="$(grep -E '^IMAGE_REF=' .env | cut -d= -f2- || true)"
echo ">> previous ref: ${PREVIOUS:-<none>}"
[ -n "${PREVIOUS}" ] && echo "IMAGE_REF=${PREVIOUS}" > .env.previous

if grep -qE '^IMAGE_REF=' .env; then
  sed -i "s|^IMAGE_REF=.*|IMAGE_REF=${IMAGE_REF}|" .env
else
  echo "IMAGE_REF=${IMAGE_REF}" >> .env
fi

docker compose -f compose.yml pull -q

# Postgres first, and wait until it accepts connections, so the migration and
# seed containers can resolve and reach it.
docker compose -f compose.yml up -d postgres
for i in $(seq 1 30); do
  if docker compose -f compose.yml exec -T postgres pg_isready -U "${POSTGRES_USER:-inkwell}" >/dev/null 2>&1; then
    echo ">> postgres ready"; break
  fi
  sleep 2
done

docker compose -f compose.yml run --rm -T api alembic upgrade head
docker compose -f compose.yml run --rm -T api inkwell db seed
docker compose -f compose.yml up -d --remove-orphans

echo ">> polling ${HEALTH_URL}"
for i in $(seq 1 30); do
  if curl -fsS "${HEALTH_URL}" | grep -q '"status":"ok"'; then
    echo ">> health ok: $(curl -fsS "${HEALTH_URL}")"
    exit 0
  fi
  sleep 2
done
echo ">> health check failed; rollback with: deploy.sh <env> ${PREVIOUS:-<previous-ref>}" >&2
docker compose -f compose.yml logs --tail=50 api
exit 1
REMOTE
