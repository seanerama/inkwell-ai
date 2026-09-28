# Assessment: fail closed on default or empty secrets (Revisit proposal 2): stage 37

- **Source:** `docs/revisit/2026-09-24-revisit.md`, proposal 2 ("Fail closed on default or
  empty secrets at boot; turn off the unauthenticated OpenAPI/docs in deployment").
- **Decision:** **ACCEPT** as **stage 37 (bug)**. No ADR is needed: it enforces what
  ADR-0008 and ADR-0004 already require ("Override in every env", `config.py:17`). No
  contract changes.

## Claim / reality (verified 2026-09-28)

| Claim | Checked | Reality |
|---|---|---|
| Insecure defaults | `server/app/config.py:18` `token_pepper = "dev-pepper-change-me"`, `:27` `blob_signing_key = "dev-blob-key-change-me"` | true |
| An unset variable becomes empty | `deploy/compose.yml:8,10` pass `${INKWELL_TOKEN_PEPPER}` / `${INKWELL_BLOB_SIGNING_KEY}` with no default or guard | true: an empty string is accepted |
| Nothing refuses to start | no validator on `Settings`; `create_app` (`main.py:32-37`) doesn't check | true |
| There's an environment switch to hang this on | `Settings.env = "dev"` (`config.py:104`) exists; compose sets `INKWELL_ENV` (default `staging`); **nothing in `server/app` reads it** | true: dormant, and safe to give meaning |
| Staging would pass a strict rule | host `.env` (lengths only, never values): `INKWELL_ENV` = `staging`, pepper 64 characters, blob key 64 characters | true: the ≥ 32 rule is safe |
| The deploy can abort before swap | `remote-deploy.sh:50-55`: `alembic upgrade head`, then `inkwell db seed` with the new image, then `up -d` | the seed loads settings, so validation fails before the swap. Alembic's `env.py` doesn't load settings |
| Ops tests would trip the rule | `tests/ops/backup_restore_test.sh:61-65`, `rotate_db_password_test.sh:44-49` use `INKWELL_ENV=staging` with 15-character secrets | true: the fixtures must go to ≥ 32 characters |
| The server test suite would trip it | `ci.yml` sets only `INKWELL_TOKEN_PEPPER=ci-pepper`, and env defaults to `dev` | no: `dev` is exempt |
| Docs routes are unauthenticated | `FastAPI(title=…, version=…)` (`main.py:37`) uses the default `/docs`, `/redoc`, `/openapi.json` | true |
| The docs routes are part of a contract | grep of `contracts/*.md` for docs/openapi/redoc | no: safe to turn off |

## Choices

- **`env` gating rather than always-on.** Local dev and the pytest suite run with the
  defaults. Gating on `env != dev` keeps them working, and deployed environments (compose
  default `staging`) fail closed. An unknown `env` is rejected, so a typo like `prd` can't
  quietly fall back to lax behaviour.
- **≥ 32 characters.** Staging's secrets are 64 hex characters, `openssl rand -hex 32`
  (per the runbook). 32 characters rules out short placeholders while accepting any sensibly
  generated secret.
- **Pepper and blob key must differ, and so must the previous pepper.** A reused secret
  couples two independent protections (token hashes and blob URLs). A previous pepper equal
  to the current one makes the grace window meaningless.
- **Pre-swap `inkwell config check`.** This is explicit and self-documenting rather than
  relying on the seed step happening to load settings. A bad `.env` then aborts the deploy
  with the old containers still serving.
- **No compose `${VAR:?}` guards.** The CI `compose-config` gate renders
  `deploy/compose.yml` without a `.env` and would fail. The app-level check covers the same
  failure with a better message.

## Not in scope

The other Revisit hardening items stay separate:
- proposal 3, the device bearer scoped to the paired host;
- proposal 4, security invariants and a deep audit (`/verity:security`);
- proposal 5, a recovery plan and off-host backup (`/verity:sre`);
- proposal 6, credential hygiene: the Anthropic key rotation record and revoking the
  `push-api-smoke` token.
