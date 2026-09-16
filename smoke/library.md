# UI-smoke: Library — many canvases, folders, titles; open, create, move, delete (Stage 11)

Manual "observably-works" check for the **Handoff Tester** (and the Operator), run on the
real tablet (Lenovo Idea Tab Pro, Android 14) with the active stylus. This is the Stage-11
acceptance: *the app opens on a **Library** of folders and canvases for the space; you can
create / rename / move / delete (to a Trash that empties after 30 days) / open them; the
canvas screen has a title and a back button; and the note pencil now has a visible
**"Note"** label.* Browser smoke does not apply to a native client (ADR-0001), so this
human pass is the replacement. Run it on every release APK that changes the Library, the
canvas top bar, the Room schema, or the thumbnail path.

## Kill-switch — which build to test

The Library is gated by `BuildConfig.LIBRARY`, **ON in both debug and release** by
documented exception (Stage-11 spec: prod is not promoted, staging is the only
environment). With `LIBRARY` **OFF** the app keeps today's behaviour: it opens the first
canvas directly (`MainActivity` → `CanvasScreen`), with no Library, breadcrumb, Trash, or
back button — that path is covered by `smoke/android-ink.md`, not here. Flip `LIBRARY` to
`false` in `app/build.gradle.kts` (both build types) to fall back without a code change.

The **"Note"** label on the pencil is present whenever the note sheet is (one-tap ask or
the card picker), independent of `LIBRARY`.

## Preconditions

- The first release APK that includes Stage 11 (or later) is installed. `INK_ENABLED` and
  `LIBRARY` are ON, so the app opens on the **Library** (not the canvas).
- Ink autosaves on pen-up (Stage 2+); pairing is set in **Settings** if you also want to
  exercise Send, but the Library scenarios do not require the server.
- Start clean: if a previous run left folders/canvases, that is fine — work in a new one.

## Scenario 1 — folders, move, rename, create, thumbnails

1. **Open on the Library.** Launch the app.
   - *Expected:* a breadcrumb bar reading **Space**, a grid of any existing canvas/folder
     tiles, a **+** FAB, and a **Trash** entry at the root.
   - *Expected screenshot A:* the Library grid at the space root.

2. **Draw a canvas to move.** Tap **+** → **New canvas**; the canvas opens with a **title**
   ("Untitled N") and a **< Library** back button at the top. Sketch a small topology
   diagram (two or three boxes with an arrow). Tap **< Library**.
   - *Expected:* back on the Library; the new canvas tile shows a **thumbnail** of the
     sketch, its title, and a relative date.

3. **Make a folder.** Tap **+** → **New folder**, name it **"Network"**, OK.
   - *Expected:* a **📁 Network** tile appears.

4. **Move the topology canvas into it.** Long-press the topology canvas tile → **Move…** →
   pick **Network**.
   - *Expected:* the canvas leaves the root; open **Network** (tap it) and it is inside.
   - *Expected screenshot B:* the canvas tile inside the Network folder (breadcrumb
     **Space › Network**).

5. **Rename it.** Open the canvas, tap its **title**, type a new name, **Save**; tap
   **< Library**.
   - *Expected:* the tile shows the new title. (You can also long-press → **Rename**.)

6. **Create a second canvas.** Breadcrumb back to **Space**, tap **+** → **New canvas**.
   - *Expected:* it opens immediately with a fresh "Untitled N" title, unique (no
     collision with the first).

## Scenario 2 — delete to Trash and restore

1. On a canvas tile, long-press → **Delete**.
   - *Expected:* the tile disappears from the grid.
2. At the root, tap **Trash**.
   - *Expected:* the deleted canvas is listed with **Restore** and **Delete forever**.
   - *Expected screenshot C:* the Trash list.
3. Tap **Restore**, then **Back**.
   - *Expected:* the canvas is back in its folder/root, ink intact.

## Scenario 3 — persistence across a kill + reopen

1. With the structure from Scenarios 1–2 (a **Network** folder, the topology canvas inside
   it, a second canvas at the root), **kill the app** (swipe from recents) and **reopen**.
2. *Expected:* the app reopens on the Library; the **Network** folder and both canvases are
   exactly where you left them; opening the topology canvas shows the **same ink** (the
   Room v2→v3 migration is additive — no ink loss); thumbnails are present on the tiles.
   - *Expected screenshot D:* the reopened Library with structure and thumbnails intact.

## Scenario 4 — the "Note" label

1. Open any canvas.
   - *Expected:* next to **Send** there is a pencil icon **with a visible "Note" label**
     (the owner could not find the bare pencil, 2026-09-16). Tapping it opens the note
     sheet.

## Pass / fail criteria

- [ ] The app opens on the **Library** (breadcrumb + grid + FAB + Trash).
- [ ] New canvas / New folder create; a new canvas opens immediately with a unique
      "Untitled N" title.
- [ ] Move… relocates a canvas into a folder; the breadcrumb reflects the location.
- [ ] Rename (title tap or long-press) sticks.
- [ ] Delete moves to **Trash**; **Restore** brings it back with ink intact; **Delete
      forever** removes it.
- [ ] Canvas screen has a **title** and a **< Library** back button that returns to the
      folder the canvas was in.
- [ ] After kill + reopen, structure and ink are intact and **thumbnails** are present.
- [ ] The pencil shows a visible **"Note"** label.

## Record the run

- Tag / build under test: __________
- Scenario 1 (folder/move/rename/create/thumbnails) (PASS/FAIL): __________
- Scenario 2 (delete → Trash → restore) (PASS/FAIL): __________
- Scenario 3 (kill+reopen; ink + structure + thumbnails intact) (PASS/FAIL): __________
- Scenario 4 ("Note" label visible) (PASS/FAIL): __________
- Notes / anomalies: __________

## Failure signals

- **App opens on the canvas, no Library:** `LIBRARY` is OFF in this build — you are testing
  the direct-to-canvas flow; use `smoke/android-ink.md` and note the build.
- **Ink lost after reopen / a canvas is gone:** the Room v2→v3 migration did not run, or a
  destructive fallback wiped data (forbidden by contract `ink-storage`); capture logs. Ink
  loss here is a hard fail.
- **No thumbnails:** the thumbnail cache under `filesDir/thumbs/<canvas_id>.png` was not
  written on close, or the canvas has no ink yet; note whether the canvas had strokes.
- **Trash never empties:** the 30-day purge runs on app start; entries younger than 30 days
  are expected to remain.
- **No "Note" label:** the note-sheet affordance is gated (needs one-tap ask or the card
  picker); confirm `ONE_TAP_ASK`/`CARD_ACTIONS` are ON.

## Results log

| Date (UTC) | Release | Operator | S1 | S2 | S3 | S4 | Notes |
|---|---|---|---|---|---|---|---|
| 2026-09-16 23:10 | v0.0.12 | seanerama | pass | pass | pass | pass | first on-device run; owner reports library worked end to end |
