# Stage 32: Fix: ink drawn outside the page vanishes on pen-up (no visible page edge when zoomed out)

- **Type:** bug
- **Depends on:** 31
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/69
- **Design:** contract `ink-storage` (`x`,`y` range `0..width_cu`/`0..height_cu`, "may
  exceed during pan; clamp at render"); SPEC §1.2 (no infinite canvas in v1), §4.2 (default
  A4, 2480 × 3508 CU); `feature-assessments/off-page-ink-assessment.md`. Code:
  `ink/InkView.kt`, `ink/WetInkLayer.kt`, `render/LayerRenderer.kt` (page-sized committed
  cache), `render/CanvasTransform.kt` (`minScale = 0.1`), `render/EraserHitTest.kt`.

## Objectives

Owner report on v0.0.20 (2026-09-25): after zooming out, the area you can write on stays
the same size, and anything written outside it disappears when the pen lifts.

Cause:
- The canvas is a fixed page, but nothing on screen shows where it ends.
- The in-progress stroke is drawn unclipped, on both the View path and the stage 31 wet layer.
- Committed ink renders from a page-sized bitmap cache, so off-page parts are silently cut
  away on commit.
- Off-page points are still stored, which the contract tolerates, but they are invisible.

The bug predates stage 31. The contract tolerates the storage side, so what needs fixing
is that the screen doesn't match what gets saved.

After this stage, **what you see while writing is what stays**:
- The page is visibly distinct from its surroundings.
- A stroke cannot start off the page.
- A stroke dragged across the edge is clipped at the edge while it is drawn, exactly as it
  will look once committed.

## What to build

1. **Visible page.**
   - Draw the page rect (`0,0 → width_cu,height_cu` through `CanvasTransform`) as the paper:
     the page fill plus a subtle edge or shadow.
   - Draw the area outside it in a distinct surround colour, in light and dark theme alike,
     using the existing theme colours; do not invent a palette.
   - Pushed-document rasters and agent annotations keep rendering exactly as today, on the page.
2. **No off-page stroke starts.**
   - A stylus `ACTION_DOWN` whose canvas-space point lies outside the page does not start a
     stroke on either path (View or wet layer), for pen and marker.
   - The gesture is consumed; it does not pan.
   - Eraser behaviour is unchanged. Erasing off-page may still hit legacy invisible strokes,
     which is fine and gives a way to clean them up.
3. **Clip the live stroke at the edge.**
   - While a stroke that started on the page crosses the edge, clip its live rendering to
     the page rect on both the View path and the wet layer, including the predicted tail.
     The wet→dry handoff must then show no change.
   - Keep storing the real points as captured. The contract allows points past the edge;
     do **not** clamp or drop stored points, because that would change geometry and the
     equality tests.
4. **Committed and exported rendering stay as they are.** The page-sized cache already
   clamps. Export (`CanvasExporter`) already covers the page only, so the agent never sees
   off-page ink.
5. **Legacy off-page strokes:** no migration and no deletion. Strokes that are entirely off
   the page stay stored but invisible. Record them as a known leftover in the assessment.

## Interface contracts

- **Exposes:** nothing new.
- **Consumes:** `ink-storage`, unchanged. This stage implements its existing "clamp at
  render" clause for the live stroke. `coordinate-mapping` is unchanged.

## Testing requirements

- **Unit:** a pure page-hit and clip helper covering: a point in or out of the page after
  pan and zoom; a segment crossing the edge clipped correctly; the page rect through
  `CanvasTransform` at `scale = 0.1`.
- **Instrumented:**
  - with the view zoomed out so the page is smaller than the view, a synthetic stylus
    stroke starting off the page commits **no** stroke;
  - a stroke starting on the page and crossing the edge commits **one** stroke with **all**
    its real points;
  - run both with the low-latency pen off and on;
  - the existing ink, canvas and low-latency tests stay green.
- **Regression:** the two instrumented cases above are the regression tests for this bug.
- **UI-smoke:** add a section to `smoke/android-ink.md`:
  - zoom out: the page edge is clearly visible;
  - write off the page: nothing appears;
  - write across the edge: the stroke is cut at the edge while drawing and looks the same
    after lifting;
  - run it with Low-latency pen on and off.

## Acceptance conditions

- [ ] Regression test(s) that fail before the fix and pass after
- [ ] Page edge visible when zoomed out, in light and dark theme
- [ ] What is drawn live equals what remains after pen-up, on both render paths
- [ ] No stored-point clamping; `ink-storage` unchanged; no migration
- [ ] Existing suite stays green; CI all-green, plus the on-demand instrumented lane green on the branch

## Pipeline test: NO
