# Stage 7: One-tap send: canvas.ask answers the note without an instruction

- **Type:** feature
- **Depends on:** 6
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/19 (request: #17)
- **Design:** SPEC §7 (`canvas.ask`), §9.4, §10.3; contracts `agent-output` (`text` annotation, `answer` card), `device-api` (`instruction` optional); ADR-0006

## Objectives

Sending a note is one tap and the agent does the obvious thing: read the page, answer
any question written on it, point out mistakes, and only annotate when it helps.
Owner acceptance: write "what is 1+9=?" on a canvas, tap Send with no instruction,
and the answer "10" appears both next to the question on the canvas and as an
`answer` card in the panel. The stage-6 annotate flow remains available as an
optional note ("Add a note…") rather than the default.

## What to build

**Server**
- Implement `canvas.ask`: move it from `SPEC_TYPES` (422) into `IMPLEMENTED_JOB_TYPES`,
  require an image like annotate, and register the existing handler for both types
  (`register_job_handler("canvas.ask", …)`; rename the module to `canvas_agent.py` or
  register twice — the handler already passes `job.type` through to `run_agent`).
- `_JOB_GUIDANCE["canvas.ask"]` (system prompt, SPEC §10.3 step 4), roughly: "Read the
  canvas as a note. If it contains a question, answer it: place a short answer as a
  `text` annotation immediately to the right of or below the question (size ≈ 0.02),
  and add one `answer` card with the full answer in Markdown. If it contains a
  mistake (arithmetic, spelling of a technical term, a wrong date), mark it with an
  `underline` and explain in a card. If it is a plan, list or diagram, give at most one
  useful observation as a card; annotate only when the observation is about a
  specific place on the page. Do nothing decorative. If there is nothing to say,
  return an empty `annotations` list and one short `answer` card."
- `build_instruction(None, "canvas.ask")` → "Read this note and respond." (replaces the
  generic "Analyse this canvas.").
- Effort stays `low` for `canvas.ask` (already in `EFFORT_BY_JOB_TYPE`).
- Tests: `canvas.ask` accepted (202) and handled to `done` with the recorded client;
  guidance text present for ask and absent for annotate; a fixture response with a
  `text` annotation and an `answer` card round-trips into `cards` rows.

**Android**
- Send becomes **one tap**: `send()` posts `type = "canvas.ask"` with `instruction = null`
  and no sheet. The instruction sheet moves behind a secondary affordance next to Send
  ("Add a note…" icon button); when a note is typed it is sent as `canvas.ask` with that
  instruction. The stage-6 preset lives inside that sheet as "Mark it up instead"
  which sends `canvas.annotate` with the typed text (or the preset when blank).
  Remove the blank-instruction fallback to `PRESET_INSTRUCTION`.
- `AnnotationRenderer`: render `text` annotations (contract `coordinate-mapping`: `at`
  maps by NM→CU, `size` × `height_cu` is the text height in CU; constant weight, agent
  color, 70% opacity as with highlight). Render `underline` as a constant-width
  polyline. Every other type not yet supported renders as the contract's fallback: a
  thin labelled rectangle around its bounding box, so nothing the agent returns is
  silently dropped (Phase 2 replaces these with real drawings).
- Panel: `PanelModel` carries cards as `(kind, title, body)`; the panel shows the
  body as plain text under the title (Markdown rendering is Phase 2). The `answer`
  card is expanded by default; others collapsed to their title.
- Kill-switch: `BuildConfig.ONE_TAP_ASK` gates the new default (OFF → stage-6 sheet
  behaviour). See Acceptance for the default.

## Interface contracts

- **Exposes:** `canvas.ask` on `POST /jobs` (no longer 422); `text`/`underline`
  rendering and the unknown-type fallback on the device; card bodies in the panel.
- **Consumes:** `agent-output` (unchanged: `text`, `underline`, `answer` already in v1),
  `device-api` (unchanged: `instruction` already optional, `canvas.ask` already listed),
  `coordinate-mapping` (`text.size` rule). **No contract change.**

## Testing requirements

- Server: pytest as listed above; contract check unchanged.
- Android JVM: `send()` with no note builds a `canvas.ask` body with `instruction`
  absent; with a note, `instruction` present; "Mark it up instead" builds
  `canvas.annotate`; `text` placement maps `at`/`size` exactly for the default canvas;
  unknown-type fallback bbox for each fixture annotation in
  `valid-full-vocabulary.json` is within 1 CU of `points × canvas size`.
- Instrumented (emulator, MockWebServer replaying a recorded `canvas.ask` response
  with a `text` annotation and an `answer` card): one tap → job done → text drawn at
  the fixture position, panel shows the card body.
- UI-smoke asset `smoke/ask.md`: on the tablet against staging with the agent on,
  write "what is 1+9=?", tap Send, expect "10" beside the question and an answer
  card; write "2+2=5", expect an underline and a correction card; write a short
  to-do list, expect at most one card and no decoration.

## Acceptance conditions

- [ ] Kill-switch / dark-launch flag (default OFF) for this net-new feature:
      `BuildConfig.ONE_TAP_ASK`. **Documented exception:** default ON in debug AND
      release, because prod is not promoted, staging is the only environment, and the
      server-side `AGENT_ENABLED` already dark-launches all agent work. Record the
      exception in the PR.
- [ ] UI-smoke "observably-works" check authored for any user-facing surface
      (`smoke/ask.md`).
- [ ] Additive migration only (no destructive schema change): none expected.
- [ ] The "what is 1+9=?" acceptance passes on the physical tablet against staging.
- [ ] Existing suite stays green; CI all-green

## Pipeline test: YES
