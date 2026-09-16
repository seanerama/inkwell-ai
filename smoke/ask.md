# UI-smoke: one-tap send — canvas.ask answers the note (Stage 7)

Manual "observably-works" check for the **Handoff Tester** (and the Operator), run on
the real tablet (Lenovo Idea Tab Pro, Android 14) with the active stylus, against the
**staging** server with the agent enabled (`AGENT_ENABLED=true`). This is the Stage-7
owner acceptance: *write "what is 1+9=?" on a canvas, tap Send with no instruction, and
the answer "10" appears both next to the question on the canvas and as an `answer` card
in the panel.* Browser smoke does not apply to a native client (ADR-0001), so this human
pass is the replacement. Run it on every release APK that changes the send/poll/render
loop, the renderer, or the panel.

## Kill-switch — which build to test

One-tap ask is gated by `BuildConfig.ONE_TAP_ASK`, **ON in both debug and release** by
documented exception (stage-7 spec: prod is not promoted, staging is the only
environment, and the server-side `AGENT_ENABLED` already dark-launches all agent work).
The Send button itself is still gated by `BuildConfig.SEND_ENABLED` (ON since v0.0.5).
With `ONE_TAP_ASK` OFF, Send opens the Stage-6 instruction sheet first and posts
`canvas.annotate` — that path is covered by `smoke/loop.md`, not here.

## Preconditions

- The first release APK that includes Stage 7 (or later) is installed. The ink
  kill-switch is ON (`BuildConfig.INK_ENABLED`), so the app opens on the **canvas**.
- The tablet is on the tailnet and paired: in **Settings**, the staging server URL and a
  minted device token are set, and **Check** shows `Server <version> (device-api/v1)`.
- Staging has `AGENT_ENABLED=true` and a real `ANTHROPIC_API_KEY`, the deployed server
  accepts `canvas.ask` (`POST /v1/jobs` no longer returns `422 not_implemented`), and
  the seeded `work` space exists (`GET /v1/spaces` lists a space with slug `work`).
- The tablet is online (the **Send** button reads "Send", not "Offline"). Next to Send
  there is a pencil icon button, **"Add a note…"** — do **not** use it in Scenario 1.
- Start each scenario on a clean canvas: **Undo** (or erase) anything left over, and
  **Close** any open side panel.

## Scenario 1 — a question: "what is 1+9=?"

1. **Write the question.** With **Pen**, handwrite `what is 1+9=?` legibly in the upper
   part of the canvas, leaving clear space to its right and below.
   - *Expected screenshot A:* the handwritten question on a white canvas.

2. **Tap Send — one tap, no sheet.** Tap **Send** once.
   - *Expected:* **no instruction sheet appears.** The small **"Working…"** spinner shows
     top-right immediately. (If a sheet appears, the build has `ONE_TAP_ASK` OFF — stop
     and note the build.)
   - *Expected screenshot B:* the "Working…" indicator with no sheet on screen.

3. **Keep drawing while it runs (non-blocking).** Scribble a stroke anywhere.
   - *Expected:* the stroke draws normally — the UI is **not** locked (SPEC §9.4 step 4).

4. **Result lands.** Within a few `/sync` polls (5 s cadence), the job reaches `done`:
   - The answer **"10"** appears on the canvas as agent text — small, in the space accent
     color at ~70% opacity, constant weight — **immediately to the right of or just
     below the question**, not on top of your ink and not somewhere unrelated.
   - The **side panel** opens (right column in landscape, bottom panel in portrait)
     showing the agent **`summary`** and, under **Cards**, an **`answer` card that is
     already expanded**: its title (e.g. "1 + 9 = 10") with the full answer body as plain
     text beneath it (Markdown characters such as `**` may show literally — that is
     expected this stage).
   - *Expected screenshot C:* "10" beside the question and the expanded answer card.

5. **Collapse / expand.** Tap the card's title row.
   - *Expected:* the body hides; tapping again shows it. Any non-answer card (if the
     agent added one) starts **collapsed** to its title.

6. **Judge placement with the layer toggle.** Tap **Layers**, toggle **Agent
   annotations** off and on.
   - *Expected:* the "10" disappears and reappears, confirming it is on the agent layer.

## Scenario 2 — a mistake: "2+2=5"

1. On a clean canvas, handwrite `2+2=5`.
2. Tap **Send** (one tap).
   - *Expected:* an agent **underline** (constant width, accent color, ~70% opacity)
     under the wrong part of the line — the `5` or the whole equation — and a panel card
     explaining the correction (the answer is 4). A small correction text beside it is
     acceptable; a highlight or decorative marks are not.
   - *Expected screenshot D:* the underline under the mistake with the correction card.

## Scenario 3 — a to-do list: nothing decorative

1. On a clean canvas, handwrite a short list, e.g.
   `- buy milk` / `- call the bank` / `- book dentist`.
2. Tap **Send** (one tap).
   - *Expected:* the panel opens with **at most one card** (one useful observation, or a
     short "nothing to add" `answer` card) and **no decoration on the canvas** — no
     highlights, boxes or arrows. An annotation is acceptable only if the card's
     observation is about a specific place on the page.
   - *Expected screenshot E:* the untouched list with the panel showing ≤ 1 card.

## Optional — the note and "Mark it up instead"

- Tap **"Add a note…"**, type `Answer in French.`, tap **Send**: the job is still
  `canvas.ask`, but the answer follows the note (e.g. "dix").
- Tap **"Add a note…"**, leave it blank, tap **"Mark it up instead"**: this is the Stage-6
  `canvas.annotate` flow with the preset ("Highlight the most important box"); a
  highlight lands somewhere on the page and the panel shows the summary.

## Pass / fail criteria

- [ ] **Scenario 1 PASS** requires **both**: "10" drawn **beside the question** on the
      canvas (screenshot C) **and** an expanded **`answer` card with a body** in the
      panel. "10" only in the panel, or only on the canvas, or drawn far from the
      question, is a **FAIL** — capture the screenshot and the job id and stop.
- [ ] Send was **one tap** — no sheet appeared (screenshot B).
- [ ] The UI stayed responsive (you could draw) while the job ran.
- [ ] **Scenario 2:** an underline under the mistake and a correction card (screenshot D).
- [ ] **Scenario 3:** at most one card and no decoration on the canvas (screenshot E).
- [ ] The agent layer hides/shows from the layer tray.

## Record the run

- Tag / build under test: __________
- Scenario 1 job id: __________  — "10" beside the question (PASS/FAIL): __________
- Scenario 2 job id: __________  — underline + correction card (PASS/FAIL): __________
- Scenario 3 job id: __________  — ≤ 1 card, no decoration (PASS/FAIL): __________
- `coordinate_clamps` observed on each job (expected 0; read it from the server as in
  `smoke/loop.md`): __________
- Notes / anomalies (e.g. the answer text overlapping ink, a fallback-labelled box for a
  type the renderer does not draw natively yet — arrow, ellipse, rect, strikethrough,
  path, margin_note render as thin labelled rectangles by design this stage): __________

## Failure signals

- **A sheet opens on Send:** `ONE_TAP_ASK` is OFF in this build — you are testing the
  Stage-6 flow; use `smoke/loop.md` instead and note the build.
- **Panel shows "Could not respond" / "Job failed":** the agent's response failed
  contract validation twice, or the image was missing — capture the job id and server
  logs.
- **Panel shows an error card, nothing on the canvas:** the job `failed` (e.g. agent
  disabled, over the per-space daily cap) — the panel shows the error card body and
  renders no layer. Capture it.
- **HTTP 422 `not_implemented` in the send status:** staging is running a pre-Stage-7
  server; redeploy before testing.
- **"Offline":** the tablet lost the tailnet; Send is disabled by design (SPEC §9.5).
- **Stuck on "Working…" past ~30 s:** capture the job id and the server logs.

## Results log

| Date (UTC) | Release | Operator | Scenario 1 (1+9) | Notes |
|---|---|---|---|---|
| 2026-09-16 | v0.0.9 | seanerama | pass — "= 10" beside the question, answer card expanded | first live agent job; v0.0.8 failed on structured-output schema limits (ADR-0006 amendment) |
