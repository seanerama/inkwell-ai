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

# 1. Ship compose and the host-side deploy script (the .env already exists on the host).
scp -q deploy/compose.yml "${USER_AT_HOST}:${REMOTE_DIR}/compose.yml"
scp -q deploy/remote-deploy.sh "${USER_AT_HOST}:/tmp/inkwell-remote-deploy.sh"

# 2. Run it on the host as a file, not via `bash -s` over stdin: docker compose
#    exec/run read stdin and would swallow the rest of a piped script.
ssh "${USER_AT_HOST}" bash /tmp/inkwell-remote-deploy.sh "${REMOTE_DIR}" "${IMAGE_REF}" "${HEALTH_URL}"
