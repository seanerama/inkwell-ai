# Stage 9: Full annotation vocabulary: diagrams marked up legibly

- **Type:** feature
- **Depends on:** 7
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/22
- **Design:** SPEC §6.1, §6.3, §12 Phase 2; contracts `agent-output`, `coordinate-mapping`; ADR-0006 amendment

## Objectives

SPEC Phase 2 acceptance: the agent marks up a hand-drawn architecture diagram with
arrows, labels and margin notes, and every element is legible and correctly placed.
Today only `highlight`, `text` and `underline` draw natively; the other six render as
labelled boxes. After this stage all nine types draw as intended, margin notes have a
real gutter, and the annotate guidance asks for diagram-quality markup.

## What to build

**Android renderer (`render/`)**
- `arrow`: constant-width line from `from` to `to` with a filled triangular head
  (head length ≈ 3 × stroke width, in CU so it scales with zoom); optional `label`
  drawn at the midpoint, offset perpendicular, with a small white halo for legibility
  over ink.
- `ellipse`: stroke-only ellipse at `center` with `rx × width_cu`, `ry × height_cu`.
- `rect`: stroke-only rectangle; optional `label` above the top-left corner.
- `strikethrough`: polyline like underline, drawn through the text (same geometry;
  distinct dash pattern so it reads differently from an underline).
- `path`: polyline, closed when `closed` is true (stroke only, no fill).
- `margin_note`: rendered in a **gutter** to the right of the canvas: the canvas view
  reserves a right gutter of `0.18 × width_cu` CU (outside the page, not exported,
  shown in a faint tint), and the note is laid out as wrapped text at `y × height_cu`
  with a thin leader line to the page edge. Multiple notes at similar `y` stack
  downward without overlapping.
- `text`: word-wrap at `0.35 × width_cu` so long answers do not run off the page.
- All agent marks keep the SPEC §6.3 rules: constant width, 70% opacity, accent color
  unless `color` is set, never pressure-tapered. The fallback box remains only for a
  future type the app has never seen.
- The exporter must NOT include the gutter or the agent layer in what is sent to the
  model unless the user toggles "include agent marks" (default off), so the model sees
  the user's ink, not its own previous marks (SPEC §13 note on drift).

**Server prompt (`agent/prompt.py`)**
- `_JOB_GUIDANCE["canvas.annotate"]` rewritten for diagrams: name what each box is if
  legible, draw arrows for missing or wrong dependencies, use `margin_note` for
  observations that are not about one spot, use `rect`/`ellipse` to group, keep
  labels ≤ 4 words, and prefer 3–8 precise marks over many. Ask for `label` on every
  arrow. Effort for annotate stays `medium`.
- The system-prompt schema description gains one line per type with its exact fields
  and the coordinate meaning of each (from the contract), so the prompt-guided JSON
  path (ADR-0006 amendment) has the full vocabulary in front of it.

## Interface contracts

- **Exposes:** native rendering of all nine `agent-output` v1 types; the gutter.
- **Consumes:** `agent-output` (unchanged), `coordinate-mapping` (unchanged; the
  gutter is a view concern, not an export concern). No contract change.

## Testing requirements

- JVM: `AnnotationGeometry` gains arrow-head and label-anchor geometry with exact
  tests for the default canvas; margin-note stacking never overlaps for three notes
  within 0.02 of each other; text wrap width; `valid-full-vocabulary.json` renders
  with zero fallback boxes (a renderer "fallback count" exposed for tests).
- Instrumented: the fixture renders every type; a pixel probe finds the arrow head
  at the expected CU; the gutter tint is present and the export PNG width is still
  1109 (gutter excluded).
- Server: annotate guidance contains the vocabulary lines; a recorded diagram
  response with all nine types validates and stores.
- UI-smoke `smoke/diagram.md`: draw a three-box architecture sketch with two arrows,
  tap "Add a note…" → "Mark it up instead"; expect labelled arrows, at least one
  margin note in the gutter, and every mark legible and on the thing it refers to.

## Acceptance conditions

- [ ] Kill-switch / dark-launch flag (default OFF) for this net-new feature:
      `BuildConfig.FULL_VOCABULARY`, same documented exception as stage 7 (ON in both
      build types; OFF = stage-7 fallback boxes). Record in the PR.
- [ ] UI-smoke "observably-works" check authored for any user-facing surface
      (`smoke/diagram.md`).
- [ ] Additive migration only (no destructive schema change): none expected.
- [ ] The architecture-diagram test passes on the tablet against staging.
- [ ] Existing suite stays green; CI all-green

## Pipeline test: YES
