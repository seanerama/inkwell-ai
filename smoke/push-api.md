# UI-smoke: push a document from a script (agent token, POST /v1/push/…)

"Observably-works" check for the **Operator**, run from the workstation against a freshly
deployed staging server. It confirms Stage 23 (SPEC §1.1 "agents can initiate", §11
security; contract `device-api` Stage 23 additions; ADR-0012 §4): a script holding an
**agent-kind** token pushes a PDF over HTTPS and it lands on the tablet — one agent-origin
canvas per page, delivered through `/sync`, with the Work tab badge ticking up. Device
tokens cannot push and agent tokens cannot read the device routes.

Throughout, `dc` is `docker compose -f deploy/compose.yml` in the environment directory on
the host (e.g. `/srv/inkwell/staging`). Everything else runs from the **workstation**.

## Preconditions

- A staging server is deployed and healthy: `GET /v1/health` returns
  `{ "status": "ok", ..., "contract": "device-api/v1" }`.
- **`PUSH_ENABLED=true`** is set in the environment's `.env` and the stack was re-upped
  (`dc up -d api worker`). With the switch **off** (the default) the push routes return
  `403 {"error":{"code":"disabled"}}` — that is the PASS for the default-safe config; the
  steps below need it ON.
- The tablet is paired to the same server and the **Work** tab is visible.
- You can reach the server host (below shown as `$HOST`, e.g.
  `https://mini-hp01.taile0ffc4.ts.net:8444`).

## Step 1 — mint an agent token on the host

```sh
dc run --rm -T api inkwell token create --name "push-api-smoke" --kind agent
export TOKEN="<the printed token>"       # the line under "token:"
export HOST="https://mini-hp01.taile0ffc4.ts.net:8444"
```

- *Expected (PASS):* the output includes `kind: agent` and a one-time `token:` line.
- *FAIL signals:* no `kind: agent` line (old build); a traceback.

## Step 2 — push a PDF from the workstation

```sh
curl -sS -X POST "$HOST/v1/push/document" \
  -H "Authorization: Bearer $TOKEN" \
  -F file=@brief.pdf -F space=work -F title="Brief" -F note="Please review"
```

- *Expected (PASS):* `201` with `{"job_id":"…","canvas_ids":["…"]}` — one id per page (a
  3-page PDF returns three `canvas_ids`).
- *FAIL signals:* `403 disabled` (switch off — set `PUSH_ENABLED=true`, `dc up -d`);
  `404 not_found` (no `work` space); `413`/`415`/`422` (too big / not a PDF-PNG-JPEG / over
  the 20-page cap); `401` (bad token).

## Step 3 — watch the Work tab badge on the tablet

Let the tablet sync (foreground poll is ~5 s).

- *Expected (PASS):* the **Work** tab badge increments and the pushed document opens as an
  agent-origin canvas (one per page) with the PDF rendered; your `--note` shows as an
  `answer` card.
- *FAIL signals:* no badge change after a sync; the canvas is blank / no raster.

## Step 4 — the access split holds (agent ≠ device)

The agent token must be refused on a device route, and a device token must be refused on
push:

```sh
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" "$HOST/v1/sync"
DEV=$(dc run --rm -T api inkwell token create --name "dev-check" | sed -n 's/^token: //p')
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $DEV" \
  -F file=@brief.pdf -F space=work "$HOST/v1/push/document"
```

- *Expected (PASS):* both print `403` (`{"error":{"code":"forbidden"}}`).
- *FAIL signals:* either returns `200`/`201` — the kinds are not enforced.

## Step 5 — push a blank canvas (JSON)

```sh
curl -sS -X POST "$HOST/v1/push/canvas" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"space":"work","title":"Sketch","landscape":false}'
```

- *Expected (PASS):* `201` with one `canvas_ids` entry; a blank agent-origin canvas appears
  on the Work tab (the tablet adds the ink layer on the first stroke).

## Operator result (fill in)

| Check | Result |
| --- | --- |
| `GET /v1/health` ok | |
| `inkwell token create --kind agent` prints `kind: agent` | |
| `POST /v1/push/document` returns `201` + `job_id`/`canvas_ids` | |
| Work tab badge increments; PDF renders; note card shows | |
| Agent token on `/v1/sync` → `403 forbidden` | |
| Device token on `/v1/push/document` → `403 forbidden` | |
| Kill-switch OFF → push `403 disabled` | |
| `POST /v1/push/canvas` returns `201` and a blank canvas appears | |

## Notes

- `PUSH_ENABLED` defaults to **OFF**; leave it off unless actively pushing.
- Limits mirror `POST /blobs`: 20 MB, PDFs ≤ 20 pages, mime by magic-byte sniff. The push
  API is rate-limited to 10 requests/min per agent token (`429` with `Retry-After`).
- Record each agent token in `.verity/deploy-access.md` by **location only** (who holds it,
  where it runs) — never the secret.
