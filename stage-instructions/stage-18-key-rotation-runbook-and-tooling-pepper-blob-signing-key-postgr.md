# Stage 18: Key-rotation runbook and tooling: pepper, blob signing key, Postgres password, Anthropic key

- **Type:** chore
- **Depends on:** 17
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/39
- **Design:** ADR-0008 (peppered token hashes), ADR-0004 (signed blob URLs), ADR-0011 (backup before rotate); `server/app/security/tokens.py`, `server/app/blobs/local.py`, `server/app/config.py`

## Objectives

Every server secret gets a written, exercised rotation procedure, and the one that
is currently destructive (the token pepper: rotating it invalidates every device
token) becomes a graceful two-step. The 2026-09-16 exposure was handled by hand and
one step (the Anthropic key) was never confirmed; after this stage the runbook is
the checklist for the next time.

## What to build

**Dual-pepper grace window (server)**
- `Settings.token_pepper_previous: str | None` (env `INKWELL_TOKEN_PEPPER_PREVIOUS`).
- Alembic (additive): `tokens.hash_version INTEGER NOT NULL DEFAULT 1`.
- Verification (`security/tokens.py`, `api/deps.require_token`): look the token up by
  the current-pepper hash; on miss and when a previous pepper is set, look up by the
  previous-pepper hash; on hit, **re-hash with the current pepper in the same
  request** (update `token_hash`, bump `hash_version`) and proceed. Revoked rows
  are never resurrected. Constant-time comparisons as today.
- `inkwell token list` shows `hash_version` and `last_used_at` so the operator can
  see when every live token has migrated and unset the previous pepper.
- `inkwell token migrate-check`: exits 0 when no live token is still on the old
  version, 1 otherwise (used by the runbook and the deploy canary note).

**`deploy/rotate-db-password.sh <env>`** (host): backup (`backup.sh --label
pre-rotate`) → generate a password → `ALTER USER inkwell PASSWORD` via `docker
compose exec -T postgres psql` reading the value from stdin (never argv) → update
`POSTGRES_PASSWORD` in `.env` in place (sed on the one key) → `docker compose up -d
api worker` → health → canary. Prints nothing secret.

**`deploy/RUNBOOK-secrets.md`** — one section per secret, each with *impact*,
*precondition (backup)*, *steps*, *verification*, *rollback*:
1. `ANTHROPIC_API_KEY`: create new key in the console → edit `.env` → `up -d api
   worker` → `inkwell canary` → delete the old key in the console. Impact: none.
2. `INKWELL_BLOB_SIGNING_KEY`: edit `.env` → `up -d api worker`. Impact: signed
   URLs issued before the restart stop working (they are short-lived); the device
   re-requests. Verification: `POST /blobs` then `GET` the returned URL.
3. `INKWELL_TOKEN_PEPPER`: set `INKWELL_TOKEN_PEPPER_PREVIOUS=<old>` and
   `INKWELL_TOKEN_PEPPER=<new>` → `up -d api worker` → use the tablet once (any
   `/sync`) → `inkwell token migrate-check` returns 0 → remove
   `..._PREVIOUS` → `up -d`. Impact: none if the tablet syncs during the window;
   otherwise re-pair (`token create`, enter on the tablet). Verification: `token
   list` shows `hash_version 2`.
4. `POSTGRES_PASSWORD`: `rotate-db-password.sh <env>`. Impact: seconds of API
   downtime during restart.
5. Device tokens: `inkwell token revoke <id>` / `token create`; re-enter on the
   tablet; the staging tablet token file location (`.verity/deploy-access.md`).
6. Android upload keystore: **not rotatable** without breaking in-place upgrades;
   document the backup location (`~/.verity/secrets/inkwell-ai/`), the GitHub
   secret names, and what to do if it leaks (new app id or a key-rotation via Play
   is out of scope for sideloaded APKs).
7. **Compromise checklist**: what was exposed → rotate in the order 1, 2, 3, 4 →
   `inkwell token list` for unexpected tokens → review `/v1/usage` for unexpected
   spend → note in STATUS with the date. Include the 2026-09-16 event as the worked
   example and close its loop (confirm the Anthropic key rotation).
- Every section ends with the exact `verity status note` line to record the rotation
  (dates and names only, never values).

**`deploy/.env.example`**: `INKWELL_TOKEN_PEPPER_PREVIOUS=` with a comment.

## Interface contracts

- **Exposes:** `INKWELL_TOKEN_PEPPER_PREVIOUS`, `tokens.hash_version`, `inkwell
  token migrate-check`, `rotate-db-password.sh`, the runbook.
- **Consumes:** `device-api` bearer auth (unchanged on the wire), `backup.sh`
  (stage 17). No contract change.

## Testing requirements

- Unit: verify-with-current hits; verify-with-previous hits, re-hashes, and the
  next request hits with current only; revoked token under either pepper is 401;
  unknown token 401; no previous pepper set → single lookup; `hash_version`
  bumps; `migrate-check` exit codes.
- `shellcheck` on `rotate-db-password.sh`; a CI test that runs it against the
  ephemeral stack from stage 17's gate and proves health afterwards.
- Manual, part of exit state: on staging, rotate the blob signing key and the
  pepper (with the grace window; the tablet keeps working without re-pairing),
  recorded in STATUS and in the runbook's results table.

## Acceptance conditions

- [ ] Clear exit-state defined: runbook merged with all seven sections; pepper rotation exercised on staging with the tablet uninterrupted and `migrate-check` green; blob key rotated on staging; the 2026-09-16 Anthropic key rotation confirmed or performed and noted in STATUS
- [ ] Additive migration only (`tokens.hash_version` with default)
- [ ] Existing suite stays green; CI all-green

## Pipeline test: NO
