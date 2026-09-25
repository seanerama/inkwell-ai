package com.inkwell.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
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
 * Stage 22: a pushed raster (a pre-rasterised PDF page or image [bitmap]) to composite into
 * the export at its [placement] (canvas units) and stacking order [z]. Raster layers carry
 * `z < 0` so [ExportComposition.ordered] draws them BENEATH ink (SPEC §5.2), letting the
 * agent see the document with the marks on top. The [bitmap] is produced by
 * [RasterBitmaps.render]; the exporter only scales it into the export rect.
 */
data class ExportRaster(
    val z: Int,
    val visible: Boolean,
    val bitmap: Bitmap,
    val placement: RasterFit.Placement,
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
 * transform so the exported pixels match what the user sees on the canvas. Stage 32: the
 * renderer is tiled, but the export pins it to LOD 0 and page (0,0), so each ink layer is
 * still a full-resolution page raster downsampled into the export — byte-identical to
 * before (`CanvasExportParityInstrumentedTest`) — with one page bitmap shared by all layers.
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
        rasters: List<ExportRaster> = emptyList(),
    ): Result {
        val export = CoordinateMapping.export(widthCu, heightCu)
        val bitmap = render(widthCu, heightCu, export, layers, rasters)
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
        rasters: List<ExportRaster>,
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
        val rasterPaint = android.graphics.Paint(
            android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG,
        )
        val dst = RectF()

        // Draw raster layers (z<0) and ink layers (z>=0) in one ascending-z pass so rasters
        // land BENEATH ink (SPEC §5.2). The ordering is the JVM-tested ExportComposition rule.
        val ops = buildList {
            layers.filter { it.visible }.forEach { add(DrawOp.Ink(it.z, it)) }
            rasters.filter { it.visible }.forEach { add(DrawOp.Raster(it.z, it)) }
        }
        // Stage 32: ONE renderer for every ink layer, pinned to LOD 0 (1 px/CU) so each layer
        // is still rasterised at full resolution and downsampled exactly as before
        // (byte-identical PNG, contract `coordinate-mapping`). Its single page tile is
        // reused across layers (rebuilt per layer) instead of allocating a page-sized
        // bitmap per layer. Export stays page (0,0) until stage 35 (region export).
        val renderer = LayerRenderer().apply {
            fixedLod = 0
            setCanvasSize(widthCu, heightCu)
        }
        try {
            for (op in ExportComposition.ordered(ops)) {
                when (op) {
                    is DrawOp.Ink -> {
                        renderer.setCommittedStrokes(op.layer.strokes)
                        renderer.invalidateAll()
                        renderer.draw(canvas, transform)
                    }
                    is DrawOp.Raster -> {
                        val rect = RasterFit.destRectPx(op.raster.placement, scale, 0f, 0f)
                        dst.set(rect.left, rect.top, rect.right, rect.bottom)
                        canvas.drawBitmap(op.raster.bitmap, null, dst, rasterPaint)
                    }
                }
            }
        } finally {
            renderer.release()
        }
        return bmp
    }

    /** The two kinds of drawable, ordered by `z` via [ExportComposition] (raster below ink). */
    private sealed interface DrawOp : ExportComposition.Ordered {
        data class Ink(override val z: Int, val layer: ExportLayer) : DrawOp
        data class Raster(override val z: Int, val raster: ExportRaster) : DrawOp
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
