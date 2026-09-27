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
 * renderer is tiled, but the export pins it to LOD 0, so each ink layer is still a
 * full-resolution raster downsampled into the export — byte-identical to before
 * (`CanvasExportParityInstrumentedTest`) — with one tile set shared by all layers.
 *
 * Stage 35 (contract `coordinate-mapping` "ADR-0014 additions — region export"): the export
 * covers a [CoordinateMapping.Region] of the canvas — the viewport ∩ the page grid. The
 * formula above is applied to the region's size, and ink, rasters and layers are drawn
 * translated by `−origin × scale`. A single-page canvas exported whole is the region
 * `(0, 0, width_cu, height_cu)`, which takes exactly the pre-stage-35 path (translate 0,
 * one page tile), so its PNG is byte-identical. On a multi-page grid the ink is drawn one
 * grid page at a time (each pass clipped to that page's export pixels, which partition the
 * image exactly), so only one page's LOD-0 tiles are pinned at once and the tile budget holds.
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
    ): Result = export(
        region = CoordinateMapping.Region.page(widthCu, heightCu),
        pageWidthCu = widthCu,
        pageHeightCu = heightCu,
        extent = com.inkwell.data.PageExtent.SINGLE,
        layers = layers,
        rasters = rasters,
    )

    /**
     * Stage 35: export [region] of a canvas whose pages are [pageWidthCu] × [pageHeightCu]
     * and whose grid is [extent]. The PNG is `export(region)` pixels (the formula applied to
     * the region) and the [Result.export] carries the region's origin for the job body.
     */
    fun export(
        region: CoordinateMapping.Region,
        pageWidthCu: Int,
        pageHeightCu: Int,
        extent: com.inkwell.data.PageExtent,
        layers: List<ExportLayer>,
        rasters: List<ExportRaster> = emptyList(),
    ): Result {
        val export = CoordinateMapping.export(region)
        val bitmap = render(region, pageWidthCu, pageHeightCu, extent, export, layers, rasters)
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
        region: CoordinateMapping.Region,
        pageWidthCu: Int,
        pageHeightCu: Int,
        extent: com.inkwell.data.PageExtent,
        export: CoordinateMapping.Export,
        layers: List<ExportLayer>,
        rasters: List<ExportRaster>,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(export.w, export.h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE) // opaque white background (contract §Export step 2)

        val scale = CoordinateMapping.exportScale(region.widthCu, region.heightCu).toFloat()
        // Stage 35: the region's origin lands at export pixel (0,0). Exactly 0 for the v1
        // whole-page export, so that path is unchanged.
        val tx = if (region.originX == 0) 0f else -region.originX * scale
        val ty = if (region.originY == 0) 0f else -region.originY * scale
        val transform = CanvasTransform(scale = scale, tx = tx, ty = ty).apply {
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
        // (byte-identical PNG, contract `coordinate-mapping`). Its tiles are reused across
        // layers (rebuilt per layer) instead of allocating a page-sized bitmap per layer.
        // Stage 35: an oversized page (e.g. a formalized canvas up to 7016 CU a side) whose
        // LOD-0 tile set would not fit the tile budget renders at a coarser LOD that is still
        // at least the export's own resolution, rather than failing to allocate and dropping
        // the ink ([exportLod]; A4 and every page up to the budget stay at LOD 0).
        val renderer = LayerRenderer()
        val lod = exportLod(pageWidthCu, pageHeightCu, extent, scale.toDouble(), renderer.budgetBytes)
        if (lod > 0) {
            android.util.Log.i(
                TAG,
                "export: page ${pageWidthCu}x$pageHeightCu CU exceeds the LOD-0 tile budget; rendering at LOD $lod " +
                    "(${TileMath.lodResolution(lod)} px/CU, export ${"%.3f".format(scale)} px/CU)",
            )
        }
        renderer.apply {
            fixedLod = lod
            setCanvasSize(pageWidthCu, pageHeightCu)
            setPageExtent(extent)
        }
        // Stage 35: on a multi-page grid, one clipped pass per grid page in the region (see
        // [pageChunks]); a single page draws in one unclipped pass, as before.
        val chunks = if (extent.pageCount > 1) {
            pageChunks(region, pageWidthCu, pageHeightCu, extent, scale, export.w, export.h)
        } else {
            null
        }
        try {
            for (op in ExportComposition.ordered(ops)) {
                when (op) {
                    is DrawOp.Ink -> {
                        renderer.setCommittedStrokes(op.layer.strokes)
                        renderer.invalidateAll()
                        if (chunks == null) {
                            renderer.draw(canvas, transform)
                            checkComplete(renderer)
                        } else {
                            for (clip in chunks) {
                                canvas.save()
                                canvas.clipRect(clip[0], clip[1], clip[2], clip[3])
                                renderer.draw(canvas, transform)
                                canvas.restore()
                                checkComplete(renderer)
                            }
                        }
                    }
                    is DrawOp.Raster -> {
                        val rect = RasterFit.destRectPx(op.raster.placement, scale, tx, ty)
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

    /**
     * Stage 35: the export-pixel rects of the grid pages the region shows, clipped to the
     * image. Page edges map to pixels by one rounding rule (`round((edge − origin) × scale)`),
     * so neighbouring rects share their boundary and the rects partition the image exactly:
     * no pixel is drawn twice (which would double translucent ink edges) or missed.
     * Each rect is `[left, top, right, bottom)` in export pixels. Pure, JVM-tested.
     */
    internal fun pageChunks(
        region: CoordinateMapping.Region,
        pageWidthCu: Int,
        pageHeightCu: Int,
        extent: com.inkwell.data.PageExtent,
        scale: Float,
        exportW: Int,
        exportH: Int,
    ): List<IntArray> {
        fun edgeX(col: Int): Int =
            Math.round((col.toDouble() * pageWidthCu - region.originX) * scale).toInt().coerceIn(0, exportW)
        fun edgeY(row: Int): Int =
            Math.round((row.toDouble() * pageHeightCu - region.originY) * scale).toInt().coerceIn(0, exportH)
        val out = ArrayList<IntArray>()
        for (row in extent.minRow..extent.maxRow) {
            val top = if (row == extent.minRow) 0 else edgeY(row)
            val bottom = if (row == extent.maxRow) exportH else edgeY(row + 1)
            if (bottom <= top) continue
            for (col in extent.minCol..extent.maxCol) {
                val left = if (col == extent.minCol) 0 else edgeX(col)
                val right = if (col == extent.maxCol) exportW else edgeX(col + 1)
                if (right > left) out.add(intArrayOf(left, top, right, bottom))
            }
        }
        return out
    }

    /** Stage 35: an export never goes out with ink silently missing (a tile that failed to allocate). */
    private fun checkComplete(renderer: LayerRenderer) {
        check(renderer.lastFrameComplete) { "not enough memory to render the ink for export" }
    }

    /**
     * Stage 35: the LOD the export rasterises ink at. LOD 0 (1 px/CU, the byte-identical
     * stage-32 path) whenever one grid page's tiles — plus, on a multi-page grid, the
     * neighbouring sub-tiles a clipped page pass can touch — fit [budgetBytes]; otherwise the
     * next coarser LOD, but never coarser than the export itself ([exportScale] px/CU), so the
     * tile is still downsampled into the PNG. A4 (34.8 MB) and landscape A4 are always LOD 0.
     * Pure, JVM-tested.
     */
    internal fun exportLod(
        pageWidthCu: Int,
        pageHeightCu: Int,
        extent: com.inkwell.data.PageExtent,
        exportScale: Double,
        budgetBytes: Long,
    ): Int {
        var lod = 0
        while (true) {
            val pageBytes = TileMath.pagePixels(pageWidthCu, lod).toLong() *
                TileMath.pagePixels(pageHeightCu, lod) * TileMath.BYTES_PER_PIXEL
            val need = if (extent.pageCount <= 1) {
                pageBytes
            } else {
                val d = TileMath.subdivisions(extent, lod).toLong()
                pageBytes * (d + 2) * (d + 2) / (d * d)
            }
            if (need <= budgetBytes) return lod
            if (lod >= TileMath.MAX_LOD || TileMath.lodResolution(lod + 1) < exportScale) return lod
            lod++
        }
    }

    private const val TAG = "CanvasExporter"

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
