# Stage 19: Fix: restore.sh default scratch project name is rejected by Docker Compose (uppercase timestamp)

- **Type:** bug
- **Depends on:** 17
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/42
- **Design:** ADR-0011; `deploy/restore.sh`, `tests/ops/backup_restore_test.sh`, `deploy/README.md`

## Objectives

The v0.0.14 host restore drill failed on the documented default invocation
(`restore.sh <set>` with no `--into`): the generated project name
`inkwell-restore-20260917T170951Z` contains uppercase letters and Docker Compose
rejects it. The drill only passed with `--into inkwell-restore-drill`. After this
stage the default path works and is covered by the CI gate, and a scratch restore
can be torn down with one documented command.

## What to build

- `deploy/restore.sh`: derive the default project name in lowercase (e.g.
  `inkwell-restore-$(date -u +%Y%m%d-%H%M%S)`); validate the final name against
  Compose's rule (`^[a-z0-9][a-z0-9_-]*$`) and fail early with a clear message for
  a bad `--into` value too.
- Print (and document in `deploy/README.md`) a **label-based teardown** that does not
  depend on the temporary compose file the script deletes:
  `docker ps -aq --filter label=com.docker.compose.project=<p> | xargs -r docker rm -f`
  plus the matching `docker volume ls` / `docker network ls` filters. Optionally
  add `restore.sh --teardown <project>` that does exactly this.
- `tests/ops/backup_restore_test.sh`: exercise the **default** project name path once
  (no `--into`), assert health, then tear down by label. Keep the gate under three
  minutes.
- **Runbook §2 verification is unexecutable** (found the same day): it verifies a
  blob-key rotation with `POST /v1/blobs` → `GET <url>`, but neither blob route is
  implemented on the server (`POST /v1/blobs` → 404; `signed_url` exists in
  `blobs/local.py` with no route serving it). Replace the verification with what is
  observable today: `dc up -d api worker` → `/v1/health` → `inkwell canary` (exports
  still store and the job runs), and state plainly that signed URLs are not yet
  served, so the key has no user-visible effect until the blob routes ship.
- Optional, cheap: `restore.sh --keep`/no-keep already implied by "left running";
  leave the behaviour, just make teardown one command.

## Interface contracts

- **Exposes:** nothing new (a working default `restore.sh`).
- **Consumes:** ADR-0011 backup set layout.

## Testing requirements

- CI gate `backup-restore` covers the default project name path and the teardown.
- `shellcheck` still clean.

## Acceptance conditions

- [ ] Regression test in place: the CI gate runs `restore.sh <set>` without `--into` and passes
- [ ] Teardown documented and tested by label
- [ ] Runbook §2 verification replaced with steps that work on the current server
- [ ] Existing suite stays green; CI all-green

## Pipeline test: NO
