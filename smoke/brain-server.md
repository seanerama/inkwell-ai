# UI-smoke: brain on the server — writes persist, full-text search, soft delete

"Observably-works" check for the **Operator**, run against a freshly deployed staging
server. It confirms Stage 25 (SPEC §4/§6.2/§8 Brain; contract `device-api` Stage 25
additions; ADR-0013 §1/§2/§5): a `to_agent` job's `brain_writes` land in `brain_entries`
with provenance, the three frozen `/brain/{space_slug}` routes work with Postgres
full-text search, and `save_to_brain` on a card creates an entry. There is no browser UI
and no device brain view yet (Stage 27), so this operator curl is the replacement smoke.

Throughout, `dc` is `docker compose -f deploy/compose.yml` in the environment directory on
the host (e.g. `/srv/inkwell/staging`). Everything else runs from the **workstation**.

## Preconditions

- A staging server is deployed and healthy: `GET /v1/health` returns
  `{ "status": "ok", ..., "contract": "device-api/v1" }`.
- **`BRAIN_ENABLED=true`** is set in the environment's `.env` and the stack was re-upped
  (`dc up -d api worker`). With the switch **off** (the default) the brain routes return
  `403 {"error":{"code":"disabled"}}` and `save_to_brain` returns
  `422 {"error":{"code":"not_implemented"}}` — that is the PASS for the default-safe
  config; the steps below need it ON.
- `AGENT_ENABLED=true` as well, so Step 3 (a real `canvas.ask`) can run. If the agent is
  off, skip Step 3 and rely on Steps 2/4/5 (POST/GET/DELETE), which do not call the model.
- You can reach the server host (below shown as `$HOST`, e.g.
  `https://mini-hp01.taile0ffc4.ts.net:8444`).

## Step 1 — mint a device token

```sh
dc run --rm -T api inkwell token create --name "brain-smoke"
export TOKEN="<the printed token>"       # the line under "token:"
export HOST="https://mini-hp01.taile0ffc4.ts.net:8444"
```

- *Expected (PASS):* a one-time `token:` line.
- *FAIL signals:* a traceback; no token line.

## Step 2 — POST a brain entry directly, then find it with full-text search

```sh
curl -sS -X POST "$HOST/v1/brain/work" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"kind":"fact","text":"The Q3 budget review is on 14 October.","tags":["Finance","Q3"]}'
```

- *Expected (PASS):* `201` with a `BrainEntry` — `{ id, space_slug:"work", kind:"fact",
  text, tags:["finance","q3"] (lowercased/deduped), source_canvas_id:null,
  source_region:null, job_id:null, created_at }`. **POST the exact same text again** →
  `200` with the **same `id`** (idempotent dedupe), not a second row.
- *FAIL signals:* `403 disabled` (switch off — turn `BRAIN_ENABLED=true` and re-up);
  `422`; two different ids for the same text.

```sh
# keyword search over text (weight A) + tags (weight B)
curl -sS -H "Authorization: Bearer $TOKEN" "$HOST/v1/brain/work?q=budget"
```

- *Expected (PASS):* a one-element array containing the entry from above. A query with no
  match (e.g. `?q=elephant`) returns `[]`. An unknown space (`/v1/brain/nope`) → `404`.

## Step 3 — a `canvas.ask` whose answer carries a write persists it

Send any note to the **Work** space from the tablet (or replay a fixture job). When the
job is `done`, its `jobs.result` gains a **`brain_entry_ids`** sibling and each write is a
new row with `job_id` set. Read it back:

```sh
curl -sS -H "Authorization: Bearer $TOKEN" "$HOST/v1/brain/work?q=<a-word-from-the-write>"
```

- *Expected (PASS):* the array includes a row whose `job_id` is the `to_agent` job's id
  and whose `source_canvas_id` is the canvas you wrote on (provenance).
- *FAIL signals:* the write is absent (switch off, or persistence skipped); `job_id` null
  on an agent-produced row.

## Step 4 — `save_to_brain` on a card creates an entry

On a card that offers a **Save to brain** action, tap it (or `POST
/v1/cards/{id}/actions/{action_id}` with the device token).

- *Expected (PASS):* `200` with the card now `state:"done"` **plus a `brain_entry_id`**
  sibling; `GET /v1/brain/work` then lists that new entry (text = the card's title and
  body). With `BRAIN_ENABLED` off the same call returns `422 not_implemented`.

## Step 5 — DELETE soft-deletes (gone from listings, retained in the row)

```sh
export EID="<an id from a GET above>"
curl -sS -o /dev/null -w "%{http_code}\n" -X DELETE \
  -H "Authorization: Bearer $TOKEN" "$HOST/v1/brain/work/$EID"
curl -sS -H "Authorization: Bearer $TOKEN" "$HOST/v1/brain/work?q=budget"
```

- *Expected (PASS):* the DELETE prints `204`; the following `GET` no longer returns that
  entry. Deleting an unknown id, or the id under the wrong space
  (`/v1/brain/home/$EID`), returns `404`.
- *FAIL signals:* the deleted entry still appears in a listing; a `5xx`.

## Cleanup

Delete any smoke entries you created (Step 5). Turn `BRAIN_ENABLED` back to its intended
value and `dc up -d api worker` if you flipped it only for this smoke.

## Results log

| Date (UTC) | Release | Operator | POST + search | Idempotent POST | Auth (none/agent) | DELETE | Notes |
|---|---|---|---|---|---|---|---|
| 2026-09-21 19:40 | v0.0.18 | Release Operator (curl on host) | pass (q=budget → 1 hit) | pass (200, same id) | pass (401 / 403) | pass (204, gone) | **Listing 500s whenever an agent-written entry is in the result** (array `source_region` vs `Rect` object) → stage 30 (#65). `save_to_brain` not exercised. |
