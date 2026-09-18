#!/usr/bin/env bash
# CI gate `env-compose-parity` (Stage 20). Guards a bug class that has bitten twice:
# an env key documented in deploy/.env.example that never reaches the containers because
# it was not added to deploy/compose.yml (AGENT_ENABLED on 2026-09-16, the previous
# pepper on 2026-09-17 -> the token-pepper grace window was dead in the real deployment).
#
# Every non-comment KEY= line in deploy/.env.example whose name starts with a
# CONTAINER prefix (INKWELL_/AGENT_/SPACES_) MUST be referenced in deploy/compose.yml.
# Host-script-only backup keys (BACKUP_*) are consumed by deploy/backup.sh, not the
# containers, so they carry no container prefix and are never checked. Compose-infra keys
# (IMAGE_REF/API_PORT/POSTGRES_PASSWORD) carry no prefix either; an explicit allow-list
# below documents that intent for any future prefixed-but-host-only key.
#
# Judged by exit code. Runs from anywhere; no Docker, no network. Also self-checks that
# the parity logic actually FAILS on a deliberately missing key ("test the test").
#
# The parity helpers return 0/1 by design and are used directly in if/! conditions (their
# return code IS the signal), and keys are read via a process substitution; both are
# intentional, so the corresponding set -e info checks are disabled file-wide.
# shellcheck disable=SC2310,SC2312
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${0}")/../.." && pwd)"

# Key-name prefixes that MUST be passed through to the api/worker containers.
CONTAINER_PREFIXES=("INKWELL_" "AGENT_" "SPACES_" "PUSH_")
# Prefixed keys that are intentionally NOT container env (none today; documents intent).
ALLOWLIST=("IMAGE_REF" "API_PORT" "POSTGRES_PASSWORD" "DATABASE_URL")

is_allowlisted() {
  local needle="${1}"
  local item
  for item in "${ALLOWLIST[@]}"; do
    if [[ "${item}" == "${needle}" ]]; then
      return 0
    fi
  done
  return 1
}

# Emit the names of non-comment KEY= lines that start with a container prefix.
extract_container_keys() {
  local file="${1}"
  local key prefix
  while IFS='=' read -r key _; do
    for prefix in "${CONTAINER_PREFIXES[@]}"; do
      case "${key}" in
        "${prefix}"*) printf '%s\n' "${key}" ;;
        *) ;;
      esac
    done
  done < <(grep -E '^[A-Za-z_][A-Za-z0-9_]*=' "${file}" || true)
}

# Return 0 when every container-prefixed key in the env file is referenced (as a whole
# token) in the compose file, else 1 (listing the missing keys on stderr).
check_parity() {
  local env_file="${1}"
  local compose_file="${2}"
  local key rc=0
  while IFS= read -r key; do
    if is_allowlisted "${key}"; then
      continue
    fi
    if ! grep -qE "(^|[^A-Za-z0-9_])${key}([^A-Za-z0-9_]|\$)" "${compose_file}"; then
      echo "  MISSING: ${key} is in ${env_file} but not referenced in ${compose_file}" >&2
      rc=1
    fi
  done < <(extract_container_keys "${env_file}")
  return "${rc}"
}

FIXTURE_DIR=""
cleanup() {
  if [[ -n "${FIXTURE_DIR}" ]]; then
    rm -rf "${FIXTURE_DIR}"
  fi
}
trap cleanup EXIT

main() {
  local real_env="${REPO_ROOT}/deploy/.env.example"
  local real_compose="${REPO_ROOT}/deploy/compose.yml"

  echo "== parity: deploy/.env.example vs deploy/compose.yml =="
  if ! check_parity "${real_env}" "${real_compose}"; then
    echo "FAIL: an env key never reaches the containers (add it to the compose app-env anchor)" >&2
    return 1
  fi
  echo "ok: every INKWELL_/AGENT_/SPACES_/PUSH_ key in .env.example is referenced in compose.yml"

  # --- test the test: a fixture with a deliberately missing key MUST fail parity ---
  echo "== self-check: the parity logic must catch a missing key =="
  FIXTURE_DIR="$(mktemp -d)"
  cat > "${FIXTURE_DIR}/.env.example" <<'FIX_ENV'
# fixture env
INKWELL_PRESENT=x
INKWELL_MISSING=y
AGENT_ENABLED=false
BACKUP_REMOTE=
POSTGRES_PASSWORD=change-me
FIX_ENV
  cat > "${FIXTURE_DIR}/compose.yml" <<'FIX_COMPOSE'
x-app-env: &app-env
  INKWELL_PRESENT: ${INKWELL_PRESENT}
  AGENT_ENABLED: ${AGENT_ENABLED:-false}
FIX_COMPOSE
  # INKWELL_MISSING is deliberately absent from the fixture compose -> parity must fail.
  if check_parity "${FIXTURE_DIR}/.env.example" "${FIXTURE_DIR}/compose.yml" 2>/dev/null; then
    echo "FAIL: self-check did not catch the deliberately missing INKWELL_MISSING key" >&2
    return 1
  fi
  echo "ok: parity logic correctly flagged the missing key"

  echo "PASS: env/compose parity holds on the real files and the check fails closed"
  return 0
}

main "$@"
