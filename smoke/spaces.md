# UI-smoke: Spaces — server-mirrored tabs, per-space send + accent, move between spaces (Stage 14)

Manual "observably-works" check for the **Handoff Tester** (and the Operator), run on the
real tablet (Lenovo Idea Tab Pro, Android 14) with the active stylus. This is the **Phase 3
acceptance**: *the Library sits under a horizontally scrollable tab bar of the server's
spaces; each tab has its own folder tree + Trash; new canvases land in the active space; a
canvas or folder can be moved to another space; **every job is posted with the canvas's own
`space_id`**; agent marks are drawn in the space's accent colour; and the same note sent
from **Work** vs **Learning** gives recognisably different responses* (ADR-0010, SPEC §2,
§4.1, §6.3, §9.3, §12 Phase 3). Browser smoke does not apply to a native client (ADR-0001),
so this human pass is the replacement. Run it on every release APK that changes spaces, the
tab bar, the send path, or the accent.

## Kill-switch — which build to test

Spaces are gated by `BuildConfig.SPACES`, **ON in both debug and release** by documented
exception (Stage-14 spec: prod is not promoted, staging is the only environment). With
`SPACES` **OFF** the app keeps the single seeded space: no tab bar, and Send resolves the
space by the `work` slug (the Stage-11 Library behaviour, covered by `smoke/library.md`).
Flip `SPACES` to `false` in `app/build.gradle.kts` (both build types) to fall back without a
code change. `SPACES` is independent of `LIBRARY` (the tab bar renders above the Library
grid), `ONE_TAP_ASK`, `CARD_ACTIONS`, and `FORMALIZE`.

## Preconditions

- The first release APK that includes Stage 14 (or later) is installed. `INK_ENABLED`,
  `LIBRARY`, and `SPACES` are ON, so the app opens on the **Library** under the space tabs.
- **Pairing is set in Settings** (base URL + token). Spaces mirror the server, so a valid
  pairing is required for the tabs to populate and for Send.
- **Stage 13's distinct per-space prompts are deployed on staging** — Work is terse /
  action-oriented, Learning is explanatory with a check question. Without them the two
  answers will not differ and step 4 cannot pass. Confirm with the Operator before the run.
- The server has the four Phase-3 spaces (**Work, Home, Learning, Business**) via
  `GET /spaces`. If it does not, the run is blocked (record it).
- Start from a device whose existing canvases were created before this build (they carry the
  local placeholder space, slug `work`) — the reconciliation moves them under **Work**.

## Scenario 1 — the tab bar mirrors the server; existing ink lands under Work

1. **Open on the Library.** Launch the app (paired, online).
   - *Expected:* a horizontally scrollable **tab bar** above the breadcrumb showing
     **Work · Home · Learning · Business** (in `position` order), the selected tab
     underlined in its space colour. The refresh control is at the right of the bar.
   - *Expected screenshot A:* the tab bar with the four spaces, **Work** selected.
2. **Existing canvases are under Work.** With **Work** selected, the canvases you had before
   this build are listed, each with its **ink** intact (open one to confirm strokes survived
   the reconciliation — no ink loss; the Room schema did not change).
   - *Expected screenshot B:* a previously-drawn canvas open, ink intact, under **Work**.
3. **Offline hint (optional).** Turn off Wi-Fi and pull the **refresh** control. If the
   device has never synced spaces you see a subtle **"spaces not synced"** hint; if it has,
   the cached tabs stay and there is no hint. Turn Wi-Fi back on and refresh — the hint
   clears. (A never-synced offline device blocks Send with *"Spaces not synced yet…"*.)

## Scenario 2 — the same note, Work vs Learning (the Phase-3 acceptance)

1. On the **Work** tab, tap **+** → **New canvas**; it opens under Work. Write a short note:
   **"why does the sky look blue?"** Tap **Send** (Ask).
   - *Expected:* the answer card + marks arrive; the marks are in the **Work accent**
     colour. Read the answer — it should be **terse / action-oriented** (Work's prompt).
   - *Expected screenshot C:* the Work answer (note the accent + terse voice).
2. Tap **< Library**. Long-press the canvas tile → **Move…** → under **Other spaces** pick
   **Learning**.
   - *Expected:* the canvas leaves **Work** and appears under the **Learning** tab at its
     root (open **Learning** to confirm; the ink is intact).
3. On the **Learning** tab, open that canvas and tap **Send** (Ask) again on the same note.
   - *Expected:* a new answer; the marks are now in the **Learning accent** colour and the
     answer is **explanatory with a check question** (Learning's prompt).
   - *Expected screenshot D:* the Learning answer (note the different accent + fuller voice).
4. **Compare.** The two answers must be **recognisably different in voice and shape** —
   Work terse/action, Learning explanatory with a check question — and the **Learning marks
   are in the Learning accent**. This is the pass condition; paste both summaries below.

## Scenario 3 — per-space isolation, move a folder, persistence

1. On **Home**, create a folder **"Bills"** and a canvas inside it. Switch to **Business**.
   - *Expected:* the breadcrumb resets to **Space** (root) and **Business** shows its own
     (empty or its own) contents — **Home's** folder is not visible here (per-space trees).
2. Long-press a folder → **Move…** → **Other spaces** → **Business**.
   - *Expected:* the whole folder subtree (its canvases, including any trashed ones) moves to
     the **Business** root; open Business to confirm; the source space no longer lists it.
3. **Kill + reopen.** Swipe the app from recents and relaunch.
   - *Expected:* the app reopens on the **last active tab** (persisted); the tabs, per-space
     contents, and all ink are exactly where you left them.

## Pass / fail criteria

- [ ] The tab bar shows the server's spaces (**Work, Home, Learning, Business**) in order,
      each selectable, the active one accented; existing canvases are under **Work** with ink.
- [ ] New canvases land in the **active** space; switching tabs re-scopes content/+/Trash and
      resets the breadcrumb to that space's root.
- [ ] **Move…** offers **Other spaces**; moving a canvas puts it at the target space root
      (folder cleared); moving a folder carries its whole subtree; ink is intact throughout.
- [ ] Send posts the **canvas's own** `space_id`; agent marks use the **space's accent**.
- [ ] The same note from **Work** vs **Learning** gives **recognisably different** answers
      (Work terse/action; Learning explanatory with a check question).
- [ ] After kill + reopen, the active tab, per-space structure, and ink all persist.

## Record the run

- Tag / build under test: __________
- Scenario 1 (tabs mirror server; ink under Work; offline hint) (PASS/FAIL): __________
- Scenario 2 (Work vs Learning same note; accents; different voices) (PASS/FAIL): __________
- Scenario 3 (isolation; move folder; persistence) (PASS/FAIL): __________
- **Work answer summary (paste):** __________
- **Learning answer summary (paste):** __________
- Notes / anomalies: __________

## Failure signals

- **No tab bar / one tab only:** `SPACES` is OFF in this build (single seeded space — use
  `smoke/library.md` and note the build), or the device is unpaired / `GET /spaces` failed
  (check pairing + the "spaces not synced" hint).
- **Send blocked with "Spaces not synced yet — check the connection and try again.":** the
  canvas's space is still the local placeholder (no server row). Refresh (pull the tab-bar
  refresh) while online, then retry; if it persists, `GET /spaces` is failing.
- **Ink lost after the update / a canvas is gone:** the reconciliation stranded ink — a hard
  fail (it runs in one transaction and must be additive; the Room schema did not change).
  Capture logs.
- **Both answers read the same:** Stage 13's distinct per-space prompts are not deployed, or
  the job was posted with the wrong `space_id`; confirm with the Operator and capture the
  posted `space_id`.
- **Marks in the wrong colour:** the accent is derived from the canvas's space `color`;
  confirm the space's `color` on the server and that the canvas is under the expected space.

## Results log

| Date (UTC) | Release | Operator | S1 | S2 | S3 | Work vs Learning differ? | Notes |
|---|---|---|---|---|---|---|---|
| 2026-09-17 02:20 | v0.0.13 | seanerama | pass | pass | pass | yes | first on-device run; owner: all steps worked |
