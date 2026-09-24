# UI-smoke: brain recall — extract writes a fact, a later ask recalls it

"Observably-works" check for the **Operator**, run against a freshly deployed staging
server. It confirms Stage 26 (SPEC §7 `canvas.extract`, §10.2/§10.3 recall, §11 per-space
tool allow-list; ADR-0013 §3-§5): **Remember** (`canvas.extract`) writes a durable fact to
the brain, and a later `canvas.ask` in the same space recalls it — first through the
baseline `<brain_context>` injection, and (with the tool loop on) through a `brain_search`
tool call the model makes itself, recorded on `result.brain_lookups`.

There is no device brain UI yet (Stage 27), so this operator curl is the smoke. Two real
model jobs run against the canary image, so it costs a little.

Throughout, `dc` is `docker compose -f deploy/compose.yml` in the environment directory on
the host (e.g. `/srv/inkwell/staging`). Everything else runs from the **workstation**.

## Preconditions

- A staging server is deployed and healthy: `GET /v1/health` returns
  `{ "status": "ok", ..., "contract": "device-api/v1" }`.
- **`AGENT_ENABLED=true`** (the model must actually run) and **`BRAIN_ENABLED=true`** (so
  writes persist and `<brain_context>` is filled). With `BRAIN_ENABLED` off the brain
  routes return `403 disabled` and no context is injected — that is the default-safe PASS,
  but the steps below need it ON.
- **`AGENT_TOOLS_ENABLED`** is the Stage 26 tool-loop switch (default OFF). Steps 2-3 pass
  with it **off** (baseline injection alone recalls the fact). Turn it **on** and re-up
  (`dc up -d api worker`) for Step 4 (the `brain_search` tool call + `result.brain_lookups`).
- After flipping any switch: `dc up -d api worker`.
- You can reach the server host (below `$HOST`, e.g.
  `https://mini-hp01.taile0ffc4.ts.net:8444`).

## Step 1 — mint a device token

```sh
dc run --rm -T api inkwell token create --name "brain-recall-smoke"
export TOKEN="<the printed token>"       # the line under "token:"
export HOST="https://mini-hp01.taile0ffc4.ts.net:8444"
```

- *Expected (PASS):* a one-time `token:` line.
- *FAIL signals:* a traceback; no token line.

## Step 2 — Remember: a `canvas.extract` writes a fact to the Work brain

Send a `canvas.extract` job to the **Work** space with a note that states a durable fact.
From the tablet, write a note like *"Q3 offsite is in Austin on 14 October"* and run
**Remember**; or replay the canary image with an instruction (any real canvas works — the
point is that the model records a `fact`). Poll the job to `done`.

```sh
# after the job is done, read the Work brain back
curl -sS -H "Authorization: Bearer $TOKEN" "$HOST/v1/brain/work?q=offsite"
```

- *Expected (PASS):* the job is `done` with exactly one `fact` card titled **"Saved to
  brain"**; its `result.brain_entry_ids` is non-empty; the `GET` returns a row whose
  `text` is the fact, with `job_id` set (the extract job) — provenance.
- *FAIL signals:* job `failed`; `brain_entry_ids` absent (`BRAIN_ENABLED` off); the search
  returns `[]`.

## Step 3 — Recall: a later `canvas.ask` sees the fact in `<brain_context>`

Send a **separate** `canvas.ask` job to the **Work** space, on a different canvas, with the
instruction **"when is the offsite?"**. Poll to `done`.

- *Expected (PASS):* the answer card mentions **Austin / 14 October** — the model recalled
  the fact it never saw on this canvas, because Step 2's entry was injected into the
  prompt's `<brain_context>` block. (Operator judges the wording.)
- *FAIL signals:* the answer says it has no idea / asks for the date — recall did not fire
  (check `BRAIN_ENABLED=true` and that both jobs used the **same** space).

## Step 4 — the `brain_search` tool (needs `AGENT_TOOLS_ENABLED=true`)

Set `AGENT_TOOLS_ENABLED=true` in the environment's `.env`, `dc up -d api worker`, then
repeat Step 3's `canvas.ask` ("when is the offsite?"). Poll to `done` and read the result.

```sh
# result.brain_lookups is a Stage 26 traceability sibling (not a contract field)
curl -sS -H "Authorization: Bearer $TOKEN" "$HOST/v1/jobs/<job-id>" | \
  python3 -c 'import sys,json; print(json.load(sys.stdin)["result"].get("brain_lookups"))'
```

- *Expected (PASS):* the answer still names Austin / 14 October, **and**
  `result.brain_lookups` is a non-empty list like
  `[{"query": "offsite", "count": 1, "mode": "and"}]` —
  the model chose to call `brain_search` and received the entry as data.
- *FAIL signals:* `brain_lookups` is absent with the switch on **and** an offsite-related
  ask (the tool was never offered — check the Work space still lists `brain_search` in its
  `tools`, and that `AGENT_TOOLS_ENABLED=true` reached the containers). With the switch
  **off** its absence is the expected default — recall then rides on Step 3 alone.

## Step 5 — agent-written rows list, and a multi-word lookup falls back to OR (Stage 30)

v0.0.18 found two bugs here: the Work listing `500`'d once Step 2 had written an entry
(its `source_region` is the `Rect` array `[x, y, w, h]`), and the model's four-word
`brain_search` query matched nothing because every term was ANDed. Check both:

```sh
# the listing (no q) includes Step 2's agent-written row -> 200, source_region an array
curl -sS -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" "$HOST/v1/brain/work"
curl -sS -H "Authorization: Bearer $TOKEN" "$HOST/v1/brain/work" | \
  python3 -c 'import sys,json; print([r["source_region"] for r in json.load(sys.stdin)])'
# the four-word query the model used on v0.0.18: AND finds nothing, OR finds the fact
curl -sS -G -H "Authorization: Bearer $TOKEN" "$HOST/v1/brain/work" \
  --data-urlencode "q=Q3 offsite date location"
```

With `AGENT_TOOLS_ENABLED=true`, repeat Step 4 and read `result.brain_lookups` again.

| Check | Expected (PASS) | FAIL signals |
|---|---|---|
| `GET /v1/brain/work` (no `q`) after Step 2 | `200`; each `source_region` is `null` or a 4-number array like `[0.1, 0.2, 0.3, 0.4]` | `500` (the v0.0.18 bug); a `{x,y,w,h}` object |
| `GET /v1/brain/work?q=Q3 offsite date location` | `200`, a non-empty list containing the Step 2 fact | `[]` (no OR fallback) |
| `result.brain_lookups` on the four-word query (tools on) | every entry has a `mode`; a four-word lookup such as `"Q3 offsite date location"` has `count ≥ 1` and `"mode": "or"` | a four-word lookup with `count 0`; `mode` absent |

## Cleanup

Soft-delete any smoke entries you created:

```sh
export EID="<an id from a GET above>"
curl -sS -o /dev/null -w "%{http_code}\n" -X DELETE \
  -H "Authorization: Bearer $TOKEN" "$HOST/v1/brain/work/$EID"   # -> 204
```

Return `AGENT_TOOLS_ENABLED` / `BRAIN_ENABLED` / `AGENT_ENABLED` to their intended values
and `dc up -d api worker` if you flipped any only for this smoke.

## Results log

| Date (UTC) | Release | Operator | Extract (Remember) | Recall (Ask, other canvas) | brain_search tool | Notes |
|---|---|---|---|---|---|---|
| 2026-09-21 19:40 | v0.0.18 | Release Operator (curl on host, synthetic canvas PNGs) | pass — done, 1 "Saved to brain" card, 2 writes, `brain_entry_ids`=2 | **pass** — answer: "Austin, 14 Oct (Q3 offsite)… flights by 30 Sep" | called with "Q3 offsite date location", **count 0** (AND-matching) → stage 30 | Recall rode on `<brain_context>` injection; `GET /brain/work?q=offsite` returned 500 (stage 30). Tools backfilled to `['brain_search']` on the four seeded spaces. |
