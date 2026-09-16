# UI-smoke: Formalize — redraw a sketch as a clean diagram on a new canvas (Stage 12)

Manual "observably-works" check for the **Handoff Tester** (and the Operator), run on the
real tablet (Lenovo Idea Tab Pro, Android 14) with the active stylus, against the
**staging** server with the agent enabled (`AGENT_ENABLED=true`). This is the Stage-12
acceptance: *Mark up ▸ Formalize sends the sketch, and the agent's clean redraw — boxes,
labelled arrows, node labels — appears as a **new canvas** beside the original in the same
folder, titled "&lt;original&gt; — formalized", opened automatically; the original is
untouched, and the new canvas is a normal, editable canvas.* Browser smoke does not apply
to a native client (ADR-0001), so this human pass is the replacement. Run it on every
release APK that changes the picker, the loop result handling, the renderer, or the
Library/canvas navigation.

## Kill-switch — which build to test

Formalize is gated by `BuildConfig.FORMALIZE`, **ON in both debug and release** by
documented exception (Stage-12 spec: prod is not promoted, staging is the only
environment, and the server-side `AGENT_ENABLED` already dark-launches all agent work).
With `FORMALIZE` **OFF** the picker offers only **Ask / Mark up** (Formalize stays greyed
under "Phase 3+") and no redraw-to-new-canvas handling runs — that path is `smoke/cards.md`,
not here. Card actions (`BuildConfig.CARD_ACTIONS`) and the Library
(`BuildConfig.LIBRARY`) must also be ON for the picker and the tile grid. Send is gated by
`BuildConfig.SEND_ENABLED` (ON since v0.0.5). Flip `FORMALIZE` to `false` in
`app/build.gradle.kts` (both build types) to fall back without a code change; the server
also gates the type via `IMPLEMENTED_JOB_TYPES` (a formalize job returns `422
not_implemented` if the server predates Stage 12).

## Preconditions

- The first release APK that includes Stage 12 (or later) is installed. The Library
  kill-switch is ON (`BuildConfig.LIBRARY`), so the app opens on the **Library** (folders +
  canvas tiles for the seeded `work` space).
- The tablet is on the tailnet and paired: in **Settings**, the staging server URL and a
  minted device token are set, and **Check** shows `Server <version> (device-api/v1)`.
- Staging has `AGENT_ENABLED=true` and a real `ANTHROPIC_API_KEY`, the deployed server
  accepts `canvas.formalize` (image required), carries the `canvas` / `source_canvas_id`
  sibling keys on the job result, and exposes `GET /v1/canvases`. The seeded `work` space
  exists.
- The tablet is online (**Send** reads "Send" / "Mark up" / "Formalize", not "Offline").

## Scenario 1 — formalize a 3-node topology

1. **New canvas + draw the topology.** In the Library, create a canvas, open it, give it a
   title (e.g. **"Topology"**). With **Pen**, sketch three roughly-drawn boxes labelled
   (e.g.) **Web**, **API**, **DB**, with a hand-drawn arrow **Web → API** and **API → DB**
   — deliberately crooked and unevenly spaced.
   - *Expected screenshot A:* the messy 3-node sketch on a white canvas.

2. **Mark up ▸ Formalize.** Tap **"Add a note…"**, choose **Formalize** in the picker (the
   primary button now reads **"Formalize"**), leave the note blank, tap **Formalize**.
   - *Expected:* no error; the **"Working…"** spinner shows top-right; the UI stays
     responsive (you can keep drawing on the original).
   - *Expected:* the picker remembered **Formalize** next time you open the sheet.

3. **The redraw opens as a new canvas.** Within a few `/sync` polls the job reaches `done`
   and the app **navigates to a new canvas** titled **"Topology — formalized"**. It shows
   a clean diagram: **three aligned, equally-sized boxes**, **labelled arrows** for the two
   connections, and **legible node labels**, preserving the sketch's left-to-right layout.
   The **answer** card summarises what was cleaned up.
   - *Expected:* the diagram is drawn **opaque and in ink-black** (it is the content, not a
     70%/accent markup layer); the layer tray labels its layer **"Diagram"**.
   - *Expected screenshot B:* the formalized canvas with the clean diagram + answer card.

4. **The original is untouched.** Press **Back** to the folder.
   - *Expected:* **both** canvases sit in the **same folder** — the original **"Topology"**
     (your sketch, unchanged) and **"Topology — formalized"** (the redraw), origin=agent.
   - *Expected screenshot C:* the folder grid showing both tiles side by side.

5. **The new canvas is editable.** Open **"Topology — formalized"** and draw on it with the
   pen.
   - *Expected:* your ink lands on top of the diagram (a first ink layer is created on
     first draw, SPEC §4.3); the diagram stays opaque beneath it.

6. **Mark up works on the new canvas.** On **"Topology — formalized"**, tap **"Add a
   note…"**, choose **Mark up**, send.
   - *Expected:* a normal `canvas.annotate` pass runs and its 70%/accent annotations appear
     over the diagram — proving the redraw is just a normal canvas.
   - *(Optional)* choose **Formalize** again → yet another **"… — formalized"** canvas
     appears in the folder.

## Pass / fail criteria

- [ ] The picker offers **Ask / Mark up / Formalize**; the primary button label follows the
      choice; the choice is remembered.
- [ ] Formalize produces a **new canvas** titled **"&lt;original&gt; — formalized"**, opened
      automatically, with a clean diagram (aligned boxes, labelled arrows, legible labels).
- [ ] The diagram renders **opaque / ink-black** and its layer is labelled **"Diagram"**.
- [ ] The **original canvas is unchanged**, and **both** canvases appear in the **same
      folder** (screenshot C).
- [ ] The new canvas is **editable** (draw on it) and **Mark up** works on it.
- [ ] The owner judges the redraw **"better than the sketch"** (Stage-12 acceptance).

## Record the run

- Tag / build under test: __________
- Formalize job id: __________ — new canvas id: __________
- Redraw better than the sketch (owner judgement) (PASS/FAIL): __________
- Original untouched + both in same folder (PASS/FAIL): __________
- Opaque "Diagram" rendering (PASS/FAIL): __________
- New canvas editable + Mark up works (PASS/FAIL): __________
- Notes / anomalies: __________

## Failure signals

- **Formalize greyed / not selectable:** `FORMALIZE` is OFF in this build (or `CARD_ACTIONS`
  is OFF, hiding the picker) — note the build and use `smoke/cards.md`.
- **HTTP 422 `not_implemented` on Send:** the server predates Stage 12 (`canvas.formalize`
  not in `IMPLEMENTED_JOB_TYPES`) — redeploy staging.
- **No new canvas appears (annotations land on the original):** the client fell back to the
  normal agent-layer path — the result is missing the `canvas` sibling key (server bug) or
  `FORMALIZE`/the store was not wired; capture the job id and `result`.
- **New canvas in the wrong folder / at the root:** `source_canvas_id` did not resolve to
  the local source canvas; capture the job's `canvas_id` and `source_canvas_id`.
- **Diagram drawn faint/accent (not opaque):** the canvas origin was not read as `agent`;
  capture a screenshot.
- **Wrong title (not "&lt;original&gt; — formalized"):** `meta.title` was not sent or the
  server default kicked in ("Formalized"); check the `POST /jobs` body carried `meta.title`.
- **"Offline":** the tablet lost the tailnet; Send is disabled by design (SPEC §9.5).

## Results log

| Date (UTC) | Release | Operator | New canvas + redraw | Original untouched | Better than sketch | Notes |
|---|---|---|---|---|---|---|
| | | | | | | |
