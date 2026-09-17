# Stage 17: Backups: nightly pg_dump + blobs snapshot on the host, retention, off-host copy, tested restore

- **Type:** chore
- **Depends on:** —
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/38
- **Design:** ADR-0011 (backups), ADR-0004 (blobs on a volume), ADR-0005 (host, deploy scripts); `deploy/compose.yml` volumes `pgdata`, `blobs`

## Objectives

Make the staging data survivable and every deploy reversible with data. After this
stage a timer takes a consistent nightly backup on mini-hp01, every deploy takes one
before migrating, an optional encrypted copy leaves the host, and a restore has been
proven both in CI and once on the host.

## What to build

**`deploy/backup.sh <env> [--label <text>]`** (host side, bash, `set -euo pipefail`,
run from `/srv/inkwell/<env>`, no host Postgres client):
- `docker compose -f compose.yml exec -T postgres pg_dump -U inkwell -Fc inkwell >
  db.dump` (credentials from the container env; never echo `.env`).
- Blobs: `docker run --rm -v <project>_blobs:/data:ro -v <set>:/out alpine tar -C
  /data -cf - . | zstd` → `blobs.tar.zst` (use the compose project's real volume name
  via `docker compose ... config --volumes`? **No** — `compose config` prints secrets
  (STATUS rule). Derive as `<compose project>_blobs` from `docker compose ls` or
  `docker volume ls --filter label=com.docker.compose.project=<env>`).
- `manifest.json`: env, UTC timestamp, label, `IMAGE_REF` from `.env` (grep the one
  key; never dump the file), alembic head (`docker compose exec -T api alembic
  current`), sizes, sha256 of both files, duration, `remote_copied: bool`.
- Set dir: `/srv/inkwell/backups/<env>/<YYYYMMDDTHHMMSSZ>[-<label>]/`, mode 700,
  owner = operator. `latest` symlink updated on success.
- Retention after a successful run: keep 14 most recent nightly sets and 8 most
  recent Sunday sets; `--label` sets (pre-deploy, manual) are kept 30 days.
- Off-host: if `BACKUP_REMOTE` is set in `.env` (an rclone remote path such as
  `r2:inkwell-backups/staging` or `gdrive:inkwell/staging`), tar the set, encrypt
  with `age -r "$BACKUP_AGE_RECIPIENT"`, `rclone copy` it, then `rclone check`.
  Failure → exit 2 and `.remote-failed` marker in the set; the local set stays.
- Exit codes: 0 ok, 1 local failure (nothing pruned), 2 local ok / remote failed.

**`deploy/restore.sh <backup-dir> [--into <project>] [--yes]`**
- Default target is a **scratch** compose project `inkwell-restore-<ts>` on a free
  port with its own volumes, built from the same `compose.yml` and `.env` minus
  `API_PORT`; `--into staging` restores over the live stack and requires `--yes`
  plus a fresh pre-restore backup (`backup.sh --label pre-restore`).
- Steps: verify sha256s against the manifest → start postgres → `pg_restore
  --clean --if-exists` → extract blobs into the volume → `alembic upgrade head`
  (proves the dump is at or behind the code) → start api → poll `/v1/health` →
  print row counts for `spaces, jobs, cards, canvases, tokens` and blob file count
  → for scratch restores, tear down with `--keep` to leave it running.

**Deploy integration (`deploy/remote-deploy.sh`)**
- Before `>> migrate`: `backup.sh <env> --label pre-deploy`; a non-zero local
  result aborts the deploy; remote failure (exit 2) warns and continues.
- After health: warn if `latest` is older than 48 h (should never be, timer).

**Schedule and host prerequisites**
- `deploy/systemd/inkwell-backup@.service` + `inkwell-backup@.timer`
  (`OnCalendar=*-*-* 03:30:00 UTC`, `Persistent=true`, `RandomizedDelaySec=10m`),
  installed and enabled for `staging` by `host-setup.sh` (also installs `zstd`,
  `age`, `rclone` via apt). Failures are visible in `journalctl -u
  inkwell-backup@staging` and the timer's last-result.

**Config and docs**
- `deploy/.env.example`: `BACKUP_REMOTE=` (empty = local only),
  `BACKUP_AGE_RECIPIENT=` (age public key), comments. `.verity/deploy-access.md`
  gets a section: where the age private key lives (operator machine), the remote's
  credential location (`rclone.conf` on the host, mode 600) — locations only.
- `deploy/README.md`: "Backups and restore" section with the three commands, the
  retention policy, and the restore-drill cadence (every minor release).
- `smoke/backup-restore.md`: the host drill checklist (run `backup.sh`, run a
  scratch `restore.sh`, compare counts against the live `/v1/spaces` and `/v1/usage`,
  tear down) with a results table.

**CI gate `backup-restore`** (`.verity/gates.json`, `ci.yml`): `tests/ops/
backup_restore_test.sh` brings up an ephemeral compose stack from `deploy/compose.yml`
with the `inkwell-ai-server:ci` image (built by the `image-build` gate), seeds spaces
and mints a token, writes one blob through `POST /blobs`, runs `backup.sh`, destroys
the stack, runs `restore.sh` into a fresh project, and asserts 4 spaces, 1 token,
1 blob file, health ok. Runs in under three minutes.

## Interface contracts

- **Exposes:** `backup.sh`, `restore.sh`, the backup set layout + `manifest.json`,
  `BACKUP_REMOTE` / `BACKUP_AGE_RECIPIENT`, the systemd timer.
- **Consumes:** `deploy/compose.yml` volume names; `inkwell` CLI (`db seed`,
  `token create`) for the CI dataset; nothing in the frozen contracts.

## Testing requirements

- CI gate above (the regression test for both scripts).
- `shellcheck` on the new scripts (add to the gate command).
- Manual, part of exit state: one timer run on staging and one scratch restore drill
  on the host, both recorded in `smoke/backup-restore.md` and STATUS.

## Acceptance conditions

- [ ] Clear exit-state defined: timer enabled on staging with one successful run (`journalctl` shows it), `latest` symlink present; a scratch restore on the host reproduced the live counts (logged in `smoke/backup-restore.md`); `remote-deploy.sh` takes a pre-deploy backup; `backup-restore` CI gate green; secrets never printed (no `compose config`, no `cat .env`)
- [ ] Off-host copy is opt-in and documented; when configured, `rclone check` passes once
- [ ] Existing suite stays green; CI all-green

## Pipeline test: NO
