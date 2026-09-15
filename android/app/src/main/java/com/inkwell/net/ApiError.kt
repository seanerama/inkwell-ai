package com.inkwell.net

import kotlinx.serialization.json.Json

/** Thrown when the server returns a non-2xx response carrying the error envelope. */
class ApiException(
    val statusCode: Int,
    val body: ErrorBody?,
) : Exception(body?.let { "${it.code}: ${it.message}" } ?: "HTTP $statusCode")

/** Parser for the `{ "error": { "code", "message" } }` envelope (contract device-api). */
object ErrorEnvelopeParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Returns the parsed [ErrorBody], or null when [raw] is not a valid envelope. */
    fun parse(raw: String?): ErrorBody? {
        if (raw.isNullOrBlank()) return null
        return try {
            json.decodeFromString(ErrorEnvelope.serializer(), raw).error
        } catch (_: Exception) {
            null
        }
    }
}
