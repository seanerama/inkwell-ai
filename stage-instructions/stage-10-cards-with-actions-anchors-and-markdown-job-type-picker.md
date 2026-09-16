# Stage 10: Cards with actions, anchors and Markdown; job-type picker

- **Type:** feature
- **Depends on:** 9
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/23
- **Design:** SPEC §4.7, §6, §9.3, §12 Phase 2; contracts `device-api` (additive routes), `agent-output`

## Objectives

Cards become real objects: they have an identity and a state the device can change,
their actions do something, tapping a card highlights the canvas region it anchors to
(and tapping an agent mark scrolls to its card), and bodies render as Markdown. The
note sheet becomes the job-type picker so ask and annotate are both one tap away.

## What to build

**Server (additive to contract `device-api` v1 — new routes and new optional fields
only; the versioning section permits both)**
- `JobOut` gains `cards: CardOut[]` built from the `cards` rows: `id, kind, title,
  body, anchors, actions, state, created_at`. `/sync` and `GET /jobs/{id}` carry it.
  `result` is unchanged.
- `PATCH /cards/{id}` with `{ state: "open"|"done"|"dismissed" }` → `CardOut`; 404 for
  unknown, 409 for an invalid transition (only `open → done|dismissed` and back to
  `open`).
- `POST /cards/{id}/actions/{action_id}` → `CardOut`: `confirm` sets `done`, `reject`
  sets `dismissed`; `run_tool`, `open_canvas`, `save_to_brain` return `422
  not_implemented` until their phases (tools, Phase 4 canvases, Phase 5 brain).
- Card state changes bump the parent job's `updated_at` so `/sync` delivers them to
  the device.
- Contract doc `contracts/device-api.md`: append the routes and the `cards` field under
  an "Additive changes (v1)" heading dated 2026-09-16; the frozen sections are not
  edited.

**Android**
- `PanelModel` cards carry `id`, `state`, `anchors`, `actions`. Rows show the state
  (open / done / dismissed) and action buttons for `confirm`/`reject`; unsupported
  kinds show disabled with "coming later". Tapping an action calls the route,
  updates the row optimistically, reconciles from `/sync`.
- Markdown: render `**bold**`, `*italic*`, `` `code` ``, bullet lists and line breaks
  with a small in-house renderer (no new dependency; Compose `AnnotatedString`).
  Headings and links render as plain text.
- Anchors: tapping a card with anchors pulses a highlight over each anchored
  annotation's bounds (or the `region` rect) for ~1.5 s and scrolls the canvas to it if
  off-screen; tapping an agent mark on the canvas (hit-test on `AnnotationGeometry.boundsCu`)
  expands and scrolls the panel to its card. Both directions from SPEC §4.7.
- Job-type picker: the note sheet gets a segmented control **Ask / Mark up**, remembers
  the last choice, and the primary Send button label follows it ("Send" / "Mark up").
  `formalize`, `extract` and `action` appear greyed with "Phase 3+".
- Card state persists per job on the device (`CardStateEntity` in Room, version bump
  to 2 with an additive migration) so reopening a canvas shows done/dismissed cards
  correctly even offline.

## Interface contracts

- **Exposes:** card routes and the `cards` field (additive, documented in the contract);
  the anchor interaction; the picker.
- **Consumes:** `agent-output` cards/anchors/actions (unchanged); `ink-storage` (Room
  v2 additive). No new contract; `device-api` extended additively per its own rules.

## Testing requirements

- Server: `cards` on `JobOut` after a done job; `PATCH` transitions and 409s; each
  action kind's effect or 422; `updated_at` bump visible through `/sync` with a cursor;
  contract check unchanged.
- Android JVM: Markdown renderer cases; anchor hit-testing maps a CU tap to the right
  card; optimistic state + reconcile; Room v2 migration test (v1 → v2 keeps strokes).
- Instrumented: MockWebServer replay with two cards; tap confirm → PATCH observed →
  row shows done; tap a card → highlight pulse present; tap the mark → panel scrolls.
- UI-smoke `smoke/cards.md`: after a diagram markup, confirm one card, dismiss
  another, kill and reopen the app, states persist; tap a card and see its arrow pulse.

## Acceptance conditions

- [ ] Kill-switch / dark-launch flag (default OFF) for this net-new feature:
      `BuildConfig.CARD_ACTIONS`, same documented exception as stage 7 (ON in both
      build types; OFF = stage-7 read-only cards). Record in the PR.
- [ ] UI-smoke "observably-works" check authored for any user-facing surface
      (`smoke/cards.md`).
- [ ] Additive migration only (no destructive schema change): Room v1 → v2 adds a
      table; server has no migration.
- [ ] `contracts/device-api.md` gains only an appended additive section.
- [ ] Existing suite stays green; CI all-green

## Pipeline test: YES
