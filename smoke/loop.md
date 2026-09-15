# UI-smoke: the loop — canvas.annotate three-box test (the Phase 1 milestone)

Manual "observably-works" check for the **Handoff Tester** (and the Operator), run on
the real tablet (Lenovo Idea Tab Pro, Android 14) with the active stylus, against the
**staging** server with the agent enabled (`AGENT_ENABLED=true`). This is the SPEC
Phase 1 acceptance: *draw three boxes, ask the agent to highlight the middle one, and
the highlight lands on the middle box, with the `summary` shown in a side panel.*
Browser smoke does not apply to a native client (ADR-0001), so this human pass is the
replacement. Run it on every release APK that changes the send/poll/render loop.

## Kill-switch — which build to test

The loop is gated by `BuildConfig.SEND_ENABLED`. Since **v0.0.5** it is **ON in both
debug and release** so this test runs on the signed release APK from the GitHub
Release (flipped by the Release Operator for staging verification; prod is not
promoted). Before v0.0.5 it was release-OFF, which is why the v0.0.4 release APK
shows no Send button.

## Preconditions

- The v0.0.5+ release APK is installed. The ink kill-switch is ON
  (`BuildConfig.INK_ENABLED`), so the app opens on the **canvas**.
- The tablet is on the tailnet and paired: in **Settings**, the staging server URL and a
  minted device token are set, and **Check** shows
  `Server <version> (device-api/v1)`.
- Staging has `AGENT_ENABLED=true` and a real `ANTHROPIC_API_KEY`, and the seeded
  `work` space exists (`GET /v1/spaces` lists a space with slug `work`).
- The tablet is online (the **Send** button reads "Send", not "Offline").

## Test — three boxes, highlight the middle one

1. **Draw three boxes.** With **Pen**, draw three clearly separated rectangles in a row
   across the canvas — left, middle, right — each a closed-ish box a few cm across.
   - *Expected screenshot A:* three hand-drawn boxes in a row on a white canvas.

2. **Send with the preset.** Tap **Send**. In the sheet, tap the preset
   **"Highlight the most important box"** (or type it), then tap **Send**.
   - *Expected screenshot B:* the instruction sheet with the preset text filled in and
     an enabled **Send** button.

3. **Keep drawing while it runs (non-blocking).** While the job runs, a small spinner
   labelled **"Working…"** shows in the top-right corner. Scribble a stroke anywhere.
   - *Expected:* the stroke draws normally — the UI is **not** locked (SPEC §9.4 step 4).
   - *Expected screenshot C:* the "Working…" indicator visible with a fresh scribble on
     the canvas.

4. **Result lands.** Within a few `/sync` polls (5 s cadence), the job reaches `done`:
   - A translucent **highlight** (space accent color, ~70% opacity, no pressure taper)
     appears **over the MIDDLE box** — not the left or right one.
   - A **side panel** opens (right column in landscape, bottom panel in portrait)
     showing the agent **`summary`** and any card titles as plain text.
   - *Expected screenshot D:* the highlight sitting on the middle box with the summary
     panel open.

5. **Judge placement with the layer toggle.** Tap **Layers**, then toggle the **Agent
   annotations** layer off and on.
   - *Expected:* the highlight disappears and reappears, confirming it is the agent
     layer and letting you eyeball that it covers the middle box (and not its neighbours).

## Pass / fail criteria

- [ ] **PASS** requires the highlight to land on the **MIDDLE** box (screenshot D). A
      highlight on the wrong box, split across boxes, or off-canvas is a **FAIL** —
      capture the screenshot and the job id and stop.
- [ ] The UI stayed responsive (you could draw) while the job ran (screenshot C).
- [ ] The side panel showed the `summary` text (screenshot D).
- [ ] The agent layer hides/shows from the layer tray.

## Record the `coordinate_clamps` value

The server counts how many returned coordinates were clamped into `[0,1]` for this job
(contract `coordinate-mapping`; a healthy three-box result should be **0**). Read it from
the job after the run — on the server host (or `exec` into the container):

```
inkwell job show <job-id>        # or: query jobs.coordinate_clamps for the job
```

Record it here for the run under test:

- Tag / build under test: __________
- Job id: __________
- Highlight landed on the middle box (PASS/FAIL): __________
- `coordinate_clamps` observed: __________  (expected: 0; any non-zero means the model
  returned out-of-band coordinates that were clamped — note it and inspect placement)
- Notes / anomalies: __________

## Failure signals

- **No Send button:** you are on a pre-v0.0.5 release build (SEND_ENABLED OFF) — install v0.0.5 or later.
- **"Offline":** the tablet lost the tailnet; Send is disabled by design (SPEC §9.5).
  Offline-created jobs are held and flushed in order on reconnect.
- **Panel shows an error card, no highlight:** the job `failed` (e.g. over the per-space
  daily cap) — the panel shows the error card body and renders no layer. Capture it.
- **Stuck on "Working…" past ~30 s:** capture the job id and the server logs.
