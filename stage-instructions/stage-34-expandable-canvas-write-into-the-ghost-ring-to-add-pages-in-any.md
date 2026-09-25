# Stage 34: Expandable canvas: write into the ghost ring to add pages in any direction (8-page cap)

- **Type:** feature
- **Depends on:** 32
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/71
- **Design:** ADR-0014 §2 (the growth rule); contract `ink-storage` "ADR-0014 additions";
  `feature-assessments/expandable-canvas-assessment.md`. Code: `ink/InkView.kt` (stroke
  start and commit), `ink/WetInkLayer.kt`, `render/CanvasTransform.kt`, `ui/CanvasViewModel.kt`,
  `data/CanvasRepository.kt` (`updatePageExtent`), and the stage 32 tiled renderer and grid drawing.

## Objectives

The owner wants the canvas to "expand as needed, similar to how Visio works". After this
stage:
- a faint **ghost ring**, one page deep, surrounds the page grid;
- a pen or marker stroke may start anywhere in the grid **or** the ring;
- on pen-up, the grid grows by whole rows and columns in any direction to cover the stroke;
- what you see while writing is what stays.

It is gated behind a kill-switch that is off in release until stage 35 makes Ask send the
visible region. Growing a canvas while Ask still exports only page (0,0) would silently
hide the new pages from the agent.

## What to build

1. **Kill switch.**
   - Add `BuildConfig.EXPANDABLE_CANVAS`: `true` in debug, **`false` in release** (stage 35
     flips release on).
   - When it is off, the ring is not drawn, the grid never grows, and a stroke that starts
     outside the grid does not start. That is the fixed-page behaviour, now with visible
     page edges from stage 32.
2. **Ghost ring.**
   - Draw the pages adjacent to the grid (one page deep, all eight directions) as faint,
     dashed-edge pages.
   - Stop drawing the ring on any side where growth would exceed the cap.
3. **Where a stroke may start.** A stylus `ACTION_DOWN` (pen or marker) starts a stroke if
   its canvas point lies in the grid or the ring. Otherwise it does not start: the gesture
   is consumed and does not pan. This applies to both render paths (View and the stage 31
   wet layer). The eraser works anywhere, unchanged.
4. **The live stroke is not clipped** anywhere inside grid ∪ ring. It may continue beyond
   the ring once started; see the cap rule below.
5. **Growth on commit.** After `commitStroke()`:
   - compute the smallest grid of whole pages covering the old grid ∪ the stroke's bbox;
   - clamp each axis to 8 pages;
   - persist it with `updatePageExtent` in the same transaction as the stroke;
   - update the renderer and the ring;
   - the grid is rectangular and never shrinks.
6. **Cap.**
   - If the stroke's bbox exceeds the cap, the stroke is still committed with all its real
     points (the contract allows points beyond the grid; they render clipped at the grid
     edge, per `ink-storage`).
   - Show a one-line, non-blocking notice: "Canvas is at its 8-page limit this way".
   - The live rendering of a stroke that crosses the cap line is clipped at that line, so
     live equals committed.
7. **Undo and erase never shrink the grid** (ADR-0014: no auto-shrink).

## Interface contracts

- **Consumes:** `ink-storage` ADR-0014 additions (grid growth rules, the 8-page cap,
  stored points untouched).
- **Exposes:** grid growth events for stage 35, which needs the current grid bounds to
  intersect with the viewport.
- No server change.

## Testing requirements

- **Unit:**
  - the growth calculation in all eight directions, including negative columns and rows;
  - multi-page jumps (a long stroke crossing two ring pages);
  - the cap clamp on each axis;
  - the start test (grid / ring / beyond);
  - with the switch off, there is no growth and the start test uses the grid only.
- **Instrumented**, with both the low-latency pen on and off:
  - a stroke started in the ring to the left commits one stroke with all its points, and
    the grid becomes `page_min_col = -1`;
  - a stroke started beyond the ring commits nothing;
  - a stroke at the cap commits and the grid stays at 8;
  - the grid extent persists across reopening the canvas;
  - the eraser works across pages.
- **Regression:** a stroke written across the old page edge does not disappear on pen-up.
  This is the original v0.0.20 report, and the test must fail on the pre-stage behaviour.
- **UI-smoke:** add `smoke/android-expandable-canvas.md` (debug APK, since release is off
  until stage 35):
  - write into each of the four side rings and one corner, and see the pages appear;
  - write off the ring: nothing happens;
  - reach the cap: the notice shows;
  - reopen the canvas: the grid is kept;
  - run with the low-latency pen on and off.

## Acceptance conditions

- [ ] Kill-switch / dark-launch flag (default OFF) for this net-new feature (`EXPANDABLE_CANVAS` off in release until stage 35)
- [ ] UI-smoke "observably-works" check authored for any user-facing surface
- [ ] Additive migration only (no destructive schema change): no new migration; grid writes only via `updatePageExtent`
- [ ] Regression: ink written across the old page edge no longer vanishes on pen-up
- [ ] Existing suite stays green; CI all-green, plus the on-demand instrumented lane green on the branch

## Pipeline test: NO
