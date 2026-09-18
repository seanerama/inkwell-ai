# UI-smoke: push a document from the server (blobs, canvas detail, to_user sync)

"Observably-works" check for the **Operator**, run against a freshly deployed staging
server. It confirms Stage 21 (SPEC §4.2/§7, §8; contract `device-api` `POST /blobs`,
`GET /blobs/{key}`, `GET /canvases/{id}`; the `to_user` `agent.push_document` job;
ADR-0012): a server-side script puts a PDF in front of the user — one agent-origin
canvas per page with a raster layer and a signed download URL, delivered through `/sync`.
There is no browser UI (ADR-0001, native client), so this operator curl-on-the-host is
the replacement smoke.

Throughout, `dc` is `docker compose -f deploy/compose.yml` in the environment directory
on the host (e.g. `/srv/inkwell/staging`).

## Preconditions

- A staging server is deployed and healthy: `GET /v1/health` returns
  `{ "status": "ok", ..., "contract": "device-api/v1" }`.
- **`PUSH_ENABLED=true`** is set in the environment's `.env` and the stack was
  re-upped (`dc up -d api worker`). With the switch **off** (the default), `inkwell push`
  exits 2 with `push disabled` and the blob routes return `403 {"error":{"code":"disabled"}}`
  — that itself is the PASS for the default-safe config; the steps below need it ON.
- You can reach the server host (below shown as `$HOST`, e.g.
  `https://inkwell-staging.example.com`).

## Step 1 — mint a device token and pick a space

```
dc run --rm -T api inkwell token create --name "push-smoke"
export TOKEN="<the printed token>"
export HOST="https://inkwell-staging.example.com"
```

## Step 2 — push a PDF from the host

Pipe a PDF into the CLI over stdin (no bind-mount needed):

```
cat brief.pdf | dc run --rm -T api inkwell push document --space work --file - --title "Brief" --note "Please review"
```

- *Expected (PASS):* the command prints one `job: <uuid>` line and one `canvas: <uuid>`
  line per page (a 3-page PDF prints three `canvas:` lines). Note the first `canvas:` id
  as `$CID`.
- *FAIL signals:* `push disabled` (the switch is off — set `PUSH_ENABLED=true` and
  `dc up -d api worker`); `no space with slug work`; `push rejected: the PDF has N pages;
  the limit is 20` (expected for an over-long PDF); a traceback.

## Step 3 — `GET /sync` shows the to_user job

```
curl -s -H "Authorization: Bearer $TOKEN" "$HOST/v1/sync" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); j=[x for x in d['jobs'] if x['type']=='agent.push_document'][-1]; print(j['direction'], j['status'], 'canvases=', len(j['result']['canvases']), 'url=', j['result']['rasters'][0]['url'][:40])"
```

- *Expected (PASS):* `to_user done canvases= 1 url= /v1/blobs/push/...` (the `canvases`
  count matches the page count). The job's `result` has `canvas`, `canvases`, `layers`,
  `rasters` (each with a `url`), and `cards` (your `--note` becomes an `answer` card).
- *FAIL signals:* the job absent; `status` not `done`; `rasters[].url` missing.

## Step 4 — `GET /canvases/{id}` returns a raster with a signed `url`

```
curl -s -H "Authorization: Bearer $TOKEN" "$HOST/v1/canvases/$CID" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); r=d['rasters'][0]; print('origin', d['origin'], 'layers', len(d['layers']), 'mime', r['mime']); print(r['url'])"
```

- *Expected (PASS):* `origin agent layers 1 mime application/pdf`, then a
  `/v1/blobs/push/<uuid>.pdf?sig=...&exp=...` URL. Copy that URL as `$URL`.
- *FAIL signals:* `404` (wrong canvas id); empty `rasters`; no `url` on the raster.

## Step 5 — `GET` the signed blob URL returns the bytes

```
curl -s -o /tmp/roundtrip.pdf -w "%{http_code} %{content_type}\n" \
  -H "Authorization: Bearer $TOKEN" "$HOST$URL"
cmp brief.pdf /tmp/roundtrip.pdf && echo "bytes match"
```

- *Expected (PASS):* `200 application/pdf` and `bytes match` — the downloaded file is
  byte-identical to the pushed PDF.
- *FAIL signals:* `403` (bad/expired signature, or the bearer token was omitted — the
  signature is required **in addition to** the token); `401` (token missing/invalid);
  `404` (unknown key); the files differ.

## Step 6 — the kill-switch really gates the routes (default-safe check)

Flip `PUSH_ENABLED=false` in `.env`, `dc up -d api worker`, then:

```
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" "$HOST$URL"
```

- *Expected (PASS):* `403` with `{"error":{"code":"disabled"}}`, and
  `cat brief.pdf | dc run --rm -T api inkwell push document --space work --file -` exits 2
  printing `push disabled`. Restore `PUSH_ENABLED=true` (and `dc up -d`) if you keep
  pushing.

## Operator result (fill in)

| Check | Result |
| --- | --- |
| `GET /v1/health` ok | pass (2026-09-18, v0.0.16) |
| `inkwell push document` prints job + canvas id(s) | pass (1-page → 1 canvas in learning; 3-page → 3 canvases in work) |
| `GET /sync` shows a `to_user` `agent.push_document` job, `done`, with `canvases[]` | pass (2 to_user jobs, done; canvases=1 and 3) |
| `GET /canvases/{id}` returns an `origin=agent` canvas with a raster `url` | pass (origin=agent, 1 layer, 1 raster application/pdf page 1, 2480×3509.5 CU) |
| `GET <signed url>` returns `200` and byte-identical PDF | pass (200 application/pdf 981 B, identical; no bearer 401, bad sig 403) |
| Kill-switch OFF → blob `403 disabled` and CLI exit 2 | not exercised on host (switch is ON on staging; covered by server tests) |

## Notes

- `PUSH_ENABLED` defaults to **OFF**; leave it off unless actively pushing.
- PDFs are capped at 20 MB and 20 pages (counted server-side with `pypdf`); PNG/JPEG are
  accepted too. The upload type is decided by sniffing the magic bytes, not the filename.
- The device renders the PDF page itself (`PdfRenderer`) from the blob — the server never
  rasterises (ADR-0012).
