# Stage 20: Fix: pepper grace window is dead in deployment (compose does not pass INKWELL_TOKEN_PEPPER_PREVIOUS) and migrate-check false-greens

- **Type:** bug
- **Depends on:** 18
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/43
- **Design:** ADR-0008, stage 18 (dual-pepper grace window); `deploy/compose.yml`, `server/app/cli.py` (`token migrate-check`), `deploy/RUNBOOK-secrets.md` §3

## Objectives

Runbook §3 was exercised on staging on 2026-09-17 and failed: the tablet token was
refused during the "grace window", `migrate-check` reported green with the token
still on the old pepper, and closing the window left the device locked out until the
operator restored the pepper from a `.env` backup. After this stage a pepper rotation
on the real deployment keeps the tablet working without re-pairing, `migrate-check`
cannot say "safe to close" while a live token has not migrated, and the bug class
(an env key that never reaches the containers) is caught by CI.

## What to build

- **Compose passthrough:** add `INKWELL_TOKEN_PEPPER_PREVIOUS:
  ${INKWELL_TOKEN_PEPPER_PREVIOUS:-}` to the `app-env` anchor in `deploy/compose.yml`.
- **Guard the bug class:** a CI check (script under `tests/ops/`, wired into
  `.verity/gates.json` and `ci.yml`) asserting every non-comment key in
  `deploy/.env.example` that starts with `INKWELL_`, `AGENT_`, `SPACES_` or
  `BACKUP_` is referenced in `deploy/compose.yml` (allow-list the compose-only keys
  such as `IMAGE_REF`, `API_PORT`, `POSTGRES_PASSWORD`). This has now bitten twice
  (`AGENT_ENABLED` on 2026-09-16, the previous pepper on 2026-09-17).
- **`migrate-check` fails closed.** Replace the MAX-based target with an explicit
  generation: `inkwell token rotate-pepper --begin` records `pepper_generation + 1`
  in a small `app_meta` table (additive Alembic migration) and prints the runbook's
  next steps; `verify_token` stamps a re-hashed row with the current generation;
  `migrate-check` exits 1 while `INKWELL_TOKEN_PEPPER_PREVIOUS` is set **and** any
  live token's `hash_version < current generation`, exits 0 otherwise; `--end`
  refuses while `migrate-check` is red and otherwise tells the operator to clear the
  previous pepper. Keep the CLI output free of secret values.
- **Runbook §3:** remove the "DO NOT RUN" banner added by the operator once the fix
  ships; rewrite the steps around `rotate-pepper --begin/--end`; add the "window
  already closed" rollback (restore `INKWELL_TOKEN_PEPPER` from `.env.bak-<ts>`, or
  re-pair) and the pre-step "copy `.env` to `.env.bak-<ts>` (mode 600)".
- **Exit state:** exercise §3 on staging end to end (the operator's curl with the
  tablet token, or the tablet syncing, migrates the row to the new generation;
  `migrate-check` goes red → green; window closed; token still 200; canary ok) and
  record it in the runbook results and STATUS.

## Interface contracts

- **Exposes:** `inkwell token rotate-pepper --begin|--end`; `app_meta` table;
  compose passthrough; the env/compose parity gate.
- **Consumes:** `device-api` bearer auth (unchanged on the wire); stage 18's
  `hash_version` column.

## Testing requirements

- Unit: with previous pepper set, a token hashed under the old pepper verifies and is
  re-hashed with the current generation; `migrate-check` is red before that request
  and green after; `--end` refuses while red; no previous pepper → single lookup.
- Ops gate: env/compose parity script runs in CI and fails on a deliberately missing
  key (test the test).
- Manual exit-state drill on staging as above.

## Acceptance conditions

- [ ] Regression test in place: unit tests for the generation-based `migrate-check` and a CI parity gate for env keys vs compose
- [ ] Additive migration only (`app_meta`)
- [ ] Runbook §3 re-exercised on staging with the tablet uninterrupted; banner removed
- [ ] Existing suite stays green; CI all-green

## Pipeline test: NO
