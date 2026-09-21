# Stage 25: Brain store: persist brain_writes, frozen brain routes with full-text search, save_to_brain card action

- **Type:** feature
- **Depends on:** 23
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/54
- **Design:** SPEC §4 (Brain), §6.2, §8 (Brain routes), §12 Phase 5; contracts `agent-output` (frozen `brain_writes`), `device-api` (frozen `/brain/{space_slug}` routes; `save_to_brain` card action); ADR-0013 §1, §2, §5

## Objectives

Today the model already emits `brain_writes` and the server throws them away after
validation. After this stage every write lands in `brain_entries` with provenance,
the three frozen brain routes work with full-text search, and tapping "Save to brain"
on a card creates an entry instead of a 422. No recall yet (stage 26); no device UI
yet (stage 27).

## What to build

**Schema (Alembic, additive)**
- `brain_entries`: add `job_id UUID NULL`, `deleted_at TIMESTAMPTZ NULL`, `hash
  VARCHAR(64) NOT NULL` (sha256 of `lower(collapse_ws(text))`, backfilled for
  existing rows), a generated column `search TSVECTOR GENERATED ALWAYS AS
  (setweight(to_tsvector('english', text), 'A') || setweight(to_tsvector('english',
  array_to_string(tags, ' ')), 'B')) STORED`, a GIN index on `search`, and a partial
  unique index on `(space_slug, hash) WHERE deleted_at IS NULL`.

**Persistence (`app/brain/store.py`, called from the job handler)**
- `persist_writes(session, job, writes)`: for each validated write, normalise tags
  (lowercase, trimmed, deduped, ≤ 10), compute the hash, insert unless a live entry
  with the same `(space_slug, hash)` exists (skip silently, count it), set
  `source_canvas_id = job.canvas_id`, `source_region` from the write, `job_id`.
  Runs inside the job's `done` transaction; a persistence error fails the job
  loudly (never a silent drop). The job result is unchanged (the model's
  `brain_writes` stay in `result`); the server adds a sibling key
  `brain_entry_ids: [...]` for traceability (contract precedent: `contract_version`).
- Kill-switch `BRAIN_ENABLED` (`Settings.brain_enabled`, default `False`,
  `compose.yml` passthrough, `.env.example`; the parity gate covers it): when off,
  writes are not persisted, the routes return `403 disabled`, and `save_to_brain`
  keeps returning `422 not_implemented`.

**Routes (`app/api/routes/brain.py`)** — exactly the frozen table:
- `GET /brain/{space_slug}?q=&limit=` → `BrainEntry[]`: unknown slug `404`; `limit`
  default 50, max 200; with `q` → `websearch_to_tsquery('english', q)` ranked by
  `ts_rank_cd` then `created_at desc`; without `q` → newest first. Soft-deleted
  rows never returned. `BrainEntry` shape: `{ id, space_slug, kind, text, tags,
  source_canvas_id, source_region, job_id, created_at }` (`job_id` is an additive
  field documented in the dated section).
- `POST /brain/{space_slug}` body `{ kind, text (1–2000), tags?, source_canvas_id? }`
  → `201 BrainEntry`; duplicate → `200` with the existing entry (idempotent).
- `DELETE /brain/{space_slug}/{id}` → `204` (soft delete); `404` unknown or wrong
  space.
- Bearer auth (device kind); agent-kind tokens `403`.

**Card action** — `save_to_brain` (`routes/cards.py`): create an entry `kind=fact`
(or the payload's `kind` when one of the four), `text = "<title>\n\n<body>"`, tags
from `payload.tags`, `source_canvas_id` from the job; set the card `done`; bump the
job (existing rule). Returns the card as today plus `brain_entry_id` sibling.

**Contract** — `contracts/device-api.md` dated section "Stage 25 additions":
`BrainEntry.job_id`, the `q` semantics, the idempotent POST, the `403 disabled` code,
`brain_entry_id` on the action response. Route table untouched. `contracts/
agent-output.md` dated note: `brain_entry_ids` server-added sibling.

## Interface contracts

- **Exposes:** persisted brain entries with provenance; the three brain routes;
  `save_to_brain`; `BRAIN_ENABLED`; `brain_entry_ids` / `brain_entry_id` siblings.
- **Consumes:** `agent-output` `brain_writes` (frozen), job handler `done` path,
  ADR-0013.

## Testing requirements

- Persistence: a done job with two writes → two entries with provenance; the same
  writes again → zero new (dedupe); tag normalisation; switch off → none persisted
  and result has no `brain_entry_ids`; persistence failure → job `failed` with an
  `error` card.
- Routes: auth matrix (none 401, agent kind 403); search ranks a text match above a
  tag match above recency; `q` with no match → `[]`; POST idempotent; DELETE soft
  and 404 cases; unknown slug 404.
- Card action: `save_to_brain` creates the entry and marks the card done.
- Migration test: existing rows get a hash; generated column populated.
- Smoke `smoke/brain-server.md` (curl on the host): run a `canvas.ask` whose
  fixture answer carries a write (or POST an entry), `GET /brain/work?q=` finds it,
  DELETE it, gone.

## Acceptance conditions

- [ ] Kill-switch `BRAIN_ENABLED` (default OFF) gates persistence, routes and the card action
- [ ] UI-smoke asset `smoke/brain-server.md` authored
- [ ] Additive migration only; route table untouched; frozen `agent-output` schema untouched
- [ ] `brain_writes` from a done job are queryable through `GET /brain/{slug}?q=` (tested)
- [ ] Existing suite stays green; CI all-green

## Pipeline test: NO
