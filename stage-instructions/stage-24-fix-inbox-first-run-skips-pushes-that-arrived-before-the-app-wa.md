# Stage 24: Fix: inbox first run skips pushes that arrived before the app was installed or upgraded

- **Type:** bug
- **Depends on:** 22
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/53
- **Design:** stage 22 (sync discovery), ADR-0012; `net/PushInbox.kt`, `net/SyncCursorStore.kt`

## Objectives

On 2026-09-21 the owner installed v0.0.16 three days after five documents had been
pushed and saw nothing. The inbox's first-run rule ("seed the cursor with the current
one and do not replay history") means any push that lands before the app's first
successful sync is lost to the device, and the same happens after a reinstall or a
new pairing. After this stage a first run backfills the pushed canvases that are not
on the device yet, and old pushes whose signed links have expired still materialise.

## What to build

- `PushInbox.poll`: when there is no persisted cursor, call `sync(null)` (the most
  recent 100 jobs, ascending), materialise every `to_user`/`done` job whose canvases
  are not already local (existing dedupe via `canvasExists`), then persist the page's
  cursor. Treat a `422`/unknown-cursor response the same way (re-seed with backfill).
- Materialise must succeed for a result whose raster `url` has expired: on `403`
  fetch `GET /canvases/{id}` for a fresh `url` (already specified in stage 22;
  verify it is wired and tested end to end).
- Library: after a backfill the badge counts reflect all newly landed canvases.

## Interface contracts

- **Exposes:** nothing new.
- **Consumes:** `device-api` `/sync` (no cursor → last 100), `GET /canvases/{id}`.

## Testing requirements

- JVM: first run with two old `to_user` jobs (one already local, one not) → exactly
  one materialised, cursor persisted; second poll materialises nothing new.
- JVM: a result with an expired `url` (server returns 403 then a fresh detail) →
  materialised after one refresh.
- Instrumented (MockWebServer): fresh install → `sync` without cursor → two pushed
  canvases appear with badges.
- On-device: the owner's five pending pushes from 2026-09-18 appear after installing
  the fixed APK (recorded in `smoke/push-inbox.md`).

## Acceptance conditions

- [ ] Regression tests in place (first-run backfill; expired-url refresh)
- [ ] The five pre-existing pushes on staging appear on the tablet after upgrade (smoke)
- [ ] Existing suite stays green; CI all-green (instrumented lane run on the branch)

## Pipeline test: NO
