# Status & Handoff

> Runtime/ops truth (framework-spec §4.6). Generated from `.verity/runtime.json`
> by the Release/Deploy Operator. Secret LOCATIONS only — never values.

**Live version:** 0.0.14
**Deployed at:** 2026-09-16T19:29:06Z
**Rollback from:** ghcr.io/seanerama/inkwell-ai-server@sha256:5e8e4755c4b5dae860e544ff4c82f742255c0eb31b6612a156fa4ccbf04849ac (v0.0.13) + data set /srv/inkwell/backups/staging/20260917T170918Z-pre-deploy

## Environments
- **staging:** {"status":"deployed v0.0.14 by digest (v0.5 ops hygiene: backups + rotation runbook); server-image + APK lanes green, instrumented lane RED on the known stage-16 test; pre-deploy backup taken; canary passed; Playwright smoke 3/3; restore drill passed (with --into workaround, stage 19); pepper rotation FAILED and was rolled back (stage 20)","url":"https://mini-hp01.taile0ffc4.ts.net:8444","image":"ghcr.io/seanerama/inkwell-ai-server@sha256:c80a4d831145132f2e00d0e6585e856b0fd3a79832b068170dd91068acbaa25d","deployed_at":"2026-09-17T17:13:26Z"}
- **prod:** {"status":"not deployed"}

## Secret locations (names + on-disk locations only, never values)
- ANTHROPIC_API_KEY, POSTGRES_PASSWORD, INKWELL_TOKEN_PEPPER, INKWELL_BLOB_SIGNING_KEY @ mini-hp01:/srv/inkwell/<env>/.env (mode 600; canonical copies in the operator's password manager)
- ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD @ GitHub Actions secrets on seanerama/inkwell-ai; offline backup ~/.verity/secrets/inkwell-ai/ on the operator workstation
- Staging backup sets @ mini-hp01:/srv/inkwell/backups/staging/<set>/ (db.dump, blobs.tar.zst, manifest.json; mode 700; latest symlink)
- Staging .env rollback copy @ mini-hp01:/srv/inkwell/staging/.env.bak-20260917T171052Z (holds the pre-rotation blob key; delete after stage 20 lands)

## Coordination notes
- 2026-09-15: walking skeleton verified on the physical tablet against staging v0.0.4 (pair, Check, Ping round-trip). Pending on operator: rotate ANTHROPIC_API_KEY (exposed in a session log), sudo systemctl enable inkwell-staging. Prod not yet promoted (confirm gate).
- 2026-09-16: Tailscale SSH check mode expires roughly daily; the operator must re-approve the login link before deploy.sh can run.
- 2026-09-16: first successful real agent job on staging (canvas.ask replay of the owner's 'what is 1+9=?' export): 3038 in / 161 out tokens.
- v0.0.12 (stages 11 Library + 12 Formalize) deployed to staging 2026-09-16 by digest; health 0.0.12, canary canvas.ask done (1 annotation, 1 card), Playwright smoke 3/3 incl. new GET /canvases bearer gate (200 [] with tablet token). Prod NOT promoted (confirm gate). On-device smokes pending: smoke/library.md, smoke/formalize.md on the v0.0.12 APK. No new server env vars; client flags LIBRARY and FORMALIZE ON in release by documented exception.
- 2026-09-16: owner ran smoke/library.md and smoke/formalize.md on the tablet with the v0.0.12 APK — both pass. v0.3 (Library + Formalize) verified on device; staging acceptance complete.
- v0.0.13 (stages 13–15, Phase 3 spaces) deployed to staging 2026-09-17 by digest. Kill-switch flip: SPACES_EDITABLE=true set in /srv/inkwell/staging/.env (deliberate, so the tablet can edit/create spaces). Seeder backfilled the four default prompts (532–559 chars, distinct). Server spaces smoke: PATCH colour round-trip 200, slug PATCH 422, POST unauth 401. Instrumented lane RED: SpaceSettingsInstrumentedTest.plus_tab_creates_a_space… times out, deterministic on two runs (35170817917, 35171120120) — the APK is released but the '+ new space' flow is UNVERIFIED; bug stage 16 filed. Prod NOT promoted. On-device smokes pending: smoke/spaces.md (Work vs Learning acceptance), smoke/space-settings.md.
- 2026-09-17: owner ran smoke/spaces.md and smoke/space-settings.md on the tablet (v0.0.13): both pass — Work vs Learning answers differ (Phase 3 acceptance met), prompt edit changed the next answer, '+' new space worked on device. Stage 16 failure is therefore emulator/test-side; staging acceptance for v0.4 complete pending the stage 16 fix for the lane.
- v0.0.14 (stages 17–18) deployed to staging 2026-09-17 by digest; remote-deploy took the first pre-deploy backup (db 25 KB, blobs 217 KB). Host restore drill passed into a scratch project (5 spaces, 19 blobs, health ok) but only with --into: default project name rejected by Compose → bug stage 19 (#42). Rotated INKWELL_BLOB_SIGNING_KEY on staging 2026-09-17 (runbook §2; note its verification route does not exist — folded into #42). Pepper rotation (runbook §3) FAILED: compose does not pass INKWELL_TOKEN_PEPPER_PREVIOUS and migrate-check false-greened; tablet token was 401 for ~2 min, rolled back from .env.bak, token 200, canary ok → bug stage 20 (#43). PENDING on operator (sudo): run /tmp/host-setup.sh on mini-hp01 to install rclone + the nightly inkwell-backup@staging.timer (files staged in /tmp). Instrumented lane still red on the stage-16 test. Prod NOT promoted.
- 2026-09-17: host-setup re-run on mini-hp01 (Omarchy/pacman): rclone installed, inkwell-backup@staging.timer enabled (next run 03:32 UTC 2026-09-18, RandomizedDelaySec); a nightly-style backup.sh run as the operator succeeded (set 20260917T235505Z, latest updated). Stage 17 exit state complete except the first timer-driven run, to be confirmed tomorrow via journalctl.
