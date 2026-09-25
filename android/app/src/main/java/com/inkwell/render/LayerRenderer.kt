package com.inkwell.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.inkwell.data.PackedPoints

/**
 * One committed stroke ready to draw: filtered stride-5 [points] in canvas units, its
 * [tool], parsed [color], and base [widthCu] (before pressure modulation).
 */
data class RenderStroke(
    val id: String,
    val points: FloatArray,
    val tool: String,
    val color: Int,
    val widthCu: Float,
    val bboxX: Float = 0f,
    val bboxY: Float = 0f,
    val bboxW: Float = 0f,
    val bboxH: Float = 0f,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RenderStroke) return false
        return id == other.id && points.contentEquals(other.points) &&
            tool == other.tool && color == other.color && widthCu == other.widthCu
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Renders one ink layer: a committed-stroke bitmap **cache** blitted through the
 * pan/zoom transform, plus a **live overlay** for the in-progress stroke.
 *
 * The cache (a canvas-resolution bitmap) is rebuilt only when the committed set
 * changes — never per frame (SPEC §9.2(5)). Pan/zoom only changes the [Matrix] the
 * cache is drawn with, so scrolling and pinching never re-rasterise committed ink.
 * The live stroke is drawn straight to the view each frame in canvas units through
 * the same transform, so capture and committed ink stay pixel-aligned.
 *
 * Width is modulated at render time: `width_cu * (0.3 + 0.7 * p)` (SPEC §9.2(6));
 * stored pressure is never touched.
 */
class LayerRenderer {

    private var canvasWidthCu = 0
    private var canvasHeightCu = 0

    private var cache: Bitmap? = null
    private var cacheDirty = true
    private var committed: List<RenderStroke> = emptyList()

    private val matrix = Matrix()
    private val strokePaint = newStrokePaint()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun setCanvasSize(widthCu: Int, heightCu: Int) {
        if (widthCu == canvasWidthCu && heightCu == canvasHeightCu) return
        canvasWidthCu = widthCu
        canvasHeightCu = heightCu
        cache?.recycle()
        cache = null
        cacheDirty = true
    }

    /** Replace the committed strokes and mark the cache for a rebuild. */
    fun setCommittedStrokes(strokes: List<RenderStroke>) {
        committed = strokes
        cacheDirty = true
    }

    fun release() {
        cache?.recycle()
        cache = null
    }

    /**
     * Draw the layer into [outCanvas] under [transform].
     *
     * @param liveStroke stride-5 points of the in-progress stroke (canvas units) or
     *   null when nothing is being drawn.
     */
    fun draw(
        outCanvas: Canvas,
        transform: CanvasTransform,
        liveStroke: FloatArray? = null,
        liveTool: String = "pen",
        liveColor: Int = Color.BLACK,
        liveWidthCu: Float = DEFAULT_WIDTH_CU,
    ) {
        rebuildCacheIfNeeded()

        matrix.reset()
        matrix.setScale(transform.scale, transform.scale)
        matrix.postTranslate(transform.tx, transform.ty)

        cache?.let { outCanvas.drawBitmap(it, matrix, bitmapPaint) }

        if (liveStroke != null && liveStroke.size >= PackedPoints.STRIDE) {
            outCanvas.save()
            outCanvas.concat(matrix)
            drawStroke(outCanvas, liveStroke, liveTool, liveColor, liveWidthCu)
            outCanvas.restore()
        }
    }

    /**
     * Stage 31: draw one extra overlay stroke (canvas units) through [transform], exactly
     * as the live stroke is drawn. [com.inkwell.ink.InkView] uses it for finished pen
     * strokes whose wet copy was dropped (pan/zoom, surface lost) until their dry copy
     * reaches the cache.
     */
    fun drawOverlayStroke(
        outCanvas: Canvas,
        transform: CanvasTransform,
        points: FloatArray,
        tool: String,
        color: Int,
        widthCu: Float,
    ) {
        if (points.size < PackedPoints.STRIDE) return
        matrix.reset()
        matrix.setScale(transform.scale, transform.scale)
        matrix.postTranslate(transform.tx, transform.ty)
        outCanvas.save()
        outCanvas.concat(matrix)
        drawStroke(outCanvas, points, tool, color, widthCu)
        outCanvas.restore()
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
            if (stroke.tool == "eraser") continue // eraser removes strokes at capture
            drawStroke(c, stroke.points, stroke.tool, stroke.color, stroke.widthCu)
        }
        cacheDirty = false
    }

    /** Draw one stroke with per-segment pressure-modulated width, in canvas units. */
    private fun drawStroke(
        c: Canvas,
        points: FloatArray,
        tool: String,
        color: Int,
        widthCu: Float,
    ) {
        val stride = PackedPoints.STRIDE
        val count = points.size / stride
        if (count == 0) return

        val alpha = if (tool == "marker") MARKER_ALPHA else 255
        strokePaint.color = color
        strokePaint.alpha = alpha

        if (count == 1) {
            strokePaint.strokeWidth = modulatedWidthCu(widthCu, tool, points[2])
            c.drawPoint(points[0], points[1], strokePaint)
            return
        }

        var i = 0
        while (i < count - 1) {
            drawSegment(c, points, i, tool, widthCu, strokePaint)
            i++
        }
    }

    companion object {
        const val DEFAULT_WIDTH_CU = 3f
        const val MARKER_ALPHA = 110
        const val MARKER_WIDTH_SCALE = 3f

        /**
         * The render-time width function (SPEC §9.2(6)): `width_cu * (0.3 + 0.7 * p)`,
         * scaled for the marker. Stage 31: shared with the front-buffered wet layer so the
         * wet pen stroke matches the dry one exactly.
         */
        fun modulatedWidthCu(widthCu: Float, tool: String, pressure: Float): Float {
            val widthScale = if (tool == "marker") MARKER_WIDTH_SCALE else 1f
            return widthCu * widthScale * (0.3f + 0.7f * pressure)
        }

        /**
         * Draw segment [index] → [index] + 1 of stride-5 [points] (canvas units) with the
         * segment's mean pressure. [paint] must already carry the colour/alpha and a
         * round cap and join. Shared by the dry cache, the live overlay and (Stage 31) the
         * wet layer.
         */
        fun drawSegment(c: Canvas, points: FloatArray, index: Int, tool: String, widthCu: Float, paint: Paint) {
            val stride = PackedPoints.STRIDE
            val a = index * stride
            val b = a + stride
            val p = (points[a + 2] + points[b + 2]) * 0.5f
            paint.strokeWidth = modulatedWidthCu(widthCu, tool, p)
            c.drawLine(points[a], points[a + 1], points[b], points[b + 1], paint)
        }

        /** A paint configured exactly like the one the dry cache strokes with. */
        fun newStrokePaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }
}
