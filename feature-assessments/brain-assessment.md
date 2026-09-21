# Intake assessment — Phase 5: Brain

- **Date:** 2026-09-21
- **Request:** owner, after Phase 4 acceptance: "plan phase 5" (owner takes stage 24
  personally).
- **Decision:** ACCEPT as three stages, 25 (store + routes) → 26 (recall + tools +
  extract) → 27 (device view + Remember), milestone `v0.7 — brain`. One ADR (0013).
  No contract change; the three frozen brain routes are implemented; two dated
  additive sections; one additive Alembic migration; no Room change.

## Claim / reality verification (main @ v0.0.16)

| Claim | Reality | Effect |
|---|---|---|
| The model already writes to the brain | `agent-output` v1 has `brain_writes[]` (kind/text/tags/source_region) and the handler validates them, then **drops them**: `brain/__init__.py` is a stub; nothing reads or writes `brain_entries` | Stage 25 persists with provenance and dedupe |
| The brain table exists | `brain_entries` since migration 0001 (space_slug, kind, text, tags, source_canvas_id, source_region, created_at); no search column, no soft delete, no job link | Stage 25 adds `search` tsvector, GIN, `hash`, `job_id`, `deleted_at` (additive) |
| The prompt has a slot for retrieved context | `build_system_prompt(system_prompt, brain_context, ...)` always emits `<brain_context></brain_context>` with the data-not-instructions notice; the handler passes `""` | Stage 26 fills it |
| The brain routes exist | Frozen in `device-api` (`GET/POST/DELETE /brain/{space_slug}`); **unimplemented** | Stage 25 |
| `save_to_brain` works | `routes/cards.py` → `422 not_implemented`; the client shows the action but the server refuses | Stage 25 server, stage 27 client polish |
| A tool runtime exists | None: `create_message` is a single `messages.create` with no `tools`; `space.tools` stored, never read (ADR-0010 deferred enforcement "until a tool runtime exists") | Stage 26 adds a bounded tool loop and `resolve_tools`; the allow-list becomes real |
| `canvas.extract` exists | In the closed job-type set; `POST /jobs` → 422 (not in `IMPLEMENTED_JOB_TYPES`) | Stage 26 |
| Retrieval has a query | One-tap sends carry no `instruction`; the canvas is an image | ADR-0013: same-canvas history + instruction FTS + recency for the baseline, `brain_search` tool when the model wants more |
| Vector search is available | `postgres:16` image, no pgvector | ADR-0013: Postgres FTS now; embeddings deferred as an additive column |
| The device has any brain UI | None (only the `save_to_brain` label string) | Stage 27 |

## Contract safety

- `agent-output`: frozen schema untouched; `brain_entry_ids` and `brain_lookups`
  are server-added siblings (precedent `contract_version`, `canvas`).
- `device-api`: route table untouched; three frozen routes implemented as written;
  `BrainEntry.job_id` and the action response's `brain_entry_id` documented under a
  dated section.
- Alembic: one additive migration (stage 25). Room: none.
- ADR-0006 amendment still holds: the final answer is prompt-guided JSON; the tool
  loop only precedes it.

## Split

| Stage | Why separate |
|---|---|
| 25 | Pure storage + routes; curl-smokable; no model change |
| 26 | The acceptance test lives here; the tool loop is the risky part and gets its own switch |
| 27 | Device UI on stable routes; carries the tablet acceptance |

## Kill-switches

`BRAIN_ENABLED` (server, stage 25: persistence, routes, injection),
`AGENT_TOOLS_ENABLED` (server, stage 26: tool loop), `BuildConfig.BRAIN` (client).
Operator flips the two server switches on staging at ship time.

## Deferred

- pgvector embeddings: if keyword recall proves too literal in use.
- Editing `space.tools` from the tablet's settings sheet (stage 15 does not); the
  CLI/PATCH route covers it.
- Brain export/import and cross-space search: not asked for.
- Card `run_tool` actions and Nightshift tools: the tool runtime from stage 26 is
  the seam; registration of external tools is the bridge's job (#18).
- FCM push, `agent.notify`: unchanged from the Phase 4 assessment.
