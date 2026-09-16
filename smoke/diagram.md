# UI-smoke: mark it up — canvas.annotate diagrams a sketch legibly (Stage 9)

Manual "observably-works" check for the **Handoff Tester** (and the Operator), run on
the real tablet (Lenovo Idea Tab Pro, Android 14) with the active stylus, against the
**staging** server with the agent enabled (`AGENT_ENABLED=true`). This is the Stage-9
Phase-2 acceptance: *draw a three-box architecture sketch with two arrows, tap
"Add a note…" → "Mark it up instead", and the agent marks up the diagram — labelled
arrows for dependencies, at least one margin note in the right-hand gutter, and every
mark legible and on the thing it refers to.* Browser smoke does not apply to a native
client (ADR-0001), so this human pass is the replacement. Run it on every release APK
that changes the renderer, the send/poll/render loop, or the annotate guidance.

## Kill-switch — which build to test

The full annotation vocabulary is gated by `BuildConfig.FULL_VOCABULARY`, **ON in both
debug and release** by documented exception (stage-9 spec: prod is not promoted, staging
is the only environment, and the server-side `AGENT_ENABLED` already dark-launches all
agent work). With `FULL_VOCABULARY` ON, all nine `agent-output` v1 types draw natively:
`highlight`, `text`, `underline`, plus `arrow` (filled head + label), `ellipse`, `rect`,
`strikethrough` (dashed), `path`, and `margin_note` (right-hand gutter). With it **OFF**,
only `highlight`/`text`/`underline` draw natively and the other six render as the
Stage-7 thin **labelled boxes** — use that only to confirm the fallback still works.

Send itself is still gated by `BuildConfig.SEND_ENABLED` (ON since v0.0.5), and the
one-tap path by `BuildConfig.ONE_TAP_ASK` (ON): a plain **Send** posts `canvas.ask`.
This diagram scenario deliberately uses **"Mark it up instead"**, which posts
`canvas.annotate` — the path that exercises the full vocabulary.

## Preconditions

- The first release APK that includes Stage 9 (or later) is installed. The ink
  kill-switch is ON (`BuildConfig.INK_ENABLED`), so the app opens on the **canvas**.
- The tablet is on the tailnet and paired: in **Settings**, the staging server URL and a
  minted device token are set, and **Check** shows `Server <version> (device-api/v1)`.
- Staging has `AGENT_ENABLED=true` and a real `ANTHROPIC_API_KEY`, the deployed server
  accepts `canvas.annotate` (`POST /v1/jobs` returns `202`), and the seeded `work` space
  exists (`GET /v1/spaces` lists a space with slug `work`).
- The tablet is online (the **Send** button reads "Send", not "Offline"). Next to Send
  there is a pencil icon button, **"Add a note…"**.
- Start on a clean canvas: **Undo** (or erase) anything left over, and **Close** any open
  side panel.

## Scenario 1 — a three-box architecture sketch with two arrows

1. **Draw the sketch.** With **Pen**, draw three labelled boxes down/across the canvas,
   leaving clear space between them and a clear right margin:
   - a box labelled `client` (top-left),
   - a box labelled `gateway` (middle),
   - a box labelled `auth` (lower-right).
   Draw a hand arrow from `client` → `gateway`, and one from `gateway` → `auth`.
   - *Expected screenshot A:* the three labelled boxes and two hand-drawn arrows on a
     white canvas, with an empty right margin.

2. **Open the note sheet and choose "Mark it up instead".** Tap **"Add a note…"**
   (pencil icon). In the sheet, leave the note **blank** and tap **"Mark it up instead"**.
   - *Expected:* the sheet closes and the small **"Working…"** spinner shows top-right.
     (This posts `canvas.annotate`, not `canvas.ask`.)
   - *Expected screenshot B:* the "Working…" indicator, no sheet on screen.
   - *Optional:* to steer it, instead type a note such as `Check the dependencies.`
     before tapping "Mark it up instead"; it is sent as the instruction.

3. **Keep drawing while it runs (non-blocking).** Scribble a stroke anywhere.
   - *Expected:* the stroke draws normally — the UI is **not** locked (SPEC §9.4 step 4).

4. **Result lands.** Within a few `/sync` polls (5 s cadence), the job reaches `done` and
   the agent layer draws over the sketch — all marks in the space accent color at ~70%
   opacity, constant weight (never pressure-tapered):
   - **Arrows** for missing/wrong dependencies are drawn with a **filled triangular
     head** pointing at the target box, and each carries a short **label** (≤ 4 words,
     e.g. "depends on", "blocks") with a white halo so it reads over the ink.
   - Any **`rect`/`ellipse`** grouping is a clean stroke-only outline around the boxes it
     groups; a **`strikethrough`** (if used) is visibly **dashed**, distinct from an
     `underline`.
   - At least one **`margin_note`** appears in a **faint right-hand gutter** outside the
     page, as wrapped text with a thin **leader line** back to the page edge; two notes
     at similar heights **stack** without overlapping.
   - The **side panel** opens showing the agent **`summary`** and any **cards** (the
     first card starts expanded).
   - *Expected screenshot C:* labelled arrows on the diagram and ≥ 1 note in the gutter.

5. **Legibility & placement check.** Read every mark:
   - Each arrow sits **on** the dependency it describes (from the right box to the right
     box), not floating in empty space.
   - Every label is legible over the ink (the white halo is doing its job) and is short.
   - No mark is a thin **labelled rectangle** with a bare type name ("arrow", "ellipse",
     …). A labelled box means this build has `FULL_VOCABULARY` **OFF** — stop and note
     the build (see Failure signals).

6. **Judge placement with the layer toggle.** Tap **Layers**, toggle **Agent
   annotations** off and on.
   - *Expected:* every agent mark (arrows, groupings, gutter notes) disappears and
     reappears together, confirming they are on the agent layer. The **gutter tint** is
     part of the agent layer and disappears with it.

## Scenario 2 — the gutter is view-only (not exported)

1. On the marked-up canvas from Scenario 1 (agent layer visible), tap **"Add a note…"**
   → **"Mark it up instead"** again (a second send).
   - *Expected:* the send succeeds and a fresh markup lands. The export the device sends
     is the **user's ink only** — the gutter and the previous agent marks are **not**
     included (default off), so the model does not mark up its own marks (SPEC §13
     drift note). The gutter is a **view** concept; it never changes the exported image
     width (still 1109 px for the default canvas).
   - *Expected:* `coordinate_clamps` on the job is **0** (read it from the server as in
     `smoke/loop.md`) — the agent returned in-range normalized coordinates.

## Pass / fail criteria

- [ ] **Scenario 1 PASS** requires **all** of: ≥ 1 **labelled arrow with a drawn head**
      on a dependency (screenshot C), ≥ 1 **margin note in the gutter** with a leader
      line, and **every mark legible and on the thing it refers to**. A bare labelled
      box for `arrow`/`ellipse`/`rect`/`strikethrough`/`path`/`margin_note` is a **FAIL**
      for this build — capture the screenshot and the job id and stop.
- [ ] "Mark it up instead" posted `canvas.annotate` (the panel shows agent markup, not a
      one-line ask answer).
- [ ] The UI stayed responsive (you could draw) while the job ran.
- [ ] The agent layer (including the gutter tint) hides/shows from the layer tray.
- [ ] **Scenario 2:** the second send succeeded and `coordinate_clamps` was 0.

## Record the run

- Tag / build under test: __________
- `FULL_VOCABULARY` (ON/OFF as built): __________
- Scenario 1 job id: __________  — labelled arrows + gutter note, all legible (PASS/FAIL): __________
- Scenario 2 job id: __________  — export ink-only, `coordinate_clamps` (expected 0): __________
- Notes / anomalies (e.g. an arrow label overlapping ink, a note running off the gutter,
  two notes overlapping instead of stacking): __________

## Failure signals

- **A mark is a thin labelled rectangle with a type name:** `FULL_VOCABULARY` is OFF in
  this build (Stage-7 fallback) — you are not testing the full vocabulary; rebuild with
  the flag ON and note the build.
- **No sheet / no "Mark it up instead":** you tapped **Send** (one-tap `canvas.ask`), not
  **"Add a note…"** — reopen the note sheet and choose **"Mark it up instead"**.
- **Panel shows "Could not respond" / "Job failed":** the agent's response failed
  contract validation twice, or the image was missing — capture the job id and server
  logs.
- **Panel shows an error card, nothing on the canvas:** the job `failed` (e.g. agent
  disabled, over the per-space daily cap) — the panel shows the error card body and
  renders no layer. Capture it.
- **HTTP 422 `not_implemented` in the send status:** staging is running a pre-Stage-9
  server; redeploy before testing.
- **"Offline":** the tablet lost the tailnet; Send is disabled by design (SPEC §9.5).
- **Stuck on "Working…" past ~30 s:** capture the job id and the server logs.

## Results log

| Date (UTC) | Release | Operator | Scenario 1 (three-box markup) | Notes |
|---|---|---|---|---|
| | | | | |
