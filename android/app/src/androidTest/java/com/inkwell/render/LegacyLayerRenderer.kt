package com.inkwell.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.inkwell.data.PackedPoints
import java.io.ByteArrayOutputStream

/**
 * Stage 32 parity oracle (test-only): a verbatim copy of the **pre-stage-32** committed-ink
 * renderer — one `widthCu × heightCu` ARGB page bitmap anchored at (0,0), fully rebuilt on
 * change, blitted through `setScale(s,s) + postTranslate(tx,ty)` with a filtering paint.
 * `TiledRendererParityInstrumentedTest` compares the tiled [LayerRenderer] against it.
 * Do not "fix" or modernise this class: its job is to be the old behaviour.
 */
class LegacyLayerRenderer {

    private var canvasWidthCu = 0
    private var canvasHeightCu = 0

    private var cache: Bitmap? = null
    private var cacheDirty = true
    private var committed: List<RenderStroke> = emptyList()

    private val matrix = Matrix()
    private val strokePaint = LayerRenderer.newStrokePaint()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun setCanvasSize(widthCu: Int, heightCu: Int) {
        if (widthCu == canvasWidthCu && heightCu == canvasHeightCu) return
        canvasWidthCu = widthCu
        canvasHeightCu = heightCu
        cache?.recycle()
        cache = null
        cacheDirty = true
    }

    fun setCommittedStrokes(strokes: List<RenderStroke>) {
        committed = strokes
        cacheDirty = true
    }

    fun release() {
        cache?.recycle()
        cache = null
    }

    fun draw(outCanvas: Canvas, transform: CanvasTransform) {
        rebuildCacheIfNeeded()
        matrix.reset()
        matrix.setScale(transform.scale, transform.scale)
        matrix.postTranslate(transform.tx, transform.ty)
        cache?.let { outCanvas.drawBitmap(it, matrix, bitmapPaint) }
    }

    private fun rebuildCacheIfNeeded() {
        if (!cacheDirty) return
        if (canvasWidthCu <= 0 || canvasHeightCu <= 0) return
        val bmp = cache ?: Bitmap.createBitmap(
            canvasWidthCu, canvasHeightCu, Bitmap.Config.ARGB_8888,
        ).also { cache = it }
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        for (stroke in committed) {
            if (stroke.tool == "eraser") continue
            drawStroke(c, stroke.points, stroke.tool, stroke.color, stroke.widthCu)
        }
        cacheDirty = false
    }

    private fun drawStroke(c: Canvas, points: FloatArray, tool: String, color: Int, widthCu: Float) {
        val stride = PackedPoints.STRIDE
        val count = points.size / stride
        if (count == 0) return
        val alpha = if (tool == "marker") LayerRenderer.MARKER_ALPHA else 255
        strokePaint.color = color
        strokePaint.alpha = alpha
        if (count == 1) {
            strokePaint.strokeWidth = LayerRenderer.modulatedWidthCu(widthCu, tool, points[2])
            c.drawPoint(points[0], points[1], strokePaint)
            return
        }
        var i = 0
        while (i < count - 1) {
            LayerRenderer.drawSegment(c, points, i, tool, widthCu, strokePaint)
            i++
        }
    }

    companion object {
        /**
         * The pre-stage-32 `CanvasExporter.export` pipeline (render + first PNG encode):
         * white export bitmap, ascending-z raster/ink pass, one fresh page-sized
         * [LegacyLayerRenderer] per ink layer.
         */
        fun legacyExportPng(
            widthCu: Int,
            heightCu: Int,
            layers: List<ExportLayer>,
            rasters: List<ExportRaster>,
        ): ByteArray {
            val export = CoordinateMapping.export(widthCu, heightCu)
            val bmp = Bitmap.createBitmap(export.w, export.h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            val scale = CoordinateMapping.exportScale(widthCu, heightCu).toFloat()
            val transform = CanvasTransform(scale = scale, tx = 0f, ty = 0f).apply {
                minScale = 0f
                maxScale = Float.MAX_VALUE
            }
            val rasterPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            val dst = RectF()
            val ops = buildList<Op> {
                layers.filter { it.visible }.forEach { add(Op(it.z, it, null)) }
                rasters.filter { it.visible }.forEach { add(Op(it.z, null, it)) }
            }
            for (op in ExportComposition.ordered(ops)) {
                if (op.ink != null) {
                    val r = LegacyLayerRenderer()
                    r.setCanvasSize(widthCu, heightCu)
                    r.setCommittedStrokes(op.ink.strokes)
                    r.draw(canvas, transform)
                    r.release()
                } else if (op.raster != null) {
                    val rect = RasterFit.destRectPx(op.raster.placement, scale, 0f, 0f)
                    dst.set(rect.left, rect.top, rect.right, rect.bottom)
                    canvas.drawBitmap(op.raster.bitmap, null, dst, rasterPaint)
                }
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            return out.toByteArray()
        }

        private class Op(override val z: Int, val ink: ExportLayer?, val raster: ExportRaster?) :
            ExportComposition.Ordered
    }
}
