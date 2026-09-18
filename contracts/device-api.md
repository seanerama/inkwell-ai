# Contract: device-api

- **Status:** frozen v1
- **Owner:** server `api/` module (producer); Android `net/` (consumer)
- **Base:** `https://<host>/v1`

## Exposes

All routes except `GET /v1/health` require `Authorization: Bearer <device_token>`
(ADR-0008). Errors are `{ "error": { "code": string, "message": string } }` with the
HTTP status; `401` invalid token, `404` unknown id, `409` invalid state transition,
`413` export over 2 MB, `422` validation, `429` rate limited with `Retry-After`.
All ids are UUIDs; all timestamps are RFC 3339 UTC.

| Route | Returns | Notes |
|---|---|---|
| `GET /health` | `{ status:"ok", version, contract:"device-api/v1" }` | unauthenticated; the smoke target |
| `GET /spaces` | `Space[]` | ordered by `position` |
| `POST /spaces` | `Space` | |
| `PATCH /spaces/{id}` | `Space` | partial update |
| `GET /canvases?space_id=&since=` | `Canvas[]` | `since` = RFC 3339, filters on `updated_at` |
| `POST /canvases` | `Canvas` | |
| `GET /canvases/{id}` | `Canvas & { layers: Layer[], rasters: Raster[] }` | never strokes |
| `DELETE /canvases/{id}` | `204` | |
| `POST /jobs` | `Job` with `202 Accepted`, `status:"queued"` | body below |
| `GET /jobs/{id}` | `Job` | |
| `POST /jobs/{id}/cancel` | `Job` | `queued→cancelled`; `running→cancel_requested` |
| `GET /sync?cursor=` | `{ jobs: Job[], cursor: string }` | see below |
| `POST /blobs` | `{ key, url, expires_at }` | multipart `file`; `url` is a signed, expiring `GET /blobs/{key}` |
| `GET /blobs/{key}?sig=&exp=` | bytes | signature required in addition to the bearer token |
| `GET /brain/{space_slug}?q=&limit=` | `BrainEntry[]` | |
| `POST /brain/{space_slug}` | `BrainEntry` | |
| `DELETE /brain/{space_slug}/{id}` | `204` | |
| `GET /usage?since=` | `{ jobs: int, input_tokens: int, output_tokens: int, by_space: {...} }` | SPEC §10.5 |

Entity shapes are exactly SPEC §4 (`Space`, `Canvas`, `Layer`, `Raster`, `Job`,
`Card`, `BrainEntry`) and are mirrored in `contracts/schema/device-api.v1.schema.json`
once Stage 0 emits it from the Pydantic response models (ADR-0007). `Job.result` for
`to_agent` jobs is a contract `agent-output` object.

## Consumes

- Device bearer tokens minted by the server CLI (ADR-0008).
- Exported canvas PNGs produced per contract `coordinate-mapping`.

## Schema / wire

**`POST /jobs` body** (`to_agent` types `canvas.ask`, `canvas.annotate`,
`canvas.formalize`, `canvas.extract`, `canvas.action`):

```json
{
  "type": "canvas.annotate",
  "space_id": "uuid",
  "canvas_id": "uuid",
  "image": "<base64 PNG, decoded size <= 2 MB>",
  "export": { "w": 1109, "h": 1568, "width_cu": 2480, "height_cu": 3508 },
  "instruction": "optional text",
  "selection": [0.1, 0.2, 0.5, 0.3]
}
```

The example `export` is the default 2480×3508 canvas under the `coordinate-mapping`
formula (`round(2480 × 1568/3508) = 1109`). `image` is persisted to the blob store on receipt; `GET /jobs/{id}` returns
`request.image_key` in its place, never the base64 (ADR-0004). `selection` is an
optional normalized `[x,y,w,h]`.

**`to_user` job types** (`agent.push_canvas`, `agent.push_document`, `agent.notify`)
are created server-side only. Their `result` carries what the device must
materialise: `{ canvas: Canvas?, layers: Layer[], rasters: Raster[], cards: Card[] }`.

**Sync cursor.** Opaque string encoding `(updated_at, id)` of the last job returned.
`GET /sync` without a cursor returns the most recent 100 jobs across both directions.
Results are ordered by `(updated_at, id)` ascending, at most 100 per page; a page of
100 means call again with the new cursor. Cursors are stable across server restarts.
Poll cadence is device policy (SPEC §8): 5 s while a job is outstanding in the
foreground, 60 s otherwise, 5 min backgrounded.

**Rate limits.** `POST /jobs`: 30 per token per minute. Per-space daily cap
(default 200) is enforced at claim time; an over-cap job is created then failed with
an `error` card, so the device sees a normal `failed` transition.

## Versioning

Frozen at **v1**. Changes are **additive only** — a breaking change is a NEW
contract, not an edit (framework-spec §4.3). Every consumer depends on this shape.

The client sends `X-Inkwell-Contract: device-api/v1`; the server responds with the
same header. New routes and new optional fields are additive. Removing a field,
changing a status code, or changing the cursor semantics is a new contract at
`/v2`, and `/v1` keeps working.

## Additive changes (v1) — 2026-09-16

Stage 10 extends v1 additively (new routes + new optional field only), permitted by the
"additive only" rule above. Nothing frozen above this heading changes.

**`cards: CardOut[]` on `Job` / `JobOut`.** `GET /jobs/{id}` and `/sync` now carry the
job's cards, ordered by `created_at` ascending. Older servers omit the field; clients
default it to `[]`. Each `CardOut` is:

```json
{
  "id": "uuid",
  "kind": "answer|task|fact|question|action|error",
  "title": "string",
  "body": "string (Markdown)",
  "anchors": [ { "annotation_id": "a2" }, { "region": [0.2, 0.4, 0.5, 0.1] } ],
  "actions": [ { "id": "c1", "label": "Save as task", "kind": "save_to_brain", "payload": {} } ],
  "state": "open|done|dismissed",
  "created_at": "timestamp"
}
```

`kind`/`state` are strings on the wire so additive vocabulary stays additive. `result`
is unchanged.

**`PATCH /cards/{id}`** — body `{ "state": "open"|"done"|"dismissed" }` → `CardOut`.
Allowed transitions are `open ↔ done` and `open ↔ dismissed` (i.e. `open→done`,
`open→dismissed`, `done→open`, `dismissed→open`); `done ↔ dismissed` directly is
rejected. `404 not_found` for an unknown id, `409 conflict` for an invalid transition,
`422 validation` for a missing/invalid `state`.

**`POST /cards/{id}/actions/{action_id}`** → `CardOut`. Finds the action by its `id` in
the card's `actions`. Effect by the action's `kind`: `confirm` sets the card `done`,
`reject` sets it `dismissed`; `run_tool`, `open_canvas` and `save_to_brain` return
`422 not_implemented` until their phases (tools / Phase 4 canvases / Phase 5 brain).
`404 not_found` if the card or the `action_id` is unknown.

A successful card-state change (either route) **bumps the parent job's `updated_at`**,
so the job re-appears through `/sync` (which orders by `updated_at`) and the device sees
the new card state.

### Stage 12 additions — 2026-09-16

Stage 12 (`canvas.formalize`) extends v1 additively: one new optional request field and
one new route. Nothing frozen above changes.

**Optional `meta: { title }` on `POST /jobs`.** The device may send a `meta` object on a
job create; it is carried onto the stored job `request` untouched. `canvas.formalize`
reads `meta.title` (the source canvas's title) to name the redraw
`"<title> — formalized"`; absent it, the redraw is titled `"Formalized"`. Older servers
ignore an unknown `meta`; the field is optional for every job type.

**`GET /canvases?space_id=&since=`** → `CanvasOut[]`. Lists the **server-known**
canvases — i.e. the agent-origin canvases the server creates itself (currently the
`canvas.formalize` redraws); user canvases live on the device and are not listed. Bearer
auth (`require_token`). Optional `space_id` (UUID) filters to one space; optional `since`
(RFC3339) filters to `created_at >= since` (a trailing `Z` is accepted, else `422
validation`). Ordered by `created_at` descending. Each `CanvasOut` is:

```json
{
  "id": "uuid",
  "space_id": "uuid",
  "title": "string",
  "width_cu": 2480,
  "height_cu": 3508,
  "origin": "user|agent",
  "created_at": "timestamp"
}
```

This gives Phase 4's canvas push a base to build on; the `canvas.formalize` redraw itself
rides back on the polled job (see the `result.canvas` sibling key in `agent-output`), not
through this route.

### Stage 13 additions — 2026-09-16

Stage 13 implements the two space-editing routes the **frozen route table above already
lists** (`POST /spaces` → `Space`, `PATCH /spaces/{id}` → `Space` partial). Nothing
frozen changes — the route table, entity shapes (`Space` = SPEC §4) and the error
envelope are untouched. This section only documents the request bodies and the error
codes those routes surface.

**Kill-switch.** Both routes require the bearer token and are additionally gated by the
server-side `SPACES_EDITABLE` env (default OFF). When off, `POST`/`PATCH /spaces` return
`403` with `error.code = "disabled"`. `GET /spaces` is never gated.

**`POST /spaces`** → `201` + `Space`. Body `SpaceCreate`:

```json
{
  "name": "string (1–120, required)",
  "slug": "string? (^[a-z0-9-]{1,64}$; derived from name when absent)",
  "system_prompt": "string? (≤ 8000 chars, default \"\")",
  "tools": ["string?"],
  "model": "string? (default \"claude-sonnet-5\")",
  "color": "string? (#RRGGBB, default #000000)",
  "position": "int? (default: max(position)+1)"
}
```

When `slug` is absent it is derived from `name`: lowercased, every run of non
`[a-z0-9]` characters becomes `-`, leading/trailing dashes are trimmed, and the result
is truncated to 64 chars. A derived slug that comes out empty → `422 validation`.

**`PATCH /spaces/{id}`** → `200` + `Space`. Body `SpaceUpdate`: every field of
`SpaceCreate` optional except `name` limits still apply — `name, system_prompt, tools,
model, color, position`. Absent fields are left untouched (`exclude_unset` semantics).
`slug` is **immutable**: if `slug` is present in the body, the route returns `422
validation` with message "slug is immutable". Unknown `id` → `404 not_found`.

**Error codes** (envelope unchanged — `{ "error": { "code", "message" } }`):

| Status | `error.code` | When |
| --- | --- | --- |
| `401` | `unauthorized` | missing/invalid bearer token |
| `403` | `disabled` | `SPACES_EDITABLE` is off (both routes) |
| `404` | `not_found` | `PATCH` on an unknown space id |
| `409` | `conflict` | `POST` with a slug that already exists |
| `422` | `validation` | bad field (colour, slug pattern, overlong prompt, name length), a derived-empty slug, or `slug` present in a `PATCH` |

### Stage 21 additions — 2026-09-18

Stage 21 implements the three blob/canvas routes the **frozen route table above already
lists** (`POST /blobs`, `GET /blobs/{key}`, `GET /canvases/{id}`) and the server-side
`to_user` push (`agent.push_document`, `agent.push_canvas`). Nothing frozen changes —
the route table, entity shapes, the `to_user` result shape, and the error envelope are
untouched. This section documents only the additive detail: the upload limits, the
additive `url` on rasters, the additive `canvases` sibling on multi-page results, and the
error codes those routes surface (ADR-0012).

**Kill-switch.** `POST /blobs` and `GET /blobs/{key}` are additionally gated by the
server-side `PUSH_ENABLED` env (default OFF). When off, both return `403` with
`error.code = "disabled"`. `GET /canvases/{id}` is never gated (bearer only). The
operator CLI `inkwell push …` is gated the same way (exit 2, "push disabled").

**`POST /blobs`** (multipart `file`) → `201 { key, url, expires_at }`.
- Size cap **20 MB**; over it → `413 { error.code: "too_large" }`.
- Mime allow-list **`application/pdf`, `image/png`, `image/jpeg`**, decided by
  **sniffing the leading magic bytes** — the declared/uploaded content-type is never
  trusted. A non-allow-listed file → `415 { error.code: "unsupported_media_type" }`.
- `key` is `push/<uuid>.<ext>`; `url` is a signed, expiring `GET /blobs/{key}` link
  (24 h TTL); `expires_at` is the RFC 3339 expiry.

**`GET /blobs/{key}?sig=&exp=`** → the stored bytes. Requires the bearer token **and** a
valid HMAC signature (ADR-0004). The `Content-Type` is inferred from the key extension
(the store does not persist the mime); the response sets
`Cache-Control: private, max-age=<remaining ttl>`.
- Missing/invalid/expired signature → `403 { error.code: "forbidden" }` (checked before
  any key lookup, so a bad signature cannot probe which keys exist).
- Unknown key (valid signature) → `404 { error.code: "not_found" }`.
- Missing bearer token → `401` even when the signature is valid.

**`GET /canvases/{id}`** → `Canvas & { layers: Layer[], rasters: Raster[] }` (the frozen
shape; never strokes). `404 { error.code: "not_found" }` for an unknown id. Each
`Raster` carries the additive field **`url`** — a fresh signed `GET /blobs/{key}` link
(24 h) — alongside its stored `mime`/`page`, so a device that missed the sync window can
re-fetch without signing anything.

**`Raster.url` (additive).** The `Raster` entity gains an optional `url` string. It is
**computed on read**, never stored (there is no schema/migration change). It appears on
`GET /canvases/{id}` rasters and on the `rasters[]` of a `to_user` job `result`.

**`result.canvases[]` (additive sibling) on `to_user` documents.** A multi-page
`agent.push_document` produces one `origin=agent` canvas per page (each with a `raster`
layer at `z=-1` pointing at the same blob key with its `page`), so the result carries a
`canvases: Canvas[]` sibling **alongside** the frozen `canvas` (which stays the first
page). A single-page push's result is therefore **exactly the frozen
`{ canvas, layers, rasters, cards }`** plus that one-element `canvases` sibling. Page
canvases are titled `"<title>"` (single page) or `"<title> — p<N>"` (multi-page); the
raster is fitted to the canvas width and placed at the top-left, portrait pages on an A4
canvas and landscape pages on the same-area swap (ADR-0012). `agent.push_canvas` writes a
blank agent-origin canvas only (`layers: []`, `rasters: []`) — the device creates the
`user/ink` layer on the first stroke.
