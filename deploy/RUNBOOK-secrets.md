# Inkwell AI — secret rotation runbook (Stage 18)

> The checklist for rotating any server secret. Design: ADR-0008 (peppered token
> hashes), ADR-0004 (signed blob URLs), ADR-0011 (backup before rotate). Secret
> **locations** are in `.verity/deploy-access.md`; secret **values** live only in the
> operator's password manager and each env's `.env` (mode 600) on the host — never in
> git, CI, or this file.

## Conventions

- All commands run on the host from the environment directory: `cd /srv/inkwell/<env>`
  (`<env>` is `staging` or `prod`). Scripts are refreshed to `/srv/inkwell/bin/` on
  every deploy.
- `dc()` below means `docker compose -f compose.yml`.
- **Backup first.** Where a section names a precondition backup, run it and confirm it
  succeeded (exit 0) before changing anything. `backup.sh <env> --label <label>` writes
  a local set and updates `latest`; a non-zero exit means do not proceed.
- **Never** run `docker compose config` and **never** `cat .env` (both leak secrets).
  Edit a single key in `.env` with an editor or a targeted `sed`.
- After every rotation, record it with the `verity status note` line given at the end
  of the section — **dates and names only, never values**.

---

## 1. `ANTHROPIC_API_KEY`

- **Impact:** none. New agent jobs pick up the new key on restart; in-flight jobs are
  short. No device or token impact.
- **Precondition (backup):** none required (no data change). `backup.sh <env> --label
  pre-rotate` is still cheap insurance if you like.
- **Steps:**
  1. Create a new key in the Anthropic console.
  2. Edit `.env`: set `ANTHROPIC_API_KEY=<new>` (this key is read straight from the
     process environment by the SDK; it is deliberately NOT a `Settings` field, see
     `config.py`).
  3. `dc up -d api worker`.
  4. `dc exec -T api inkwell canary` — must come back `done` with ≥1 annotation and ≥1
     card (requires `AGENT_ENABLED=true`; otherwise verify by running one real job from
     the tablet).
  5. Delete the OLD key in the Anthropic console.
- **Verification:** the canary (or a tablet job) succeeds; `GET /v1/usage` shows the new
  job's spend.
- **Rollback:** re-enter the previous key in `.env` and `dc up -d api worker` (only if
  the old key was not yet deleted in the console).
- `verity status note "rotated ANTHROPIC_API_KEY on <env> <YYYY-MM-DD>"`

---

## 2. `INKWELL_BLOB_SIGNING_KEY`

- **Impact:** **none today.** No blob route ships yet — `blobs/local.py` has a
  `signed_url` helper but nothing serves it and there is no `POST /v1/blobs`, so no
  signed blob URLs (ADR-0004) are issued to devices. Rotating this key therefore has no
  user-visible effect until the blob routes land; when they do, pre-restart URLs would
  stop verifying (short-lived; the device would re-request).
- **Precondition (backup):** none required (no data change).
- **Steps:**
  1. Edit `.env`: set `INKWELL_BLOB_SIGNING_KEY=<new>`.
  2. `dc up -d api worker`.
- **Verification:** `dc up -d api worker`, then poll `/v1/health` until
  `{"status":"ok"}`, then `dc exec -T api inkwell canary` — exports still store and the
  job runs (this is what is observable today; there is no blob route to verify a signed
  URL against yet).
- **Rollback:** restore the previous key in `.env` and `dc up -d api worker`.
- `verity status note "rotated INKWELL_BLOB_SIGNING_KEY on <env> <YYYY-MM-DD>"`

---

## 3. `INKWELL_TOKEN_PEPPER` (with the dual-pepper grace window)

- **Impact:** **none** when the tablet syncs during the grace window — the token keeps
  working with no re-pairing. Without the grace window, rotating the pepper invalidates
  every device token (ADR-0008 original consequence). If a device never syncs during the
  window, only that device must re-pair afterwards.
- **Precondition (backup):** `backup.sh <env> --label pre-rotate` (confirm exit 0). The
  rotation writes to `device_tokens`. Also copy `.env` aside first:
  `cp .env .env.bak-$(date +%Y%m%d-%H%M%S) && chmod 600 .env.bak-*` — the locked-out
  rollback below restores the old pepper from it.
- **How the grace window works:** the compose `app-env` anchor now passes
  `INKWELL_TOKEN_PEPPER_PREVIOUS` to `api` **and** `worker` (Stage 20 — before that it
  never reached the containers and the window was dead). `verify_token` looks a token up
  by the CURRENT-pepper hash first; on a miss, and only while
  `INKWELL_TOKEN_PEPPER_PREVIOUS` is set, it looks it up by the PREVIOUS-pepper hash and,
  if the row is live, re-hashes it to the current pepper in that same request and stamps
  `hash_version` with the CURRENT pepper generation. Revoked rows are never resurrected
  under either pepper. When the previous pepper is unset, behaviour is exactly
  single-pepper (the safe default — no kill-switch flag needed).
- **`inkwell token migrate-check` semantics (Stage 20 — fails closed):** the generation
  is an explicit integer in the `app_meta` table, bumped by `rotate-pepper --begin`
  (a missing row means generation 1). `migrate-check` exits **1** while
  `INKWELL_TOKEN_PEPPER_PREVIOUS` is set **and** any live (`revoked_at IS NULL`) token
  has `hash_version <` the current generation, printing the stragglers to stderr; it
  exits **0** otherwise. This means it is RED the instant the window opens (before any
  token migrates) and only goes GREEN once every live token has been re-hashed — it can
  never say "safe to close" while a device is still on the old pepper. (The old
  `MAX(hash_version)` rule false-greened at the start of a rotation; that was the bug.)
- **Steps:**
  1. `dc exec -T api inkwell token rotate-pepper --begin` — records the next generation
     in `app_meta` and prints these steps. It touches no secret.
  2. In `.env` set `INKWELL_TOKEN_PEPPER_PREVIOUS=<old-pepper>` and
     `INKWELL_TOKEN_PEPPER=<new-pepper>`.
  3. `dc up -d api worker`.
  4. Use the tablet once — any `/v1/sync` (open the app, let it sync), or the operator's
     curl with the tablet token. This re-hashes its token to the new pepper/generation.
  5. `dc exec -T api inkwell token list` — the tablet's row should now show the new `v`.
  6. `dc exec -T api inkwell token migrate-check` — must exit `0`. While it exits `1`,
     one or more live devices have not synced yet: sync them (or revoke/accept re-pair)
     before finishing.
  7. `dc exec -T api inkwell token rotate-pepper --end` — refuses (exit 1) while
     `migrate-check` is red; on success it confirms it is safe to close the window.
  8. Remove `INKWELL_TOKEN_PEPPER_PREVIOUS` from `.env` (clear the value) and
     `dc up -d api worker`.
- **Verification:** `inkwell token list` shows the live token(s) at the new
  `hash_version` with a recent `last_seen`; `migrate-check` exits 0; the tablet keeps
  working without re-pairing.
- **Rollback:**
  - *Window still open* (previous pepper still set): swap the two values back
    (`INKWELL_TOKEN_PEPPER=<old>`, previous cleared) and `dc up -d api worker`; tokens
    re-hash back on next use.
  - *Window already closed / device locked out*: restore `INKWELL_TOKEN_PEPPER` from the
    `.env.bak-<ts>` you took above (and clear `INKWELL_TOKEN_PEPPER_PREVIOUS`) and
    `dc up -d api worker`; the device's token verifies again. If the old pepper is truly
    gone, re-pair the device with `inkwell token create`.
- `verity status note "rotated INKWELL_TOKEN_PEPPER on <env> <YYYY-MM-DD> (grace window; migrate-check green)"`

---

## 4. `POSTGRES_PASSWORD`

- **Impact:** a few seconds of API downtime while `api` + `worker` are recreated. The
  Postgres container keeps running (only the role password changes); no data loss.
- **Precondition (backup):** the script takes it for you — `backup.sh <env> --label
  pre-rotate` runs first and a local failure aborts the rotation.
- **Steps:**
  1. `deploy/rotate-db-password.sh <env>` (or `/srv/inkwell/bin/rotate-db-password.sh
     <env>`). It: backs up → generates a strong password → `ALTER USER inkwell PASSWORD`
     inside the postgres container (value fed on stdin, never argv, never echoed) →
     rewrites only `POSTGRES_PASSWORD` in `.env` → `dc up -d api worker` → polls
     `/v1/health` → runs the canary only if `AGENT_ENABLED=true`.
  2. Update the canonical copy in the password manager under "Inkwell / <env>" — copy the
     new value out of `.env` (it is not printed by the script).
- **Verification:** the script prints `health=ok` and exits `0`; a tablet request (e.g.
  `GET /v1/spaces`) still returns 200 (proves the API reaches the DB with the new
  password). The `rotate-db-password` CI gate exercises exactly this path.
- **Rollback:** restore from the `pre-rotate` set with `restore.sh
  /srv/inkwell/backups/<env>/latest --into <env> --yes` (which also re-backs-up first),
  or manually `ALTER USER inkwell PASSWORD` back to the previous value and restore the
  `.env` key. The old password stops working the moment the `ALTER` commits.
- `verity status note "rotated POSTGRES_PASSWORD on <env> <YYYY-MM-DD>"`

---

## 5. Device tokens

- **Impact:** the revoked device stops working immediately and must re-pair; other
  devices are unaffected.
- **Precondition (backup):** `backup.sh <env> --label pre-rotate` (writes
  `device_tokens`).
- **Steps:**
  1. `dc exec -T api inkwell token list` — find the id to revoke.
  2. `dc exec -T api inkwell token revoke <id>`.
  3. `dc exec -T api inkwell token create --name <device>` — copy the plaintext (shown
     once).
  4. Enter the new token on the tablet's pairing screen.
  5. Update the password manager: "Inkwell / staging tablet token" (location recorded in
     `.verity/deploy-access.md`).
- **Verification:** `inkwell token list` shows the old id `revoked` and the new id
  `active`; the tablet reaches `GET /v1/spaces` (200).
- **Rollback:** none for the revoked token (revocation is intentional). If you revoked the
  wrong device, mint a fresh token and re-pair it — a revoked row is never resurrected.
- `verity status note "rotated device token on <env> <YYYY-MM-DD> (revoked <old-id>, minted <new-id>)"`

---

## 6. Android upload keystore — **NOT rotatable**

- **Impact:** the upload key signs the APK. Rotating it changes the signing identity,
  which **breaks in-place upgrades** for every installed device — a rotated key means a
  new app id (a fresh install), not an upgrade. For our sideloaded APK there is no Play
  App Signing key-rotation path. So: do **not** rotate it; protect it instead.
- **Backup location (LOCATION only):** the offline backup is
  `~/.verity/secrets/inkwell-ai/` on the operator workstation (`upload-keystore.jks` +
  `keystore.env`, mode 600), mirrored in the password manager under "Inkwell / Android
  upload keystore". The CI signing secrets are the GitHub Actions repository secrets
  `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`
  (`inkwell-upload`), `ANDROID_KEY_PASSWORD` (see `.verity/deploy-access.md`).
- **If it leaks:** you cannot cleanly rotate a sideloaded app's signing key. Options, all
  out of the normal path: (a) publish under a **new app id** and have the owner do a
  fresh install; (b) if the app is ever moved to Google Play, enrol in Play App Signing
  and use its key-rotation — out of scope for sideloaded APKs today. Meanwhile rotate the
  GitHub secrets' *passwords* only if the passphrase (not the key) leaked, and re-encode
  `ANDROID_KEYSTORE_BASE64` from the offline backup.
- **Verification:** a `v*` tag build in GitHub Actions still produces an APK that installs
  as an upgrade over the previous version on the tablet.
- **Rollback:** restore `upload-keystore.jks` + `keystore.env` from the offline backup and
  re-set the GitHub secrets from it.
- `verity status note "reviewed Android upload keystore protection on <YYYY-MM-DD> (not rotated — see runbook §6)"`

---

## 7. Compromise checklist

When a secret is (or may be) exposed:

1. **Identify what leaked** — which key(s), where (a session log, a screenshot, a pushed
   file), and the window of exposure.
2. **Rotate in order 1 → 2 → 3 → 4** using the sections above: `ANTHROPIC_API_KEY`,
   `INKWELL_BLOB_SIGNING_KEY`, `INKWELL_TOKEN_PEPPER` (grace window), `POSTGRES_PASSWORD`.
   Rotate all of them if you cannot bound the exposure.
3. **Audit tokens:** `dc exec -T api inkwell token list` — revoke any device token you do
   not recognise (§5).
4. **Audit spend:** `GET /v1/usage` — look for unexpected jobs/spend that would indicate
   the Anthropic key was used by someone else.
5. **Record it in STATUS** with the date, using the `verity status note` lines from each
   section you actioned.

### Worked example — 2026-09-16 exposure

- **What happened:** `ANTHROPIC_API_KEY` was exposed in a session log (recorded in
  STATUS 2026-09-15/16). It was handled by hand and the key-rotation step was never
  confirmed closed.
- **Close the loop (do now):**
  1. Follow §1: create a new Anthropic key, edit `.env`, `dc up -d api worker`, run the
     canary/one real job, then **delete the old key** in the console.
  2. Confirm `GET /v1/usage` shows no unexpected spend during the exposure window.
  3. `dc exec -T api inkwell token list` for any unexpected tokens (none expected — the
     leak was the Anthropic key, not the pepper).
  4. Record it: `verity status note "closed 2026-09-16 ANTHROPIC_API_KEY exposure on staging <YYYY-MM-DD>: new key in place, old key deleted, /v1/usage clean"`.
- `verity status note "completed compromise checklist for 2026-09-16 exposure on staging <YYYY-MM-DD>"`

## Rotation log

Dates and names only, never values. Mirror each line in STATUS with `verity status note`.

| Date (UTC) | Env | Secret | Outcome |
|---|---|---|---|
| 2026-09-16 | staging | POSTGRES_PASSWORD, INKWELL_TOKEN_PEPPER, INKWELL_BLOB_SIGNING_KEY | rotated by hand after a session-log exposure; volumes wiped (pre-runbook) |
| 2026-09-17 | staging | INKWELL_BLOB_SIGNING_KEY | rotated (§2); §2 verification route did not exist (fixed in stage 19) |
| 2026-09-17 | staging | INKWELL_TOKEN_PEPPER | **failed** — grace window dead in deployment; rolled back from `.env.bak`; tablet 401 for ~2 min (stage 20) |
| 2026-09-18 | staging | INKWELL_TOKEN_PEPPER | rotated (§3 on v0.0.15): begin → window → token use (v1→v2) → migrate-check green → end → window closed; tablet token 200 throughout, canary ok |
