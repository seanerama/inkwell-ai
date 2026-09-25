# Contract: ink-storage

- **Status:** frozen v1
- **Owner:** Android `data/` (Room) and `ink/` modules. Device-internal; the server never sees strokes.

Ink is the user's data and lives only on the device (SPEC §3). Its on-disk format is
frozen so that every later client release can read every earlier canvas.

## Exposes

Room entities mirroring SPEC §4.1–4.5: `SpaceEntity`, `CanvasEntity`, `LayerEntity`,
`StrokeEntity`, `RasterEntity`. Room schema export is on
(`room.schemaLocation`) and the JSON schemas are committed under
`android/app/schemas/` so migrations are reviewable.

## Consumes

Nothing external. Server ids (`Space.id`, `Canvas.id`) are stored as-is when a canvas
is mirrored from the server.

## Schema / wire

**Stroke points are a packed little-endian `FloatArray` BLOB, stride 5:**

```
[ x0, y0, p0, tilt0, t0,  x1, y1, p1, tilt1, t1,  ... ]
```

| Field | Unit | Range |
|---|---|---|
| `x`, `y` | canvas units (CU) | `0..width_cu`, `0..height_cu` (may exceed during pan; clamp at render) |
| `p` | pressure | `0.0..1.0`, raw from the digitizer, never pre-modulated |
| `tilt` | radians | `0` = perpendicular |
| `t` | milliseconds since stroke start | stored as float, integral values |

`StrokeEntity` columns: `id TEXT PK`, `layer_id TEXT`, `tool TEXT` (`pen`, `marker`,
`eraser`), `color TEXT` (`#RRGGBB`), `width_cu REAL`, `points BLOB`, `point_count
INTEGER`, `bbox_x, bbox_y, bbox_w, bbox_h REAL` (CU, denormalised for hit-testing),
`created_at INTEGER` (epoch ms). `point_count * 5 * 4 == length(points)` is an
invariant checked on read.

Ink is **never rasterised for storage** (SPEC §1.3). The one-euro filter runs on the
live stream before points are committed; the stored points are the filtered ones.
Width modulation (`width_cu * (0.3 + 0.7 * p)`) is a render-time function of stored
`p`, so retuning it never touches stored data.

Room database version starts at `1`. Every schema change ships a `Migration`; a
destructive fallback is forbidden in release builds.

## ADR-0014 additions — page grid (2026-09-25)

Additive: widened range plus new columns with defaults. Every existing row stays valid
with unchanged meaning.

- **`CanvasEntity.width_cu` / `height_cu` are the page size.** A canvas is a grid of equal
  pages. New integer columns `page_min_col`, `page_max_col`, `page_min_row` and
  `page_max_row` (NOT NULL, default `0`, with `page_min_* ≤ 0 ≤ page_max_*`) give the
  grid. Page `(c, r)` covers `[c·width_cu, (c+1)·width_cu) × [r·height_cu, (r+1)·height_cu)`.
  Each axis is capped at 8 pages (`page_max − page_min + 1 ≤ 8`). This ships as a Room
  schema **v5** migration with defaults; destructive fallback is still forbidden.
- **Stroke `x`,`y` range (widened).** Coordinates are canvas units in the same space as
  before, now bounded by the grid: `page_min_col·width_cu ≤ x < (page_max_col+1)·width_cu`,
  and likewise for `y`. **Negative values are valid.** A pre-v5 canvas (grid `0,0,0,0`)
  keeps exactly the old range. The stride, units, byte order, filtering rule and
  width-modulation rule are unchanged.
- **Growth is recorded, never inferred.** The grid only grows (ADR-0014 §2), and it
  always covers every committed stroke's bbox up to the cap. The v5 migration grows the
  grid of any canvas whose existing strokes lie off page `(0,0)` so that it covers their
  bboxes, capped. It never moves or edits stored points.

## Versioning

Frozen at **v1**. Changes are **additive only** — a breaking change is a NEW
contract, not an edit (framework-spec §4.3). Every consumer depends on this shape.

New columns with defaults are additive. Changing the point stride or units is a new
`points_v2` column with a migration that leaves `points` readable.
