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
