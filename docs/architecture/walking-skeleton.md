# Stage 0 — Walking skeleton

The thinnest end-to-end slice that compiles, runs, passes real tests, goes green in
CI, builds release artifacts, and deploys to staging on mini-hp01. It proves the spine
(tablet → tailnet → API → Postgres → worker → API → tablet) before any ink or model
work. Every feature stage depends on it.

## Objective

A tablet build that pairs with the server, calls `GET /v1/health`, submits a
`ping` job, and shows the job come back `done` through `/sync`. No ink, no model
call, no UI beyond one screen.

## What to build

**Repo layout** (SPEC §14): `android/`, `server/`, `contracts/` (exists), `deploy/`.

**Server (`server/`)**
- `pyproject.toml` + `uv.lock`; Python 3.12; FastAPI, SQLAlchemy 2, Alembic,
  Pydantic v2, `anthropic` (installed, unused in Stage 0), structlog for JSON logs.
- Package `app/` with modules `api/`, `jobs/`, `agent/` (stub), `brain/` (stub),
  `blobs/` (`LocalBlobStore`), `db/`.
- Alembic migration 0001: `spaces`, `canvases`, `layers`, `rasters`, `jobs`, `cards`,
  `brain_entries`, `device_tokens` exactly as SPEC §4 plus ADR-0003/0008 columns
  (`locked_at`, `locked_by`, `cancel_requested`, `token_hash`, ...). Seed the four
  default spaces.
- Routes live in Stage 0: `GET /health`, `GET /spaces`, `POST /jobs` (accepting a
  new **internal** type `system.ping` in addition to the SPEC types, which return
  `422 not_implemented` until their stages land), `GET /jobs/{id}`, `GET /sync`.
- Worker entrypoint: claims `system.ping`, sets `result = {"pong": true}`, `done`.
- CLI: `inkwell token create --name <n>`, `inkwell token revoke <id>`.
- Bearer auth middleware, `X-Inkwell-Contract: device-api/v1` echo, error envelope.
- Structured JSON log line per request and per job transition (ADR-0009).
- `Dockerfile` (multi-stage, non-root, `linux/amd64` + `linux/arm64`), commands `api`
  and `worker`.

**Android (`android/`)**
- Gradle 8 with version catalog and dependency locking; `minSdk` 31, `targetSdk` 34.
- One Compose screen: server URL + token fields (token stored via
  `EncryptedSharedPreferences`), a "Check" button showing `/health` version, and a
  "Ping" button that posts a `system.ping` job and polls `/sync` until `done`.
- Retrofit + kotlinx.serialization client generated against contract `device-api`.
- Room database version 1 with the `ink-storage` entities (schema export committed),
  even though no screen writes strokes yet. This freezes the schema early.
- Contract Kotlin classes generated from `contracts/schema/agent-output.v1.schema.json`
  (ADR-0007), compiled but unused.

**Deploy (`deploy/`)**
- `compose.yml` (api, worker, postgres:16, volumes `pgdata`, `blobs`), `.env.example`,
  `systemd/inkwell-<env>.service`, `deploy.sh` skeleton the Operator completes in
  `/verity:ship` (pull tag → `alembic upgrade head` → `compose up -d` → health poll).
- `tailscale serve` setup steps documented in `deploy/README.md`.

**CI (`.github/workflows/`)**
- `ci.yml` gates job runs `.verity/gates.json` (below). Add a Postgres service
  container for the server tests. Add Gradle caching.
- `release.yml` on `v*` tags: build and push `ghcr.io/seanerama/inkwell-ai-server:<tag>`
  multi-arch; build signed APK; create the GitHub Release with the APK attached.

## `.verity/gates.json` (created by this stage)

```json
{
  "schema": 1,
  "gates": [
    { "name": "server-lint",     "command": "cd server && uv run ruff check . && uv run ruff format --check ." },
    { "name": "server-test",     "command": "cd server && uv run pytest -q" },
    { "name": "contract-schema", "command": "cd server && uv run python -m app.contracts check" },
    { "name": "android-lint",    "command": "cd android && ./gradlew --no-daemon lint" },
    { "name": "android-test",    "command": "cd android && ./gradlew --no-daemon testDebugUnitTest" },
    { "name": "android-build",   "command": "cd android && ./gradlew --no-daemon assembleDebug" },
    { "name": "image-build",     "command": "docker build -t inkwell-ai-server:ci server" }
  ]
}
```

`server-test` requires `DATABASE_URL`; CI provides a Postgres service and the local
runner uses `docker compose -f deploy/compose.test.yml up -d postgres`.

## Testing requirements (real, exit-code judged)

- **Server integration (pytest, real Postgres):** health returns the contract header;
  unauthenticated request → 401; token mint → authenticated `/spaces` returns four
  seeded spaces; `POST /jobs system.ping` → 202 queued; run the worker claim loop once
  in-process → `GET /jobs/{id}` is `done` with `{"pong": true}`; `/sync` with no
  cursor returns it, and with the returned cursor returns nothing.
- **Contract schema check:** Pydantic-emitted JSON Schema equals
  `contracts/schema/agent-output.v1.schema.json`; every `valid-*` fixture validates;
  every `invalid-*` fixture is rejected (both tiers, see `contracts/fixtures/README.md`).
- **Android JVM unit tests:** the generated contract classes parse every `valid-*`
  fixture and reject every `invalid-*` fixture; NM→CU mapping for the default canvas
  is exact for `[0,0]`, `[1,1]`, `[0.5,0.5]`; sync cursor round-trips.
- **Android instrumented (emulator, CI):** the pairing screen renders and the Room
  database opens at version 1. Kept minimal so the emulator lane stays under a few
  minutes.

## Acceptance conditions

1. `verity gates run` is green locally and the CI `gates` job is green on `main`.
2. `v0.0.1` tag produces a multi-arch server image on GHCR and a signed APK on the
   GitHub Release.
3. `deploy.sh staging` on mini-hp01 brings up the stack under systemd;
   `curl https://mini-hp01.taile0ffc4.ts.net:8444/v1/health` returns `{"status":"ok"}`
   (staging is 8444; prod is 8443; 443 belongs to another service on the host).
4. The APK, sideloaded on the tablet on the tailnet, pairs with a CLI-minted token,
   shows the server version, and a Ping round-trips to `done` via `/sync`.
5. STATUS.md records the staging deployment, image tag, and secret locations.

## Operator prerequisites (outside code)

- Complete Tailscale SSH check-mode login for mini-hp01 from the deploying session
  (done once on 2026-09-15; repeats when the check expires).
- On mini-hp01: `sudo usermod -aG docker smahoney` (not yet done as of 2026-09-15),
  re-login, and `sudo mkdir -p /srv/inkwell/{staging,prod}` owned by the operator.
- Run `tailscale serve --bg --https=8444 http://127.0.0.1:8001` (staging) and
  `--https=8443 http://127.0.0.1:8000` (prod). Leave the existing 443 route alone.
- Create the Android upload keystore; store it per `.verity/deploy-access.md`; set
  the four GitHub Actions secrets.
- Install Tailscale on the tablet and sign in.

## Pipeline test: YES
