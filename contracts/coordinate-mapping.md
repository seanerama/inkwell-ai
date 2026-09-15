# Contract: coordinate-mapping

- **Status:** frozen v1
- **Owner:** Android `render/` exporter (producer of images); server `agent/` (validator); Android `render/` (consumer of geometry)

This is SPEC §5, the highest-risk seam in the system, frozen so that the exporter,
the prompt, the validator, and the renderer can never drift from each other.

## Exposes

Three coordinate spaces and the exact conversions between them:

| Space | Units | Used by |
|---|---|---|
| Canvas units (CU) | absolute, `0..width_cu` × `0..height_cu` | storage, strokes, rendering |
| Export pixels (EX) | `0..export_w` × `0..export_h` | the PNG sent to the model |
| Normalized (NM) | `0.0..1.0` both axes | everything the agent returns |

## Consumes

Canvas dimensions (`width_cu`, `height_cu`; default 2480 × 3508) and the set of
visible layers.

## Schema / wire

**Export (device):**

1. `scale = 1568 / max(width_cu, height_cu)`; `export_w = round(width_cu * scale)`,
   `export_h = round(height_cu * scale)`. Longest edge is exactly 1568 px.
2. Render all visible layers in ascending `z` onto opaque white, then encode PNG.
3. Reject an export whose PNG exceeds 2 MB (re-encode with palette reduction once,
   then fail locally with a user-visible message; never send).
4. Send `export: { w, h, width_cu, height_cu }` with the job (contract `device-api`).

**Prompt (server):** the fixed preamble must contain this sentence verbatim:

> The image is a document canvas. All coordinates you return must be normalized
> floating-point values between 0.0 and 1.0, where [0,0] is the top-left corner of the
> image and [1,1] is the bottom-right. Never return pixel values.

**Validation (server), applied to every numeric coordinate in an `agent-output`
response, including `rect.x/y/w/h`, `ellipse.center/rx/ry`, `text.at`, `margin_note.y`
and every `points[]` entry:**

- value in `[0, 1]`: accepted as is;
- value in `[-0.05, 0)` or `(1, 1.05]`: clamped to `0` or `1` and counted in the
  `coordinate_clamps` metric on the job;
- otherwise: the response is rejected; retry once per ADR-0006; the rejection is
  logged with the job id and the offending path (`annotations[3].points[1][0]`).

**Mapping back (device):** `cu_x = nm_x * width_cu`, `cu_y = nm_y * height_cu`.
`text.size` maps to `size * height_cu` CU. `margin_note.y` maps to `y * height_cu` and
renders in a gutter outside the canvas bounds. Selection rects map the same way.

Because the export preserves aspect ratio and normalization is relative to the image
bounds, stored annotations are independent of export resolution. Changing the 1568 px
target later would not invalidate any stored annotation.

## Versioning

Frozen at **v1**. Changes are **additive only** — a breaking change is a NEW
contract, not an edit (framework-spec §4.3). Every consumer depends on this shape.

Changing the tolerance band, the origin corner, or the meaning of `size` is breaking.
