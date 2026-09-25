# Stage 32: Page-grid canvas foundation: Room v5 page extent, tiled level-of-detail ink cache, visible page edges

- **Type:** chore
- **Depends on:** 33
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/69
- **Design:** ADR-0014 (canvases grow by pages; §1 grid model, §3 tiled rendering);
  contract `ink-storage` "ADR-0014 additions — page grid"; `feature-assessments/expandable-canvas-assessment.md`.
  Code: `data/Entities.kt:38-39`, `data/InkDatabase.kt:45,128`, `data/CanvasRepository.kt`
  (`CanvasState`, creation at 124-146 and 227-233), `data/LibraryRepository.kt:72-80`,
  `net/PushInbox.kt:158-170`, `data/dao/Daos.kt`, `render/LayerRenderer.kt:61-68,97-101,135-148`,
  `render/CanvasExporter.kt`, `data/ThumbnailRenderer.kt:50`, `ink/InkView.kt:116-117,183-186`,
  `ui/CanvasScreen.kt:137`.

> **History:** stage 32 was first planned (2026-09-25) as "Fix: ink drawn outside the page
> vanishes on pen-up": refuse off-page strokes and clip at the page edge. ADR-0014 reversed
> that premise, since the owner wants the canvas to grow. The work-item (#69) was
> repurposed rather than closed, because a closed issue reads as merged in the ledger.

## Objectives

This stage lays the foundation for expandable canvases without changing what a single-page
canvas does. After it:
- a canvas carries a **page grid** (Room v5);
- committed ink renders from **page tiles** at a resolution matched to the zoom, within a
  fixed memory budget, instead of one page-sized bitmap (about 34.8 MB, and a second one
  per ink layer during export);
- the page edges are **visible**: each page drawn as paper, with a distinct surround;
- ink written off the page since v0.0.20 becomes **visible again**, because the migration
  grows those canvases' grids to cover it.

Growth while writing is stage 34. Region export is stage 35.

## What to build

1. **Room v5.**
   - `CanvasEntity` gains `page_min_col`, `page_max_col`, `page_min_row`, `page_max_row`
     (Int, NOT NULL, default 0), per the `ink-storage` ADR-0014 section.
   - Add `MIGRATION_4_5` with ADD COLUMN … DEFAULT 0 and commit `schemas/…/5.json`. No
     destructive fallback.
   - **Covering existing ink:** at the end of the migration, or in a one-shot post-migration
     repair run once and recorded in prefs:
     - for each canvas whose strokes' bboxes (`bbox_*` columns) extend beyond page (0,0),
       set the grid to the smallest rectangle of whole pages covering them;
     - clamp to 8 pages per axis, keeping page (0,0) inside;
     - never touch stored points.
   - `CanvasState` carries the extent. Add a DAO `updatePageExtent`.
   - Every creation path writes the grid defaults explicitly: default canvas, new canvas,
     formalize, pushed.
2. **Tiled level-of-detail committed-ink cache.**
   - Replace `LayerRenderer`'s single page bitmap with per-page tiles keyed by
     `(layer, col, row, lodLevel)`.
   - A tile renders only the committed strokes whose bbox intersects it.
   - `lodLevel` is picked from `CanvasTransform.scale`, in powers of two, capped at
     today's full resolution (1 px per CU).
   - Only tiles intersecting the viewport are drawn or built.
   - An LRU keeps the total tile bytes under a documented budget. Start at 96 MB and
     justify the number in the assessment.
   - Invalidation is per tile: adding, erasing or undoing a stroke dirties only the tiles
     its bbox touches, not the whole layer.
   - Pushed rasters and agent annotations keep their current paths.
   - At page (0,0) and scale 1, the output must be pixel-identical to today. There is an
     instrumented test for this.
3. **Visible page grid.**
   - Draw every page in the grid as paper, with a subtle edge or shadow, and the area
     outside the grid in a surround colour. Use the existing theme tokens, light and dark.
   - Draw it beneath the pushed raster and ink.
   - The ghost ring is **not** drawn yet (stage 34).
4. **Thumbnails** render the full grid bounds scaled into the thumbnail box
   (`ThumbnailRenderer`), and render at thumbnail resolution, not full CU.
5. **Export is unchanged in this stage:** page (0,0) only, as today. Stage 35 changes it.
   Do **not** let `CanvasExporter` allocate a full-page bitmap per ink layer any more if it
   can render the layers directly into the export bitmap. That is a free memory win; keep
   the output byte-identical.
6. **Stage 31 interplay:** the wet layer, prediction and the wet→dry handoff must still
   match. After a commit, the dry tile (not a page bitmap) is what the handoff waits on.

## Interface contracts

- **Consumes:** `ink-storage`, per its ADR-0014 additions (Room v5 columns, widened range).
  `coordinate-mapping` and `device-api` are untouched in this stage.
- **Exposes:** `CanvasState.pageExtent` and the tiled renderer API, for stages 34 and 35.

## Testing requirements

- **Unit:**
  - the tile keying and `lodLevel` choice;
  - tiles for a stroke bbox, including negative coordinates and page edges;
  - LRU eviction under budget;
  - the grid-covering calculation for the migration repair, including the 8-page clamp;
  - thumbnail fit of a multi-page grid.
- **Instrumented:**
  - `InkDatabaseMigrationTest` 4→5: defaults, plus the repair for off-page strokes,
    positive and negative;
  - single-page render parity against the pre-change renderer at scale 1 and at 0.5;
  - a canvas with a 3×3 grid of strokes renders all tiles and stays under budget;
  - the existing canvas, export, annotation, low-latency and loop tests stay green,
    updating hard-coded `setCanvasSize(2480, 3508)` call sites only where the API changed.
- **UI-smoke:** add a "Page grid" section to `smoke/android-ink.md`:
  - zoom out: page edges are visible;
  - a canvas that had off-page ink now shows it, with its extra pages;
  - write, erase and pan feel as before;
  - memory check: open a canvas with ink on many pages, pan around, and confirm there is
    no stutter or crash (developer options, memory).

## Acceptance conditions

- [ ] Exit state: Room v5 with grid extent; existing canvases open unchanged; previously invisible off-page ink visible again
- [ ] Committed ink renders from viewport-culled LOD tiles under a memory budget; single-page output pixel-identical
- [ ] Page edges visible in light and dark theme
- [ ] Additive migration only; `ink-storage` ADR-0014 section honoured; stored points never modified
- [ ] Existing suite stays green; CI all-green, plus the on-demand instrumented lane green on the branch

## Pipeline test: NO
