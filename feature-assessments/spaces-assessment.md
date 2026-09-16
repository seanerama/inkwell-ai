# Intake assessment — Phase 3: Spaces

- **Date:** 2026-09-16
- **Request:** owner, after v0.0.12 (library + formalize verified on the tablet):
  "plan phase 3 spaces".
- **Decision:** ACCEPT as three stages, 13 (server) → 14 (tabs, mirror, move, send)
  → 15 (on-device editing and creation), milestone `v0.4 — spaces`. One ADR
  (0010: spaces are server-owned; the device mirrors them by server id). No new
  contract; one dated additive section on `device-api`.

## Claim / reality verification (main @ v0.0.12)

| Claim | Reality | Effect |
|---|---|---|
| Spaces exist on the server with per-space prompts | `spaces` table has `system_prompt`, `tools`, `model`, `color`, `position`; the seeder inserts four rows with **empty prompts**; the worker already builds the system prompt from `space.system_prompt` and uses `space.model` | The Phase 3 acceptance ("Work vs Learning differ") is a **data** gap first: stage 13 seeds distinct prompts and backfills empty ones on deploy |
| The contract has space editing routes | `device-api` route table lists `POST /spaces` and `PATCH /spaces/{id}`; the server implements **only `GET`** | Stage 13 implements both without touching the table; bodies documented in a dated additive section |
| The device has a spaces model | Room `spaces` table mirrors the server shape, but the device **seeds its own `Work`** with a local uuid and files all canvases/folders under it; sends look the server's `work` up by slug; formalize stores its canvas under the **server** id | Two id systems. ADR-0010: server ids are canonical; stage 14 reconciles the placeholder onto the server's `work` space in one transaction (no Room migration) |
| Per-space tool sets (SPEC Phase 3) | No tool runtime exists: the agent call has no `tools` parameter; `tools` is stored and never read | **Deferred.** `tools` stays mirrored data; the §11 allow-list is implemented with the first tool runtime (Phase 5 brain or the Nightshift bridge, #18). Acceptance does not depend on it |
| Canvas grid per space (SPEC §9.3) | Stage 11's Library is already per-space by construction (`LibraryRepository` takes a `spaceId`) | Stage 14 adds only the tab bar and the id source |
| Agent marks use the space accent (SPEC §6.3) | Renderer supports an accent, but `CanvasViewModel.accentColor` is hard-coded to `DEFAULT_ACCENT` | Stage 14 wires the space colour |
| Move canvas between spaces (SPEC Phase 3) | Move… targets folders in one space only; folders are per space (stage 11 decision) | Stage 14: moving to another space lands at that space's root; folders move as a subtree |

Latent defect found during verification: a formalized canvas is stored under the
server's space id while the Library lists by the local placeholder id. It may
disappear from the Library after a restart on v0.0.12. Stage 14's reconciliation
fixes it as a side effect; no separate bug stage.

## Contract safety

- `device-api`: frozen route table unchanged. Stage 13 adds a dated additive section
  with the `POST`/`PATCH /spaces` bodies, the `409 conflict` and `403 disabled`
  codes, and the immutable-slug rule.
- `agent-output`, `coordinate-mapping`: untouched.
- `ink-storage`: stays at v3. Reconciliation is a transactional data rewrite, not a
  schema change; the stage must prove ink survives.
- Alembic: no migration; every column already exists.

## Split

| Stage | Side | Why separate |
|---|---|---|
| 13 | server | Makes the four agents different and editable; independently smoke-able with curl; the tablet needs nothing from it to build |
| 14 | client | The acceptance test lives here; the id reconciliation is the risky part and deserves its own review |
| 15 | client | Editing UI is pure surface on top of 13 + 14; keeps 14 reviewable |

## Kill-switches

`SPACES_EDITABLE` (server, default off; the operator sets it on staging at ship
time), `BuildConfig.SPACES` and `BuildConfig.SPACE_SETTINGS` (client; release-ON by
the documented single-environment exception, same as `LIBRARY`/`FORMALIZE`).

## Deferred / re-offered

- Per-space tool allow-list: with the first tool runtime.
- `DELETE /spaces`: not in the contract; would orphan canvases and a brain namespace.
- Drag-to-reorder tabs: "Move left/right" in stage 15 is enough for four to six tabs.
- Per-space job presets on the Send picker (SPEC §9.4): after stage 15, once
  prompts are edited in practice.
- ADR-0009 re-offer: a `help` space becomes possible after stage 15; the Architect
  re-offers `helper-bot` then.
- Nightshift v3 bridge (#18): unchanged; a Nightshift role maps naturally onto a
  space once tools exist.
