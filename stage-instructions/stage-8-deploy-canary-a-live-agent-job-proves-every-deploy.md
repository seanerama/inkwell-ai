# Stage 8: Deploy canary: a live agent job proves every deploy

- **Type:** chore
- **Depends on:** 7
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/21
- **Design:** ADR-0006 amendment (2026-09-16), ADR-0005; contract `device-api` (jobs), `agent-output`

## Objectives

v0.0.8 shipped an agent that failed on its first real call because every test used a
recorded client. After this stage, every deploy with the agent enabled runs one real
`canvas.ask` job through the live worker and the live Anthropic API against a bundled
fixture note, and the deploy fails loudly if it does not come back `done` with at
least one annotation. No API key enters GitHub; the canary runs on the host with the
host's key.

## What to build

- `server/app/canary/fixture.png`: a rendered (not handwritten) 1109×1568 PNG of the
  text "what is 1+9=?" produced by a committed generator script
  (`server/scripts/make_canary_fixture.py`, Pillow as a dev dependency) so it is
  reproducible; commit the PNG.
- CLI `inkwell canary [--space work] [--timeout 90]`: creates a `canvas.ask` job for
  the seeded space directly in the database (no device token), stores the fixture in
  the blob store, waits for the worker to finish it, prints status, summary, the
  annotation types, clamp count and tokens, and exits 0 only on `done` with ≥1
  annotation and ≥1 card. Exit 2 on `failed` (printing the error), 3 on timeout.
  Marks the job `request.canary = true` so `/usage` can exclude it.
- `deploy/remote-deploy.sh`: after the health poll, if `AGENT_ENABLED=true` in the
  env, run `docker compose run --rm -T api inkwell canary` and fail the deploy on a
  nonzero exit (the previous ref is already printed for rollback).
- `GET /usage` excludes canary jobs (`request.canary`) from the counts.
- `.verity/smoke.json`: unchanged (browser smoke stays server-health only).

## Interface contracts

- **Exposes:** `inkwell canary`; the deploy gate.
- **Consumes:** contract `device-api` job shapes (unchanged), `agent-output`. No
  contract change.

## Testing requirements

- pytest: canary job creation stores the fixture and marks `request.canary`; with the
  recorded client it reaches `done` and the CLI exits 0; with a failing fake it exits 2
  with the error printed; timeout path exits 3; `/usage` excludes canary jobs.
- The fixture generator is deterministic (same bytes on two runs).
- `tests/live/test_real_call.py` is rewritten to drive `inkwell canary` end to end and
  stays opt-in (`INKWELL_LIVE_TESTS=1`); the Tester runs it before Phase 2 merges.

## Acceptance conditions

- [ ] Clear exit-state defined (what "done" means here): `./deploy/deploy.sh staging
      <digest>` prints `>> canary ok: done, N annotations, M cards` and exits 0; with
      `ANTHROPIC_API_KEY` deliberately broken on the host it exits nonzero after the
      health check and names the canary as the failing step.
- [ ] Canary jobs do not appear in `/usage`.
- [ ] Existing suite stays green; CI all-green

## Pipeline test: YES
