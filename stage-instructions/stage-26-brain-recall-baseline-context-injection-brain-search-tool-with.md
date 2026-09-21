# Stage 26: Brain recall: baseline context injection, brain_search tool with per-space allow-list, canvas.extract

- **Type:** feature
- **Depends on:** 25
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/55
- **Design:** SPEC §10.2 (`retrieve_brain`, `resolve_tools`), §10.3 (prompt order, data-not-instructions), §11 (per-space tool allow-list), §7 (`canvas.extract`), §12 Phase 5 acceptance; ADR-0006 amendment (prompt-guided JSON), ADR-0010 (tools deferral closed), ADR-0013 §3–§5

## Objectives

The Phase 5 acceptance, server side: a fact saved from one canvas is recalled when a
later job in the same space needs it. After this stage the `<brain_context>` block is
filled from the store on every job, the model can call `brain_search` when a space
allows it, and **Remember** (`canvas.extract`) exists as the deliberate way to write
things down. Stage 27 puts it on the tablet.

## What to build

**Baseline injection (`app/brain/retrieve.py`, wired in `jobs/canvas_annotate.py`)**
- `retrieve_brain(session, space_slug, canvas_id, instruction) -> str`: gather in
  order (a) live entries with `source_canvas_id == canvas_id` newest first, (b)
  full-text matches for `instruction` when present (ranked), (c) newest entries in
  the space; dedupe by id; stop at 12 entries or ~1,500 tokens (chars/4). Format
  one per line: `[kind] text — #tag1 #tag2 — YYYY-MM-DD (id:<short>)`. Empty store →
  the block stays empty (today's behaviour). Passed as `brain_context` to
  `build_system_prompt` (the parameter already exists).
- Gated by `BRAIN_ENABLED` (stage 25): off → empty block.

**Tool runtime (`app/agent/tools.py`, `app/agent/client.py`)**
- Tool definitions: `brain_search` — input `{ query: string, limit?: int ≤ 10 }`,
  runs the stage 25 search for the job's space, returns entries in the same
  one-per-line format wrapped in `<brain_search_result>…</brain_search_result>`
  with the data-not-instructions sentence. Results are also appended to the job's
  `result.brain_lookups: [{query, count}]` sibling for traceability.
- `resolve_tools(space.tools)`: names → definitions; unknown names logged and
  dropped; empty list → no `tools` parameter at all (today's call, byte-identical).
- Loop in `create_message`: pass `tools` when non-empty; while `stop_reason ==
  "tool_use"` and rounds < 3: execute each tool block, append the assistant turn
  and `tool_result` blocks, call again. On the 3rd round pass
  `tool_choice: {"type": "none"}` to force the answer. The final text block is the
  JSON as today (ADR-0006 amendment; structured outputs stay behind
  `AGENT_STRUCTURED_OUTPUT`). Sum `input_tokens`/`output_tokens` across rounds.
  Any tool error becomes a `tool_result` with `is_error: true`, never an exception.
- Kill-switch `AGENT_TOOLS_ENABLED` (`Settings.agent_tools_enabled`, default
  `False`, compose passthrough, `.env.example`): off → tools never sent even when
  the space lists them.
- Seed: `DEFAULT_SPACES[*].tools = ["brain_search"]`; the seeder backfills only rows
  whose `tools` is empty (stage 13 rule). The stage 15 settings sheet does not edit
  `tools`; leave that to a later stage — `PATCH /spaces/{id}` covers it.

**`canvas.extract`**
- Added to `IMPLEMENTED_JOB_TYPES`; effort `medium`; guidance: "Task: remember.
  Read the canvas and record every durable fact, task, reference or decision as
  `brain_writes` (kind, concise text in the user's words, 1–5 tags,
  `source_region` of the handwriting it came from). Do not record questions,
  scratch work or anything already in the brain context. Annotate minimally: one
  `highlight` per recorded region. Cards: exactly one `fact` card titled "Saved to
  brain" listing what was recorded, or an `answer` card saying nothing durable was
  found." Default instruction "Remember what is on this canvas."

**Prompt** — the preamble's brain notice already exists; add one sentence to the
role text: "You may have a `brain_search` tool; use it when the canvas refers to
something you would need to look up (a name, a date, an earlier decision)."

## Interface contracts

- **Exposes:** filled `<brain_context>`, `brain_search` tool, enforced
  `space.tools`, `canvas.extract`, `AGENT_TOOLS_ENABLED`, `result.brain_lookups`.
- **Consumes:** stage 25 store and search; `agent-output` (unchanged);
  `device-api` `POST /jobs` (type `canvas.extract`, already in the closed set).

## Testing requirements

- Retrieval: ordering (same-canvas first, then instruction matches, then recent);
  cap by count and by chars; empty store → empty block; switch off → empty block.
- Tool loop with the fake client: (a) no tools when the space list is empty or
  the switch is off — call shape identical to today's recorded calls; (b) one
  `tool_use` round → tool executed, `tool_result` appended, final JSON parsed,
  tokens summed; (c) 3 rounds → forced answer; (d) tool error → `is_error` result
  and the job still completes; (e) unknown tool name in `space.tools` ignored.
- **Acceptance test (server):** job 1 (`canvas.extract`) with a fixture write
  "Q3 offsite is in Austin on 14 October" in space `work`; job 2 (`canvas.ask`)
  on a different canvas with instruction "when is the offsite?" → the recorded
  `system` contains the Austin entry in `<brain_context>`; with tools on, the fake
  model calls `brain_search("offsite")` and receives it.
- `canvas.extract` end to end with a fixture; `prompt` snapshot tests updated.
- Smoke `smoke/brain-recall.md` (curl on the host, two real jobs with the canary
  image + instructions): extract → ask → the answer mentions Austin (operator
  judges); `result.brain_lookups` shows a lookup when tools are on.

## Acceptance conditions

- [ ] Kill-switch `AGENT_TOOLS_ENABLED` (default OFF) gates the tool loop; `BRAIN_ENABLED` gates injection
- [ ] UI-smoke asset `smoke/brain-recall.md` authored
- [ ] No contract change: model output shape unchanged; `tools` only sent when allowed; call shape byte-identical when no tools (tested)
- [ ] The server acceptance test (extract then ask, fact recalled) passes
- [ ] Existing suite stays green; CI all-green

## Pipeline test: NO
