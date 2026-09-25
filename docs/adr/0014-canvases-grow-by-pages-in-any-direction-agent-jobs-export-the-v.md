# 0014. Canvases grow by pages in any direction; agent jobs export the visible region

- **Status:** Accepted
- **Date:** 2026-09-25
- **Supersedes:** SPEC §1.2 non-goal "Infinite canvas. v1 canvases are fixed-size pages"
  (for user canvases; see Decision 1). Partly answers SPEC §13 open questions 1 (canvas
  size) and 3 (multi-page).

## Context

Every canvas is one fixed page: A4 at 300 DPI, 2480 × 3508 CU (SPEC §4.2). On v0.0.20 the
owner zoomed out and wrote beyond the page. The ink showed while being written and vanished
on pen-up. The live stroke is drawn unclipped, but committed ink renders from a page-sized
bitmap cache (`LayerRenderer`), and nothing on screen marks where the page ends.

The owner does not want a fixed page: *"I want the canvas to expand as needed, similar to
how Visio works."* Asked on 2026-09-25, the owner chose:
- **Growth:** add whole pages in **any** direction.
- **What an agent job sees:** the **visible area**.

What currently assumes one page:
- **`ink-storage`:** `x`,`y` ∈ `0..width_cu` / `0..height_cu` ("may exceed during pan;
  clamp at render").
- **`coordinate-mapping`:** export = the **whole** canvas scaled to a 1568 px longest edge,
  and mapping back is `cu = nm × width_cu` (so the origin is always 0).
- **`device-api`:** the job request `export: { w, h, width_cu, height_cu }` describes the
  whole canvas.
- **Device rendering:** one page-sized committed-ink bitmap (about 35 MB ARGB for A4). A
  3 × 3-page canvas would need about 310 MB, which would run out of memory.
- **Server:** it stores `export` as an opaque dict (`JobCreate.export: dict`) and never
  interprets the region. Only `canvas.formalize` sizes a new canvas, and it copies the
  **source canvas's** `width_cu/height_cu`.
- **Device canvases** are device-local (Room); the server holds only agent-origin canvases
  (pushed documents, formalized output).

## Decision

1. **A canvas is a grid of equal pages.**
   - `width_cu × height_cu` keeps its current meaning but is now the **page** size. It is
     fixed at creation; A4 portrait by default, and pushed or formalized canvases keep the
     size they are created with.
   - The canvas adds a page-grid extent: `page_min_col ≤ 0 ≤ page_max_col` and
     `page_min_row ≤ 0 ≤ page_max_row`, integers with default `0,0,0,0`.
   - Page `(c, r)` covers `[c·width_cu, (c+1)·width_cu) × [r·height_cu, (r+1)·height_cu)`.
     Canvas bounds are the union of all pages; **coordinates may be negative**.
   - Existing canvases are exactly page `(0,0)`. Nothing already stored moves.
2. **Growth rule (Visio-style, any direction).**
   - Around the current grid the device shows a one-page **ghost ring**: faint pages you
     can write into.
   - A pen or marker stroke may start anywhere in the grid or the ghost ring. A stroke that
     starts beyond the ring does not start.
   - On commit, the grid grows by whole rows or columns until it covers the stroke's bbox.
     The grid stays **rectangular**.
   - The grid is capped at **8 pages per axis**. A stroke that would exceed the cap commits
     clipped to the cap, and the device says why. This bounds memory, thumbnail and export
     work.
   - There is **no auto-shrink** in this decision. Trimming empty pages is a later, explicit action.
   - The live stroke is never clipped inside the writable area, so what you see is what stays.
3. **Rendering is page-tiled.**
   - Committed ink is cached per page tile, only for tiles in view, at a resolution that
     matches the current zoom (level of detail).
   - An LRU memory budget replaces the single page-sized cache, so memory does not grow
     with the grid.
   - The page grid (edges and surround) is always visible, including the ghost ring.
   - Pushed-document rasters stay on page `(0,0)`.
4. **Agent jobs export the visible region.**
   - The export **region** is the viewport, intersected with the canvas bounds and snapped
     to whole CU.
   - It is exported with the existing formula applied to the region: longest edge 1568 px,
     the 2 MB cap, and the same PNG rules.
   - The job request describes the region: `width_cu/height_cu` are the **region** size,
     plus two new optional integers, `origin_x_cu` and `origin_y_cu` (default `0`).
   - Mapping back is `cu = origin + nm × size`. For every existing job and every
     single-page full export, the origin is 0 and the size is the whole canvas, so this is
     byte-identical to today.
   - **Legibility floor:** if the region's longest edge exceeds **2 × the page's longest
     edge**, Send and Ask are disabled with an inline "zoom in to send" hint. Handwriting
     below about 0.22 px/CU in the export is not reliably legible.
   - `selection` stays normalized, relative to the exported image.
5. **Agent geometry is anchored to its job's region, never to the current bounds.**
   - Annotations, anchors, card regions and `margin_note` gutters map through the region
     recorded with their job. So growing the canvas later never moves existing agent ink.
   - Legacy jobs have an implied region of `(0, 0, export.width_cu, export.height_cu)`.
   - `brain_writes[].source_region` is normalized to the job's region, and a
     `brain_entries.job_id` leads back to it.
6. **The server changes minimally.**
   - It accepts and persists the two optional `export` fields; `export` is already an
     opaque dict.
   - `canvas.formalize` sizes its new canvas from the **export region**
     (`export.width_cu × export.height_cu`), falling back to the source canvas and then
     A4. This way the formalized geometry maps 1:1 onto a single-page new canvas.
   - No server table gains grid columns: device canvases are device-local, and agent-origin
     canvases are created as one page.
7. **Contracts are amended additively:**
   - `ink-storage`: the coordinate range, and the page-grid columns through a Room v5
     migration with defaults;
   - `coordinate-mapping`: region export and origin-aware mapping back;
   - `device-api`: the two optional `export` fields and the formalize sizing note.

   Every existing payload and row stays valid, with unchanged meaning.

## Alternatives considered

- **Whole-canvas export.** Simplest, but handwriting on a multi-page canvas shrinks below
  legibility. Rejected by the owner.
- **Ink-bbox export**, capped. Useful, but it hits the cap on big canvases and is harder to
  predict than "what I see". May come later as a selection gesture (SPEC §13 Q4).
- **Grow right and down only.** Avoids negative coordinates, but cannot add room above or
  left of the first note. Rejected by the owner.
- **Free-form infinite canvas.** No page structure for later print or export, and no
  natural tile. Rejected by the owner.
- **Clamp or drop stored points at the page edge** (the original stage 32). Loses ink and
  keeps the fixed page the owner does not want. Superseded.
- **A new contract instead of amendments.** Not needed: every change is a widened range,
  an optional field with a default that reproduces today's semantics exactly, or a new
  column with a default. None is breaking under framework-spec §4.3.

## Consequences

- **Stage 32**, as specified (refuse off-page starts, clip at the page edge), is
  **superseded**. `/verity:plan` should replan it as the page-grid work. Stage 33 (the
  low-latency defaults) is independent; its dependency on 32 existed only to avoid both
  stages editing the same files at once, and it can be re-pointed to 31.
- Expected stage split, which `/verity:plan` decides:
  1. page-grid data model + Room v5 migration + tiled LOD rendering + visible grid and
     ghost ring;
  2. the growth rule (commit-time growth, 8-page cap, live stroke unclipped inside the
     writable area);
  3. region export + origin-aware mapping of agent geometry + the legibility floor +
     formalize sizing on the server.

  The Android toolchain is unchanged.
- **Memory** becomes proportional to the tiles in view, not to canvas size. The tiled
  cache is the riskiest piece and needs a device check on the Idea Tab Pro with a full
  8 × 8 canvas.
- **Thumbnails** render the grid bounds scaled down. Library cards for multi-page canvases
  show the whole grid.
- **Strokes already written off the page** (invisible since v0.0.20) become visible once
  the grid covers them: the first growth or any grid that includes their bbox. A migration
  that grows the grid on upgrade to cover existing stroke bboxes, capped, surfaces them
  immediately. That belongs to stage 1 of the split.
- Export or print of pages (PDF per page) becomes possible later, because pages are real.
- SPEC §1.2 and §4.2 now disagree with the code. This ADR is the record; the Vision role
  owns SPEC.md and may fold it in.
