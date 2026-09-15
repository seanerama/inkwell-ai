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
