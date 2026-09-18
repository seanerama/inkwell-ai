# Intake assessment — Phase 4: Bidirectional (agents push documents)

- **Date:** 2026-09-18
- **Request:** owner, after v0.0.15 verified: "plan phase 4".
- **Decision:** ACCEPT as three stages, 21 (server push origin) → 22 (device inbox)
  → 23 (agent push API), milestone `v0.6 — bidirectional`. One ADR (0012). No new
  contract; three frozen-but-unbuilt routes implemented; two dated additive
  sections; Room v4 and one server column, both additive.

## Claim / reality verification (main @ v0.0.15)

| Claim | Reality | Effect |
|---|---|---|
| The device learns about pushed jobs via `/sync` | `sync_page` orders by `(updated_at, id)` across both directions and is cursor-stable, but the client **never persists a cursor**: `SendLoop` polls per job and `SyncWorker` only flushes the offline queue | Stage 22 adds a persisted cursor and a `to_user` dispatcher |
| Rasters exist end to end | Server `Raster` model and Room `RasterEntity`/`RasterDao` exist since stage 1/3; **nothing renders them**: `LayerRenderer` is ink-only and `CanvasExporter` ignores raster layers | Stage 22 adds a raster renderer and export compositing |
| Blob routes exist | Frozen in the contract table; **not implemented** (`POST /blobs` → 404, found on 2026-09-17); `LocalBlobStore.signed_url/verify` exist unused | Stage 21 implements both as frozen |
| Canvas detail exists | Only `GET /canvases` (stage 12); `GET /canvases/{id}` frozen but unbuilt | Stage 21 |
| Something can create `to_user` jobs | Nothing. `Job.direction` column exists; `JobCreate` is device-only and `POST /jobs` sets `to_agent` | Stage 21 CLI, stage 23 HTTP |
| The server can rasterise PDFs | No PDF library in the runtime image (Pillow is dev-only) | ADR-0012: device renders with `PdfRenderer`; server only counts pages (`pypdf`) |
| The device can download a signed blob | Signature required on `GET /blobs/{key}`; the device holds no signing key | ADR-0012: signed `url` travels in the job result and on canvas detail |
| A push principal exists | Only device tokens (ADR-0008); no scopes | Stage 23 adds `tokens.kind` |
| Badge on the tab (SPEC §9.3) | Stage 14 tab bar has no badge state | Stage 22 `seen_at` |

## Contract safety

- `device-api`: route table untouched. Stage 21 implements three frozen routes and
  adds a dated section (limits, `url` on rasters, `canvases[]` sibling). Stage 23 adds
  two routes under its own dated section. The `to_user` result shape is honoured
  exactly for single-page pushes.
- `agent-output`, `coordinate-mapping`: untouched. Export ordering (raster then ink)
  is the SPEC §5.2 rule, not a contract change.
- `ink-storage`: v4 adds `canvases.seen_at` only.
- Alembic: `tokens.kind` (stage 23), default `device`.

## Split

| Stage | Why separate |
|---|---|
| 21 | Server-only, smoke-able with curl; unblocks the device work |
| 22 | The acceptance test lives here; raster rendering and the cursor are the risky parts |
| 23 | A new auth principal deserves its own review; the host CLI already satisfies the SPEC acceptance without it |

## Kill-switches

`PUSH_ENABLED` (server, default off; the operator sets it on staging at ship time,
like `SPACES_EDITABLE`) and `BuildConfig.PUSH_INBOX` (client, release-ON by the
documented exception).

## Deferred

- `agent.notify` (cards without a canvas): needs a card surface outside a canvas;
  revisit with Phase 5's brain view.
- FCM push: SPEC says Phase 5 optimisation; the 60 s foreground poll is enough for
  one user.
- Server-side PDF rasterisation and a second client: only if a non-Android client
  appears.
- Agent-initiated **annotations** on pushed canvases (push + markup in one job): the
  push result can carry an `annotation` layer later; not needed for acceptance.
- Nightshift bridge (#18): stage 23's agent token and push routes are the seam it
  will use; the bridge itself stays deferred until Nightshift is deployed.
