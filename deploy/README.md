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

## Backups and restore (ADR-0011)

Staging is backed up with nightly logical dumps on the host, a snapshot before every
deploy, and an optional encrypted off-host copy. All three run the same two scripts
(installed at `/srv/inkwell/bin/` by `host-setup.sh`, refreshed by `deploy.sh`).

```sh
# Take a backup now (host, from the env dir or via the installed script):
/srv/inkwell/bin/backup.sh staging                 # nightly-style set
/srv/inkwell/bin/backup.sh staging --label manual  # a kept, labelled set

# Restore a set into a scratch stack (own volumes + free port; left running):
/srv/inkwell/bin/restore.sh /srv/inkwell/backups/staging/latest

# Restore OVER the live stack (destructive; takes a pre-restore backup first):
/srv/inkwell/bin/restore.sh /srv/inkwell/backups/staging/<set> --into staging --yes

# Tear down a scratch restore (the restore prints its own project name; use it here):
/srv/inkwell/bin/restore.sh --teardown inkwell-restore-<ts>
```

A scratch restore is left running under the project name it prints (`project=…`,
`inkwell-restore-<ts>` by default). Tear it down with the one command above, or by label
if the script is not to hand — this removes containers, network, and volumes without the
scratch's ephemeral compose file:

```sh
docker ps -aq     --filter label=com.docker.compose.project=<p> | xargs -r docker rm -f
docker network ls -q --filter label=com.docker.compose.project=<p> | xargs -r docker network rm
docker volume ls  -q --filter label=com.docker.compose.project=<p> | xargs -r docker volume rm -f
```

Each set lives in `/srv/inkwell/backups/<env>/<UTC-timestamp>[-<label>]/` (mode 700) with
`db.dump` (custom-format `pg_dump`), `blobs.tar.zst`, and a self-describing `manifest.json`
(image ref, alembic head, sizes, sha256s, duration, `remote_copied`). A `latest` symlink
points at the newest set. The nightly run is scheduled by `inkwell-backup@staging.timer`
(03:30 UTC); check it with `journalctl -u inkwell-backup@staging` and
`systemctl status inkwell-backup@staging.timer`.

**Retention** (pruned only after a successful run): the 14 most recent nightly sets and
the 8 most recent Sunday sets are kept; `--label` sets (pre-deploy, manual) are kept for
30 days.

**Off-host copy** is opt-in: set `BACKUP_REMOTE` (an rclone remote path) and
`BACKUP_AGE_RECIPIENT` (an `age` public key) in the env's `.env`. Each set is then
encrypted with `age` and copied with `rclone copy` + verified with `rclone check`. The
private key never lives on the host (locations in `.verity/deploy-access.md`). A local set
always succeeds; an off-host failure is loud (exit 2 + a `.remote-failed` marker).

**Restore drill:** run a scratch `restore.sh` on the host and compare its printed counts
against the live `/v1/spaces` and `/v1/usage` every minor release; record it in
`smoke/backup-restore.md`. The `backup-restore` CI gate exercises the same round-trip on
every push.

## Pushing from a script (agent tokens)

Any script on the tailnet can push a PDF/PNG or a blank canvas to a space over HTTPS with
an **agent-kind** token (Stage 23, ADR-0012 §4). Agent tokens can only reach the push API;
they cannot read the device routes, and device tokens cannot push. The push API is gated by
the same `PUSH_ENABLED` kill-switch as the blob routes (default OFF).

Mint an agent token on the host (never on the workstation):

```sh
dc run --rm -T api inkwell token create --name "workstation-push" --kind agent
# prints: id / name / kind: agent / token — store the token; it is not recoverable
export TOKEN="<the printed token>"
export HOST="https://mini-hp01.taile0ffc4.ts.net:8444"   # staging :8444, prod :8443
```

Push a document (multipart; one agent-origin canvas per page, delivered via `/sync`):

```sh
curl -sS -X POST "$HOST/v1/push/document" \
  -H "Authorization: Bearer $TOKEN" \
  -F file=@brief.pdf -F space=work -F title="Brief" -F note="Please review"
# {"job_id":"…","canvas_ids":["…"]}
```

Push a blank canvas (JSON):

```sh
curl -sS -X POST "$HOST/v1/push/canvas" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"space":"work","title":"Sketch","landscape":false}'
```

Limits mirror `POST /blobs`: 20 MB, PDFs ≤ 20 pages, mime decided by magic-byte sniff
(`413`/`415`/`422`); unknown space → `404`; kill-switch off → `403 disabled`; rate limit
10/min per agent token → `429`. Record each agent token in `.verity/deploy-access.md` by
**location only** (who holds it and where it runs — never the secret itself).

## Local gate database

The `server-test` gate needs a real Postgres:

```sh
docker compose -f deploy/compose.test.yml up -d postgres
export DATABASE_URL=postgresql+psycopg://inkwell:inkwell@localhost:5432/inkwell
node .verity/run-gates.cjs
```

If host port 5432 is taken, set `INKWELL_TEST_PG_PORT` (e.g. `5433`) before `up` and
point `DATABASE_URL` at that port.
