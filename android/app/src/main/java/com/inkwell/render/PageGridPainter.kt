package com.inkwell.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.inkwell.data.PageExtent

/**
 * Stage 32 (ADR-0014 §3): paints the visible page grid **beneath** the pushed raster and
 * ink — the area outside the grid in a surround colour, a soft shadow under the grid, and
 * every page as paper with a subtle edge, all through the same [CanvasTransform] as ink.
 *
 * The paper stays white in both themes (it is the page the export sends on an opaque white
 * background, contract `coordinate-mapping`, and ink is dark); the surround, edge and
 * shadow come from the app's theme tokens ([setColors], wired by `CanvasScreen`). The
 * ghost ring of writable pages around the grid is stage 34, not drawn here.
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

    /** Theme colours (ARGB). */
    fun setColors(paper: Int, surround: Int, edge: Int, shadow: Int) {
        paperPaint.color = paper
        surroundColor = surround
        edgePaint.color = edge
        shadowPaint.color = shadow
    }

    fun draw(canvas: Canvas, transform: CanvasTransform, pageW: Int, pageH: Int, extent: PageExtent) {
        canvas.drawColor(surroundColor)
        if (pageW <= 0 || pageH <= 0) return
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

    companion object {
        /** Material 3 light `surfaceVariant` / `outlineVariant` / `scrim` fallbacks. */
        const val DEFAULT_SURROUND = 0xFFE7E0EC.toInt()
        const val DEFAULT_EDGE = 0xFFCAC4D0.toInt()
        const val DEFAULT_SHADOW = 0x33000000

        private const val EDGE_WIDTH_PX = 1.5f
        private val SHADOW_STEPS = listOf(2f to 1f, 5f to 0.5f)
    }
}
