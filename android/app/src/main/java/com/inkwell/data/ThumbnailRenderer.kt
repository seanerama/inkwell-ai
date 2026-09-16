package com.inkwell.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.inkwell.render.CanvasExporter
import com.inkwell.render.ExportLayer
import com.inkwell.render.RenderStroke
import java.io.File
import java.io.FileOutputStream

/**
 * Android-only helper that renders and caches a 256-px canvas thumbnail (Stage 11).
 *
 * Reuses the existing export path ([CanvasExporter] — the same coordinate-mapping used to
 * send a canvas to the agent), then scales the result down so its long edge is
 * [Thumbnails.SIZE_PX]. The PNG is cached at `filesDir/thumbs/<canvas_id>.png` (see the
 * pure [Thumbnails.relativePath]) and regenerated only when the canvas's `updated_at` is
 * newer than the cached file's mtime — a thumbnail is a disposable cache, never the
 * source of truth (ink stays vectors, contract `ink-storage`). The bitmap render is
 * Android-dependent, so it is exercised in the emulator lane; the path derivation it uses
 * is unit-tested via [Thumbnails].
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
     * only when missing or stale. Returns the cache file, or null when the export failed
     * (e.g. the canvas is too detailed to encode). Safe to call on close / on grid load.
     */
    fun ensure(
        canvasId: String,
        updatedAt: Long,
        widthCu: Int,
        heightCu: Int,
        strokes: List<RenderStroke>,
    ): File? {
        val out = file(canvasId)
        if (isFresh(canvasId, updatedAt)) return out

        val layers = listOf(ExportLayer(z = 0, visible = true, strokes = strokes))
        val result = CanvasExporter.export(widthCu, heightCu, layers)
        val png = (result as? CanvasExporter.Result.Success)?.png ?: return null

        val full = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
        val scaled = scaleToLongEdge(full, Thumbnails.SIZE_PX)
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (scaled !== full) scaled.recycle()
        full.recycle()
        return out
    }

    private fun scaleToLongEdge(bmp: Bitmap, longEdge: Int): Bitmap {
        val maxDim = maxOf(bmp.width, bmp.height)
        if (maxDim <= longEdge) return bmp
        val scale = longEdge.toFloat() / maxDim
        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }
}
