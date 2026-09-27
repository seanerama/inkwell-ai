# Stage 36: Fit the page grid to the screen when a canvas opens, plus a Fit button (Ask sends the whole page again by default)

- **Type:** chore
- **Depends on:** 35
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/77
- **Design:** ADR-0014 §4 ("the export region is the viewport ∩ grid"); contract
  `coordinate-mapping` "ADR-0014 additions — region export", which is **unchanged**:
  fitting the view changes what is on screen, not the export rule. Owner decision
  (2026-09-27): option 2 from the stage 35 hand-off.
- **Code:**
  - `render/CanvasTransform.kt`: `scale/tx/ty`, `minScale 0.1`, `maxScale 8`, no fit today.
  - `ink/InkView.kt:350`: `setTransform(scale, tx, ty)`, which has no callers.
  - `ui/CanvasScreen.kt:116,697`: toolbar and overflow.
  - `ui/CanvasViewModel.kt`: canvas open, grid extent, and the stage 35 export region.

## Objectives

Stage 35 made Ask, Annotate and Formalize export **what is on screen**. Canvases still open
at scale 1 at the origin, with no fit, so on the Idea Tab Pro a freshly opened A4 note
shows only its top half, and Ask sends only that. Before stage 35, Ask always sent the
whole page.

The owner chose to **fit the page grid to the screen whenever a canvas opens**. The fitted
view makes stage 35's region equal to the whole grid:
- A single-page note is fully visible, so Ask sends exactly the whole page again (the
  stage 35 byte-identity path).
- A multi-page canvas opens showing all of its pages.

A **Fit** control brings that view back at any time.

This stage has **no kill switch** by design. It restores the pre-stage-35 default for
single-page Ask and has no new data, contract or server surface. It is a chore with an
exit state.

## What to build

1. **Fit math.** Add a pure function, for example `CanvasTransform.fitting(gridBoundsCu,
   viewW, viewH, marginPx)`, that returns `scale/tx/ty` so the whole grid is visible:
   - the grid is centred in the view;
   - a uniform margin (start at 16 dp on each side);
   - `scale = min((viewW − 2m)/gridW, (viewH − 2m)/gridH)`, clamped to `[minScale, maxScale]`;
   - if the clamp bites (for example an 8 × 8 grid below `minScale`), centre the grid at
     `minScale`.
2. **Fit on open.**
   - When a canvas opens, apply the fit once the view has a real size (after first
     layout; guard against a 0 × 0 size).
   - Re-apply it when the canvas changes. Do **not** re-fit on rotation or window resize
     mid-session; keep the user's view, and the Fit button covers that case.
   - **Never re-fit while writing, and never because the grid grew** (stage 34). Growth
     must not jump the view.
3. **Fit control.**
   - Add a "Fit" action to the toolbar strip, or the overflow if the strip is full. Use the
     stage 28 convention: reachable and never clipped.
   - It animates or snaps to the fitted view. A snap is fine; no animation is required.
4. **Send gating interplay.** After a fit, the stage 35 legibility floor must never block
   sending for any grid up to 2 pages on its longest axis. Assert this in tests for:
   - A4 portrait on a landscape and a portrait tablet-sized view;
   - A4 landscape;
   - a 2 × 1 grid.

   For larger grids, fitting may legitimately put the region beyond the floor ("Zoom in to
   send"). That is correct per the contract; leave it.
5. **Pushed and formalized canvases** also fit on open. A formalized canvas opens straight
   after its job, and it should be fully visible.

## Interface contracts

- **Consumes:** `coordinate-mapping` ADR-0014 additions (the region rule is unchanged);
  `ink-storage` grid bounds.
- **Exposes:** nothing new. No server change, no migration, no contract change.

## Testing requirements

- **Unit:**
  - the fit math for a single A4 portrait page in a landscape view (height-bound) and in
    a portrait view (width-bound);
  - A4 landscape;
  - 2 × 1, 1 × 3 and a grid with negative columns;
  - the clamp at `minScale` for an 8 × 8 grid;
  - margins honoured;
  - after a fit, the stage 35 region (viewport ∩ grid) **equals the whole grid** for the
    cases above;
  - the floor is not triggered for grids up to 2 pages on the longest axis.
- **Instrumented:**
  - opening a single-page canvas shows the whole page, and Ask's export equals the
    pre-stage-35 whole-page export byte for byte (reuse the stage 32/35 export-parity
    fixture);
  - opening a 2 × 1 canvas shows both pages;
  - growing the grid mid-session does not change the view;
  - after panning and zooming, Fit restores the fitted view;
  - with the low-latency pen on, strokes after a fit commit at the right canvas positions.
- **UI-smoke:** update `smoke/android-expandable-canvas.md`:
  - open a note: the whole page is visible, and Ask answers about the whole page;
  - open a multi-page canvas: all pages are visible;
  - write into the ring: the page grows, and the view doesn't jump;
  - Fit brings the whole grid back.

## Acceptance conditions

- [ ] Exit state: every canvas opens fitted to its page grid; a single-page note's Ask exports the whole page (byte-identical to pre-stage-35)
- [ ] Fit control reachable and never clipped; restores the fitted view
- [ ] Never re-fits on growth or while writing
- [ ] No migration, no contract or server change
- [ ] Existing suite stays green; CI all-green, plus the on-demand instrumented lane green on the branch

## Pipeline test: NO
