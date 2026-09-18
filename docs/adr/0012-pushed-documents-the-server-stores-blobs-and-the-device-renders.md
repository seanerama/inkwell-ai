# 0012. Pushed documents: the server stores blobs and the device renders them; agent-kind tokens for the push API

- **Status:** Accepted
- **Date:** 2026-09-18

## Context

Phase 4 (SPEC §12) makes the loop bidirectional: an agent puts a PDF or image in
front of the user as a raster layer on a new canvas, and the user annotates it and
sends it back. Three things are undecided by the spec and unbuilt in the code:

1. **Who rasterises a PDF page.** SPEC §4.5 allows `mime: application/pdf` with a
   `page`, which implies the device can hold the PDF itself. The server has no PDF
   library in its runtime image (Pillow is dev-only), while Android ships
   `PdfRenderer` natively.
2. **How the device fetches the bytes.** The frozen contract serves blobs through
   `GET /blobs/{key}?sig=&exp=` with a signature *in addition to* the bearer token
   (ADR-0004), but the device cannot sign. Neither blob route is implemented yet.
3. **Who is allowed to push.** Only device bearer tokens exist. A "server-side
   script" (the acceptance) can use the operator CLI on the host, but the Nightshift
   bridge (#18) and any script on another machine need an HTTP door, and a device
   token is the wrong principal for that.

## Decision

1. **Server stores, device renders.** `agent.push_document` stores the original
   bytes once (`application/pdf`, `image/png`, `image/jpeg`; 20 MB cap; PDFs capped
   at 20 pages, counted server-side with `pypdf`) and creates one canvas per page,
   each with a `raster` layer at `z = -1` whose `Raster` row points at the same blob
   key with `page = n`. The device renders the page with `PdfRenderer` at canvas
   resolution and caches the bitmap; images are decoded directly. The raster is
   fitted to the canvas width (A4 portrait by default; landscape pages get a
   landscape canvas of the same area) and placed at the top-left.
2. **Signed URLs travel inside the result.** The `to_user` job result's `rasters[]`
   entries carry an additive `url` (a signed `GET /blobs/{key}` link, 24 h TTL) next
   to the contract fields, and `GET /canvases/{id}` returns fresh signed `url`s on
   its rasters, so a device that missed the window re-fetches without signing
   anything. Both blob routes are implemented as frozen: bearer token **and**
   signature required on `GET`; `POST /blobs` is multipart and returns
   `{ key, url, expires_at }`.
3. **`to_user` jobs are created `done`.** There is nothing to run: the server
   creates the canvas, layer, raster and card rows, writes the contract's
   `{ canvas, layers, rasters, cards }` into `result`, and the device discovers the
   job through `/sync` like any other. No worker involvement, no new job-state
   machinery.
4. **Token kinds.** `tokens.kind` (`device` | `agent`, default `device`, additive
   migration). Agent-kind tokens may call the push routes (`POST /v1/push/document`,
   `POST /v1/push/canvas`) and `GET /v1/health` only; device-kind tokens are refused
   on the push routes with `403 forbidden`. `inkwell token create --kind agent`
   mints them; the CLI `inkwell push …` uses the database directly on the host, the
   HTTP routes are for everything else.
5. **Origin markers.** Pushed canvases are `origin=agent` (SPEC §4.2) and carry the
   pushing job id on their raster layer's `job_id`; the device shows them with an
   unread badge on the space tab until opened. `agent.notify` (cards without a
   canvas) is deferred: it needs a card surface outside a canvas that does not exist
   yet.

## Alternatives considered

- **Server renders PDF pages to PNG.** Adds a rendering dependency to the image
  (pdfium or poppler), doubles blob storage, and fixes the resolution at push time.
  Rejected while the only client renders PDFs natively.
- **Device signs blob URLs with a shared key.** Puts the signing key on the device,
  which ADR-0004 exists to avoid.
- **Push through the worker as a normal job.** Uniform, but there is no work to do
  and it would delay delivery by a poll interval for nothing.
- **Reuse device tokens for scripts.** Simplest, but a leaked script token would
  then read every job and card the device can; separate kinds keep the blast
  radius to "can push documents".
- **One canvas with a multi-page raster.** A canvas is a fixed page (SPEC §4.2);
  page-per-canvas keeps annotation coordinates and the export unambiguous.

## Consequences

- Room gets one additive column (`canvases.seen_at`); the server gets two
  (`tokens.kind`, `rasters.url` is *not* stored, it is computed on read).
- Multi-page documents produce several canvases; the device groups them in a folder
  named after the document.
- The `agent-output` contract is untouched; pushes never involve the model.
- Sending a pushed canvas back is an ordinary `to_agent` job whose export
  composites the raster beneath the ink, so the agent sees the document and the
  marks together.
