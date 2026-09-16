# Intake assessment — SPEC Phase 2 ("full vocabulary")

- **Date:** 2026-09-16
- **Request:** owner: "I need more features before I start using it more. plan phase 2."
  SPEC §12 Phase 2: all annotation types, cards with actions, layer tray with
  per-layer visibility, job type picker. Acceptance: the agent marks up a hand-drawn
  architecture diagram and every element is legible and correctly placed.
- **Decision:** ACCEPT as three stages (8 chore, 9 feature, 10 feature), milestone
  `v0.2 — phase 2`. No new contract; one additive extension to `device-api` v1.

## Claim / reality verification (main @ v0.0.9)

| Claim | Reality | Effect |
|---|---|---|
| "All annotation types render" is new work | True: renderer draws `highlight`, `text`, `underline`; the other six are labelled fallback boxes (stage 7) | Stage 9 draws all nine natively and adds the margin-note gutter |
| Layer tray with per-layer visibility (Phase 2 item) | Already built in stage 6 (`toggleLayerTray`, visibility per layer) | Not a stage; nothing to do |
| Job-type picker (Phase 2 item) | Partly: ask is one tap, annotate is "Mark it up instead" in the note sheet; `formalize`/`extract`/`action` are 422 on the server and belong to later phases | Stage 10 turns the sheet into an Ask/Mark-up picker; the rest stays greyed |
| Cards "with actions" | Cards rows have `id`, `state`, `anchors`, `actions` in Postgres, but the device only ever sees the agent's raw `result.cards` (no ids, no state); there are **no card routes** and the client has no action UI, no Markdown, no anchor interaction | Stage 10: additive `cards` on `JobOut`, `PATCH /cards/{id}`, `POST /cards/{id}/actions/{action_id}`, client actions/anchors/Markdown |
| The agent is safe to iterate on | **False today**: no live-API gate; the v0.0.8 structured-output failure shipped through green CI | Stage 8 adds a deploy-time canary using the host's key; no secret in GitHub |
| SPEC §8 `/canvases` routes exist | **False**: never implemented (stage 1 built spaces/jobs/sync only) | Not needed for Phase 2 (canvases are device-local until Phase 4 pushes); noted for the Phase 4 intake |
| Structured outputs guarantee the vocabulary | No: ADR-0006 amendment — prompt-guided JSON; the prompt must describe every type precisely | Stage 9 adds per-type field lines to the schema description and rewrites the annotate guidance |

## Impact and contract safety

- `agent-output`, `coordinate-mapping`, `ink-storage`: untouched. The gutter is a view
  concern; exports still exclude it (and the agent layer by default).
- `device-api`: stage 10 adds two routes and one optional field. Its own versioning
  section allows "new routes and new optional fields"; the change is appended to the
  contract doc under a dated additive heading, the frozen text is not edited.
- Room: v1 → v2 additive (a card-state table). No server migration.
- Kill-switches follow the stage-7 documented exception (ON in both build types while
  staging is the only environment); server-side `AGENT_ENABLED` remains the master
  switch.

## Order and dependencies

8 (canary) and 9 (vocabulary) both depend only on 7 and can run in parallel; 10
depends on 9 because the anchor pulse reuses the native geometry. Ship 8 first so
the stage 9 prompt changes are verified live at deploy.

## Deferred

- `formalize` (new canvas from a sketch) → needs Phase 4's canvas push path.
- `extract`, `save_to_brain`, `run_tool` → Phase 5 / tools.
- Backups + key-rotation runbook (ops) → separate intake; recommended right after 8.
- SPEC §13 open questions 1–2 (canvas size, agent ink style) → review after stage 9
  when the diagram test has been run a few times.
