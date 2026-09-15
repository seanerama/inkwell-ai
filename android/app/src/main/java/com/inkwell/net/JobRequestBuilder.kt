package com.inkwell.net

import com.inkwell.render.CoordinateMapping
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/**
 * Assembles the `POST /jobs` request body from a canvas export, exactly per contract
 * `device-api` §Schema/wire. The field names are the contract's verbatim:
 * `type`, `space_id`, `canvas_id`, `image`, `export` (`{ w, h, width_cu, height_cu }`),
 * `instruction`, `selection` (optional normalized `[x,y,w,h]`).
 *
 * `image` is the base64 of the export PNG (java.util.Base64 so the encoding is pure
 * JVM and unit-testable — Android's `android.util.Base64` is not available in local
 * unit tests). The server persists `image` to the blob store on receipt and returns
 * `request.image_key` in its place (ADR-0004), so the base64 is request-only.
 */
object JobRequestBuilder {

    /** The `export` sub-object with the contract's exact snake_case keys. */
    fun exportJson(export: CoordinateMapping.Export): JsonObject = JsonObject(
        mapOf(
            "w" to JsonPrimitive(export.w),
            "h" to JsonPrimitive(export.h),
            "width_cu" to JsonPrimitive(export.widthCu),
            "height_cu" to JsonPrimitive(export.heightCu),
        ),
    )

    /** Base64-encode PNG bytes for the `image` field (no line wrapping). */
    fun encodeImage(pngBytes: ByteArray): String = Base64.getEncoder().encodeToString(pngBytes)

    /**
     * Build a `to_agent` job request (e.g. `type = "canvas.annotate"`). [selection] is
     * an optional normalized `[x,y,w,h]`; pass null to omit it.
     */
    fun build(
        type: String,
        spaceId: String,
        canvasId: String,
        pngBytes: ByteArray,
        export: CoordinateMapping.Export,
        instruction: String? = null,
        selection: List<Double>? = null,
    ): JobCreateRequest = JobCreateRequest(
        type = type,
        spaceId = spaceId,
        canvasId = canvasId,
        image = encodeImage(pngBytes),
        export = exportJson(export),
        instruction = instruction,
        selection = selection,
    )
}
