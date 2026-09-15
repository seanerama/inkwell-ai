# Inkwell — Technical Specification

**Status:** Draft v1 — ready for implementation
**Target device:** Lenovo Idea Tab Pro 12.7" (Android 14, MediaTek Dimensity 8300, 2944×1840, Tab Pen Plus — 4096 pressure levels, tilt, palm rejection)
**Audience:** Coding agents implementing the Android client and the Python server

---

## 1. Summary

Inkwell is a bidirectional handwriting workspace. The user writes and draws on canvases with a stylus. Canvases are sent to AI agents, which read them as images (vision), and respond with structured annotations rendered back onto the canvas plus cards rendered in a side panel. Agents can also originate work — pushing PDFs, images, and new canvases to the user asynchronously.

Canvases are organized into **spaces** (Work, Home, Learning, Business). A space is not a folder; it is an agent context that determines which system prompt, tool set, and knowledge namespace apply.

### 1.1 Goals

- Handwriting and diagrams are first-class input. The agent sees layout, arrows, spatial relationships, and emphasis — not an OCR string.
- Agent output lands **in the right place** on the canvas.
- Agents can initiate, not only respond.
- Adding a new agent = adding a space. No intent classification or routing layer.

### 1.2 Non-goals (v1)

- Real-time / streaming responses. Requests complete; they do not stream.
- Multi-user collaboration or shared canvases.
- On-device inference. All model calls go to the server.
- Infinite canvas. v1 canvases are fixed-size pages.
- Offline agent interaction. Ink capture works offline; agent calls require network.

### 1.3 Design invariants

These must not be violated by any implementation decision:

1. **Ink is stored as vectors, never as bitmaps.** Rasterization happens only at export time.
2. **Agents return JSON, never ink.** The client renders agent geometry.
3. **All agent-returned geometry is in normalized `[0,1]` coordinates** relative to the exported image bounds, mapped back to canvas units on device.
4. **All agent work is asynchronous**, modeled as jobs. Even fast calls go through the job queue.

---

## 2. Glossary

| Term | Meaning |
|---|---|
| **Space** | A named agent context (Work, Home, …). Owns a system prompt, tool set, and brain namespace. |
| **Canvas** | A fixed-size page belonging to one space. Container for layers. |
| **Layer** | An ordered, toggleable content plane on a canvas. Has an owner and a type. |
| **Stroke** | A single pen-down→pen-up ink path with pressure and tilt samples. |
| **Job** | A unit of async work between device and server, in either direction. |
| **Annotation** | Agent-produced geometry rendered onto a canvas layer. |
| **Card** | Agent-produced structured output rendered in the side panel, not on the canvas. |
| **Brain** | Per-space knowledge store the agent reads from and writes to. |
| **CU** | Canvas Unit — the canvas's intrinsic coordinate system. |

---

## 3. Architecture

```
┌──────────────────────────────────────────────┐
│  Android client (Kotlin / Jetpack Compose)   │
│                                              │
│  Ink capture ──▶ Room (SQLite) ──▶ Renderer  │
│       │                                │     │
│       └──▶ Exporter (PNG) ─────────────┘     │
│                    │                         │
│              Sync client (poll)              │
└────────────────────┬─────────────────────────┘
                     │ HTTPS + Bearer token
┌────────────────────▼─────────────────────────┐
│  FastAPI server                              │
│                                              │
│  /jobs  /sync  /canvases  /spaces  /brain    │
│       │                                      │
│  Job worker ──▶ Agent runtime ──▶ Anthropic  │
│                       │             API      │
│                       └──▶ Tools, Brain      │
│                                              │
│  Postgres + object storage (PNG/PDF blobs)   │
└──────────────────────────────────────────────┘
```

Device is the source of truth for **ink**. Server is the source of truth for **jobs, agent output, and the brain**.

---

## 4. Core data model

### 4.1 Space

```
Space
  id            uuid
  name          string          # "Work"
  slug          string          # "work" — brain namespace key
  system_prompt text
  tools         string[]        # tool ids enabled for this space
  model         string          # default "claude-sonnet-5"
  color         string          # UI accent
  position      int             # tab order
  created_at    timestamp
```

Spaces are user-editable but ship with four defaults: `work`, `home`, `learning`, `business`.

### 4.2 Canvas

```
Canvas
  id            uuid
  space_id      uuid
  title         string
  width_cu      int             # default 2480
  height_cu     int             # default 3508
  created_at    timestamp
  updated_at    timestamp
  origin        enum(user, agent)
```

Default canvas is A4 at 300 DPI in canvas units: **2480 × 3508 CU**. `origin` records whether the user created it or an agent pushed it.

### 4.3 Layer

```
Layer
  id            uuid
  canvas_id     uuid
  z             int             # render order, ascending
  owner         enum(user, agent)
  type          enum(ink, raster, annotation)
  visible       bool
  opacity       float           # 0.0–1.0, default 1.0
  job_id        uuid?           # set when owner = agent
  created_at    timestamp
```

Rules:

- A canvas always has at least one `user`/`ink` layer.
- `raster` layers hold a PDF page or image; they render beneath ink by default (`z` < 0 convention).
- Each agent response creates exactly one new `agent`/`annotation` layer, linked to its job. Layers are never mutated by a later job — a new response makes a new layer. This gives free history and undo.
- The user may draw on top of an agent layer; those strokes go to a `user`/`ink` layer with higher `z`.

### 4.4 Stroke

```
Stroke
  id            uuid
  layer_id      uuid
  tool          enum(pen, marker, eraser)
  color         string          # hex
  width_cu      float           # base width before pressure
  points        Point[]
  created_at    timestamp

Point
  x             float           # canvas units
  y             float           # canvas units
  p             float           # pressure 0.0–1.0
  tilt          float           # radians, 0 = perpendicular
  t             int             # ms since stroke start
```

Serialize `points` as a packed array, not JSON objects per point. Suggested on-disk form: four parallel `FloatArray`s (or a single interleaved `FloatArray` of stride 5) stored as a BLOB.

### 4.5 Raster

```
Raster
  id            uuid
  layer_id      uuid
  blob_uri      string          # local cache path or server object key
  mime          string          # image/png, image/jpeg, application/pdf
  page          int?            # for multi-page PDFs
  x_cu, y_cu    float           # top-left placement
  w_cu, h_cu    float           # rendered size
```

### 4.6 Job

```
Job
  id            uuid
  space_id      uuid
  canvas_id     uuid?
  direction     enum(to_agent, to_user)
  type          string          # see §7
  status        enum(queued, running, done, failed, cancelled)
  request       jsonb
  result        jsonb?
  error         string?
  created_at    timestamp
  updated_at    timestamp
```

Both directions use this table. A user sending a canvas creates `direction=to_agent`. An agent pushing a PDF creates `direction=to_user`, which the device discovers via `/sync`.

### 4.7 Card

```
Card
  id            uuid
  job_id        uuid
  kind          enum(answer, task, fact, question, action, error)
  title         string
  body          string          # markdown
  anchors       Anchor[]        # optional links back to canvas regions
  actions       CardAction[]
  state         enum(open, done, dismissed)
  created_at    timestamp

Anchor
  annotation_id uuid?           # link to a rendered annotation
  region        Rect?           # normalized [0,1] fallback

CardAction
  id            string
  label         string
  kind          enum(confirm, reject, run_tool, open_canvas, save_to_brain)
  payload       jsonb
```

Tapping a card with anchors highlights the corresponding canvas region, and vice versa.

---

## 5. Coordinate system

**This is the highest-risk area of the spec. Implement it exactly.**

### 5.1 Three coordinate spaces

| Space | Units | Used by |
|---|---|---|
| **Canvas units (CU)** | Absolute, `0..width_cu` × `0..height_cu` | Storage, strokes, rendering |
| **Export pixels (EX)** | `0..export_w` × `0..export_h` | The PNG sent to the model |
| **Normalized (NM)** | `0.0..1.0` on both axes | Everything the agent returns |

### 5.2 Export

When exporting a canvas for a vision call:

1. Compute `export_w`, `export_h` by scaling `width_cu`×`height_cu` so the **longest edge is 1568 px**, preserving aspect ratio. (1568 px is the point above which the Anthropic API downscales; exporting larger wastes tokens and introduces a scale factor you did not choose.)
2. Render all visible layers, in `z` order, flattened onto a white background.
3. Encode as PNG.
4. Record `export_w`, `export_h`, and `scale = export_w / width_cu` in the job request.

### 5.3 Contract with the agent

The system prompt **must** state:

> The image is a document canvas. All coordinates you return must be normalized floating-point values between 0.0 and 1.0, where `[0,0]` is the top-left corner of the image and `[1,1]` is the bottom-right. Never return pixel values.

### 5.4 Mapping back

```
cu_x = nm_x * width_cu
cu_y = nm_y * height_cu
```

Because normalization is relative to the image bounds and the image preserves canvas aspect ratio, this mapping is exact and independent of export resolution. Export resolution may change without breaking stored annotations.

### 5.5 Validation

Reject any returned coordinate outside `[-0.05, 1.05]`. Clamp values inside that tolerance band to `[0,1]`. Log rejections — a spike indicates prompt drift.

---

## 6. Agent output schema

Agents return a single JSON object. No prose, no markdown fences. Validate against this schema server-side before storing; on validation failure, retry once with the error appended, then fail the job.

```json
{
  "summary": "string — one sentence, shown in the panel header",
  "annotations": [ Annotation ],
  "cards": [ Card ],
  "brain_writes": [ BrainWrite ]
}
```

### 6.1 Annotation types

Every annotation has `id` (string, unique within response), `type`, and optional `color` (hex) and `label` (string).

```jsonc
// Freeform region highlight
{ "id": "a1", "type": "highlight", "points": [[0.1,0.2],[0.4,0.2],[0.4,0.3],[0.1,0.3]] }

// Directed arrow, optionally labelled
{ "id": "a2", "type": "arrow", "from": [0.2,0.4], "to": [0.7,0.5], "label": "blocks" }

// Ellipse around a region
{ "id": "a3", "type": "ellipse", "center": [0.5,0.5], "rx": 0.12, "ry": 0.08 }

// Rectangle
{ "id": "a4", "type": "rect", "x": 0.1, "y": 0.1, "w": 0.3, "h": 0.2 }

// Underline or strikethrough along a path
{ "id": "a5", "type": "underline", "points": [[0.1,0.5],[0.6,0.5]] }
{ "id": "a6", "type": "strikethrough", "points": [[0.1,0.5],[0.6,0.5]] }

// Freeform path (the general case)
{ "id": "a7", "type": "path", "points": [[0.1,0.1],[0.2,0.3],[0.4,0.2]], "closed": false }

// Text placed on the canvas
{ "id": "a8", "type": "text", "at": [0.7,0.2], "text": "check units", "size": 0.02 }

// Margin note — anchored to a y position, rendered in the gutter
{ "id": "a9", "type": "margin_note", "y": 0.35, "text": "This contradicts p.2" }
```

`size` for text is expressed as a fraction of canvas height, so it scales correctly.

### 6.2 Brain writes

```jsonc
{
  "kind": "fact" | "task" | "reference" | "decision",
  "text": "string",
  "tags": ["string"],
  "source_canvas_id": "uuid",
  "source_region": [0.1, 0.2, 0.3, 0.1]   // optional [x,y,w,h]
}
```

### 6.3 Rendering rules

- Annotations render on a dedicated agent layer at 70% opacity by default, in the space's accent color unless `color` is specified.
- Agent annotations are visually distinct from user ink: constant stroke width, no pressure variation, slight transparency. The user must never be confused about who drew what.
- Agent layers are individually toggleable from the layer tray.

---

## 7. Job types

| `type` | Direction | Purpose |
|---|---|---|
| `canvas.ask` | to_agent | Freeform question about the canvas |
| `canvas.annotate` | to_agent | Mark up / critique the canvas |
| `canvas.formalize` | to_agent | Redraw sketch as clean diagram (returns new canvas) |
| `canvas.extract` | to_agent | Pull structured data into the brain |
| `canvas.action` | to_agent | Run tools based on canvas content |
| `agent.push_canvas` | to_user | Agent creates a canvas for the user |
| `agent.push_document` | to_user | Agent sends a PDF or image to annotate |
| `agent.notify` | to_user | Cards only, no canvas |

The request payload for `to_agent` types:

```json
{
  "canvas_id": "uuid",
  "space_id": "uuid",
  "image": "base64 PNG",
  "export": { "w": 1109, "h": 1568, "width_cu": 2480, "height_cu": 3508 },
  "instruction": "optional user text or selected preset",
  "selection": [0.1, 0.2, 0.5, 0.3]
}
```

`selection` is an optional normalized rect. When present, the prompt should direct attention there without cropping the image — the model still needs surrounding context.

---

## 8. HTTP API

Base: `https://<host>/v1`. All endpoints require `Authorization: Bearer <device_token>`.

### Spaces

```
GET    /spaces                     → Space[]
POST   /spaces                     → Space
PATCH  /spaces/{id}                → Space
```

### Canvases

```
GET    /canvases?space_id=&since=  → Canvas[]
POST   /canvases                   → Canvas
GET    /canvases/{id}              → Canvas + layers + rasters (not strokes)
DELETE /canvases/{id}              → 204
```

Strokes are never uploaded. The server sees canvases only as exported images.

### Jobs

```
POST   /jobs                       → Job          # 202 Accepted, status=queued
GET    /jobs/{id}                  → Job
POST   /jobs/{id}/cancel           → Job
```

### Sync

```
GET    /sync?cursor=<opaque>       → { jobs: Job[], cursor: string }
```

Returns jobs updated since the cursor, in either direction. This is how the device learns that a `to_agent` job finished *and* that an agent pushed something new. Poll every 5s while the app is foregrounded and a job is outstanding; every 60s otherwise. Back off to 5 minutes when backgrounded.

FCM push is a Phase 5 optimization, not a v1 requirement.

### Blobs

```
POST   /blobs                      → { key, url }
GET    /blobs/{key}                → bytes
```

### Brain

```
GET    /brain/{space_slug}?q=&limit=  → BrainEntry[]
POST   /brain/{space_slug}            → BrainEntry
DELETE /brain/{space_slug}/{id}       → 204
```

---

## 9. Android client

### 9.1 Stack

- Kotlin, Jetpack Compose
- Room for local persistence
- Retrofit + OkHttp, kotlinx.serialization
- WorkManager for sync polling
- `minSdk` 31, `targetSdk` 34

### 9.2 Ink capture — requirements

This determines whether the app feels good. Get it right before anything else.

1. Use a `pointerInteropFilter` or a custom `View` handling raw `MotionEvent`. Compose's `pointerInput` alone drops samples.
2. **Iterate historical points.** `event.historySize` contains samples batched between frames. Skipping these produces visibly polygonal strokes.
   ```kotlin
   for (i in 0 until event.historySize) {
       addPoint(event.getHistoricalX(i), event.getHistoricalY(i),
                event.getHistoricalPressure(i), event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i))
   }
   addPoint(event.x, event.y, event.pressure, event.getAxisValue(MotionEvent.AXIS_TILT))
   ```
3. Reject `MotionEvent`s whose `getToolType()` is `TOOL_TYPE_FINGER` while a stylus is in range. Finger gestures pan and zoom; stylus draws.
4. Apply a one-euro filter to the point stream to smooth jitter without adding latency. Do not use a moving average — it lags.
5. Render the in-progress stroke to a separate overlay so committed strokes are not re-rasterized every frame.
6. Width modulation: `width_cu * (0.3 + 0.7 * pressure)`. Tune later; store raw pressure regardless.

**Acceptance:** a slow diagonal line shows no visible segmentation; a fast flick produces a continuous stroke; resting a palm on the screen produces no ink.

### 9.3 Screen structure

```
TabBar            — spaces, horizontally scrollable
├─ CanvasGrid     — canvases in the active space
└─ CanvasScreen
   ├─ Canvas      — pan/zoom, layered rendering
   ├─ Toolbar     — tools, colors, layer tray, send
   └─ SidePanel   — cards; collapsible; overlay on portrait
```

The panel is a persistent right-hand column in landscape (the tablet's natural orientation at 12.7") and a bottom sheet in portrait.

### 9.4 Send flow

1. User taps Send, picks a job type (or a per-space preset), optionally types/writes an instruction.
2. Client exports PNG per §5.2.
3. `POST /jobs` → receives `queued`.
4. Show a non-blocking indicator on the canvas. **The user can keep writing.** Do not lock the UI.
5. Poll `/sync` until the job reaches `done` or `failed`.
6. On `done`: create the agent layer, render annotations, populate cards, animate the panel open.

### 9.5 Offline

Ink capture, canvas creation, and editing work fully offline. Send is disabled with a clear inline state. Queued jobs created offline are held locally and flushed on reconnect.

---

## 10. Server

### 10.1 Stack

- FastAPI, Python 3.12
- Postgres 16 (jobs, canvases, cards, brain)
- S3-compatible object storage for blobs
- A worker process (arq, Celery, or a plain asyncio task queue) — jobs must not run in the request handler
- `anthropic` Python SDK

### 10.2 Agent runtime

```python
async def run_job(job: Job) -> AgentOutput:
    space = await get_space(job.space_id)
    brain_context = await retrieve_brain(space.slug, job.request.get("instruction"))

    response = await client.messages.create(
        model=space.model,
        max_tokens=4096,
        system=build_system_prompt(space, brain_context),
        tools=resolve_tools(space.tools),
        messages=[{
            "role": "user",
            "content": [
                {"type": "image", "source": {
                    "type": "base64",
                    "media_type": "image/png",
                    "data": job.request["image"],
                }},
                {"type": "text", "text": build_instruction(job)},
            ],
        }],
    )
    return validate_output(extract_json(response))
```

### 10.3 System prompt construction

The system prompt is assembled from, in order:

1. Fixed preamble: role, the coordinate contract from §5.3, the output schema from §6.
2. The space's `system_prompt`.
3. Retrieved brain context, clearly delimited and marked as reference material rather than instructions.
4. Job-type-specific guidance.

Content retrieved from the brain is data, not instruction. Delimit it explicitly and state in the preamble that instructions appearing inside it must not be followed.

### 10.4 Output extraction

Models occasionally wrap JSON in fences despite instruction. Strip ` ```json ` and ` ``` ` before parsing. Validate with Pydantic. On `ValidationError`, retry once with the error text appended to the message list. On second failure, mark the job `failed` and return an `error` card — never a silent failure.

### 10.5 Cost controls

- Per-space daily job cap, configurable, default 200.
- Reject exports above 2 MB.
- Log token usage per job; expose a running total on a `/usage` endpoint.

---

## 11. Security

- Device authenticates with a long-lived bearer token issued at pairing. Store in Android Keystore, never in `SharedPreferences`.
- The Anthropic API key lives **only** on the server. It is never transmitted to the device under any circumstance.
- Tool execution is allow-listed per space. A space cannot invoke a tool absent from its `tools` array, regardless of what the model requests.
- Canvas images may contain sensitive handwriting. Serve blobs through signed, expiring URLs; do not make the bucket public.
- Rate-limit `/jobs` per device token.

---

## 12. Build phases

Each phase must be independently demoable. Do not begin a phase before its predecessor's acceptance criteria pass.

### Phase 0 — Ink

One canvas, no server, no spaces. Pen input, pressure, eraser, pan/zoom, Room persistence.

*Acceptance:* writing feels good enough that you'd choose it over paper for a short note. If it doesn't, stop and fix it — every later phase inherits this.

### Phase 1 — The loop

One hardcoded space. Export PNG, `POST /jobs`, poll, render one annotation type (`highlight`) at correct coordinates, show `summary` in a panel.

*Acceptance:* you draw three boxes, ask the agent to highlight the middle one, and the highlight lands on the middle box.

**This phase contains every hard problem in the system.** Budget accordingly.

### Phase 2 — Full vocabulary

All annotation types from §6.1. Cards with actions. Layer tray with per-layer visibility. Job type picker.

*Acceptance:* the agent can mark up a hand-drawn architecture diagram — arrows, labels, margin notes — and every element is legible and correctly placed.

### Phase 3 — Spaces

Four tabs, per-space prompts and tool sets, canvas grid, move-canvas-between-spaces.

*Acceptance:* the same canvas sent from Work and from Learning produces recognizably different responses.

### Phase 4 — Bidirectional

`to_user` jobs. Agent pushes a PDF; it appears as a raster layer on a new canvas in the right space, with a notification badge on the tab. User annotates it and sends it back.

*Acceptance:* a server-side script can put a document in front of you without you asking.

### Phase 5 — Brain

Per-space store, `brain_writes` persisted, retrieval injected into the system prompt, a browsable view.

*Acceptance:* a fact written on a canvas in January is correctly recalled in March.

---

## 13. Open questions

Resolve before Phase 2; they are noted here rather than decided because they depend on how the tool feels in use.

1. **Canvas size.** A4-at-300DPI is a guess. If most canvases are diagrams rather than pages, a landscape or square default may be better. Revisit after Phase 1.
2. **Agent ink style.** Constant-width vector is the safe choice. Synthesized handwriting-style strokes would feel more cohesive but risk being mistaken for the user's own ink.
3. **Multi-page canvases.** Currently one canvas = one page. Long documents need either multi-page canvases or a linked-canvas notion.
4. **Selection gesture.** Lasso-to-select-region before sending is powerful but competes with lasso-to-move for existing ink. Needs a mode or a modifier.
5. **Conflict on concurrent edit.** If the user draws while a job is running, the agent's annotations reference a canvas state that has since changed. v1 accepts the drift. If it proves confusing, snapshot the canvas at send time and render annotations against the snapshot.

---

## 14. Repository layout

```
inkwell/
├── android/
│   ├── app/src/main/java/com/inkwell/
│   │   ├── ink/          # capture, filtering, stroke model
│   │   ├── render/       # canvas + layer rendering
│   │   ├── data/         # Room entities, DAOs, repositories
│   │   ├── net/          # Retrofit API, sync worker
│   │   └── ui/           # Compose screens
│   └── build.gradle.kts
├── server/
│   ├── app/
│   │   ├── main.py       # FastAPI app, routers
│   │   ├── models.py     # SQLAlchemy
│   │   ├── schemas.py    # Pydantic — §6 lives here
│   │   ├── agent.py      # runtime, prompt construction
│   │   ├── worker.py     # job processing
│   │   └── brain.py
│   ├── migrations/
│   └── pyproject.toml
└── SPEC.md
```

Keep §6's schema definitions in exactly one place — `server/app/schemas.py` — and generate the Kotlin data classes from it. Two hand-maintained copies of the annotation schema will diverge, and the divergence will present as annotations silently failing to render.
