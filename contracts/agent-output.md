# Contract: agent-output

- **Status:** frozen v1
- **Owner:** server `agent/` module (producer); Android `render/` and `ui/` (consumers)
- **Schema file:** `contracts/schema/agent-output.v1.schema.json` (source of truth, ADR-0007)
- **Fixtures:** `contracts/fixtures/agent-output/`

## Exposes

The single JSON object an agent returns for every `to_agent` job, stored verbatim in
`jobs.result` and rendered by the device:

```
{ summary: string, annotations: Annotation[], cards: Card[], brain_writes: BrainWrite[] }
```

Annotation vocabulary (v1, closed set): `highlight`, `arrow`, `ellipse`, `rect`,
`underline`, `strikethrough`, `path`, `text`, `margin_note`. Every annotation has a
response-unique `id`, optional `color` (`#RRGGBB`) and `label`. Card kinds: `answer`,
`task`, `fact`, `question`, `action`, `error`. Card action kinds: `confirm`, `reject`,
`run_tool`, `open_canvas`, `save_to_brain`. Brain write kinds: `fact`, `task`,
`reference`, `decision`.

## Consumes

- An exported canvas image and export metadata (contract `coordinate-mapping`).
- The space's system prompt, tool allow-list, and retrieved brain context (server-internal).

## Schema / wire

- All geometry is **normalized** `[0,1]` relative to the exported image; `text.size` is
  a fraction of canvas height. The server accepts values in `[-0.05, 1.05]`, clamps to
  `[0,1]`, and rejects anything outside (contract `coordinate-mapping`).
- Semantic rules enforced server-side after schema validation: annotation `id`s are
  unique within a response; `Anchor.annotation_id` must reference an `id` in the same
  response; `cards[].body` is Markdown.
- The model is asked for this shape through structured outputs using the schema file
  directly (ADR-0006). The schema is therefore written with `additionalProperties:
  false` and explicit `required` lists everywhere.
- Rendering rules (SPEC §6.3) are consumer behaviour, not wire: agent layer at 70%
  opacity in the space accent color unless `color` is set; constant stroke width.
- Server-side storage: `jobs.result` holds the validated object plus a
  `contract_version: "agent-output/v1"` sibling key added by the server, never by the model.

## Versioning

Frozen at **v1**. Changes are **additive only** — a breaking change is a NEW
contract, not an edit (framework-spec §4.3). Every consumer depends on this shape.

Additive means: a new annotation `type`, a new card `kind`, a new optional property.
Consumers must ignore unknown optional properties and must render an unknown
annotation `type` as a labelled `rect` around its bounding box rather than dropping it.

## Additive changes (v1) — 2026-09-16

Stage 12 (`canvas.formalize`) adds **server-added sibling keys** to `jobs.result`,
alongside the existing `contract_version` key. This is not a change to the model's output
shape and not a change to the frozen schema above: the model still produces exactly
`{ summary, annotations, cards, brain_writes }`. These keys are written by the server
after validation, never by the model — the same precedent as `contract_version`.

For a done `canvas.formalize` job the server adds:

- **`canvas`** — the agent-origin canvas the server created to hold the redraw:
  `{ id, space_id, title, width_cu, height_cu, origin, created_at }` (`origin` is
  `"agent"`). The device creates a local canvas with this server id.
- **`source_canvas_id`** — the id of the canvas that was formalized (the job's
  `canvas_id`), or `null` if the job carried none.

These keys appear only for `canvas.formalize`; `canvas.annotate` / `canvas.ask` results
are unchanged. Consumers that do not know them ignore them (they are optional siblings,
exactly like `contract_version`). Because they are not part of the `AgentOutput` model,
they are absent from `contracts/schema/agent-output.v1.schema.json`, which stays frozen.

## Additive changes (v1) — 2026-09-21

Stage 25 (ADR-0013 §2) adds one more **server-added sibling key** to a done `to_agent`
job's `jobs.result`, alongside `contract_version` (and the Stage 12 `canvas`/
`source_canvas_id` keys). This is not a change to the model's output shape and not a
change to the frozen schema: the model still produces exactly `{ summary, annotations,
cards, brain_writes }`, and `brain_writes` itself is unchanged. The key is written by the
server after validation, never by the model.

- **`brain_entry_ids`** — the ids of the `brain_entries` rows the server created from this
  job's `brain_writes` (provenance/traceability; ADR-0013 §2). Skipped writes (exact-text
  duplicates already live in the space) are not listed, so this can be shorter than
  `brain_writes`. The key is present only when the `BRAIN_ENABLED` kill-switch is on;
  with the switch off no writes are persisted and the key is absent.

Because it is not part of the `AgentOutput` model it is absent from
`contracts/schema/agent-output.v1.schema.json`, which stays frozen — exactly like
`contract_version` and the Stage 12 keys.
