# Stage 6: The loop: canvas.annotate end to end with highlight

- **Type:** feature
- **Depends on:** 4, 5
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/6
- **Design:** SPEC §9.4 send flow, §12 Phase 1, contracts `device-api`, `agent-output`, `coordinate-mapping`

## Objectives

SPEC Phase 1, the milestone: draw three boxes, ask the agent to highlight the middle
one, and the highlight lands on the middle box, with the `summary` shown in a side
panel. This stage wires Stage 4 (device export and render) to Stage 5 (server agent)
through the Stage 1/2 spine. It builds nothing new in isolation; it proves the seams.

## What to build

- `ui/`: Send button on `CanvasScreen` → instruction text field (typed; a preset
  "Highlight the most important box" for the feel test) → `POST /jobs` with
  `type: canvas.annotate`. Non-blocking in-progress indicator on the canvas; the user
  can keep writing (SPEC §9.4 step 4).
- `net/SyncWorker` (WorkManager): 5 s cadence while a job is outstanding in the
  foreground, 60 s otherwise, 5 min backgrounded; offline detection disables Send
  with an inline state and holds offline-created jobs locally until reconnect.
- On `done`: create the agent layer linked to the job, render highlights via
  Stage 4's renderer, open a `SidePanel` (right column landscape, bottom sheet
  portrait) showing `summary` and, for now, card titles as plain text.
- On `failed`: show the error card body in the panel; no layer.
- Layer tray minimal: list layers with a visibility toggle so the agent layer can be
  hidden (ahead of Phase 2 because it is needed to judge placement).
- Settings: space is hardcoded to the seeded `work` space id fetched from `/spaces`.

## Interface contracts

- **Exposes:** the send/poll/render loop later job types reuse.
- **Consumes:** contract `device-api` (`POST /jobs`, `GET /sync`), contract
  `agent-output` (result), contract `coordinate-mapping` (via Stage 4). No new seam;
  no contract change.

## Testing requirements

- JVM unit tests: sync cadence state machine picks 5 s / 60 s / 5 min correctly;
  offline queue flushes in order on reconnect; a `done` job produces exactly one new
  agent layer and never mutates an existing one; a `failed` job produces none.
- Instrumented (emulator) with a MockWebServer playing the Stage 5 recorded
  responses: Send → queued → polled → `done` → highlight rendered at the fixture's
  bbox; panel shows the summary.
- End-to-end on the device against staging with `AGENT_ENABLED=true` (the Tester's
  and the Handoff Tester's job): three boxes, preset instruction, highlight on the
  middle box. Record the result and the `coordinate_clamps` value in
  `smoke/loop.md`.

## Acceptance conditions

- [ ] Kill-switch / dark-launch flag (default OFF) for this net-new feature:
      `BuildConfig.SEND_ENABLED` (release default OFF until the Handoff Tester
      passes the three-box test; then flipped ON in a follow-up PR).
- [ ] UI-smoke "observably-works" check authored for any user-facing surface:
      `smoke/loop.md` (the three-box test with pass/fail and clamp count).
- [ ] Additive migration only (no destructive schema change).
- [ ] The three-box acceptance passes on the physical tablet against staging.
- [ ] Existing suite stays green; CI all-green

## Pipeline test: YES
