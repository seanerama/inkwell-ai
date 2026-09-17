# Intake assessment — Ops hygiene: backups and key rotation

- **Date:** 2026-09-17
- **Request:** owner, after v0.0.13 was verified on the tablet: "go ahead with your
  recommendation" (ops hygiene before Phase 4).
- **Decision:** ACCEPT as two chores, 17 (backups with a tested restore) → 18
  (key-rotation runbook and tooling), milestone `v0.5 — ops hygiene`. One ADR
  (0011). No contract change; one additive Alembic migration in stage 18.

## Claim / reality verification (main @ v0.0.13)

| Claim | Reality | Effect |
|---|---|---|
| State on the host is worth protecting | `pgdata` (spaces with edited prompts, jobs, cards, tokens, agent canvases) and `blobs` (`/data/blobs`, canvas exports) are Docker named volumes in `deploy/compose.yml`; no backup script, timer or doc exists (`overview.md` lists it as item 9 of the leftovers) | Stage 17 |
| Deploys are safe to roll back | Migrations are additive-only and `.env.previous` records the last image, but nothing snapshots the data before `migrate` | Stage 17 adds a pre-deploy backup and a deploy guard |
| A restore has ever been tried | Never | Stage 17 makes restore a script, CI-tests it, and requires one host drill |
| Secrets can be rotated | Four server secrets (`POSTGRES_PASSWORD`, `INKWELL_TOKEN_PEPPER`, `INKWELL_BLOB_SIGNING_KEY`, `ANTHROPIC_API_KEY`) plus the Android keystore. The 2026-09-16 exposure was handled by hand: three rotated on the host, volumes wiped, `ANTHROPIC_API_KEY` rotation asked of the owner and never confirmed. ADR-0008 says rotating the pepper "invalidates all tokens (documented)" but nothing is documented | Stage 18 writes the runbook and adds a previous-pepper grace window so pepper rotation no longer means re-pairing the tablet |
| Tooling exists | `inkwell token create/revoke/list`, `inkwell db seed`, `inkwell canary` | Stage 18 adds `token list` hash-version visibility and a `rotate-db-password.sh`; nothing else new |
| Host tooling | Unknown this session (Tailscale SSH check expired mid-inspection). Assume no `rclone`/`age` on the host; the stage installs them in `host-setup.sh` | Stage 17 |

## Contract safety

- No contract touched. Stage 18's `tokens.hash_version` column is additive
  (Alembic, default 1).
- Kill-switches are not applicable to chores; the off-host copy is opt-in by
  configuration (`BACKUP_REMOTE` unset = local only).

## Split

| Stage | Why separate |
|---|---|
| 17 | Backups are a precondition for rotating anything; independently testable in CI |
| 18 | Rotation touches auth code (dual pepper) and deserves its own review |

## Deferred

- Point-in-time recovery (WAL): not at this data size.
- Backup of the tablet's Room database: ink is device-truth by design (SPEC §3);
  Phase 4 introduces canvas sync, which is the real answer. Until then the owner
  can rely on Android's app backup being off for this app (Room + keystore).
- Monitoring/alerting beyond systemd failure logs: revisit with prod promotion.
