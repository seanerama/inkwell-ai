# Stage 1: Server walking skeleton: API, Postgres queue, image, staging deploy

- **Type:** chore
- **Depends on:** none
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/1
- **Design:** `docs/architecture/walking-skeleton.md` (server half), ADR-0001..0004, 0008, 0009

## Objectives

Stand up the server spine so every later stage has a real API, a real database, a
real worker, a real image, and a real staging host to land on. No model call, no ink.
After this stage `verity gates run` is green, CI is green on `main`, and
`https://mini-hp01.taile0ffc4.ts.net:8444/v1/health` answers from a systemd-managed
compose stack.

## What to build

- `server/pyproject.toml` (Python 3.12, FastAPI, SQLAlchemy 2, Alembic, Pydantic v2,
  `anthropic` installed but unused, structlog, pytest, ruff) with a committed `uv.lock`.
- `server/app/` modules `api/`, `jobs/`, `agent/` (stub), `brain/` (stub), `blobs/`
  (`BlobStore` interface + `LocalBlobStore`), `db/`, `contracts/` (schema check CLI).
- Alembic migration 0001: `spaces`, `canvases`, `layers`, `rasters`, `jobs`, `cards`,
  `brain_entries`, `device_tokens` per SPEC §4 plus ADR-0003 (`locked_at`,
  `locked_by`, `lease_expires_at`, `cancel_requested`) and ADR-0008 (`token_hash`,
  `revoked_at`, `last_seen_at`). Seed the four default spaces with an idempotent seeder.
- Routes: `GET /v1/health`, `GET /v1/spaces`, `POST /v1/jobs`, `GET /v1/jobs/{id}`,
  `POST /v1/jobs/{id}/cancel`, `GET /v1/sync`. `POST /jobs` accepts internal type
  `system.ping`; SPEC job types return `422 not_implemented` until their stages land.
- Worker entrypoint (`python -m app.worker`): `SKIP LOCKED` claim loop, lease timeout
  and one requeue, `LISTEN/NOTIFY` wake; handles `system.ping` → `{"pong": true}`.
- CLI `inkwell token create --name <n>` / `inkwell token revoke <id>`; bearer auth
  dependency; error envelope; `X-Inkwell-Contract: device-api/v1` on every response;
  30/min per-token rate limit on `POST /jobs`.
- `app.contracts check`: emits the Pydantic JSON Schema for `AgentOutput` and diffs it
  against `contracts/schema/agent-output.v1.schema.json`; runs both fixture tiers.
  (The Pydantic models for contract `agent-output` are written here so the check
  exists from day one, even though nothing produces them yet.)
- Structured JSON logging: one line per request (request id, route, status, ms) and
  per job transition (job id, from, to).
- `server/Dockerfile`: multi-stage, non-root, commands `api` and `worker`.
- `deploy/compose.yml` (api :8000, worker, postgres:16, volumes `pgdata`, `blobs`),
  `deploy/compose.test.yml` (postgres only, for local gates), `deploy/.env.example`,
  `deploy/systemd/inkwell-staging.service` and `inkwell-prod.service`,
  `deploy/README.md` with the `tailscale serve` commands, `deploy/deploy.sh` skeleton
  (pull tag → `alembic upgrade head` → `compose up -d` → health poll) for `/verity:ship`.
- `.verity/gates.json` with the server gates from the walking-skeleton doc (Android
  gates are added by Stage 2). `.github/workflows/ci.yml`: add a Postgres service to
  the `gates` job and install `uv`. `.github/workflows/release.yml`: on `v*` tags build
  and push `ghcr.io/seanerama/inkwell-ai-server:<tag>` for `linux/amd64,linux/arm64`
  and create the GitHub Release.

## Interface contracts

- **Exposes:** contract `device-api` v1 routes listed above; the `inkwell token` CLI;
  the image `ghcr.io/seanerama/inkwell-ai-server`; `BlobStore`; the worker job
  handler registry (`register_handler(type, fn)`) that Stage 5 plugs into.
- **Consumes:** `contracts/schema/agent-output.v1.schema.json` and
  `contracts/fixtures/` (read-only, frozen).

## Testing requirements

pytest against a real Postgres (CI service container; locally `compose.test.yml`).
Judged by exit code only.

- `/health` returns `status: ok`, the version, and the contract header.
- Unauthenticated `/spaces` → 401; revoked token → 401; minted token → four spaces.
- `POST /jobs system.ping` → 202 `queued`; one worker iteration in-process → `done`
  with `{"pong": true}`; `/sync` without cursor returns it; `/sync` with the returned
  cursor returns an empty page and the same cursor.
- Lease expiry: a job left `running` past `lease_expires_at` is requeued once, then
  failed on the second expiry with an `error` card.
- Cancel: `queued → cancelled` is immediate; `running` sets `cancel_requested`.
- Rate limit: the 31st `POST /jobs` in a minute → 429 with `Retry-After`.
- Contract check passes: schema equality plus all eight fixtures behave per
  `contracts/fixtures/README.md`.
- `docker build` of the server image succeeds in the gates.

## Acceptance conditions

- [ ] Clear exit-state defined (what "done" means here): `.verity/gates.json` exists
      with the seven server gates; `verity gates run` green locally; CI `gates`,
      `structure`, `secret-scan` green on the merge commit.
- [ ] `v0.0.1` tag pushes a multi-arch image to GHCR and creates a Release.
- [ ] `deploy.sh staging` on mini-hp01 brings the stack up under systemd and
      `curl https://mini-hp01.taile0ffc4.ts.net:8444/v1/health` returns `{"status":"ok"}`.
- [ ] STATUS.md updated by the Operator with image tag and secret locations.
- [ ] Existing suite stays green; CI all-green

## Operator prerequisites (not code; do before deploy)

On mini-hp01: `sudo usermod -aG docker smahoney` and re-login;
`sudo mkdir -p /srv/inkwell/{staging,prod}` owned by the operator;
`tailscale serve --bg --https=8444 http://127.0.0.1:8001` for staging. Secrets
(`ANTHROPIC_API_KEY`, `POSTGRES_PASSWORD`, `INKWELL_TOKEN_PEPPER`,
`INKWELL_BLOB_SIGNING_KEY`) in `/srv/inkwell/staging/.env`, locations per
`.verity/deploy-access.md`.

## Pipeline test: YES
