# UI-smoke: cards with actions, anchors and Markdown; job-type picker (Stage 10)

Manual "observably-works" check for the **Handoff Tester** (and the Operator), run on
the real tablet (Lenovo Idea Tab Pro, Android 14) with the active stylus, against the
**staging** server with the agent enabled (`AGENT_ENABLED=true`). This is the Stage-10
acceptance: *cards are real objects — you can confirm/dismiss them and the state sticks
(and survives a kill + reopen), tapping a card pulses the canvas region it anchors to,
card bodies render as Markdown, and the note sheet is an Ask / Mark-up job-type picker.*
Browser smoke does not apply to a native client (ADR-0001), so this human pass is the
replacement. Run it on every release APK that changes the panel, the card routes, the
renderer, or the note sheet.

## Kill-switch — which build to test

Card actions are gated by `BuildConfig.CARD_ACTIONS`, **ON in both debug and release** by
documented exception (Stage-10 spec: prod is not promoted, staging is the only
environment, and the server-side `AGENT_ENABLED` already dark-launches all agent work).
With `CARD_ACTIONS` **OFF** the app is the Stage-7 read-only cards: no action buttons, no
state changes, no job-type picker, bodies shown as plain text — that path is covered by
`smoke/ask.md`, not here. Send is still gated by `BuildConfig.SEND_ENABLED` (ON since
v0.0.5). Flip `CARD_ACTIONS` to `false` in `app/build.gradle.kts` (both build types) to
fall back without a code change.

## Preconditions

- The first release APK that includes Stage 10 (or later) is installed. The ink
  kill-switch is ON (`BuildConfig.INK_ENABLED`), so the app opens on the **canvas**.
- The tablet is on the tailnet and paired: in **Settings**, the staging server URL and a
  minted device token are set, and **Check** shows `Server <version> (device-api/v1)`.
- Staging has `AGENT_ENABLED=true` and a real `ANTHROPIC_API_KEY`, the deployed server
  accepts `canvas.ask` / `canvas.annotate`, exposes the Stage-10 card routes
  (`PATCH /v1/cards/{id}`, `POST /v1/cards/{id}/actions/{id}`) and the `cards` field on
  jobs, and the seeded `work` space exists.
- The tablet is online (**Send** reads "Send" or "Mark up", not "Offline"). Next to Send
  there is a pencil icon button, **"Add a note…"**, which opens the note sheet with the
  **Ask / Mark up** segmented control.
- Start each scenario on a clean canvas: **Undo** (or erase) leftovers and **Close** any
  open side panel.

## Scenario 1 — mark up a diagram, then act on the cards

1. **Draw a small diagram.** With **Pen**, sketch two or three labelled boxes with an
   arrow between two of them (as in `smoke/diagram.md`).
   - *Expected screenshot A:* the hand-drawn diagram on a white canvas.

2. **Pick "Mark up" and send.** Tap **"Add a note…"**, choose **Mark up** in the picker
   (the primary button now reads **"Mark up"**), leave the note blank, tap **Mark up**.
   - *Expected:* no error; the **"Working…"** spinner shows top-right; the UI stays
     responsive (you can keep drawing).
   - *Expected:* the picker remembered your last choice next time you open the sheet.

3. **Result lands.** Within a few `/sync` polls the job reaches `done`: agent annotations
   appear on the canvas and the **side panel** opens with the **summary** and one or more
   **cards**. Each card shows a **state pill** (open) and, where the agent offered them,
   **Confirm / Reject** buttons. Any `Save` / `Run` / `Open` action shows **disabled**
   with "— coming later".
   - *Expected:* a card body with `**bold**` or a `- bullet` renders as **bold** / a real
     bullet, **not** literal `*` characters.
   - *Expected screenshot B:* the panel with a card, its state pill, and action buttons.

4. **Confirm one card.** Tap **Confirm** (or the confirm-style action) on one card.
   - *Expected:* the pill flips to **done** immediately (optimistic) and stays done after
     the next `/sync` (server reconciled). 

5. **Dismiss another card.** On a second card, tap **Reject** (or dismiss).
   - *Expected:* its pill flips to **dismissed** and stays.
   - *Expected screenshot C:* one card `done`, one `dismissed`.

6. **Anchors — card → canvas.** Tap a card that has an anchor (e.g. the one about the
   arrow).
   - *Expected:* the anchored region on the canvas **pulses** a translucent accent
     highlight for ~1.5 s (and the canvas scrolls to it if off-screen).
   - *Expected screenshot D:* the pulse over the arrow/box while the card is tapped.

7. **Anchors — canvas → card.** Tap an agent mark on the canvas (a light finger tap).
   - *Expected:* the panel **expands and scrolls to** that mark's card.

## Scenario 2 — persistence across a kill + reopen

1. With the panel from Scenario 1 still showing one `done` and one `dismissed` card,
   **kill the app** (swipe it away from recents) and **reopen** it.
2. Reopen the same canvas.
   - *Expected:* the card states persist — the confirmed card still shows **done** and
     the dismissed one still **dismissed** (per-card state is stored locally in Room v2,
     so this holds **even offline**). No ink was lost (the v1→v2 migration is additive).
   - *Expected screenshot E:* the reopened canvas with the same card states.

## Scenario 3 — the Ask side of the picker

1. On a clean canvas, handwrite `what is 1+9=?`.
2. Tap **"Add a note…"**, choose **Ask** (button reads **"Send"**), tap **Send**.
   - *Expected:* the `canvas.ask` answer ("10") lands beside the question and an
     **answer** card (expanded, Markdown body) appears — as in `smoke/ask.md`.
   - *Expected:* **Formalize / Extract / Action** appear greyed ("Phase 3+") in the picker
     and cannot be selected.

## Pass / fail criteria

- [ ] The note sheet shows an **Ask / Mark up** picker; the primary button label follows
      the choice; the choice is remembered next time.
- [ ] Confirm sets a card **done** and Reject sets it **dismissed**, both optimistic and
      then stable after `/sync`.
- [ ] Unsupported actions (`run_tool` / `open_canvas` / `save_to_brain`) render disabled
      with "coming later" and never change state.
- [ ] Card bodies render Markdown (`**bold**`, `*italic*`, `` `code` ``, bullets), not raw
      characters.
- [ ] Tapping a card pulses its anchored canvas region (screenshot D); tapping a mark
      scrolls the panel to its card.
- [ ] After kill + reopen, the `done` / `dismissed` states persist and no ink was lost
      (screenshot E).

## Record the run

- Tag / build under test: __________
- Scenario 1 job id: __________ — confirm→done, reject→dismissed (PASS/FAIL): __________
- Anchor pulse observed (card→canvas) (PASS/FAIL): __________
- Panel scroll observed (canvas→card) (PASS/FAIL): __________
- Scenario 2 persistence after kill+reopen (PASS/FAIL): __________
- Scenario 3 Ask answer + greyed Phase-3+ types (PASS/FAIL): __________
- Notes / anomalies: __________

## Failure signals

- **No action buttons / no picker / plain-text bodies:** `CARD_ACTIONS` is OFF in this
  build — you are testing the Stage-7 read-only cards; use `smoke/ask.md` and note the
  build.
- **State reverts after `/sync`:** the optimistic update was not reconciled — the server
  rejected the transition (409) or the action route failed; capture the job/card id and
  the server logs.
- **HTTP 404 on a card action:** the card id or action id is stale (re-run the job) or the
  server predates the card routes; redeploy staging.
- **HTTP 422 `not_implemented`:** the action kind is a later phase (`run_tool` /
  `open_canvas` / `save_to_brain`) — it should have been disabled in the UI; capture it.
- **State lost after reopen:** the Room v1→v2 migration did not run (or a destructive
  fallback wiped data — which is forbidden); capture logs. Ink loss here is a hard fail.
- **"Offline":** the tablet lost the tailnet; Send is disabled by design (SPEC §9.5).

## Results log

| Date (UTC) | Release | Operator | Scenario 1 (confirm/dismiss) | Persistence | Notes |
|---|---|---|---|---|---|
| | | | | | |
