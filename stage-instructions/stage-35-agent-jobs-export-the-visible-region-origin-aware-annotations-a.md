# Stage 35: Agent jobs export the visible region: origin-aware annotations and anchors, legibility floor, formalize sizing; expandable canvas on in release

- **Type:** feature
- **Depends on:** 34
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/72
- **Design:** ADR-0014 §4–§6; contracts `coordinate-mapping` "ADR-0014 additions — region
  export" and `device-api` "ADR-0014 additions — expandable canvases";
  `feature-assessments/expandable-canvas-assessment.md`.
  - **Device code:** `render/CanvasExporter.kt:87-94,124-165`, `render/CoordinateMapping.kt:30-153`,
    `net/JobRequestBuilder.kt:22-29`, `ui/CanvasViewModel.kt:106,199,246,331,440,605-607,722,824,846`,
    `render/AnnotationRenderer.kt:52-53,182-350,435-441`, `render/AnnotationGeometry.kt:126,210-232`,
    `render/AnchorHitTest.kt:34-42`, `ink/InkView.kt:305-308`.
  - **Server code:** `server/app/jobs/canvas_annotate.py` (`_formalize_canvas`).

## Objectives

After this stage, Ask, Annotate and Formalize send **what is on screen**: the viewport,
intersected with the page grid. Agent markup lands where it belongs on a multi-page
canvas, and growing the canvas afterwards never moves it. A too-zoomed-out view disables
Send with a "zoom in to send" hint. With that in place, **`EXPANDABLE_CANVAS` turns on in
release.**

## What to build

1. **Region export (device).**
   - `CanvasViewModel` computes the region: the viewport rect in CU, intersected with the
     page-grid bounds and snapped to whole CU.
   - `CanvasExporter` rasterises that region: ink tiles, the pushed raster, and ink
     layers, translated by `-origin × scale`. Apply the `coordinate-mapping` formula to the
     region, and keep the 2 MB cap and palette retry as they are.
   - `CoordinateMapping.Export` gains `originX`/`originY`, and the nm↔cu helpers take the
     origin into account.
   - `JobRequestBuilder` emits `origin_x_cu`/`origin_y_cu`, always, including 0.
2. **Legibility floor.** If the region's longest edge exceeds `2 ×` the page's longest edge,
   disable Send, Ask and the job-type picker with an inline "Zoom in to send" hint.
   Recompute on every transform change.
3. **Origin-aware agent geometry.**
   - Carry each job's export region `(originX, originY, widthCu, heightCu)` with its result
     (`LoopOutcome`, `PendingRedraw` and the in-memory annotation set; annotations are not
     persisted today, and that stays out of scope).
   - `AnnotationRenderer`, `AnnotationGeometry` (margin-note bounds and stacking),
     `AnchorHitTest` and the label clamps map and clamp through **that job's region**,
     never through the canvas's current bounds.
   - The margin-note gutter sits at the region's right edge.
   - Jobs without an origin (none on device today, but defensively) map as origin `(0,0)`.
4. **Formalize sizing (server).**
   - `_formalize_canvas` sizes the new canvas from `job.request.export.width_cu/height_cu`
     when present.
   - Otherwise it falls back to the source canvas, then to A4.
   - Server test: a region export produces a new canvas of the region's size. A request
     without `export` sizes are unchanged.
5. **Canary and server smoke still pass.** `server/app/canary/run.py` sends a single-page
   export with no origin, which is valid per the contract; leave it as it is.
6. **Turn on release.** Set `BuildConfig.EXPANDABLE_CANVAS = true` in release, with a
   documented-exception comment like LIBRARY/FORMALIZE. The stage-34 kill switch stays in
   place for rollback.

## Interface contracts

- **Consumes:**
  - `coordinate-mapping` ADR-0014 additions: region export and origin-aware mapping back;
    the validation band is unchanged.
  - `device-api` ADR-0014 additions: the optional `export.origin_x_cu/origin_y_cu`, and
    the formalize result size.
  - `ink-storage` grid bounds.
- **Exposes:** nothing new. No new contract. The additions are already frozen by ADR-0014.

## Testing requirements

- **Unit (device):**
  - region = viewport ∩ grid, including negative origins and partial overlap;
  - the export formula on a landscape region (`3508 × 2480` → `1568 × 1109`);
  - `JobRequestBuilder` emits the origin keys;
  - nm→cu with an origin, for `rect`, `points`, `text.size`, `margin_note.y` and anchors;
  - the legibility floor threshold;
  - a later grid change does not move an existing job's annotations.
- **Unit (server):** formalize sizing from the region; unchanged without it. The existing
  suite is green.
- **Instrumented:**
  - on a 2×1 canvas scrolled to page (1,0), Ask exports that region and a highlight fixture
    lands on page (1,0) at the right spot;
  - after growing the canvas left, that highlight does not move;
  - zoomed out beyond the floor, Send is disabled with the hint.
  - Update the page-sized expectations in `CanvasExportInstrumentedTest`,
    `AnnotationRenderInstrumentedTest` and `LoopInstrumentedTest` only where the region
    now differs.
- **UI-smoke:** extend `smoke/android-expandable-canvas.md` (now on the release APK):
  - on a multi-page canvas, Ask about writing on page 2: the answer is about that writing
    and the markup lands on it;
  - zoom far out: "Zoom in to send";
  - Formalize from a region: the new canvas matches.
  - Server: the canary passes after deploy.

## Acceptance conditions

- [ ] Kill-switch / dark-launch flag (default OFF) for this net-new feature (the stage 34 `EXPANDABLE_CANVAS` kill switch remains; this stage deliberately turns release ON, per the documented exception)
- [ ] UI-smoke "observably-works" check authored for any user-facing surface
- [ ] Additive migration only (no destructive schema change): no migration; request shape additive per `device-api` ADR-0014 additions
- [ ] Agent geometry maps through its job's region; unchanged after later growth
- [ ] Existing suite stays green (server and Android); CI all-green, plus the on-demand instrumented lane green on the branch

## Pipeline test: NO
