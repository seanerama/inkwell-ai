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
) {
    val isTerminal: Boolean get() = status == "done" || status == "failed" || status == "cancelled"
}

@Serializable
data class SyncResponse(
    val jobs: List<Job> = emptyList(),
    val cursor: String,
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
