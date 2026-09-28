# Stage 37: Fix: server starts with default or empty secrets outside dev; unauthenticated OpenAPI docs exposed in deployment

- **Type:** bug
- **Depends on:** (none)
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/79
- **Design:** `docs/revisit/2026-09-24-revisit.md` proposal 2; ADR-0008 (peppered bearer
  tokens; `config.py:17` "Override in every env"); ADR-0004 (HMAC-signed blob URLs);
  `feature-assessments/fail-closed-secrets-assessment.md`.
- **Code:** `server/app/config.py:17-27,104`, `server/app/main.py:37`, `server/app/worker.py`,
  `server/app/cli.py`, `deploy/compose.yml:8-12`, `deploy/remote-deploy.sh:50-55`,
  `tests/ops/*.sh`, `server/tests/conftest.py`.

## Objectives

The server silently runs with insecure secrets in a deployed environment:
- `Settings.token_pepper` defaults to `"dev-pepper-change-me"` and `blob_signing_key` to
  `"dev-blob-key-change-me"`.
- Compose passes `${INKWELL_TOKEN_PEPPER}` and `${INKWELL_BLOB_SIGNING_KEY}` straight
  through, so an unset variable becomes an **empty string** and the server starts anyway.
  An empty or default pepper weakens every device token hash (ADR-0008). A default blob
  key lets anyone who can read the repo forge signed blob URLs (ADR-0004).

FastAPI's `/docs`, `/redoc` and `/openapi.json` are also served unauthenticated. Only the
tailnet stands in front of them.

`Settings.env` (`INKWELL_ENV`) already exists (default `"dev"`, `config.py:104`), and
compose sets it (default `staging`), but **nothing reads it**. After this stage:
- **Outside `dev`, the server refuses to start** unless both secrets are set,
  non-default and strong enough. This covers the API, the worker and the CLI.
- **The OpenAPI and docs routes are off outside `dev`.**
- A misconfigured deploy aborts **before** the container swap, because `remote-deploy.sh`
  runs `inkwell db seed` with the new image first.

## What to build

1. **Validation** at `Settings` construction, for example a pydantic `model_validator`.
   When `env` is not `dev`:
   - `token_pepper` and `blob_signing_key` must each be **≥ 32 characters**, and must not
     be the dev default or empty/whitespace;
   - the two must differ;
   - if `token_pepper_previous` is set (non-empty), it must meet the same rule and
     differ from `token_pepper`.

   Failures raise a clear error naming the **variable** and the **rule**, never the value.

   Allowed `env` values are `dev`, `staging` and `prod`. An unknown value is rejected
   (typo safety).

   Staging today has `INKWELL_ENV=staging` and 64-character secrets, so it passes
   unchanged. This was verified on the host on 2026-09-28 (lengths only).
2. **Every entry point fails closed.** The API (`create_app`), the worker (`app.worker`)
   and the CLI (`inkwell …`, including `db seed` and `token` commands) all load settings
   and so fail fast. Add an explicit **`inkwell config check`** CLI command: exit 0 with
   "config ok (env=…)", or non-zero with the failing rule. Do not import settings into
   Alembic's `env.py`.
3. **Pre-swap abort.** In `deploy/remote-deploy.sh`, run `inkwell config check` with the
   new image **before** migrate. On failure, print the rule and abort with the old
   containers still running. Keep the script's `shellcheck` clean; there is a gate for it.
4. **Docs routes:** `FastAPI(docs_url=None, redoc_url=None, openapi_url=None)` when
   `env != "dev"`; unchanged in dev. None of these routes is part of any frozen contract.
5. **Test and ops fixtures:**
   - `server/tests` run as `dev` and are unaffected. Add tests that construct `Settings`
     under `staging` and `prod`.
   - `tests/ops/backup_restore_test.sh` and `tests/ops/rotate_db_password_test.sh` use
     `INKWELL_ENV=staging` with 15-character secrets. Raise them to ≥ 32-character
     throwaway values so they keep passing.
   - The CI `image-contracts` gate runs the image without `INKWELL_ENV`. It must keep
     passing, which it does if `app.contracts check` doesn't require deployment secrets
     or runs as `dev`; verify this.
6. **Docs:** in `deploy/README.md` and `deploy/RUNBOOK-secrets.md`, state the rule (≥ 32
   characters, non-default, distinct) and the new `inkwell config check`. Keep it short.

**Not in scope:** compose-level `${VAR:?}` guards. The CI `compose-config` gate renders
`deploy/compose.yml` without a `.env`, so they would break it; the app-level check covers
the same failure.

## Interface contracts

- **Exposes:** nothing new on the wire. `/v1/health` and every `device-api` route are
  unchanged. `/docs`, `/redoc` and `/openapi.json` were never contract routes.
- **Consumes:** ADR-0008 and ADR-0004 (secrets); no contract change and no migration.

## Testing requirements

- **Regression (must fail before the fix):**
  - `Settings` under `INKWELL_ENV=staging` with an **empty** pepper, the **default**
    pepper, the **default** blob key, or a 31-character secret raises, with the variable
    named and the value absent from the message;
  - `create_app()` under `staging` with a missing secret raises;
  - under `staging` with valid secrets, `/openapi.json` and `/docs` return 404 and
    `/v1/health` returns 200.
- **Also:** an unknown env is rejected; `token_pepper_previous` equal to the pepper is
  rejected; dev with the defaults still works (the existing suite).
- **Ops:** the `backup-restore` and `rotate-db-password` gates pass with the updated
  fixtures; `inkwell config check` exits non-zero on a bad `.env` inside the ops test
  stack.
- **UI-smoke (operator, post-deploy):** add to `smoke/server-usage.md`, or a new
  `smoke/fail-closed-secrets.md`:
  - on staging, `curl …/openapi.json` returns 404;
  - `docker compose run --rm api inkwell config check` prints `config ok (env=staging)`;
  - optionally, a dry run against a scratch `.env` with the pepper blanked fails with the
    variable named.

## Acceptance conditions

- [ ] Regression test(s) that fail before the fix and pass after
- [ ] Outside `dev`: empty, default, short or duplicate secrets refuse to start (API, worker, CLI); the error names the variable, never the value
- [ ] `inkwell config check` exists and `remote-deploy.sh` runs it before migrate (a bad config aborts pre-swap)
- [ ] `/docs`, `/redoc`, `/openapi.json` return 404 outside `dev`
- [ ] Existing suite stays green (server, ops gates, image gates); CI all-green

## Pipeline test: NO
