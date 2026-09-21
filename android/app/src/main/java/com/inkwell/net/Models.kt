package com.inkwell.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire models for contract `device-api` v1. Field names and shapes mirror the Stage 1
 * FastAPI response models exactly (SPEC §4). `@SerialName` maps snake_case wire keys.
 */

@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    val contract: String,
)

@Serializable
data class Space(
    val id: String,
    val name: String,
    val slug: String,
    @SerialName("system_prompt") val systemPrompt: String,
    val tools: List<String> = emptyList(),
    val model: String,
    val color: String,
    val position: Int,
    @SerialName("created_at") val createdAt: String,
)

/**
 * `POST /spaces` request body (Stage 15; contract device-api §Stage 13 additions, `SpaceCreate`).
 * `name` is required (1–120); every other field is nullable with a null default so
 * `explicitNulls=false` (ApiClient's Json) OMITS the unset keys and the server applies its own
 * defaults (`model=claude-sonnet-5`, `color=#000000`, derived `slug`, `position=max+1`, …).
 */
@Serializable
data class SpaceCreateRequest(
    val name: String,
    val color: String? = null,
    val slug: String? = null,
    @SerialName("system_prompt") val systemPrompt: String? = null,
    val model: String? = null,
    val tools: List<String>? = null,
    val position: Int? = null,
)

/**
 * `PATCH /spaces/{id}` request body (Stage 15; contract device-api §Stage 13 additions,
 * `SpaceUpdate`). Every field is nullable with a null default so `explicitNulls=false` omits
 * the unchanged ones — a true partial PATCH (`exclude_unset` on the server). `slug` is
 * deliberately absent: it is immutable server-side (sending it → `422 "slug is immutable"`).
 */
@Serializable
data class SpacePatchRequest(
    val name: String? = null,
    val color: String? = null,
    @SerialName("system_prompt") val systemPrompt: String? = null,
    val model: String? = null,
    val position: Int? = null,
)

/** `POST /jobs` request body (contract device-api). For Stage 2 only `system.ping`. */
@Serializable
data class JobCreateRequest(
    val type: String,
    @SerialName("space_id") val spaceId: String? = null,
    @SerialName("canvas_id") val canvasId: String? = null,
    val image: String? = null,
    val export: JsonObject? = null,
    val instruction: String? = null,
    val selection: List<Double>? = null,
    /**
     * Stage 12 (device-api additive): optional device metadata. `canvas.formalize` sends
     * `{ "title": <source canvas title> }` so the server names the redraw
     * "<title> — formalized". Null → omitted from the body (explicitNulls=false).
     */
    val meta: JsonObject? = null,
)

@Serializable
data class Job(
    val id: String,
    @SerialName("space_id") val spaceId: String,
    @SerialName("canvas_id") val canvasId: String? = null,
    val direction: String,
    val type: String,
    val status: String,
    val request: JsonObject = JsonObject(emptyMap()),
    val result: JsonObject? = null,
    val error: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    /**
     * Stage 10 (additive, device-api v1): the job's cards with device-changeable state.
     * Defaulted to empty so responses from older servers (no `cards` field) still parse.
     */
    val cards: List<CardResponse> = emptyList(),
) {
    val isTerminal: Boolean get() = status == "done" || status == "failed" || status == "cancelled"
}

/**
 * A card as the server returns it (Stage 10, contract device-api §Additive changes v1):
 * identity (`id`), device-changeable `state`, its `anchors` and `actions`. `kind` and
 * `state` are kept as `String` on the wire so additive vocabulary stays additive — the
 * UI layer maps them to enums (unknown values degrade gracefully rather than failing
 * deserialization).
 */
@Serializable
data class CardResponse(
    val id: String,
    val kind: String,
    val title: String,
    val body: String = "",
    val anchors: List<AnchorResponse> = emptyList(),
    val actions: List<CardActionResponse> = emptyList(),
    val state: String,
    @SerialName("created_at") val createdAt: String,
)

/** A card anchor: either an annotation id or a normalized `[x,y,w,h]` region rect. */
@Serializable
data class AnchorResponse(
    @SerialName("annotation_id") val annotationId: String? = null,
    val region: List<Double>? = null,
)

/** A card action: `kind` is a wire string (confirm/reject/run_tool/open_canvas/save_to_brain). */
@Serializable
data class CardActionResponse(
    val id: String,
    val label: String,
    val kind: String,
    val payload: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class SyncResponse(
    val jobs: List<Job> = emptyList(),
    val cursor: String,
)

/** `PATCH /cards/{id}` request body (Stage 10, contract device-api §Additive changes v1). */
@Serializable
data class CardStateRequest(
    val state: String,
)

// --- Stage 22: pushed (`to_user`) job result + `GET /canvases/{id}` (contract device-api) ---

/**
 * A canvas as it appears inside a `to_user` job `result` or `GET /canvases/{id}` (SPEC §4.2).
 * Pushed canvases are `origin=agent`. Defaults tolerate an older/partial server and match the
 * frozen A4 canvas (2480×3508).
 */
@Serializable
data class WireCanvas(
    val id: String,
    @SerialName("space_id") val spaceId: String,
    val title: String = "",
    @SerialName("width_cu") val widthCu: Int = 2480,
    @SerialName("height_cu") val heightCu: Int = 3508,
    val origin: String = "agent",
)

/** A layer inside a `to_user` result / canvas detail (SPEC §4.3). Raster layers sit at `z=-1`. */
@Serializable
data class WireLayer(
    val id: String,
    @SerialName("canvas_id") val canvasId: String,
    val z: Int = -1,
    val owner: String = "agent",
    val type: String = "raster",
    @SerialName("job_id") val jobId: String? = null,
)

/**
 * A raster inside a `to_user` result / canvas detail (SPEC §4.5). The additive [url] is a
 * fresh signed `GET /blobs/{key}` link (24 h, never stored — computed on read). Placement
 * `x_cu/y_cu/w_cu/h_cu` is server-computed (top-left, fitted to canvas width, ADR-0012).
 */
@Serializable
data class WireRaster(
    val id: String,
    @SerialName("layer_id") val layerId: String,
    val url: String? = null,
    val mime: String = "application/pdf",
    val page: Int? = null,
    @SerialName("x_cu") val xCu: Float = 0f,
    @SerialName("y_cu") val yCu: Float = 0f,
    @SerialName("w_cu") val wCu: Float = 0f,
    @SerialName("h_cu") val hCu: Float = 0f,
)

/**
 * A `to_user` job's `result` (contract device-api §`to_user` job types): the rows the device
 * must materialise. `canvas` is the first page; the additive `canvases` sibling carries every
 * page of a multi-page `agent.push_document` (absent for a single page — then use `[canvas]`).
 */
@Serializable
data class PushResult(
    val canvas: WireCanvas? = null,
    val canvases: List<WireCanvas>? = null,
    val layers: List<WireLayer> = emptyList(),
    val rasters: List<WireRaster> = emptyList(),
    val cards: List<CardResponse> = emptyList(),
)

/** `GET /canvases/{id}` response: a canvas with its layers + rasters (never strokes). */
@Serializable
data class CanvasDetail(
    val id: String,
    @SerialName("space_id") val spaceId: String,
    val title: String = "",
    @SerialName("width_cu") val widthCu: Int = 2480,
    @SerialName("height_cu") val heightCu: Int = 3508,
    val origin: String = "agent",
    val layers: List<WireLayer> = emptyList(),
    val rasters: List<WireRaster> = emptyList(),
)

// --- Stage 27: brain (contract device-api §Stage 25 additions; server-truth, ADR-0013 §6) ---

/**
 * A brain entry as the server returns it (contract `device-api` `GET /brain/{slug}` →
 * `BrainEntry[]`). The full shape is `{ id, space_slug, kind, text, tags, source_canvas_id,
 * source_region, job_id, created_at }` — `job_id` is the Stage-25 additive field (the
 * `to_agent` job whose `brain_writes` produced the row, or null for a device/`save_to_brain`
 * entry). `kind` is one of fact/task/reference/decision, kept as a wire string so an
 * additive kind degrades gracefully. Defaults tolerate an older/partial server. The device
 * NEVER mirrors this into Room — the Brain view fetches live every time (ADR-0013 §6).
 */
@Serializable
data class BrainEntry(
    val id: String,
    @SerialName("space_slug") val spaceSlug: String = "",
    val kind: String,
    val text: String,
    val tags: List<String> = emptyList(),
    @SerialName("source_canvas_id") val sourceCanvasId: String? = null,
    @SerialName("source_region") val sourceRegion: List<Double>? = null,
    @SerialName("job_id") val jobId: String? = null,
    @SerialName("created_at") val createdAt: String = "",
)

/**
 * `POST /brain/{slug}` request body (contract device-api §Stage 25 additions,
 * `{ kind, text (1–2000), tags?, source_canvas_id? }`). `tags`/`source_canvas_id` are
 * nullable so `explicitNulls=false` (ApiClient's Json) omits the unset keys. The route is
 * idempotent server-side: a live entry with the same normalised text returns `200` with the
 * existing row rather than creating a duplicate.
 */
@Serializable
data class BrainCreate(
    val kind: String,
    val text: String,
    val tags: List<String>? = null,
    @SerialName("source_canvas_id") val sourceCanvasId: String? = null,
)

/** The error envelope `{ "error": { "code", "message" } }` (contract device-api). */
@Serializable
data class ErrorEnvelope(
    val error: ErrorBody,
)

@Serializable
data class ErrorBody(
    val code: String,
    val message: String,
)
