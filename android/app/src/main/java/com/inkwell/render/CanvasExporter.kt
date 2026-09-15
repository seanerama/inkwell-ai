package com.inkwell.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.ByteArrayOutputStream

/**
 * One visible layer's worth of committed strokes to flatten into the export, plus its
 * stacking order. Only ink/raster-style stroke layers participate in the pre-send
 * export; agent annotation layers do not exist at send time (they are created from the
 * job result, contract `device-api`).
 */
data class ExportLayer(
    val z: Int,
    val visible: Boolean,
    val strokes: List<RenderStroke>,
)

/**
 * Flattens a canvas to the PNG that is sent to the model, per the frozen
 * `coordinate-mapping` contract:
 *
 *  1. `scale = 1568 / max(width_cu, height_cu)`; `export_w = round(width_cu*scale)`,
 *     `export_h = round(height_cu*scale)` — longest edge is exactly 1568 px. For the
 *     default 2480×3508 canvas this is **1109×1568** (round → 1109; the "1108" prose is
 *     issue #9, the formula wins).
 *  2. Render all VISIBLE layers in ascending `z` onto an opaque WHITE background.
 *  3. Encode PNG.
 *  4. Size guard: never send > 2 MB. If the PNG exceeds 2 MB, re-encode once with
 *     palette reduction; if it still exceeds 2 MB, fail locally with a user-visible
 *     message (contract §Export step 3 / `device-api` 413).
 *
 * Rendering reuses [LayerRenderer] (the Stage 3 renderer) through a CU→EX scale
 * transform so the exported pixels match what the user sees on the canvas.
 */
object CanvasExporter {

    /** Never send a PNG larger than this (contract `coordinate-mapping` §Export). */
    const val MAX_PNG_BYTES = 2 * 1024 * 1024

    sealed interface Result {
        val export: CoordinateMapping.Export

        /** A PNG within the size budget, ready to send. */
        data class Success(
            override val export: CoordinateMapping.Export,
            val png: ByteArray,
        ) : Result {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Success) return false
                return export == other.export && png.contentEquals(other.png)
            }

            override fun hashCode(): Int = 31 * export.hashCode() + png.contentHashCode()
        }

        /** The PNG could not be brought under 2 MB; nothing is sent (user-visible). */
        data class TooLarge(
            override val export: CoordinateMapping.Export,
            val bytes: Int,
            val message: String,
        ) : Result
    }

    /**
     * Export [layers] for a [widthCu]×[heightCu] canvas. Returns [Result.Success] with
     * the PNG and its [CoordinateMapping.Export] metadata, or [Result.TooLarge] when the
     * PNG cannot be reduced below [MAX_PNG_BYTES].
     */
    fun export(
        widthCu: Int,
        heightCu: Int,
        layers: List<ExportLayer>,
    ): Result {
        val export = CoordinateMapping.export(widthCu, heightCu)
        val bitmap = render(widthCu, heightCu, export, layers)
        try {
            val first = encode(bitmap)
            if (first.size <= MAX_PNG_BYTES) {
                return Result.Success(export, first)
            }
            // One palette-reduction retry (contract §Export step 3).
            val reduced = reducePalette(bitmap)
            try {
                val second = encode(reduced)
                if (second.size <= MAX_PNG_BYTES) {
                    return Result.Success(export, second)
                }
                return Result.TooLarge(
                    export = export,
                    bytes = second.size,
                    message = "This canvas is too detailed to send " +
                        "(${second.size / 1024} KB after palette reduction; the limit is " +
                        "${MAX_PNG_BYTES / 1024} KB). Try sending a smaller region.",
                )
            } finally {
                if (reduced !== bitmap) reduced.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Render the visible layers, ascending `z`, onto an opaque white [export]-sized bitmap. */
    private fun render(
        widthCu: Int,
        heightCu: Int,
        export: CoordinateMapping.Export,
        layers: List<ExportLayer>,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(export.w, export.h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE) // opaque white background (contract §Export step 2)

        val scale = CoordinateMapping.exportScale(widthCu, heightCu).toFloat()
        val transform = CanvasTransform(scale = scale, tx = 0f, ty = 0f).apply {
            // The export scale (~0.447 for the default canvas) is below the interactive
            // min; widen the bounds so the transform applies it verbatim.
            minScale = 0f
            maxScale = Float.MAX_VALUE
        }

        for (layer in layers.filter { it.visible }.sortedBy { it.z }) {
            val renderer = LayerRenderer()
            renderer.setCanvasSize(widthCu, heightCu)
            renderer.setCommittedStrokes(layer.strokes)
            renderer.draw(canvas, transform)
            renderer.release()
        }
        return bmp
    }

    private fun encode(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        // PNG is lossless; the quality arg is ignored by the PNG encoder.
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /**
     * Palette reduction: re-encode via a 16-bit RGB_565 copy so the PNG encoder emits
     * far fewer distinct colors and compresses smaller. One retry only.
     */
    private fun reducePalette(bmp: Bitmap): Bitmap =
        bmp.copy(Bitmap.Config.RGB_565, false) ?: bmp
}
