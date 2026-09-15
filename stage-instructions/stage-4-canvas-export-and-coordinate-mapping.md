# Stage 4: Canvas export and coordinate mapping

- **Type:** feature
- **Depends on:** 3
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/4
- **Design:** SPEC §5, contract `coordinate-mapping`, contract `device-api` (`POST /jobs` body)

## Objectives

Implement the device half of the highest-risk seam: flatten a canvas to a PNG whose
longest edge is exactly 1568 px, produce the `export` metadata, and map normalized
geometry back to canvas units exactly. Also render the first agent geometry type
(`highlight`) on an agent layer so Stage 6 only has to wire, not draw.

## What to build

- `render/CanvasExporter`: scale computation per the contract, render visible layers
  in `z` order onto opaque white, PNG encode, size guard (one palette-reduction retry,
  then a user-visible failure; never send >2 MB).
- `render/Coordinates`: `nmToCu`, `cuToNm`, `sizeToCu`, selection rect mapping;
  pure functions with no Android dependencies so they run in JVM tests.
- `render/AnnotationRenderer` for `highlight` only: constant width, 70% opacity,
  space accent color unless `color` set, on an `agent/annotation` layer.
- `data/`: `LayerRepository.createAgentLayer(canvasId, jobId)`; agent layers are
  never mutated after creation.
- `net/`: `JobRequestBuilder` assembling the contract `device-api` `POST /jobs` body
  (`image`, `export`, `instruction`, `selection`) from an export.
- `ui/`: a debug-only "Export preview" action that shows the PNG and its dimensions,
  and a debug-only "Render fixture" action that draws
  `contracts/fixtures/agent-output/valid-full-vocabulary.json` highlights onto the
  current canvas so placement can be eyeballed before the server exists.

## Interface contracts

- **Exposes:** `CanvasExporter`, `Coordinates`, `AnnotationRenderer(highlight)`,
  `JobRequestBuilder`.
- **Consumes:** contract `coordinate-mapping` (exact rules), contract `agent-output`
  (highlight shape via generated types), contract `device-api` (job body).

## Testing requirements

- JVM unit tests: for canvases 2480×3508, 3508×2480, and 1000×1000 the export
  dimensions have longest edge 1568 and preserve aspect within rounding;
  `nmToCu([0,0])`, `[1,1]`, `[0.5,0.5]` are exact; `cuToNm(nmToCu(p)) == p` within
  1e-6; `text.size` maps to `size * height_cu`; the job body serialises with the
  contract field names and `image` is valid base64 of a PNG.
- Instrumented: exporting a canvas with three drawn rectangles yields a PNG whose
  pixel at the centre of the middle rectangle is non-white and whose corners are white.
- Fixture render test (instrumented): rendering the `valid-full-vocabulary` highlight
  places its bbox at `points × canvas size` within one CU.

## Acceptance conditions

- [ ] Kill-switch / dark-launch flag (default OFF) for this net-new feature:
      the two debug actions are debug-build only; no release-visible surface yet.
- [ ] UI-smoke "observably-works" check authored for any user-facing surface:
      `smoke/android-export.md` (debug build: export preview shows 1109×1568 for the
      default canvas; fixture highlight lands where expected).
- [ ] Additive migration only (no destructive schema change).
- [ ] Existing suite stays green; CI all-green

## Pipeline test: NO
