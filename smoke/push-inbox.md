# UI-smoke: Push inbox on the device — badge, raster page, annotate, send back (Stage 22)

Manual "observably-works" check for the **Handoff Tester** (with the **Operator** driving
the host push), run on the real tablet (Lenovo Idea Tab Pro, Android 14) with the active
stylus. This is the **Phase 4 acceptance from the tablet**: *a document pushed from the host
appears on the right space's tab with a badge; it opens as a page you can write on (the
PDF/image renders beneath your ink); and **Ask** sends the document and your marks back
together, so the answer references the document's content*. A multi-page PDF lands as a
folder of pages (ADR-0012, SPEC §4.3/§4.5, §8, §9.3, §12 Phase 4; contract `device-api`
`/sync`, `GET /canvases/{id}`, `GET /blobs/{key}`). Browser smoke does not apply to a native
client (ADR-0001), so this human pass is the replacement. Run it on every release APK that
changes the push inbox, sync discovery, raster rendering, or the export path.

## Kill-switch — which build to test

The push inbox is gated by `BuildConfig.PUSH_INBOX`, **ON in both debug and release** by
documented exception (Stage-22 spec: prod is not promoted, staging is the only environment,
and the server push routes are additionally gated by server-side `PUSH_ENABLED`). With
`PUSH_INBOX` **OFF** the device does no `to_user` cursor polling, shows no badges, and the
raster rendering/export code stays inert (the app behaves exactly as before Stage 22). Flip
`PUSH_INBOX` to `false` in `app/build.gradle.kts` (both build types) to fall back without a
code change. It is independent of `LIBRARY`/`SPACES`/`FORMALIZE`.

## Preconditions

- The first release APK that includes Stage 22 (or later) is installed. `INK_ENABLED`,
  `LIBRARY`, `SPACES`, and `PUSH_INBOX` are ON, so the app opens on the **Library** under the
  space tabs and polls for pushed documents.
- **Pairing is set in Settings** (base URL + token) to the staging server, and the tabs
  populate (the device is online).
- **Server push is enabled:** staging has **`PUSH_ENABLED=true`** and the stack was re-upped
  (see `smoke/push-server.md`). With it OFF the host `inkwell push` exits 2 and nothing is
  delivered — the device correctly shows no badge (that is the default-safe state, not this
  test).
- The server has the Phase-3 spaces (**Work, Home, Learning, Business**); this run pushes to
  **Learning**. Confirm the space's per-space prompt is deployed so the answer in step 6 can
  reference the document.
- Two small PDFs on the host: a legible **1-page** `brief.pdf` and a **3-page**
  `report.pdf`. (`smoke/push-server.md`'s host CLI is the push mechanism.)

Throughout, `dc` is `docker compose -f deploy/compose.yml` in the environment directory on
the host (e.g. `/srv/inkwell/staging`), and the workstation reaches the host over Tailscale
SSH (`ssh mini-hp01`).

## Scenario 1 — a pushed 1-page document badges the tab and opens as a page

1. **Baseline.** On the tablet, open the app on the Library and select the **Learning** tab.
   Note there is **no badge** on the tab yet.
   - *Expected screenshot A:* the tab bar, **Learning** selected, no badge.
2. **Push from the host.** From the workstation:
   ```
   ssh mini-hp01 'cd /srv/inkwell/staging && cat brief.pdf | \
     dc run --rm -T api inkwell push document --space learning --file - --title "Brief" \
       --note "Please review the summary"'
   ```
   - *Expected:* the command prints one `job:` line and one `canvas:` line.
3. **The badge appears within 60 s.** Leave the app in the foreground (the foreground poll
   is 60 s; a **pull-to-refresh** on the Library forces it immediately).
   - *Expected:* a **badge** (count **1**) appears on the **Learning** tab.
   - *Expected screenshot B:* the **Learning** tab showing the "1" badge.
4. **Open the pushed canvas.** Switch to **Learning**; the new canvas tile shows a **"New"
   dot**. Open it.
   - *Expected:* the tile's title is **"Brief"**; opening it renders the **PDF page** as the
     background (legible), and the **New** dot and the **tab badge clear** (seen).
   - *Expected screenshot C:* the opened canvas with the PDF page visible beneath a blank
     ink layer; the Learning tab now has no badge.
5. **Annotate on top.** With the stylus, **write a question on the page**, e.g. circle a line
   and write **"what does this mean?"**. Confirm your ink lands **on top of** the document
   (the page does not cover your strokes). Open the **layer tray**: it lists a **"Document"**
   layer (toggle it off/on to confirm it hides/shows the page) above **Ink**.
   - *Expected screenshot D:* your handwriting over the document; the tray showing
     **Document** + **Ink**.
6. **Ask (send back).** Tap **Send** (Ask).
   - *Expected:* the loop runs and an **answer card + marks** arrive in the **Learning
     accent** colour. The answer **references the document's content** (not a generic reply),
     proving the export composited the **document beneath your marks** and the agent saw
     both.
   - *Expected screenshot E:* the answer card referencing the document.

## Scenario 2 — a 3-page document lands as a folder of three pages

1. **Push a 3-page PDF:**
   ```
   ssh mini-hp01 'cd /srv/inkwell/staging && cat report.pdf | \
     dc run --rm -T api inkwell push document --space learning --file - --title "Report"'
   ```
2. **Refresh and look on Learning.** Pull-to-refresh; the badge increments (by **3**).
   - *Expected:* a **folder named "Report"** appears in the Learning Library; opening it
     shows **three** canvases titled **"Report — p1/2/3"**, each with a **New** dot.
   - *Expected screenshot F:* the "Report" folder open with three pages.
3. **Open page 2.** It renders the **second** PDF page (not page 1), beneath ink; its dot
   clears. Optionally annotate and **Ask** — the answer references that page.

## Scenario 3 — re-open survives a restart; no duplicate on re-sync

1. Force-stop and relaunch the app. The pushed canvases are still present (Room-persisted),
   the page still renders, and the badges reflect only **unopened** ones.
2. Pull-to-refresh again.
   - *Expected:* **no duplicate** canvases or folders appear (dedupe by canvas id; the cursor
     was advanced after the first materialise).

## Scenario 4 — first-run backfill: pushes that predate the app's first sync (Stage 24)

Regression for the Stage-24 fix: before this fix a first run (fresh install, reinstall,
upgrade, or a new pairing) only *seeded* the sync cursor and returned without materialising,
so any `to_user` push that arrived **before** the app's first successful sync was silently
lost. Now the first run **backfills** from `sync(null)` (the most recent 100 jobs) and
materialises every pushed canvas not already on the device; old pushes whose signed raster
links have expired still land (the download re-fetches a fresh `url` from `GET /canvases/{id}`
on a 403).

**Worked example — the owner's five pending pushes from 2026-09-18.** On 2026-09-21 the owner
installed v0.0.16 three days after five documents had been pushed to a space and saw nothing.
That is the bug this scenario proves is fixed.

1. **Set up the missed pushes.** From the host, push several documents to a space *while the
   target device is NOT paired / not yet installed* (or before its first sync), e.g.:
   ```
   ssh mini-hp01 'cd /srv/inkwell/staging && for f in doc1.pdf doc2.pdf doc3.pdf doc4.pdf doc5.pdf; do \
     cat $f | dc run --rm -T api inkwell push document --space learning --file - --title "$f"; done'
   ```
   - *Expected:* five `job:`/`canvas:` lines. These are now "old" pushes (before any sync).
2. **Install the fixed APK and pair** (fresh install / upgrade). Open the app on the Library.
   Wait for the first poll (60 s foreground, or pull-to-refresh to force it).
   - *Expected:* the **Learning** tab shows a badge counting **all five** backfilled canvases;
     opening the space lists the five documents, each with a **New** dot. No push is missed.
3. **Expired links still land.** If more than ~24 h elapsed since the push (the owner's case:
   three days), the original signed raster links are expired; opening each canvas still renders
   the PDF page (the blob was re-fetched via `GET /canvases/{id}` on a 403). Confirm every page
   renders — none is blank.
4. **No duplicates on re-sync.** Pull-to-refresh again: no duplicate canvases (dedupe by canvas
   id; the cursor was advanced only after the page materialised).

## Handoff result (fill in)

| Check | Result |
| --- | --- |
| Kill-switch: which build (debug/release), `PUSH_INBOX` ON | release v0.0.16, ON (2026-09-21) |
| 1-page push → **Learning** tab shows a badge within 60 s (or on pull-to-refresh) | pass — tablet synced and fetched the blob 10 s after the CLI push (server log) |
| Tile shows a **New** dot; opening renders the **PDF page** legibly beneath ink | pass (owner: page legible) |
| Opening clears the **New** dot and the **tab badge** | pass (owner) |
| Layer tray shows a **Document** layer whose toggle hides/shows the page | not reported (owner did not exercise) |
| Writing lands **on top** of the document; **Ask** answer references the document | pass — owner annotated the plan and the Ask answer referenced its content (Phase 4 acceptance met) |
| 3-page push → **folder** "Report" with three **"Report — pN"** pages | pass (v0.0.17, 2026-09-21: folder "Three pages" with p1–p3 appeared after an app restart; pages blank because the fixture PDF has no content) |
| Page 2 renders the **second** PDF page | inconclusive — fixture pages are empty; retest with a real multi-page PDF (stage 29) |
| Restart: canvases persist; re-refresh makes **no duplicates** | not reported |
| **Stage 24 backfill:** the five pushes from 2026-09-18 (predating the tablet's first sync) all appear after upgrading to the fixed APK, with expired links re-fetched and no duplicates | pending — verify on the stage-29 build (worked example: owner's five 2026-09-18 pushes) |
| **Stage 29 upgrade backfill:** upgrading from v0.0.17 (cursor already persisted) backfills the five 2026-09-18 pushes on the first poll without a reinstall, exactly once (no duplicates on the next poll) | pending — verify on the stage-29 APK: install over v0.0.17, open Library, wait one poll → the five appear; pull-to-refresh again → still five |
| **Stage 29 re-pushes:** the two 2026-09-21 13:01 re-pushes (a 1-page and a 3-page, relative signed `url`) appear and their blobs download (regression for the silent failure) | pending — verify on the stage-29 APK: both land, pages render, folder for the 3-page |
| **Stage 29 visible errors:** Settings ("Inbox: last poll HH:MM, N new, error: …") shows the poll result; a paused/unpaired or failing poll shows an error instead of nothing | pending — Settings from the Library gear shows the Inbox line |
| **Stage 29 Resync:** "Resync inbox" in Settings clears the cursor + skipped set and re-runs the backfill; a poisoned job (skipped after 3 failures) is retried | pending — tap Resync after a backfill; skipped count drops |
| Screenshots A–F attached | none |

## Notes

- The device renders the PDF page itself with `PdfRenderer` (ADR-0012); the server never
  rasterises. Images (PNG/JPEG) are decoded directly and also render beneath ink.
- The blob is downloaded once via the raster's signed `url` and cached on disk; a stale
  (403) link is re-fetched from `GET /canvases/{id}` automatically.
- Poll cadence (SPEC §8): 60 s foreground, 5 s while a send is outstanding, 5 min
  backgrounded (the durable `SyncWorker`). Pull-to-refresh forces a poll.
- Pushed canvases are ordinary canvases: rename, move, trash, and Formalize all work.
- **Stage 29:** the inbox poll no longer swallows errors — the last poll (time, count, error,
  skipped count) is shown in Settings, reached via the Library gear (stage 28). An upgrade
  (cursor already stored) now runs the `sync(null)` backfill once, keyed on a persisted
  `inbox_schema_version`. A job that fails to materialise on 3 consecutive polls is skipped so
  it cannot wedge the cursor; **Resync inbox** clears the cursor + skips and re-backfills. The
  server ships a RELATIVE signed raster `url` (`/v1/blobs/…`); the device resolves it against
  the paired base URL before the download (this was the reproduction target — the URL was NOT
  the root cause; the swallow was).
