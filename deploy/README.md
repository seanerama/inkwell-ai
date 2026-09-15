# Deploy

Inkwell AI runs as a `docker compose` stack (api + worker + postgres) on **mini-hp01**,
managed by systemd and fronted by `tailscale serve` for TLS on the tailnet (ADR-0005).
Access locations (host, secret paths) are in `.verity/deploy-access.md` (gitignored).

## Layout on the host

```
/srv/inkwell/staging/   compose.yml  .env  pgdata/  blobs/
/srv/inkwell/prod/      compose.yml  .env  pgdata/  blobs/
```

Secrets live only in each environment's `.env` (mode 600): `POSTGRES_PASSWORD`,
`INKWELL_TOKEN_PEPPER`, `INKWELL_BLOB_SIGNING_KEY`, `ANTHROPIC_API_KEY`
(unused until Stage 5). Copy `.env.example` and fill in real values.

## One-time host setup (needs sudo; the operator runs it interactively)

```sh
scp deploy/host-setup.sh deploy/systemd/*.service smahoney@mini-hp01.taile0ffc4.ts.net:/tmp/
ssh -t smahoney@mini-hp01.taile0ffc4.ts.net 'bash /tmp/host-setup.sh'
```

`host-setup.sh` adds the operator to the `docker` group, creates
`/srv/inkwell/{staging,prod}`, installs the systemd units (boot-time `compose up`
only), and configures `tailscale serve`: staging `:8444 -> 127.0.0.1:8001`, prod
`:8443 -> 127.0.0.1:8000`. Port 443 belongs to another service on the host and is
left alone. Log out and back in afterwards so the group applies, then create each
environment's `.env` from `.env.example` (mode 600).

## Deploying (no sudo)

```sh
./deploy/deploy.sh staging v0.0.1                                        # by tag
./deploy/deploy.sh staging ghcr.io/seanerama/inkwell-ai-server@sha256:…  # by digest (preferred)
```

The digest is published on every GitHub Release as the `server-image.digest` asset and
in the release body. Deploying by digest makes staging and prod byte-identical.
`deploy.sh` records the previous reference in `.env.previous` on the host and prints
it, so rollback is re-running `deploy.sh` with that reference (migrations are additive).

Smoke check (also `.verity/smoke.json`, run by `verity smoke run`):

```sh
curl https://mini-hp01.taile0ffc4.ts.net:8444/v1/health
# {"status":"ok","version":"0.0.1","contract":"device-api/v1"}
```

## Local gate database

The `server-test` gate needs a real Postgres:

```sh
docker compose -f deploy/compose.test.yml up -d postgres
export DATABASE_URL=postgresql+psycopg://inkwell:inkwell@localhost:5432/inkwell
node .verity/run-gates.cjs
```

If host port 5432 is taken, set `INKWELL_TEST_PG_PORT` (e.g. `5433`) before `up` and
point `DATABASE_URL` at that port.
