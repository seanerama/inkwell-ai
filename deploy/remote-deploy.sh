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
for i in $(seq 1 30); do
  if curl -fsS "${HEALTH_URL}" | grep -q '"status":"ok"'; then
    echo ">> health ok: $(curl -fsS "${HEALTH_URL}")"
    docker compose -f compose.yml ps --format "table {{.Name}}\t{{.Status}}" </dev/null
    exit 0
  fi
  sleep 2
done
echo ">> health check failed; rollback with: deploy.sh <env> ${PREVIOUS:-<previous-ref>}" >&2
docker compose -f compose.yml logs --tail=50 api </dev/null
exit 1
