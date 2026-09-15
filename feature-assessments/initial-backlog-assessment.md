# Intake assessment — initial backlog (Mode A)

- **Date:** 2026-09-15
- **Source:** Architect handoff (`docs/architecture/overview.md`,
  `docs/architecture/walking-skeleton.md`, ADR-0001..0009, four frozen contracts) and
  SPEC.md §12 build phases.
- **Decision:** ACCEPT as six dependency-ordered stages covering the walking skeleton
  and SPEC Phases 0–1 (milestone `v0.1 — the loop`). Phases 2–5 are DEFERRED to later
  intake so the backlog stays thin and the loop's lessons (SPEC §13 open questions)
  can feed them.

## Claim / reality verification against the live repository

| Claim (from architecture or SPEC) | Reality (checked 2026-09-15) | Effect on plan |
|---|---|---|
| Repo is a fresh scaffold with no application code | True: only `.verity/`, `.github/`, SPEC.md, README, LICENSE, STATUS, plus the Architect's `docs/` and `contracts/` (uncommitted) | Stages 1 and 2 create `server/` and `android/` from nothing; no retrofit |
| CI currently fails by design until `.verity/gates.json` exists | True: the scaffold's only run (`35002817909`) failed in the `gates` job | Stage 1 owns `gates.json`; Stage 2 appends Android gates |
| `verity adr list` / `contract list` reflect the design | True: 9 ADRs, 4 contracts; `agent-output` has a JSON Schema and 8 fixtures that validate as documented | Stage 1's contract check consumes them unchanged |
| mini-hp01 has Docker and Tailscale and is reachable | True (SSH inspection): Docker 29.7, Compose 5.5, Tailscale 1.102 | Deploy target stands |
| Port 443 free on mini-hp01 for `tailscale serve` | **False**: 443 already proxies to `127.0.0.1:4317` | Ports moved to 8443 (prod) / 8444 (staging); ADR-0005 and specs updated |
| Operator can run Docker on mini-hp01 | **False today**: not in `docker` group; `/srv/inkwell` absent | Listed as Stage 1 operator prerequisites, not code |
| GitHub repo exists for work-items | True: `seanerama/inkwell-ai`, public, default `main`; labels `feature`/`chore`/`stage` and milestone `v0.1 — the loop` created by this intake | Issues link stage ↔ spec ↔ future PR |
| Structured outputs and the `anthropic` SDK cover ADR-0006 | Not verifiable from the repo (no code yet); the `claude-api` skill documents `output_config.format` and a parse helper | Stage 5 requires the builder to read the SDK docs before writing `agent/client.py` |
| Expo/EAS pipeline applies to the client | **False**: the client is native Kotlin | New catalog method `gradle-github-releases` (Stage 2) |

## Impact and contract safety

- No stage edits a frozen contract. Stage 1 introduces one **internal** job type,
  `system.ping`, on `POST /jobs`; it is additive (a new accepted `type` value) and is
  documented as internal in the spec. No new contract is needed.
- Stage 5 adds nullable columns to `jobs` (additive migration).
- Stage 2 may fall back from generated to hand-written contract classes; ADR-0007
  already permits this with the fixture tests as the guard.
- Nothing in these six stages is architecture-affecting beyond what the ADRs record,
  so no new ADR and no confirm-gate is required.

## Split rationale

The Architect's walking skeleton was one stage. It is split into Stage 1 (server,
image, CI, staging deploy) and Stage 2 (Android, APK release, ping round-trip) so
each PR is reviewable and the server half can deploy while the Android toolchain is
being set up. The spine is proven at the end of Stage 2. Stage 5 (server agent
runtime) depends only on Stage 1 so it can proceed in parallel with the device
stages 3–4; Stage 6 joins them.

## Deferred (next intake, after Stage 6 passes on the tablet)

- SPEC Phase 2: full annotation vocabulary, cards with actions, layer tray, job-type
  picker.
- SPEC Phase 3: spaces UI, per-space prompts and tools, move canvas.
- SPEC Phase 4: `to_user` jobs and raster layers.
- SPEC Phase 5: brain retrieval and writes.
- Ops: backups for `pgdata` and `blobs`, token-pepper rotation runbook, `S3BlobStore`
  + Coolify promotion.
- `helper-bot` catalog feature (ADR-0009): re-offer after Phase 3.
- SPEC §13 open questions 1 (canvas size) and 2 (agent ink style): review after Stage 6.

## Rejected

None.
