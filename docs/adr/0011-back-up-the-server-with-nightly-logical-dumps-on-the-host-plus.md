# 0011. Back up the server with nightly logical dumps on the host plus an rclone off-host copy

- **Status:** Accepted
- **Date:** 2026-09-17

## Context

Staging on mini-hp01 is the only environment and now holds state the owner would
mind losing: the four spaces with hand-edited system prompts, agent-origin canvases,
jobs and cards history, device tokens (`pgdata` volume), and every canvas export
(`blobs` volume, ADR-0004). Nothing is backed up. ADR-0004 explicitly left recovery
to "the SRE plan". The host is a single mini PC with one disk; a deploy runs
additive migrations against the live database with no snapshot first. Phase 4 will
add agent-pushed PDFs and canvases, which raises the stakes.

Data volume is tiny (one user, a few hundred jobs), so simplicity beats throughput.

## Decision

1. **Logical, consistent, nightly.** A host-side script runs `pg_dump -Fc` *inside*
   the running `postgres` container (no Postgres client on the host) and archives
   the `blobs` volume with a throwaway container (`tar` + `zstd`). Both land in
   `/srv/inkwell/backups/<env>/<UTC-timestamp>/` with a `manifest.json` (image
   ref, alembic head, sizes, sha256s, duration).
2. **Retention on the host:** keep the last 14 nightly and the last 8 weekly
   (Sunday) sets; prune after a successful run only.
3. **Pre-deploy backup.** `remote-deploy.sh` takes a backup *before* `migrate`; a
   deploy refuses to proceed if that backup fails. Rollback therefore always has a
   matching snapshot.
4. **Off-host copy, optional but expected.** If `BACKUP_REMOTE` (an rclone remote
   path) is set in `.env`, each set is encrypted with `age` to the operator's
   public key (`BACKUP_AGE_RECIPIENT`) and copied with `rclone copy`. The private
   key never lives on the host. The local backup succeeds even when the copy fails;
   the copy failure is loud (non-zero exit, marker file).
5. **Restore is a script, and it is tested.** `deploy/restore.sh <backup-dir>
   [--into <compose-project>]` rebuilds Postgres and blobs from a set into a
   *scratch* compose project by default, runs migrations to prove the dump is at or
   behind the code, and prints row counts. The same script runs in CI against an
   ephemeral stack with seeded data (`backup-restore` gate). A restore drill on the
   host is part of the stage's exit state and is repeated on every minor release.
6. **Schedule:** a systemd timer per environment (`inkwell-backup@<env>.timer`,
   03:30 UTC), installed by `host-setup.sh`.

## Alternatives considered

- **`pg_basebackup` + WAL archiving.** Point-in-time recovery for a single-user
  app with minutes of nightly data at risk is not worth a second storage path.
- **restic.** A fine tool, but adds a binary and a repository format; rclone
  already covers the owner's likely targets (Cloudflare R2, Google Drive, another
  machine over Tailscale) and `age` gives the encryption restic would.
- **Raw copies of the Docker volumes.** Inconsistent while Postgres is running;
  logical dumps are portable across Postgres minor versions and image rebuilds.
- **Storing blobs in Postgres to get one dump.** Rejected in ADR-0004.
- **A managed Postgres.** Contradicts ADR-0005's choice to run on the owner's
  hardware over Tailscale.

## Consequences

- Host disk use is bounded by retention; the manifest makes each set self-describing.
- Every deploy gets slower by one small dump; acceptable at this data size.
- Stage 18's rotation runbook can require "backup first" as a precondition.
- The off-host target is the operator's choice and is recorded in
  `.verity/deploy-access.md` as a location, not a credential.
- When prod is promoted it inherits the same timer and scripts unchanged.
