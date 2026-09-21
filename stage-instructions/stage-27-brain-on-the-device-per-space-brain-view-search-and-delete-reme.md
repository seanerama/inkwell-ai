# Stage 27: Brain on the device: per-space brain view, search and delete, Remember in the picker, save-to-brain cards

- **Type:** feature
- **Depends on:** 26
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/56
- **Design:** SPEC §12 Phase 5 ("a browsable view"), §4.7 (`save_to_brain`), §9.3; contract `device-api` (frozen brain routes; stage 25 dated section); ADR-0013 §6

## Objectives

The Phase 5 acceptance from the tablet: write a fact on a canvas, tap **Remember**,
and weeks later on a new canvas ask about it and get it back. Plus the browsable
view the spec asks for: each space tab has a Brain you can search, prune and add
to, and "Save to brain" on a card works.

## What to build

**Brain view (`ui/BrainScreen.kt`, `ui/BrainViewModel.kt`)**
- Entry: a brain icon in the Library top bar (next to Trash) and the tab's ⋯ menu →
  "Brain". Scoped to the active space (`GET /brain/{slug}`).
- List newest first: kind chip (fact / task / reference / decision), text, tags,
  date. Search field → `?q=` (debounced; empty = newest). Long-press → Delete
  (`DELETE`, with undo via re-`POST` for 5 s). "+" → manual entry dialog (kind,
  text, tags) → `POST`. Tap an entry with a `source_canvas_id` that exists locally →
  open that canvas and flash the `source_region` if present.
- Online only (server-truth, ADR-0013 §6): offline shows "Brain needs a connection"
  and the last fetched list greyed.

**Picker (`ui/CanvasScreen.kt`, `net/JobRequestBuilder.kt`)**
- Fourth option **Remember** → `canvas.extract`; Send label follows. The result
  handler shows the "Saved to brain" card as any card; highlights render as usual.

**Cards (`ui/PanelModel.kt`, `ui/CanvasViewModel.kt`)**
- `save_to_brain` actions are enabled (server returns 200 since stage 25); on
  success the card shows a "Saved" state and a snackbar with "View in Brain".

**Retrofit** — `getBrain(slug, q, limit)`, `postBrain(slug, body)`, `deleteBrain(slug,
id)`; models per the contract plus `job_id`.

**Kill-switch** `BuildConfig.BRAIN` (OFF = no brain icon/menu, no Remember option,
`save_to_brain` actions hidden). Documented release-ON exception.

## Interface contracts

- **Exposes:** Brain view; Remember; working save-to-brain.
- **Consumes:** `device-api` brain routes (frozen) and `POST /jobs` with
  `canvas.extract`; stages 25–26.

## Testing requirements

- Unit: view-model list/search/delete/undo/add state transitions; request bodies.
- Instrumented (MockWebServer): brain list renders, search issues `?q=`, delete
  calls DELETE and removes the row, add POSTs and inserts, Remember posts
  `canvas.extract`.
- Compose UI test: picker shows four options with the flag on, three with it off.
- Smoke `smoke/brain-inbox.md` (tablet, the Phase 5 acceptance): on a Work canvas
  write "Q3 offsite: Austin, 14 Oct" → Remember → "Saved to brain" card → Brain
  view shows the entry. New canvas → write "when is the offsite?" → Ask → the
  answer says Austin, 14 Oct. Delete the entry in the Brain view → Ask again →
  the answer no longer knows. Results table.

## Acceptance conditions

- [ ] Kill-switch `BuildConfig.BRAIN` (documented release-ON exception)
- [ ] UI-smoke asset `smoke/brain-inbox.md` authored (Remember → Ask recalls → delete → forgets)
- [ ] No schema change on the device (brain is not mirrored)
- [ ] Existing suite stays green; CI all-green (instrumented lane run on the branch and linked)

## Pipeline test: NO
