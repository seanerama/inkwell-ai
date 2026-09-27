package com.inkwell.net

import com.inkwell.render.CoordinateMapping
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.Base64

/**
 * Assembles the `POST /jobs` request body from a canvas export, exactly per contract
 * `device-api` §Schema/wire. The field names are the contract's verbatim:
 * `type`, `space_id`, `canvas_id`, `image`, `export` (`{ w, h, width_cu, height_cu,
 * origin_x_cu, origin_y_cu }`), `instruction`, `selection` (optional normalized `[x,y,w,h]`).
 *
 * Stage 35 (contract `device-api` "ADR-0014 additions"): `width_cu`/`height_cu` are the
 * exported **region**'s size and `origin_x_cu`/`origin_y_cu` its top-left corner. The device
 * always sends both origin keys, including `0`.
 *
 * `image` is the base64 of the export PNG (java.util.Base64 so the encoding is pure
 * JVM and unit-testable — Android's `android.util.Base64` is not available in local
 * unit tests). The server persists `image` to the blob store on receipt and returns
 * `request.image_key` in its place (ADR-0004), so the base64 is request-only.
 */
object JobRequestBuilder {

    /** The `export` sub-object with the contract's exact snake_case keys (origin always present). */
    fun exportJson(export: CoordinateMapping.Export): JsonObject = JsonObject(
        mapOf(
            "w" to JsonPrimitive(export.w),
            "h" to JsonPrimitive(export.h),
            "width_cu" to JsonPrimitive(export.widthCu),
            "height_cu" to JsonPrimitive(export.heightCu),
            "origin_x_cu" to JsonPrimitive(export.originX),
            "origin_y_cu" to JsonPrimitive(export.originY),
        ),
    )

    /**
     * Stage 35: the export region a job request (or a job's stored `request.export`)
     * describes, or null when [export] has no usable `width_cu`/`height_cu` (positive
     * integers). A missing or malformed origin is `0` — a job with no recorded origin maps
     * as origin `(0,0)` (contract `coordinate-mapping` ADR-0014 additions).
     */
    fun regionOf(export: JsonObject?): CoordinateMapping.Region? {
        if (export == null) return null
        val w = intOrNull(export["width_cu"]) ?: return null
        val h = intOrNull(export["height_cu"]) ?: return null
        if (w <= 0 || h <= 0) return null
        return CoordinateMapping.Region(
            originX = intOrNull(export["origin_x_cu"]) ?: 0,
            originY = intOrNull(export["origin_y_cu"]) ?: 0,
            widthCu = w,
            heightCu = h,
        )
    }

    private fun intOrNull(e: JsonElement?): Int? {
        val p = e as? JsonPrimitive ?: return null
        if (p.isString) return null
        return p.intOrNull
    }

    /** Base64-encode PNG bytes for the `image` field (no line wrapping). */
    fun encodeImage(pngBytes: ByteArray): String = Base64.getEncoder().encodeToString(pngBytes)

    /** The `meta` sub-object carrying the source canvas [title] (Stage 12, `canvas.formalize`). */
    fun metaJson(title: String): JsonObject = JsonObject(mapOf("title" to JsonPrimitive(title)))

    /**
     * Build a `to_agent` job request (e.g. `type = "canvas.annotate"`). [selection] is
     * an optional normalized `[x,y,w,h]`; pass null to omit it. [title] is an optional
     * source-canvas title (Stage 12): non-null/non-blank → sent as `meta.title` so a
     * `canvas.formalize` redraw is named "<title> — formalized"; null/blank omits `meta`.
     */
    fun build(
        type: String,
        spaceId: String,
        canvasId: String,
        pngBytes: ByteArray,
        export: CoordinateMapping.Export,
        instruction: String? = null,
        selection: List<Double>? = null,
        title: String? = null,
    ): JobCreateRequest = JobCreateRequest(
        type = type,
        spaceId = spaceId,
        canvasId = canvasId,
        image = encodeImage(pngBytes),
        export = exportJson(export),
        instruction = instruction,
        selection = selection,
        meta = title?.takeIf { it.isNotBlank() }?.let { metaJson(it) },
    )
}
