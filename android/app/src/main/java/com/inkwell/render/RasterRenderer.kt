package com.inkwell.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.roundToInt

/**
 * Renders a pushed raster layer BENEATH the ink (Stage 22, ADR-0012, SPEC §4.3). A PDF page
 * is rasterised with [PdfRenderer] at canvas resolution (up to ~2480 px wide for A4) into a
 * CACHED bitmap; an image is decoded with `inSampleSize` to bound memory. The cache is built
 * once and blitted through the pan/zoom transform every frame — pan/zoom only changes the
 * destination rect, never re-rasterising the page (the same discipline as [LayerRenderer]).
 *
 * Placement comes from the raster's `x_cu/y_cu/w_cu/h_cu` (server-computed, top-left). The
 * cache bitmap is sized to the placement in canvas units (1 px per CU), so drawing it into
 * the transformed destination rect scales it exactly like ink.
 */
class RasterRenderer {

    /** What to render: the local cache file, its mime/page, and its canvas placement. */
    data class RasterSpec(
        val blobPath: String,
        val mime: String,
        val page: Int?,
        val placement: RasterFit.Placement,
    )

    private var spec: RasterSpec? = null
    private var cache: Bitmap? = null
    private var cacheKey: String? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()

    /** Set (or clear, with null) the raster to render; a changed spec drops the cache. */
    fun setRaster(spec: RasterSpec?) {
        if (spec == this.spec) return
        this.spec = spec
        if (keyOf(spec) != cacheKey) {
            cache?.recycle()
            cache = null
            cacheKey = null
        }
    }

    /** True when a raster is set (used to skip the whole path when there is none). */
    fun hasRaster(): Boolean = spec != null

    /**
     * Draw the raster into [outCanvas] under [transform], beneath whatever the caller draws
     * next (ink). No-op when there is no raster or the page could not be rasterised.
     */
    fun draw(outCanvas: Canvas, transform: CanvasTransform) {
        val s = spec ?: return
        val bmp = ensureCache(s) ?: return
        val rect = RasterFit.destRectPx(s.placement, transform.scale, transform.tx, transform.ty)
        dst.set(rect.left, rect.top, rect.right, rect.bottom)
        outCanvas.drawBitmap(bmp, null, dst, bitmapPaint)
    }

    fun release() {
        cache?.recycle()
        cache = null
        cacheKey = null
    }

    private fun ensureCache(s: RasterSpec): Bitmap? {
        val key = keyOf(s)
        cache?.let { if (key == cacheKey) return it }
        cache?.recycle()
        val rendered = runCatching { rasterise(s) }.getOrNull()
        cache = rendered
        cacheKey = if (rendered != null) key else null
        return rendered
    }

    /** Rasterise the page/image to a canvas-resolution bitmap (the expensive, cached step). */
    private fun rasterise(s: RasterSpec): Bitmap? {
        val targetW = s.placement.wCu.roundToInt().coerceAtLeast(1)
        val targetH = s.placement.hCu.roundToInt().coerceAtLeast(1)
        return RasterBitmaps.render(s.blobPath, s.mime, s.page, targetW, targetH)
    }

    private fun keyOf(s: RasterSpec?): String? =
        s?.let { "${it.blobPath}|${it.page}|${it.placement.wCu}x${it.placement.hCu}" }
}
