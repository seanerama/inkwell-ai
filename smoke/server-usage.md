# UI-smoke: server `GET /usage` returns zeros on a fresh deploy

"Observably-works" check for the **Operator**, run against a freshly deployed staging
server. It confirms the Stage 5 usage surface (SPEC §10.5, contract `device-api`
`GET /usage`) is wired and reachable before any real agent traffic exists. There is no
browser UI for this endpoint (ADR-0001, native client), so this operator curl is the
replacement smoke.

## Preconditions

- A staging server is deployed and healthy: `GET /v1/health` returns
  `{ "status": "ok", ..., "contract": "device-api/v1" }`.
- No `canvas.annotate` jobs have run yet (fresh database), so all totals must be zero.
- You can reach the server host (below shown as `$HOST`, e.g.
  `https://inkwell-staging.example.com`).

## Step 1 — mint a device token

Device tokens are minted by the server CLI (ADR-0008). On the server host (or an
`exec` into the container):

```
inkwell token create --name "usage-smoke"
```

Copy the printed `token:` value — it is shown once and is not recoverable. Export it:

```
export TOKEN="<the printed token>"
export HOST="https://inkwell-staging.example.com"
```

## Step 2 — curl `GET /usage`

```
curl -s -H "Authorization: Bearer $TOKEN" "$HOST/v1/usage"
```

- *Expected (PASS):* exactly

  ```json
  {"jobs": 0, "input_tokens": 0, "output_tokens": 0, "by_space": {}}
  ```

  On a fresh deploy every total is `0` and `by_space` is empty. Seeing this is the PASS.

- *Also acceptable:* passing a `since` filter narrows the window and must still return
  zeros on a fresh deploy:

  ```
  curl -s -H "Authorization: Bearer $TOKEN" "$HOST/v1/usage?since=2020-01-01T00:00:00Z"
  ```

- *FAIL signals:*
  - `401` — the token is missing/typo'd (the endpoint requires the bearer token).
  - `404`/`5xx` — the route is not deployed or the server is unhealthy.
  - Non-zero totals on a database that has never run an agent job — investigate before
    trusting later usage numbers.

Operator result (fill in):
- [ ] `GET /v1/health` ok: __________
- [ ] Token minted: __________
- [ ] `GET /usage` returned all-zero totals and empty `by_space`: __________

## Notes

- Once real `canvas.annotate` jobs run (kill-switch `AGENT_ENABLED=true`), `jobs`,
  `input_tokens`, and `output_tokens` accumulate and `by_space` gains a per-space
  breakdown keyed by space id. Re-running this curl then shows the running totals
  (SPEC §10.5). With `AGENT_ENABLED=false` (the default), annotate jobs fail with an
  "Agent disabled" card and record no tokens, so totals stay at zero.
