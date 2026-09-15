# UI-smoke: Android pairing + ping round-trip

Manual "observably-works" check for the **Operator**, run on the real tablet (Lenovo
Idea Tab Pro, Android 14) on the tailnet. Browser smoke does not apply to a native
client (ADR-0001), so this is the human replacement. Run it after every release that
ships a new APK.

## Preconditions

- Tablet is signed into Tailscale and can reach the staging host
  (`https://mini-hp01.taile0ffc4.ts.net:8444`).
- Staging server is up: from any tailnet machine,
  `curl https://mini-hp01.taile0ffc4.ts.net:8444/v1/health` returns
  `{"status":"ok","version":"<v>","contract":"device-api/v1"}`.
- A device token has been minted on the server:
  `inkwell token create --name tablet` — copy the plaintext token it prints **once**.
- The signed APK for the tag under test is downloaded from its GitHub Release.

## Steps

1. **Sideload the APK.**
   - Copy the `app-release.apk` from the GitHub Release to the tablet and open it.
   - Approve "install from unknown source" if prompted; install; open **Inkwell AI**.
   - *Expected screenshot A:* the pairing screen — a title "Inkwell AI — Pairing",
     a "Server URL" field, a masked "Device token" field, an outlined **Check**
     button, a filled **Ping** button, and a status line reading `Not paired.`

2. **Enter the connection details.**
   - Server URL: `https://mini-hp01.taile0ffc4.ts.net:8444`
   - Device token: paste the minted token (it renders as dots).

3. **Check the server version.**
   - Tap **Check**.
   - *Expected screenshot B:* the status line reads `Server <version> (device-api/v1)`
     where `<version>` matches the staging `/health` version from the preconditions.
   - Failure looks like `Check failed: ...` — capture it and stop.

4. **Ping round-trip.**
   - Tap **Ping**.
   - *Expected screenshot C (transient):* status shows `Submitting ping...` then
     `Ping queued (<id8>), polling /sync...`. The screen must remain responsive.
   - Within a few `/sync` polls (5 s cadence):
   - *Expected screenshot D:* status reads `Ping done — round-trip OK ({"pong":true})`.
   - A `Ping failed: ...` or a status stuck on "polling" past ~30 s is a failure —
     capture the status line and the server logs.

## Pass criteria

- [ ] Screen renders (screenshot A).
- [ ] Check shows the staging version and `device-api/v1` (screenshot B).
- [ ] Ping reaches `done` with `{"pong":true}` (screenshot D).
- [ ] The token was entered once and persists across an app restart (it is stored in
      `EncryptedSharedPreferences`; reopening the app keeps the URL and token).

## Kill-switch note

`BuildConfig.PING_ENABLED` gates the **Ping** button. Since v0.0.4 it is **ON in both
debug and release** until Stage 6 lands (the round-trip is the walking-skeleton
acceptance on the tablet); Stage 6 turns it OFF in release when Send replaces it.

## Results log

| Date (UTC) | Release | Operator | A | B | C/D | Notes |
|---|---|---|---|---|---|---|
| 2026-09-15 22:35 | v0.0.3 | seanerama | pass | pass (showed 0.0.1: #15) | n/a (Ping OFF in release) | first APK on the tablet |
| 2026-09-15 22:51 | v0.0.4 | seanerama | pass | pass (0.0.4) | pass — job done in 15 ms, delivered via /sync | walking skeleton proven |
