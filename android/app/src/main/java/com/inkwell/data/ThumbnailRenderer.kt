package com.inkwell.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.inkwell.render.LayerRenderer
import com.inkwell.render.RenderStroke
import java.io.File
import java.io.FileOutputStream

/**
 * Android-only helper that renders and caches a 256-px canvas thumbnail (Stage 11).
 *
 * Stage 32 (ADR-0014): the thumbnail shows the **whole page grid**, scaled into the
 * [Thumbnails.SIZE_PX] box ([Thumbnails.fit]), and is rasterised **at thumbnail
 * resolution**: strokes are drawn as vectors straight into the small bitmap (with the same
 * stroke routine as the canvas, [LayerRenderer.drawStrokeWith]) instead of exporting a
 * full-resolution page and scaling it down. A multi-page grid shows faint page dividers.
 *
 * The PNG is cached at `filesDir/thumbs/<canvas_id>.png` (see the pure
 * [Thumbnails.relativePath]) and regenerated only when the canvas's `updated_at` is newer
 * than the cached file's mtime — a thumbnail is a disposable cache, never the source of
 * truth (ink stays vectors, contract `ink-storage`). The bitmap render is exercised in the
 * emulator lane; the path and fit it uses are unit-tested via [Thumbnails].
 */
class ThumbnailRenderer(private val filesDir: File) {

    /** The cache file for [canvasId] (may not yet exist). */
    fun file(canvasId: String): File = File(filesDir, Thumbnails.relativePath(canvasId))

    /** True when a fresh thumbnail exists for a canvas last updated at [updatedAt]. */
    fun isFresh(canvasId: String, updatedAt: Long): Boolean {
        val f = file(canvasId)
        return f.exists() && f.lastModified() >= updatedAt
    }

    /**
     * Ensure a fresh 256-px thumbnail exists for the canvas, rendering it from [strokes]
     * only when missing or stale. [widthCu]×[heightCu] is the page size and [extent] the
     * page grid. Returns the cache file, or null when rendering failed. Safe to call on
     * close / on grid load.
     */
    fun ensure(
        canvasId: String,
        updatedAt: Long,
        widthCu: Int,
        heightCu: Int,
        strokes: List<RenderStroke>,
        extent: PageExtent = PageExtent.SINGLE,
    ): File? {
        val out = file(canvasId)
        if (isFresh(canvasId, updatedAt)) return out

        val bmp = render(widthCu, heightCu, strokes, extent) ?: return null
        try {
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bmp.recycle()
        }
        return out
    }

    /** Render the grid thumbnail bitmap (white paper, page dividers, ink), or null. */
    fun render(widthCu: Int, heightCu: Int, strokes: List<RenderStroke>, extent: PageExtent): Bitmap? {
        if (widthCu <= 0 || heightCu <= 0) return null
        val fit = Thumbnails.fit(extent.widthCu(widthCu), extent.heightCu(heightCu))
        if (fit.scale <= 0.0) return null
        val bmp = Bitmap.createBitmap(fit.widthPx, fit.heightPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val scale = fit.scale.toFloat()
        val originX = extent.leftCu(widthCu).toFloat()
        val originY = extent.topCu(heightCu).toFloat()

        if (extent.pageCount > 1) {
            val divider = Paint().apply { color = DIVIDER_COLOR; strokeWidth = 1f }
            for (col in extent.minCol + 1..extent.maxCol) {
                val x = (col * widthCu - originX) * scale
                c.drawLine(x, 0f, x, fit.heightPx.toFloat(), divider)
            }
            for (row in extent.minRow + 1..extent.maxRow) {
                val y = (row * heightCu - originY) * scale
                c.drawLine(0f, y, fit.widthPx.toFloat(), y, divider)
            }
        }

        c.save()
        c.scale(scale, scale)
        c.translate(-originX, -originY)
        val paint = LayerRenderer.newStrokePaint()
        for (s in strokes) {
            if (s.tool == "eraser") continue
            LayerRenderer.drawStrokeWith(c, s.points, s.tool, s.color, s.widthCu, paint)
        }
        c.restore()
        return bmp
    }

    private companion object {
        const val DIVIDER_COLOR = 0xFFD0D0D0.toInt()
    }
}
