# Intake assessment — one-tap send (`canvas.ask` by default)

- **Date:** 2026-09-15
- **Request:** issue #17, from the owner's first real send on staging: "I shouldn't
  have to instruct what to do when sending a note." A note reading "what is 1+9=?"
  should simply be answered.
- **Decision:** ACCEPT as **Stage 7** (feature, depends on 6). No new contract, no ADR.

## Claim / reality verification against the live codebase (main @ v0.0.7)

| Claim (issue #17) | Reality | Effect on the stage |
|---|---|---|
| "Client always sends `canvas.annotate`" | True: `CanvasViewModel.send()` hardcodes the type and falls back to the preset "Highlight the most important box" when the field is blank — which is exactly why a question got a highlight instead of an answer | Client switches the default to `canvas.ask` with no instruction; fallback removed |
| "Server defaults an empty instruction to 'Annotate this canvas'" | True for annotate; for other types it says "Analyse this canvas." | `canvas.ask` gets real guidance and its own default instruction |
| "Server supports `canvas.ask`" (implied) | **False**: `routes/jobs.py` returns `422 not_implemented`; only `canvas.annotate` has a handler. The handler itself is type-agnostic (passes `job.type` to `run_agent`) | Stage implements `canvas.ask` server-side by registering the existing handler; small |
| "The answer can be shown on the canvas and in the panel" (implied) | **Partly false**: `AnnotationRenderer` draws only `highlight`; `PanelModel` carries `summary` + card *titles* only, not bodies. A `text` answer would be silently dropped and a card body invisible | Stage adds `text` + `underline` rendering, the contract's unknown-type fallback, and card bodies in the panel |
| "No contract change" | True: `canvas.ask`, optional `instruction`, `text` annotations and `answer` cards are all in the frozen v1 contracts | Additive only |
| Effort for ask | Already `low` in `EFFORT_BY_JOB_TYPE` | No change |

## Impact and contract safety

Additive throughout. `IMPLEMENTED_JOB_TYPES` grows; no schema or migration; the
client's request body uses fields the contract already defines. The renderer's
fallback rule comes straight from the `agent-output` contract's versioning section
("render an unknown annotation type as a labelled rect around its bounding box").

## Not in this stage (deferred)

- Full annotation vocabulary and Markdown card rendering (SPEC Phase 2).
- Spaces UI / per-space prompts (Phase 3) — the ask guidance is space-agnostic here.
- The Nightshift v3 bridge (issue #18): revisit when Nightshift is deployed; this
  stage keeps the agent seam untouched so the bridge stays additive.
