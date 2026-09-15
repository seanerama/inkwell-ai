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

## Operator prerequisites (one-time, outside code)

```sh
sudo usermod -aG docker smahoney        # then re-login
sudo mkdir -p /srv/inkwell/{staging,prod}
sudo chown -R smahoney:smahoney /srv/inkwell
```

## Tailscale serve (TLS + public tailnet name)

Port 443 belongs to another service on the host — do not touch it.

```sh
# staging: https://mini-hp01.taile0ffc4.ts.net:8444  ->  127.0.0.1:8001
tailscale serve --bg --https=8444 http://127.0.0.1:8001

# prod:    https://mini-hp01.taile0ffc4.ts.net:8443  ->  127.0.0.1:8000
tailscale serve --bg --https=8443 http://127.0.0.1:8000
```

The compose stack binds the API to `127.0.0.1:${API_PORT}` (staging `8001`, prod
`8000`), so only `tailscale serve` exposes it.

## Deploying

```sh
./deploy/deploy.sh staging v0.0.1     # pull tag -> migrate -> seed -> up -> health poll
```

Smoke check:

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
