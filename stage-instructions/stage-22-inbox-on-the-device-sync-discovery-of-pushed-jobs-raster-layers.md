# Stage 22: Inbox on the device: sync discovery of pushed jobs, raster layers from PDFs and images, tab badges, send back

- **Type:** feature
- **Depends on:** 21
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/48
- **Design:** SPEC §4.3 (raster below ink), §4.5, §8 Sync cadence, §9.3 (badge on tab), §12 Phase 4; contracts `device-api` (Sync cursor, `to_user` result), `ink-storage` (v3 → v4 additive), `coordinate-mapping` (export composites the raster); ADR-0012

## Objectives

The Phase 4 acceptance from the tablet: a document pushed from the host appears on
the right space's tab with a badge, opens as a page you can write on, and **Ask**
sends the document and your marks back together. The device gains a persisted sync
cursor so it learns about pushed jobs even when it did not ask for anything.

## What to build

**Sync discovery (`net/SyncWorker.kt`, `net/SyncCursorStore.kt`, `net/PushInbox.kt`)**
- Persist the `/sync` cursor (prefs). On first run seed it with the *current* cursor
  (`sync(null).cursor`) so old history is not replayed. Then on every poll: `sync(cursor)`
  → for each job with `direction == "to_user"` and `status == "done"` not yet
  materialised (dedupe by job id in a small `inbox_jobs` table or by canvas id), hand it
  to `PushInbox.materialise(job)`; advance the cursor only after materialisation
  succeeds. Pages of 100 loop until short.
- Cadence per contract: foreground 60 s (5 s while a send is outstanding, already the
  case), background 5 min via the existing periodic `SyncWorker`. The Library's
  pull-to-refresh also triggers a poll.

**Materialise (`net/PushInbox.kt`, `data/CanvasRepository.kt`)**
- From `result`: canvases (`canvases[]` when present, else `[canvas]`), `layers`,
  `rasters` (with `url`), `cards`. For each canvas: Room `CanvasEntity` with the
  **server id**, `origin=agent`, the space's server id, `folder_id` = a new folder
  named after the document when there is more than one page (created once per job),
  `seen_at = null`; each raster layer as `LayerEntity(type=raster, z=-1, job_id)`; each
  `RasterEntity` with `blob_uri` = **local cache file path** after download
  (`GET <url>` with the bearer header; retry with a fresh `url` from `GET
  /canvases/{id}` on 403). Cards attach to the job as today. No user ink layer until
  the first stroke (stage 12 rule).
- Room v3 → v4, additive: `canvases.seen_at INTEGER NULL`; migration test extends the
  existing suite (ink survives; `rasters` table unchanged since v1).

**Rendering (`render/RasterRenderer.kt`, `render/LayerRenderer.kt`, `ink/InkView.kt`)**
- A raster layer renders beneath ink: PDF pages via `android.graphics.pdf.PdfRenderer`
  at canvas resolution (2480 px wide for A4) into a cached bitmap; images via
  `BitmapFactory` with `inSampleSize` to bound memory. Placement from `x_cu, y_cu,
  w_cu, h_cu`. Pan/zoom reuse the cached bitmap; never re-render per frame.
- `CanvasExporter` composites raster layers first, then ink, so the agent sees the
  document with the marks (SPEC §5.2 export ordering; `coordinate-mapping` unchanged).
- `ThumbnailRenderer` includes the raster so Library tiles show the page.
- Layer tray labels the layer "Document" with a visibility toggle (existing UI).

**Badges and Library (`ui/SpaceTabBar.kt`, `ui/LibraryScreen.kt`)**
- Tab badge = count of canvases in that space with `seen_at == null`; tile shows a
  "New" dot; opening the canvas sets `seen_at`. Multi-page folders show the count.
- Pushed canvases are ordinary canvases: rename, move, trash, Formalize all work.

**Send back** — nothing new: Ask/Mark up on a pushed canvas posts the canvas's server
id and the composited export; annotations render on top as usual.

**Kill-switch** `BuildConfig.PUSH_INBOX` (OFF = no cursor polling for `to_user`, no
badges; raster rendering code stays inert). Documented release-ON exception.

## Interface contracts

- **Exposes:** persisted sync cursor; pushed canvases in the Library; raster
  rendering and export.
- **Consumes:** `device-api` `/sync`, `GET /canvases/{id}`, `GET /blobs/{key}` (stage
  21); `ink-storage` v4 (additive); ADR-0012.

## Testing requirements

- Unit: cursor seeding and advancement (advance only after success); dedupe; folder
  creation for multi-page; raster fit maths; export order (raster below ink) with a
  fake bitmap.
- Instrumented (MockWebServer): a `/sync` page with one `to_user` job whose raster
  `url` serves a 1-page PDF fixture → canvas + raster in Room, file in cache, badge
  count 1 → open → `seen_at` set, badge 0. Room 3→4 migration test.
- Smoke `smoke/push-inbox.md` (the Phase 4 acceptance, on the tablet): from the
  workstation `ssh mini-hp01 'cat brief.pdf | dc run … inkwell push document --space
  learning --file - --title brief'`; within 60 s the Learning tab shows a badge; open
  it; the page is legible; write a question on it; Ask; the answer references the
  document's content. Then a 3-page PDF → a folder with three pages. Results table.

## Acceptance conditions

- [ ] Kill-switch `BuildConfig.PUSH_INBOX` (documented release-ON exception)
- [ ] UI-smoke asset `smoke/push-inbox.md` authored (push → badge → annotate → Ask)
- [ ] Additive migration only (Room v4 `seen_at`; migration test proves ink survives)
- [ ] Export of a pushed canvas contains the raster beneath the ink (tested)
- [ ] Existing suite stays green; CI all-green (instrumented lane run on the branch and linked)

## Pipeline test: NO
