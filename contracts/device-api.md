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
