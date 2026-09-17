# UI-smoke: spaces on the server — distinct prompts, create & edit

"Observably-works" check for the **Operator**, run against a freshly deployed staging
server. It confirms Stage 13 (SPEC §1.1 / §10.3, contract `device-api` `GET /spaces`,
`POST /spaces`, `PATCH /spaces/{id}`): the four default spaces now carry distinct
prompts (backfilled on deploy), spaces can be created/edited when `SPACES_EDITABLE=true`,
and the same canvas sent from different spaces reads differently. There is no browser UI
(ADR-0001, native client), so this operator curl is the replacement smoke.

## Preconditions

- A staging server is deployed and healthy: `GET /v1/health` returns
  `{ "status": "ok", ..., "contract": "device-api/v1" }`.
- `deploy/remote-deploy.sh` ran the seeder on this deploy, so the four default prompts
  are present (backfilled without overwriting any operator edit).
- You can reach the server host (below shown as `$HOST`, e.g.
  `https://inkwell-staging.example.com`).

## Step 1 — mint a device token

```
inkwell token create --name "spaces-smoke"
```

Copy the printed `token:` value (shown once). Export it:

```
export TOKEN="<the printed token>"
export HOST="https://inkwell-staging.example.com"
```

## Step 2 — `GET /spaces` shows four distinct prompts

```
curl -s -H "Authorization: Bearer $TOKEN" "$HOST/v1/spaces" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); [print(s['slug'], '::', s['system_prompt'][:70]) for s in d]"
```

- *Expected (PASS):* four rows — `work`, `home`, `learning`, `business` — each with a
  **non-empty and visibly different** `system_prompt` (a terse-colleague voice for
  `work`, a warm household voice for `home`, a patient-tutor voice for `learning`, a
  strategy/finance voice for `business`).
- *FAIL signals:* any empty `system_prompt`; two spaces sharing the same prompt; `401`
  (bad token); `404`/`5xx` (route/server unhealthy).

## Step 3 — `PATCH` Learning's colour and read it back

Requires `SPACES_EDITABLE=true` in the environment's `.env` (default is OFF). With the
switch **off**, `PATCH`/`POST` return `403 {"error":{"code":"disabled"}}` — that itself
is the PASS for the default-safe config.

```
# grab Learning's id
export LID=$(curl -s -H "Authorization: Bearer $TOKEN" "$HOST/v1/spaces" \
  | python3 -c "import sys,json; print(next(s['id'] for s in json.load(sys.stdin) if s['slug']=='learning'))")

# with SPACES_EDITABLE=true:
curl -s -X PATCH -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"color":"#123456"}' "$HOST/v1/spaces/$LID"

# read it back
curl -s -H "Authorization: Bearer $TOKEN" "$HOST/v1/spaces" \
  | python3 -c "import sys,json; print(next(s['color'] for s in json.load(sys.stdin) if s['slug']=='learning'))"
```

- *Expected (PASS, switch ON):* the `PATCH` returns `200` with the Learning space and its
  new `color`; the read-back prints `#123456`; `slug`, `name`, and `system_prompt` are
  unchanged. Sending `{"slug":"x"}` returns `422 {"error":{"code":"validation","message":"slug is immutable"}}`.
- *Expected (PASS, switch OFF / default):* `PATCH` returns `403` with `error.code="disabled"`.
- *FAIL signals:* `200` mutating fields you did not send; `403` when you set the switch
  ON (env not picked up — check the compose env / restart); `5xx`.

## Step 4 — a `canvas.ask` in Learning reads like a tutor (operator judges)

Requires `AGENT_ENABLED=true`. Submit a one-tap `canvas.ask` with `space_id` = Learning
and a small exported note (e.g. a wrong arithmetic answer), poll the job, and read the
`answer` card.

```
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"type\":\"canvas.ask\",\"space_id\":\"$LID\",\"image\":\"<base64 PNG>\"}" \
  "$HOST/v1/jobs"
# then GET /v1/jobs/<id> until status == "done" and inspect result.cards
```

- *Expected (PASS):* the answer explains the *why* and checks understanding with a
  question rather than just stating the result — recognisably a tutor, not the terse
  colleague you would get from `work`. Operator judges the tone.
- *FAIL signals:* an "Agent disabled" error card (`AGENT_ENABLED` off); a terse, owner/
  deadline-style answer (Learning is not picking up its prompt).

## Operator result (fill in)

| Check | Result |
| --- | --- |
| `GET /v1/health` ok | pass (0.0.13, 2026-09-17) |
| Token minted | pass (existing tablet token) |
| `GET /spaces` → four distinct, non-empty prompts | pass (532–559 chars each, distinct openings) |
| `PATCH` Learning colour → read back `#123456` (switch ON) *or* `403 disabled` (switch OFF) | pass (200, read back #123456, restored) |
| `PATCH {"slug":...}` → `422 "slug is immutable"` (switch ON) | pass (422 slug is immutable) |
| `canvas.ask` in Learning reads tutor-toned (operator judgment) | pass (owner, tablet, 2026-09-17) |

## Notes

- `SPACES_EDITABLE` defaults to **OFF**; leave it off unless actively editing spaces
  from a device. `GET /spaces` is never gated.
- The seeder backfills an empty prompt but **never overwrites** a prompt an operator has
  edited, so re-deploying is safe.
