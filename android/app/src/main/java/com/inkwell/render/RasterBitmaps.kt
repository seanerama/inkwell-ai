package com.inkwell.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Android-only rasterisation of a pushed blob to a bitmap (Stage 22): PDF pages via
 * [PdfRenderer], images via [BitmapFactory] with `inSampleSize` (memory guard). Shared by
 * the on-screen [RasterRenderer] (cached, blitted through the transform) and the pre-send
 * [CanvasExporter] (composited beneath ink), so both paths rasterise identically. Pure
 * placement/scale maths live in [RasterFit]; this object only turns bytes into pixels.
 */
object RasterBitmaps {

    /**
     * Rasterise the blob at [blobPath] to a [targetWpx]×[targetHpx] bitmap, or null on any
     * failure (missing file, undecodable image, empty PDF). PDFs are white-backed and opaque.
     */
    fun render(blobPath: String, mime: String, page: Int?, targetWpx: Int, targetHpx: Int): Bitmap? {
        val w = targetWpx.coerceAtLeast(1)
        val h = targetHpx.coerceAtLeast(1)
        val file = File(blobPath)
        if (!file.exists()) return null
        return if (mime.substringBefore(';').trim() == "application/pdf") {
            renderPdf(file, page ?: 0, w, h)
        } else {
            renderImage(file, w, h)
        }
    }

    private fun renderPdf(file: File, page: Int, targetW: Int, targetH: Int): Bitmap? {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount == 0) return null
                val idx = page.coerceIn(0, renderer.pageCount - 1)
                renderer.openPage(idx).use { pdfPage ->
                    val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    pdfPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                }
            }
        }
    }

    private fun renderImage(file: File, targetW: Int, targetH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = RasterFit.sampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH)
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }
}
