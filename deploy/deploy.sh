#!/usr/bin/env bash
# Inkwell AI deploy skeleton (completed by /verity:ship). Runs from the workstation
# and targets mini-hp01 over the tailnet. See .verity/deploy-access.md (gitignored).
#
#   ./deploy/deploy.sh <staging|prod> [IMAGE_TAG]
#
# Steps: sync compose + unit files -> pull the tag -> alembic upgrade head ->
# seed default spaces -> compose up -d -> health poll.
set -euo pipefail

ENVIRONMENT="${1:?usage: deploy.sh <staging|prod> [IMAGE_TAG]}"
IMAGE_TAG="${2:-latest}"

case "$ENVIRONMENT" in
  staging) HEALTH_PORT=8444 ;;
  prod)    HEALTH_PORT=8443 ;;
  *) echo "environment must be 'staging' or 'prod'" >&2; exit 2 ;;
esac

HOST="mini-hp01.taile0ffc4.ts.net"
REMOTE_DIR="/srv/inkwell/${ENVIRONMENT}"
HEALTH_URL="https://${HOST}:${HEALTH_PORT}/v1/health"

echo ">> deploying ${IMAGE_TAG} to ${ENVIRONMENT} (${REMOTE_DIR}) on ${HOST}"

# 1. Ship compose + systemd unit (the .env already exists on the host, out of band).
scp deploy/compose.yml "smahoney@${HOST}:${REMOTE_DIR}/compose.yml"
scp "deploy/systemd/inkwell-${ENVIRONMENT}.service" \
    "smahoney@${HOST}:/tmp/inkwell-${ENVIRONMENT}.service"

# 2. On the host: set the tag, pull, migrate, seed, bring the stack up, poll health.
ssh "smahoney@${HOST}" IMAGE_TAG="${IMAGE_TAG}" ENVIRONMENT="${ENVIRONMENT}" \
    REMOTE_DIR="${REMOTE_DIR}" HEALTH_URL="${HEALTH_URL}" 'bash -s' <<'REMOTE'
set -euo pipefail
cd "${REMOTE_DIR}"

sudo cp "/tmp/inkwell-${ENVIRONMENT}.service" "/etc/systemd/system/inkwell-${ENVIRONMENT}.service"
sudo systemctl daemon-reload

# Pin the tag in .env (replace or append IMAGE_TAG).
grep -q '^IMAGE_TAG=' .env && sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=${IMAGE_TAG}/" .env \
  || echo "IMAGE_TAG=${IMAGE_TAG}" >> .env

docker compose -f compose.yml pull
docker compose -f compose.yml run --rm api alembic upgrade head
docker compose -f compose.yml run --rm api inkwell db seed

sudo systemctl enable --now "inkwell-${ENVIRONMENT}"
sudo systemctl restart "inkwell-${ENVIRONMENT}"

echo ">> polling ${HEALTH_URL}"
for i in $(seq 1 30); do
  if curl -fsS "${HEALTH_URL}" | grep -q '"status":"ok"'; then
    echo ">> health ok"
    exit 0
  fi
  sleep 2
done
echo ">> health check failed" >&2
exit 1
REMOTE

echo ">> deploy complete"
