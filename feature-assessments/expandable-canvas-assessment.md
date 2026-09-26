# Assessment: expandable canvas (ADR-0014): stages 32 (repurposed), 34, 35

- **Request (owner, 2026-09-25):** "I don't want to be limited to A4 size. I want the canvas
  to expand as needed, similar to how Visio works."
- **Owner choices:** growth adds pages **in any direction**; Ask sends the **visible area**.
- **Architecture:** ADR-0014, plus dated additive sections in `ink-storage`,
  `coordinate-mapping` and `device-api`.
- **Decision:** **SPLIT into three stages**, in dependency order:
  - **32 (chore):** grid model, Room v5, tiled LOD rendering, visible edges.
  - **34 (feature):** the growth rule behind `EXPANDABLE_CANVAS`, off in release.
  - **35 (feature):** region export, origin-aware agent geometry, formalize sizing, and
    release turned on.
- **Stage 33** (low-latency defaults) is re-pointed to depend on 31 and goes **first**.
  Stage 32 then depends on 33, so two stages never edit `InkView`/`WetInkLayer` at once.

## Claim / reality (code survey, 2026-09-25)

| ADR-0014 assumption | Reality in `android/app/src/main/java/com/inkwell/` | Consequence |
|---|---|---|
| One page-sized committed cache | `render/LayerRenderer.kt:61-68,135-148`: one `widthCu × heightCu` ARGB bitmap anchored at (0,0), fully rebuilt on change; about **34.8 MB**. `CanvasExporter.kt:156` allocates another one per ink layer | stage 32: per-page LOD tiles under an LRU budget, per-tile invalidation, and render layers straight into the export |
| Room holds canvas size only | `data/Entities.kt:38-39`; `InkDatabase` v4, migrations 1→2→3→4; no DAO query touches size | stage 32: v5 extent columns and `updatePageExtent` |
| Canvases are created in a few places | `CanvasRepository.kt:124-146,227-233`, `LibraryRepository.kt:72-80`, `PushInbox.kt:158-170` | stage 32: set grid defaults explicitly in all four |
| Pushed rasters assume a page | `RasterFit.destRectPx` is placement-based; one raster per canvas | no change |
| Agent geometry maps via canvas size | `AnnotationRenderer.kt:52-53` (`nm × widthCu/heightCu`, no origin); gutter at `pageRight = widthCu` (343-350); label clamps (311, 435, 441); `AnnotationGeometry.kt:126,210-232`; `AnchorHitTest.kt:34-42` | stage 35: map through each job's region |
| The device keeps each job's export region | **No.** Annotations are in memory only (`CanvasViewModel.kt:199,246,331,722`), not reloaded on reopen; only an agent-layer row with `job_id` is saved | stage 35: carry the region with the result in memory. Persisting annotations is a separate, pre-existing gap and out of scope |
| Export is whole-page | `CanvasExporter.kt:87-94,124-165` at `tx = ty = 0`; `JobRequestBuilder.kt:22-29` sends `{w,h,width_cu,height_cu}` | stage 35: region translate and origin keys |
| Selection exists | `selection` is never filled in (`CanvasViewModel` passes none); only tests use the helpers | nothing to migrate |
| View bounds | `CanvasTransform` scale 0.1–8, **no pan clamp and no fit on open** | no change needed; "fit grid to screen" is a possible later nicety |
| Thumbnails | `ThumbnailRenderer.kt:50` exports page (0,0) at full CU | stage 32: grid bounds at thumbnail resolution |
| Hit testing | eraser, `StrokeMapper` and `StrokeBuilder` bbox are unbounded; no point clamps anywhere | no change |
| Server | stores `export` as an opaque dict; only `_formalize_canvas` sizes a canvas (from the source canvas) | stage 35: size from the export region |

## Why this split

- **32 first, and invisible apart from page edges.** The tiled renderer is the riskiest
  change (memory, parity, the stage 31 wet→dry handoff). Proving it on single-page
  canvases, with a pixel-parity test, isolates that risk before anything grows. It is
  typed as a chore: infrastructure with an exit state, not a switchable feature. The
  migration's repair makes the pre-existing invisible off-page ink visible, which fixes
  the v0.0.20 report for existing data.
- **34 behind a switch, off in release.** Growth without region export would make Ask send
  only page (0,0), silently hiding new pages from the agent. Shipping growth dark keeps
  every release coherent.
- **35 turns it on.** Region export, origin-aware geometry and the legibility floor are
  what make multi-page canvases correct end to end. The server change (formalize sizing)
  is small enough to share the stage.

## Memory budget (stage 32)

A full-resolution A4 tile is about 34.8 MB. At an LOD of 1/2 it is about 8.7 MB, and at
1/4 about 2.2 MB. The Idea Tab Pro is a mid/high tier tablet; its app heap limit is
unverified and stage 32 should log `ActivityManager.getMemoryClass()`. So:
- the starting budget is 96 MB: two full-resolution tiles plus headroom, or dozens of
  zoomed-out tiles;
- zoomed-in writing touches 1–2 tiles, and zoomed-out viewing uses low-LOD tiles.

The builder may tune the budget with a measurement recorded here.

### Stage 32 measured budget

The builder had no device or emulator (instrumented tests run in CI), so the budget is set
by arithmetic and is checked by `TiledRendererBudgetInstrumentedTest`; the device figure
comes from the smoke run (`smoke/android-ink.md` step 13, logcat `InkView memoryClass=…`).

- **Budget: 96 MB per ink layer (unchanged).** Tile bitmaps are native memory
  (API 26+), so they do not count against the Java heap `memoryClass`; the budget bounds
  native pixels instead.
- **Single-page canvas:** one LOD-0 page tile, 34.8 MB — exactly the old cache, used at
  every scale ≥ 0.5, so output is pixel-identical. Zoomed out, the LOD-1 tile adds 8.7 MB.
- **Multi-page grid:** the finest LODs are split into 620 × 877 px sub-tiles (2.2 MB for
  A4), so a 2944 × 1840 view at scale 1 over a page corner costs at most 24 small tiles
  (about 52 MB), not four whole pages (139 MB).
- **The visible set always fits:** the LOD is coarsened until the visible tiles fit the
  budget. A 3 × 3 grid, fully visible at scale 0.2, is 9 tiles at LOD 2 (19.6 MB); an
  8 × 8 grid fully visible on the tablet is 64 pages at LOD 3 (about 35 MB).
- **Export** keeps its own LOD-0 page raster (34.8 MB, as before), now shared by all ink
  layers instead of one per layer.

## Deferred / out of scope

- Persisting agent annotations across a canvas reopen (a pre-existing gap).
- Trimming empty pages (ADR-0014: no auto-shrink).
- "Fit grid to screen" and pan clamping.
- Per-page PDF export or print.
- Region selection by lasso (SPEC §13 Q4).
