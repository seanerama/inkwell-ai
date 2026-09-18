# Stage 21: Push origin on the server: to_user jobs, blob routes, canvas detail, inkwell push CLI

- **Type:** feature
- **Depends on:** 20
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/47
- **Design:** SPEC §4.2–4.5, §7 (`agent.push_document`, `agent.push_canvas`), §8 (Blobs, Sync), §12 Phase 4; contract `device-api` (frozen routes `POST /blobs`, `GET /blobs/{key}`, `GET /canvases/{id}`; `to_user` result shape); ADR-0004, ADR-0012

## Objectives

The server half of "a server-side script can put a document in front of you". After
this stage the operator runs `inkwell push document --space work --file brief.pdf`
on the host and a `to_user` job appears in `/sync` carrying everything the device
needs: the canvas (one per page), a raster layer, the raster with a signed download
URL, and an optional card. The two frozen blob routes and canvas detail exist. No
device work yet (stage 22).

## What to build

**Blob routes (`app/api/routes/blobs.py`)** — both frozen in the contract table:
- `POST /blobs` multipart `file` (≤ 20 MB; mime allow-list `application/pdf`,
  `image/png`, `image/jpeg`; sniff magic bytes, do not trust the declared type) →
  `201 { key, url, expires_at }`; `url` is `LocalBlobStore.signed_url(key, ttl)`.
- `GET /blobs/{key}?sig=&exp=` → bytes with the stored mime; bearer token required
  **and** signature verified (`403 forbidden` on bad/expired sig, `404` unknown key).
  Streams from the store; sets `Cache-Control: private, max-age=<remaining ttl>`.
- Keys are `<uuid>.<ext>` under a `push/` prefix; the store already namespaces.

**Canvas detail (`app/api/routes/canvases.py`)**
- `GET /canvases/{id}` → `Canvas & { layers: Layer[], rasters: Raster[] }` (frozen
  shape). Each raster gains an additive `url` (fresh signed link, 24 h) and
  `mime`/`page` as stored. Bearer auth; `404` unknown.

**Push (`app/push/service.py`, CLI `inkwell push`)**
- `push_document(space, bytes, mime, title, card_body=None)`: store the blob once;
  for PDFs count pages with `pypdf` (new runtime dependency; reject > 20 pages with a
  clear error); for each page create `Canvas(origin=agent, title="<title>" or
  "<title> — p<N>" when multi-page, dims per ADR-0012 fit rule)`, one
  `Layer(owner=agent, type=raster, z=-1, job_id=<job>)`, one `Raster(blob_uri=<key>,
  mime, page=n, x_cu=0, y_cu=0, w_cu=<fit>, h_cu=<fit>)`; one `Job(direction=to_user,
  type=agent.push_document, status=done, result={ canvas: <first>, canvases: [...all],
  layers, rasters (each with url), cards })`. `canvases` is an additive sibling
  alongside the contract's `canvas`, so single-page pushes are exactly the frozen
  shape. One optional `answer` card ("<title> from <origin>") attached to the job.
- `push_canvas(space, title, width, height, card_body=None)`: a blank agent-origin
  canvas row only (the device creates the `user/ink` layer on the first stroke, the
  stage 12 rule), job type `agent.push_canvas`, result `{ canvas, layers: [],
  rasters: [], cards }`.
- CLI: `inkwell push document --space <slug> --file <path> [--title <t>] [--note
  <card body>]` and `inkwell push canvas --space <slug> --title <t> [--landscape]`.
  Prints the job id and canvas ids. Runs on the host via `docker compose run --rm -T
  api inkwell push …`; the file is passed by bind-mounting or `-` for stdin — document
  the stdin form (`cat brief.pdf | dc run --rm -T api inkwell push document --space
  work --file - --title brief`).
- **Kill-switch** `PUSH_ENABLED` (`Settings.push_enabled`, default `False`, passed
  through `compose.yml`, added to `.env.example`): when off, `inkwell push` exits 2
  with "push disabled" and the blob routes return `403 disabled`. `/sync` is unchanged
  (any already-created `to_user` job still syncs).
- The env/compose parity gate (stage 20) covers the new key automatically.

**Contract** — `contracts/device-api.md`, dated additive section "Stage 21
additions": the multipart limits and mime allow-list, the `url` on rasters, the
`canvases` sibling on multi-page results, the error codes. Route table untouched.

## Interface contracts

- **Exposes:** `POST /blobs`, `GET /blobs/{key}`, `GET /canvases/{id}`,
  `to_user` jobs of type `agent.push_document` / `agent.push_canvas` in `/sync`,
  `inkwell push`, `PUSH_ENABLED`.
- **Consumes:** `device-api` (frozen shapes), ADR-0004 blob store, ADR-0012.

## Testing requirements

- Blobs: upload/download round-trip; oversize 413; wrong mime 415 (magic sniffing);
  bad/expired signature 403; missing bearer 401 even with a valid signature.
- Push: 1-page PDF → 1 canvas/layer/raster, job `done`, result matches the frozen
  shape plus `url`; 3-page PDF → 3 canvases and `canvases[]`; PNG push; > 20 pages
  rejected; kill-switch off → CLI exit 2 and blob routes 403; `/sync` returns the job
  in cursor order after a `to_agent` job.
- Canvas detail: layers and rasters with a verifiable `url`.
- Fixtures: a tiny 1-page and 3-page PDF under `server/tests/fixtures/`.
- Smoke `smoke/push-server.md` (curl on the host): push a PDF, `GET /sync` shows it,
  `GET /canvases/{id}` returns a raster `url`, `GET` that url returns the bytes.

## Acceptance conditions

- [ ] Kill-switch `PUSH_ENABLED` (default OFF) gates push and the blob routes
- [ ] UI-smoke asset `smoke/push-server.md` authored
- [ ] Additive migration only (none expected; all tables exist) and contract table untouched
- [ ] A pushed 3-page PDF yields three agent-origin canvases and one `done` `to_user` job visible in `/sync` (tested)
- [ ] Existing suite stays green; CI all-green

## Pipeline test: NO
