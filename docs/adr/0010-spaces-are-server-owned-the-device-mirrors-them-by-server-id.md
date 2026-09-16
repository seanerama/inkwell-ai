# 0010. Spaces are server-owned; the device mirrors them by server id

- **Status:** Accepted
- **Date:** 2026-09-16

## Context

SPEC §2 makes a space an *agent context*: it owns the system prompt, tool set, model
and brain namespace, all of which live and act on the server (`spaces` table, worker
reads `space.system_prompt` and `space.model` per job). Canvases, by contrast, are
device-truth (SPEC §3): ink never leaves the tablet except as an export.

As of v0.0.12 the two sides disagree about space *identity*. The tablet seeds its own
`Work` space with a locally generated uuid (`CanvasRepository.ensureDefaultSpace`) and
files every canvas and folder under it; when it sends a job it looks the server's
`work` space up by slug and posts *that* uuid. A `canvas.formalize` result carries the
server's `space_id`, so the redraw is stored locally under the server id while the
library lists by the local id. Phase 3 (tabs, per-space prompts, move-between-spaces)
cannot be built on two id systems.

## Decision

**The server is the source of truth for spaces; the device holds a mirror keyed by
the server's uuid.** Concretely:

- The device's `spaces` table stores server rows verbatim (id, name, slug, prompt,
  tools, model, color, position) and is refreshed from `GET /spaces` on app start
  when paired, on pull-to-refresh, and after every space edit.
- A local placeholder is allowed only while unpaired (slug `work`, local id). On the
  first successful mirror the placeholder is **reconciled**: every canvas and folder
  whose `space_id` is a placeholder id is rewritten to the server space with the same
  slug, then the placeholder row is deleted, all in one transaction. Ink is untouched
  (strokes hang off layers, not spaces).
- Every job is posted with the canvas's own `space_id`; the slug lookup of `work`
  goes away once the mirror exists.
- Space *edits* (name, colour, model, prompt, order, creation) go to the server first
  (`POST /spaces`, `PATCH /spaces/{id}`, both in the frozen `device-api` route table)
  and reach the device through the mirror. There is no offline space editing.
- Canvas *placement* in a space stays device-truth: moving a canvas or folder between
  spaces is a local `space_id` rewrite. Agent-origin canvases the server knows about
  may therefore diverge from their server row's `space_id`; Phase 4's canvas sync
  reconciles from the device side.
- Slugs are immutable after creation (brain namespace key, SPEC §4.1).
- `tools` is stored and mirrored but **not enforced** until a tool runtime exists
  (none does: the agent call has no `tools` parameter). The SPEC §11 allow-list
  becomes real when tools do.

## Alternatives considered

- **Device-owned spaces pushed to the server.** Matches how canvases work, but the
  server needs the prompt at job time and a `POST /spaces` from an unpaired device
  cannot happen; spaces would also need a sync cursor. Rejected: the prompt is the
  space, and the prompt runs on the server.
- **Keep both ids and map by slug forever.** Every query and every job would carry
  the mapping; formalize already shows what goes wrong. Rejected.
- **A Room migration that rewrites ids.** A migration cannot call the network, and
  the server id is not known until the device is paired. Reconciliation at mirror
  time (idempotent, transactional) does the same job without a schema change.

## Consequences

- No Room schema change for Phase 3; `ink-storage` stays at v3.
- `CanvasViewModel.workSpaceId` and `DeviceRepository.workSpaceId()` are retired
  behind the `SPACES` flag and deleted when the flag is.
- A device that was never paired shows one placeholder tab; sending is blocked with a
  clear message until spaces have been mirrored once.
- Deleting a space is deliberately not offered (no `DELETE /spaces` in the contract);
  it would orphan canvases and a brain namespace.
