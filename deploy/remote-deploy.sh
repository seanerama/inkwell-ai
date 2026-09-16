#!/usr/bin/env bash
# Runs ON THE HOST, invoked by deploy.sh: `bash /tmp/inkwell-remote-deploy.sh <env-dir> <image-ref> <health-url>`.
# Kept as a file (not a stdin heredoc) because `docker compose exec/run` read stdin and
# would swallow the rest of a script piped through `bash -s`.
set -euo pipefail
REMOTE_DIR="${1:?env dir}"; IMAGE_REF="${2:?image ref}"; HEALTH_URL="${3:?health url}"
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

docker compose -f compose.yml pull -q </dev/null

# Postgres first; wait until it accepts connections.
docker compose -f compose.yml up -d postgres </dev/null
for i in $(seq 1 30); do
  if docker compose -f compose.yml exec -T postgres pg_isready -U inkwell >/dev/null 2>&1 </dev/null; then
    echo ">> postgres ready"; break
  fi
  sleep 2
done

echo ">> migrate"
docker compose -f compose.yml run --rm -T api alembic upgrade head </dev/null
echo ">> seed"
docker compose -f compose.yml run --rm -T api inkwell db seed </dev/null
echo ">> up"
docker compose -f compose.yml up -d --remove-orphans </dev/null

echo ">> polling ${HEALTH_URL}"
HEALTHY=0
for i in $(seq 1 30); do
  if curl -fsS "${HEALTH_URL}" | grep -q '"status":"ok"'; then
    echo ">> health ok: $(curl -fsS "${HEALTH_URL}")"
    docker compose -f compose.yml ps --format "table {{.Name}}\t{{.Status}}" </dev/null
    HEALTHY=1
    break
  fi
  sleep 2
done
if [ "${HEALTHY}" -ne 1 ]; then
  echo ">> health check failed; rollback with: deploy.sh <env> ${PREVIOUS:-<previous-ref>}" >&2
  docker compose -f compose.yml logs --tail=50 api </dev/null
  exit 1
fi

# Deploy canary (Stage 8): when the agent is enabled, prove one real canvas.ask job runs
# end to end through the live worker + live Anthropic API. The API key lives only on the
# host, so the canary runs here, never in GitHub. Read AGENT_ENABLED from the on-host
# .env (this script's own environment may not carry it).
AGENT_ENABLED_VAL="$(grep -E '^AGENT_ENABLED=' .env | tail -n1 | cut -d= -f2- || true)"
AGENT_ENABLED_VAL="${AGENT_ENABLED_VAL//[\"\' ]/}"  # strip surrounding quotes/spaces
if [ "${AGENT_ENABLED_VAL}" = "true" ]; then
  echo ">> canary: running one live agent job"
  if CANARY_OUT="$(docker compose -f compose.yml run --rm -T api inkwell canary </dev/null)"; then
    # The CLI's final stdout line is `canary ok: done, N annotations, M cards`.
    echo ">> $(printf '%s\n' "${CANARY_OUT}" | tail -n1)"
  else
    printf '%s\n' "${CANARY_OUT}" >&2
    echo "!! canary FAILED — the live agent job did not complete; deploy aborted." >&2
    echo "!! rollback with: deploy.sh <env> ${PREVIOUS:-<previous-ref>}" >&2
    exit 1
  fi
else
  echo ">> canary: skipped (AGENT_ENABLED not true in .env)"
fi

exit 0
