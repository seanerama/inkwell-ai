# Stage 12: Formalize: redraw a sketch as a clean diagram on a new canvas

- **Type:** feature
- **Depends on:** 11
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/28
- **Design:** SPEC §7 (`canvas.formalize`), §4.2 `origin=agent`, §6.3, §12 Phase 2 leftover; contracts `agent-output` (server sibling key, additive), `device-api` (additive routes), `coordinate-mapping`; ADR-0006 amendment

## Objectives

The owner's first real request was "make this topology look better". After this
stage, **Mark up ▸ Formalize** sends the sketch and the agent returns a clean redraw —
boxes, arrows, labels — which appears as a **new canvas** next to the original in the
same folder, titled "<original> — formalized", opened automatically. The original is
untouched. No Phase 4 push machinery: the redraw rides back on the same job the
device is already polling.

## What to build

**Server**
- `canvas.formalize` implemented: accepted on `POST /jobs` (image required), same
  handler, `effort: high` (already in the table).
- `_JOB_GUIDANCE["canvas.formalize"]`: "Redraw the sketch as a clean diagram on a
  blank page of the same size. Use `rect` for boxes (aligned to a grid, equal sizes
  for peers), `arrow` with `label` for connections, `text` for every legible label,
  `ellipse` for cloud/external nodes, `path` only for shapes that fit nothing else.
  Preserve the sketch's layout and relative positions; straighten, align and space
  evenly. Do not critique; no cards except one `answer` card summarising what was
  cleaned up. Coordinates are relative to the same page bounds."
- On `done`, the handler creates a `canvases` row (`origin=agent`, same space and
  dimensions, title "<source title> — formalized" where the device sends the source
  title in the request `meta.title`, else "Formalized") and adds two **server-added
  sibling keys** to `jobs.result` next to `contract_version`:
  `canvas: { id, space_id, title, width_cu, height_cu, origin, created_at }` and
  `source_canvas_id`. The model never produces these (contract `agent-output`
  precedent).
- Contract `device-api`: `POST /jobs` request gains optional `meta: { title }`;
  documented under the dated additive section. `GET /canvases?space_id=` implemented
  minimally (server-known canvases, i.e. agent-origin ones), so Phase 4 has a base.

**Android**
- Picker gains **Formalize** as a third option (Ask / Mark up / Formalize); Send label
  follows. `JobRequestBuilder` sends `meta.title`.
- `JobResultHandler`: when `result.canvas` is present, create the local canvas with
  the **server's id**, `origin=agent`, in the same folder as the source, with one
  `agent/annotation` layer holding the annotations linked to the job, and **no** user
  ink layer until the user first draws (then one is created above it, SPEC §4.3).
  Navigate to it and show the answer card.
- Rendering rule: on an `origin=agent` canvas the agent layer renders **opaque** and
  in ink black (it is the content, not markup); the 70 % / accent rules stay for agent
  layers on user canvases (SPEC §6.3 exists to distinguish marks from ink; here there
  is no ink to confuse). Layer tray labels it "Diagram".
- The new canvas is fully editable: the user draws on top; "Mark up" on it works as
  on any canvas; "Formalize" on it re-formalizes into yet another canvas.
- Kill-switch `BuildConfig.FORMALIZE` (client) and server `IMPLEMENTED_JOB_TYPES`.

## Interface contracts

- **Exposes:** `canvas.formalize`; `result.canvas` / `result.source_canvas_id`
  sibling keys; `meta.title`; `GET /canvases`.
- **Consumes:** `agent-output` (unchanged model output; server sibling keys are
  permitted by the contract), `coordinate-mapping` (same page bounds), `device-api`
  (additive), `ink-storage` (v3 from stage 11). **No new contract.**

## Testing requirements

- Server: formalize accepted; recorded diagram response → `done`, a `canvases` row
  with `origin=agent`, `result.canvas.id` equals it, `source_canvas_id` set, one
  `answer` card; guidance and `effort: high` asserted; `GET /canvases` lists it;
  canary unchanged (ask).
- Android JVM: result with `canvas` → local canvas created with the server id, in the
  source's folder, one agent layer, zero ink layers; opaque rendering flag set for
  agent-origin canvases; picker state machine.
- Instrumented: MockWebServer replay of a formalize result → new canvas opens, tiles
  show both canvases in the same folder, the diagram draws opaque.
- UI-smoke `smoke/formalize.md`: draw the 3-node topology, Formalize; expect a new
  canvas beside it with three aligned boxes, labelled arrows, node labels legible;
  draw on it; Mark up on the new canvas works.

## Acceptance conditions

- [ ] Kill-switch / dark-launch flag (default OFF) for this net-new feature:
      `BuildConfig.FORMALIZE`, stage-7 documented exception (ON in both build types);
      server type gated by `IMPLEMENTED_JOB_TYPES`.
- [ ] UI-smoke "observably-works" check authored for any user-facing surface
      (`smoke/formalize.md`).
- [ ] Additive migration only (no destructive schema change): none on the server
      (`canvases` exists); none on the device beyond stage 11's v3.
- [ ] `contracts/device-api.md` and `contracts/agent-output.md` gain only appended
      additive notes (sibling keys, `meta.title`, `GET /canvases`).
- [ ] The topology formalize passes on the tablet against staging: the owner judges
      the redraw "better than the sketch".
- [ ] Existing suite stays green; CI all-green

## Pipeline test: YES
