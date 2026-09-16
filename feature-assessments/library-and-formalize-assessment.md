# Intake assessment — Library (file structure) and Formalize

- **Date:** 2026-09-16
- **Request:** owner, after the Phase 2 diagram test: "plan formalize; we also want to
  be able to save canvases, in some sort of file structure."
- **Decision:** ACCEPT as two stages, 11 (library) then 12 (formalize), milestone
  `v0.3 — library & formalize`. No new contract; two additive extensions.

## Claim / reality verification (main @ v0.0.11)

| Claim | Reality | Effect |
|---|---|---|
| Canvases need "saving" | Ink already autosaves to Room on every pen-up; what is missing is *multiple* canvases and organisation: `CanvasRepository` loads the first canvas of the first space and creates one if none exists; no list/library screen | Stage 11 is organisation, not persistence |
| A file structure fits the spec | SPEC §2: a space is an agent context, not a folder; §9.3 has a per-space CanvasGrid; §13 Q3 leaves multi-page/linked canvases open | Folders live inside a space (one tree per space); spaces stay the agent boundary for Phase 3 |
| Formalize "returns a new canvas" (SPEC §7) | Not built: `canvas.formalize` → 422; the `agent-output` contract has no "new canvas" payload; Phase 4's `to_user` push path does not exist | The redraw is plain `agent-output` annotations on a blank page; the server adds `result.canvas` as a sibling key (the contract already permits server-added siblings: `contract_version`), and the device materialises the canvas from the job it is polling |
| Server knows canvases | `canvases` table exists (`origin` enum, dims) but no routes; jobs do not validate `canvas_id` | Stage 12 creates agent-origin rows and adds a minimal `GET /canvases`; user canvases stay device-truth (SPEC §3) |
| Agent geometry can be "content" | Renderer draws all nine types (stage 9) but always as 70 % accent marks (SPEC §6.3) | Stage 12 renders the agent layer opaque on `origin=agent` canvases; §6.3's purpose (never confuse marks with ink) is preserved because such canvases start with no ink |
| The note pencil is discoverable | Owner could not find it (icon-only, label only in accessibility text) | Folded into stage 11's toolbar work |

## Impact and contract safety

- `ink-storage`: Room v2 → v3 additive (folders table, two nullable columns);
  `points` format untouched; migration test required.
- `agent-output`: model output unchanged; server sibling keys `canvas`,
  `source_canvas_id` appended to the contract's note about server-added keys.
- `device-api`: optional `meta.title` on `POST /jobs`; `GET /canvases` implemented
  (already listed in the frozen route table); dated additive section.
- `coordinate-mapping`: unchanged (same page bounds for the redraw).

## Rejected / deferred

- A device-to-server canvas sync (full `/canvases` CRUD): deferred to Phase 4, where
  agent pushes need it; until then user canvases remain device-truth.
- Cross-space folders: rejected (contradicts SPEC §2).
- Vector "handwriting-style" redraws (SPEC §13 Q2): rejected for now; constant-width
  clean geometry is the point of formalize.
