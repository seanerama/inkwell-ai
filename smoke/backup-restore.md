# Host-drill: backup + scratch restore reproduces the live counts

"Observably-works" check for the **Operator**, run **on the host** (mini-hp01) against the
live staging stack. It confirms Stage 17 (ADR-0011): `backup.sh` takes a consistent set,
`restore.sh` rebuilds it into a throwaway stack, and the restored row counts match what
the live server reports. This is not a browser flow (ADR-0001); the operator runs it on
the host and records the result below. Repeat it every minor release.

## Preconditions

- You are on the host as the operator (docker-group member) and `/srv/inkwell/bin/backup.sh`
  and `/srv/inkwell/bin/restore.sh` are installed (via `host-setup.sh` / a deploy).
- The live staging stack is healthy: `GET /v1/health` returns
  `{ "status": "ok", ..., "contract": "device-api/v1" }`.
- `zstd` is installed on the host (`host-setup.sh` installs it). `age`/`rclone` are only
  needed if `BACKUP_REMOTE` is set.

## Step 1 — read the live counts to compare against

Mint (or reuse) a token and read the live surfaces. `HOST` is the tailnet name, e.g.
`https://mini-hp01.taile0ffc4.ts.net:8444`.

```
export TOKEN="<a device token>"
export HOST="https://mini-hp01.taile0ffc4.ts.net:8444"

# number of spaces (expect the four seeded + any created):
curl -s -H "Authorization: Bearer $TOKEN" "$HOST/v1/spaces" \
  | python3 -c "import sys,json; print('spaces:', len(json.load(sys.stdin)))"

# usage totals (jobs that recorded tokens):
curl -s -H "Authorization: Bearer $TOKEN" "$HOST/v1/usage" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print('usage jobs:', d['jobs'])"
```

## Step 2 — take a backup

```
/srv/inkwell/bin/backup.sh staging --label drill
ls -l /srv/inkwell/backups/staging/latest
cat /srv/inkwell/backups/staging/latest/manifest.json
```

- *Expected (PASS):* a new set directory (mode 700) with `db.dump`, `blobs.tar.zst`, and
  `manifest.json`; the `latest` symlink points at it; exit code `0`.
- *FAIL signals:* exit `1` (local failure — check `docker compose ps`, the postgres
  container, disk space); any secret printed to the terminal (there must be none — no
  `compose config`, no `cat .env`).

## Step 3 — restore into a scratch stack

```
/srv/inkwell/bin/restore.sh /srv/inkwell/backups/staging/latest
```

- *Expected (PASS):* it verifies the sha256s, starts a scratch project
  `inkwell-restore-<ts>` on a free port, runs `pg_restore` + blob extract + `alembic
  upgrade head`, reports `health=ok`, and prints:
  ```
  rows: spaces=<N> jobs=<N> cards=<N> canvases=<N> tokens=<N>
  blobs: files=<N>
  ```
- The `spaces` count and `jobs`/blob counts must match the live values from Step 1
  (usage `jobs` counts token-recording jobs; the restore `jobs` count is the total rows,
  so compare `spaces` and the blob file count most directly, and sanity-check the rest).
- *FAIL signals:* a sha256 mismatch (corrupt set); `alembic upgrade head` failing (the
  dump is ahead of the code); `health` never `ok`; counts that do not match live.

## Step 4 — tear the scratch stack down

Copy the teardown line the restore printed:

```
docker compose -p inkwell-restore-<ts> -f <printed-workdir>/compose.yml down -v
```

- *Expected (PASS):* the scratch containers and its `_pgdata` / `_blobs` volumes are gone;
  the live staging stack is untouched (its project name is `staging`, not the scratch one).

## Operator result (fill in)

| Check | Result |
| --- | --- |
| `GET /v1/health` ok (live) | |
| `backup.sh staging` exit 0, `latest` present, manifest written | |
| No secret printed during backup | |
| `restore.sh` scratch: `health=ok` | |
| Restored `spaces` count == live `/v1/spaces` | |
| Restored blob file count == live | |
| Scratch stack torn down; live stack untouched | |
| (if `BACKUP_REMOTE` set) `rclone check` passed once | |

## Notes

- The scratch restore is left **running** on purpose so you can poke it before tearing it
  down; it uses its own volumes and a free port, so it never touches live data.
- Off-host copy is opt-in; when `BACKUP_REMOTE` is unset the backup is local-only and
  exits `0` with `remote_copied: false` in the manifest.
- A restore that runs `alembic upgrade head` cleanly proves the dump is at or behind the
  deployed code — the backup can always be restored onto the current image.
