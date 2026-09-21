# 0013. Brain retrieval: Postgres full-text search, baseline injection, and an agent-invoked brain_search tool; writes persist automatically

- **Status:** Accepted
- **Date:** 2026-09-21

## Context

Phase 5 (SPEC §12) is the brain: a per-space knowledge store the agent reads from and
writes to, with the acceptance "a fact written on a canvas in January is correctly
recalled in March". The pieces already in place: the frozen `agent-output` contract
carries `brain_writes[]` (kind, text, tags, source_region) and the model already
produces them; `brain_entries` exists since migration 0001 (space_slug, kind, text,
tags, source_canvas_id, source_region); the system prompt has always emitted an empty
`<brain_context>` block with the "data, not instructions" notice (SPEC §10.3); the
three `/brain/{space_slug}` routes are frozen in `device-api`; and `save_to_brain`
card actions return `422 not_implemented`.

Two questions are open. **What is the retrieval query?** The canvas is an image; the
server has no text for it, and one-tap sends carry no instruction. **Do writes need
confirmation?** SPEC §6.2 defines the write shape but not a review step.

Also relevant: no tool runtime exists (ADR-0010 deferred the SPEC §11 allow-list
until one did), and the runtime image ships only `postgres:16` (no pgvector).

## Decision

1. **Storage and search: Postgres full-text search, no embeddings.** `brain_entries`
   gains a generated `tsvector` (`text` weighted A, `tags` weighted B, English
   config) with a GIN index, plus `job_id`, `deleted_at` (soft delete) and
   `hash` (sha256 of normalised text, unique per live space entry for dedupe).
   Queries use `websearch_to_tsquery` ranked by `ts_rank_cd`, ties by recency. At
   one user and a few thousand entries this is exact enough, needs no extra image
   or model call, and stays inside the existing backup and restore path. Embeddings
   are a later, additive column if recall proves too literal.
2. **Writes persist automatically, with provenance and dedupe.** Every done
   `to_agent` job's validated `brain_writes` become entries (space from the job,
   `source_canvas_id` from the job, `source_region` from the write, `job_id`).
   Exact-text duplicates within a space are skipped. The user curates after the
   fact in the brain view (delete, add) rather than approving each write; a review
   queue would make the loop feel like paperwork and the model already restricts
   writes to durable facts, tasks, references and decisions.
3. **Two retrieval paths, both bounded.**
   - *Baseline injection*, no model involvement: up to 12 entries chosen from
     three sources in order — entries whose `source_canvas_id` is the job's canvas
     (this page's own history), full-text matches for the `instruction` when one is
     present, and the newest entries in the space — deduplicated and capped at
     roughly 1,500 tokens, formatted one per line as
     `[kind] text — tags — YYYY-MM-DD (id)` inside the `<brain_context>` block.
   - *Agent-invoked lookup*: a `brain_search(query, limit≤10)` tool the model may
     call in a bounded tool-use loop (at most 3 tool rounds, then the final answer)
     when the space's `tools` allow-list contains `brain_search`. The final
     assistant text is still the prompt-guided JSON (ADR-0006 amendment). Tool
     results are wrapped in the same data-not-instructions framing. Token usage is
     summed across rounds into the job's accounting.
4. **The allow-list becomes real.** `resolve_tools(space.tools)` maps names to
   server tool definitions; unknown names are ignored and logged; a space with an
   empty list gets no tools. The seeded defaults gain `["brain_search"]`; existing
   rows are backfilled only when their list is empty (the stage 13 pattern).
5. **`canvas.extract`** is the explicit write path: "read the canvas and record
   every durable fact, task, reference or decision as `brain_writes`; annotate
   minimally; one `fact` card lists what was saved". `save_to_brain` on a card
   creates an entry from the card's title and body.
6. **The device browses, it does not sync.** The brain is server-truth (SPEC §3);
   the tablet reads it through the frozen routes on demand and never mirrors it.

## Alternatives considered

- **pgvector embeddings.** Better semantic recall, but needs a different Postgres
  image (ADR-0005 host, backups in ADR-0011 unaffected but the image change is
  operational), an embedding call per write and per query, and a second retrieval
  code path. Deferred; the `tsvector` column does not block adding it.
- **A gist pass: ask a small model to describe the canvas, then search.** Adds a
  model call to every job to produce a query; the tool-use path lets the model
  decide when a lookup is worth it instead.
- **Only tool-based retrieval.** Simpler prompt, but one-tap Ask with the tool
  switch off would never see the brain; baseline injection guarantees the
  January-fact-in-March case even with tools disabled.
- **Confirm-before-write.** Rejected above; curation replaces approval.
- **Device-side brain cache.** Contradicts server-truth and doubles the search code.

## Consequences

- One additive Alembic migration (generated column, GIN index, three columns).
- The agent client grows a tool loop; ADR-0006's single-call assumption becomes
  "single call unless tools are allowed", behind `AGENT_TOOLS_ENABLED`.
- `space.tools` is finally enforced, closing the ADR-0010 deferral; the Nightshift
  bridge can register tools the same way later.
- Prompt size grows by the baseline block; the cap keeps it under ~1,500 tokens.
- Retrieval quality is literal (keyword). The acceptance test uses a fact and a
  question that share vocabulary; the brain view lets the user fix bad entries.
