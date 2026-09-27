package com.inkwell.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import com.inkwell.data.PageExtent
import com.inkwell.data.PageGrowth

/**
 * Stage 32 (ADR-0014 §3): paints the visible page grid **beneath** the pushed raster and
 * ink — the area outside the grid in a surround colour, a soft shadow under the grid, and
 * every page as paper with a subtle edge, all through the same [CanvasTransform] as ink.
 *
 * The paper stays white in both themes (it is the page the export sends on an opaque white
 * background, contract `coordinate-mapping`, and ink is dark); the surround, edge and
 * shadow come from the app's theme tokens ([setColors], wired by `CanvasScreen`).
 *
 * Stage 34 (ADR-0014 §2): with `ring` on, the **ghost ring** — the writable pages one page
 * deep around the grid in all eight directions ([PageGrowth.ringPages]) — is drawn as
 * faint paper with dashed edges, beneath the grid's shadow. It is absent on an axis whose
 * grid is already at the 8-page cap.
 */
class PageGridPainter {

    private val paperPaint = Paint().apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val shadowPaint = Paint().apply { style = Paint.Style.FILL; color = DEFAULT_SHADOW }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = EDGE_WIDTH_PX
        color = DEFAULT_EDGE
    }
    private var surroundColor = DEFAULT_SURROUND
    // Stage 34: ghost-ring pages — faint paper and a dashed edge.
    private val ringPaint = Paint().apply { style = Paint.Style.FILL; color = ringFill(Color.WHITE) }
    private val ringEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = EDGE_WIDTH_PX
        color = DEFAULT_EDGE
        pathEffect = DashPathEffect(floatArrayOf(RING_DASH_PX, RING_GAP_PX), 0f)
    }

    /** Theme colours (ARGB). */
    fun setColors(paper: Int, surround: Int, edge: Int, shadow: Int) {
        paperPaint.color = paper
        surroundColor = surround
        edgePaint.color = edge
        shadowPaint.color = shadow
        ringPaint.color = ringFill(paper)
        ringEdgePaint.color = edge
    }

    /**
     * Paint the surround, the ghost ring when [ring] is on (stage 34, `EXPANDABLE_CANVAS`),
     * and the grid's pages.
     */
    fun draw(
        canvas: Canvas,
        transform: CanvasTransform,
        pageW: Int,
        pageH: Int,
        extent: PageExtent,
        ring: Boolean = false,
    ) {
        canvas.drawColor(surroundColor)
        if (pageW <= 0 || pageH <= 0) return
        if (ring) drawRing(canvas, transform, pageW, pageH, extent)
        val left = transform.canvasToViewX(extent.leftCu(pageW).toFloat())
        val top = transform.canvasToViewY(extent.topCu(pageH).toFloat())
        val right = transform.canvasToViewX(extent.rightCu(pageW).toFloat())
        val bottom = transform.canvasToViewY(extent.bottomCu(pageH).toFloat())

        // A soft two-step drop shadow under the whole grid (fixed view pixels, any zoom).
        val baseAlpha = shadowPaint.alpha
        for ((offset, alphaScale) in SHADOW_STEPS) {
            shadowPaint.alpha = (baseAlpha * alphaScale).toInt()
            canvas.drawRect(left + offset, top + offset, right + offset, bottom + offset, shadowPaint)
        }
        shadowPaint.alpha = baseAlpha

        canvas.drawRect(left, top, right, bottom, paperPaint)

        // Each page's edge: vertical lines at every column boundary, horizontal lines at
        // every row boundary (the outer ones outline the grid).
        for (c in extent.minCol..extent.maxCol + 1) {
            val x = transform.canvasToViewX(c.toFloat() * pageW)
            canvas.drawLine(x, top, x, bottom, edgePaint)
        }
        for (r in extent.minRow..extent.maxRow + 1) {
            val y = transform.canvasToViewY(r.toFloat() * pageH)
            canvas.drawLine(left, y, right, y, edgePaint)
        }
    }

    private fun drawRing(canvas: Canvas, transform: CanvasTransform, pageW: Int, pageH: Int, extent: PageExtent) {
        for ((c, r) in PageGrowth.ringPages(extent)) {
            val l = transform.canvasToViewX(c.toFloat() * pageW)
            val t = transform.canvasToViewY(r.toFloat() * pageH)
            val rt = transform.canvasToViewX((c + 1).toFloat() * pageW)
            val b = transform.canvasToViewY((r + 1).toFloat() * pageH)
            canvas.drawRect(l, t, rt, b, ringPaint)
            val inset = EDGE_WIDTH_PX
            canvas.drawRect(l + inset, t + inset, rt - inset, b - inset, ringEdgePaint)
        }
    }

    companion object {
        /** Material 3 light `surfaceVariant` / `outlineVariant` / `scrim` fallbacks. */
        const val DEFAULT_SURROUND = 0xFFE7E0EC.toInt()
        const val DEFAULT_EDGE = 0xFFCAC4D0.toInt()
        const val DEFAULT_SHADOW = 0x33000000

        private const val EDGE_WIDTH_PX = 1.5f

        /** Stage 34: the ghost ring's faint paper alpha (of 255) and its dash pattern (view px). */
        private const val RING_FILL_ALPHA = 0x59
        private const val RING_DASH_PX = 10f
        private const val RING_GAP_PX = 8f

        private fun ringFill(paper: Int): Int = (paper and 0x00FFFFFF) or (RING_FILL_ALPHA shl 24)
        private val SHADOW_STEPS = listOf(2f to 1f, 5f to 0.5f)
    }
}
