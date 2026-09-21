# Stage 29: Fix: inbox backfill also runs on upgrade, plus a manual Resync in Settings

- **Type:** bug
- **Depends on:** 24
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/59
- **Design:** stage 22/24 (`net/PushInbox.kt`, `net/SyncCursorStore.kt`, `net/BlobDownloader.kt`), `ui/LibraryViewModel.kt`, ADR-0012

## Objectives

Two findings from 2026-09-21 on v0.0.17. (1) Stage 24's backfill only runs when no
cursor is persisted, so upgrading from v0.0.16 never backfilled the five older pushes.
(2) Two fresh pushes were returned to the tablet on every 60 s poll (server logs: seven
polls with the identical cursor) and never downloaded — `materialise` throws before
its first request and `LibraryViewModel.pollPushInboxSuspending` swallows the
exception, so the cursor never advances and nothing is shown. Checked and ruled out: the downloader's `repoProvider` and the poll's provider are
the same `LoopServices.repositoryFrom(tokenStore)`, the result payloads are identical
in shape to the 11:41 push that worked on v0.0.16, the Room inserts use REPLACE, and
the server returns the two jobs for the tablet's cursor. The cause is therefore
unknown without device logs: the first task is to make the failure visible (below),
reproduce on the emulator with the 13:01 payloads (`server/tests/fixtures` shape,
relative `url`), and state the root cause in the PR.

## What to build

- **Reproduce first:** an instrumented test that feeds the exact 2026-09-21 13:01
  `to_user` results (single page and three pages, relative `url`) through the
  Library-route wiring against MockWebServer and asserts the blob GET happens; fix
  whatever it exposes.
- **Never swallow:** `pollPushInboxSuspending` logs (`Log.w("PushInbox", …)`) with the
  job id and exception class/message and records `lastPollError` (message +
  time) and `lastPollAt`/`lastMaterialised` in a small `InboxStatus` state; Settings
  shows them ("Inbox: last poll 13:04, 0 new, error: not paired: cannot download blob").
- **Poison-page guard:** if the same job fails to materialise on 3 consecutive polls,
  skip it (record it in `InboxStatus.skipped` with the reason) and advance the cursor
  past the page, so one bad job cannot block every later push; a manual Resync retries
  skipped jobs.
- **Backfill on upgrade:** persist an `inbox_schema_version` next to the cursor;
  when the stored version is older than the current constant, run the `sync(null)`
  backfill once (dedupe protects against duplicates) and store the version.
- **Settings → "Resync inbox":** clears the cursor and skipped set, runs the backfill,
  shows the result.

## Interface contracts

- **Exposes:** `InboxStatus`, Resync. **Consumes:** `device-api` `/sync`, `GET
  /canvases/{id}`, `GET /blobs/{key}`.

## Testing requirements

- JVM: upgrade path (old version stored) → one backfill; throwing materialise →
  error recorded, cursor unchanged; third consecutive failure → job skipped and cursor
  advanced; Resync retries the skipped job.
- Instrumented (MockWebServer): Library route with no canvas ever opened → a pushed
  job downloads its blob (regression for the null-provider lead).
- On device: the five 2026-09-18 pushes and the two 2026-09-21 re-pushes appear after
  upgrading to the fixed APK (fill the pending rows in `smoke/push-inbox.md`).

## Acceptance conditions

- [ ] Regression tests in place (upgrade backfill; no swallowed errors; poison-page guard; reproduction of the 2026-09-21 payloads)
- [ ] Root cause of the 2026-09-21 silent failure stated in the PR
- [ ] Existing suite stays green; CI all-green (instrumented lane run on the branch and linked)

## Pipeline test: NO
