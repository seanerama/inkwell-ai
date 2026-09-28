# UI-smoke: staging fails closed on weak secrets; OpenAPI docs are off

"Observably-works" check for the **Operator**, run after a deploy to staging. It confirms
Stage 37: outside `dev` the server refuses missing/default/short/duplicate secrets
(ADR-0008 pepper, ADR-0004 blob key) and does not serve the unauthenticated OpenAPI
schema or docs. There is no browser UI for this (ADR-0001, native client), so these
operator commands are the smoke.

## Preconditions

- Staging is deployed and healthy: `GET /v1/health` returns
  `{ "status": "ok", ..., "contract": "device-api/v1" }`.
- You are on the host as the operator, in `/srv/inkwell/staging`. `dc()` means
  `docker compose -f compose.yml`.
- **Never** `cat .env` or run `docker compose config` (both leak secrets).

## Step 1 — docs routes return 404

From any tailnet machine (`HOST=https://mini-hp01.taile0ffc4.ts.net:8444`):

```
curl -s -o /dev/null -w '%{http_code}\n' "$HOST/openapi.json"   # expect 404
curl -s -o /dev/null -w '%{http_code}\n' "$HOST/docs"           # expect 404
curl -s -o /dev/null -w '%{http_code}\n' "$HOST/redoc"          # expect 404
curl -s "$HOST/v1/health"                                        # expect "status":"ok"
```

## Step 2 — the live config passes the check

```
dc run --rm -T api inkwell config check
# expect exactly:  config ok (env=staging)   (exit 0)
```

## Step 3 (optional) — a blanked pepper is refused, variable named

A dry run against a scratch copy; the live `.env` is never touched and nothing is started
besides the one-shot check container.

```
umask 077
sed 's/^INKWELL_TOKEN_PEPPER=.*/INKWELL_TOKEN_PEPPER=/' .env > /tmp/inkwell-bad.env
docker compose -f compose.yml --env-file /tmp/inkwell-bad.env \
  run --rm -T --no-deps api inkwell config check; echo "exit=$?"
shred -u /tmp/inkwell-bad.env 2>/dev/null || rm -f /tmp/inkwell-bad.env
# expect: config error: ... INKWELL_TOKEN_PEPPER is empty or unset ...   exit=1
# the output names the variable and the rule and contains NO secret value
```

## Pass / fail

- **Pass:** Step 1 shows `404` for all three docs routes and health ok; Step 2 prints
  `config ok (env=staging)`; Step 3 (if run) exits non-zero naming `INKWELL_TOKEN_PEPPER`.
- **Fail:** any docs route returns `200`; Step 2 prints a `config error` (the deploy
  should already have aborted on this, so investigate how the stack started); or Step 3
  exits 0 or prints a secret value.

Operator result (fill in):
- [ ] `/openapi.json`, `/docs`, `/redoc` → 404; `/v1/health` ok: __________
- [ ] `inkwell config check` → `config ok (env=staging)`: __________
- [ ] (optional) blank-pepper dry run refused, variable named, no value shown: __________
