package com.inkwell.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.inkwell.contracts.Annotation
import com.inkwell.contracts.Highlight
import com.inkwell.contracts.Text
import com.inkwell.contracts.Underline

/**
 * Renders agent annotations onto an agent/annotation layer.
 *
 * Drawn natively as of Stage 7: `highlight` (Stage 4), `text` and `underline` (Stage 7).
 * **Every other type renders as the contract's fallback** — a thin labelled rectangle
 * around its bounding box (contract `agent-output` §Versioning: "render an unknown
 * annotation type as a labelled rect around its bounding box rather than dropping it")
 * — so nothing the agent returns is silently dropped. Phase 2 replaces the fallbacks
 * with real drawings.
 *
 * Rendering rules (SPEC §6.3, contract `agent-output` §Rendering rules) — agent marks
 * must be visually distinct from user ink:
 *  - **constant stroke width / constant weight** (no pressure variation, no bold);
 *  - **70% opacity** (slight transparency);
 *  - the space **accent color** unless the annotation's own `color` is set.
 *
 * Geometry comes from [AnnotationGeometry] (pure NM→CU mapping, contract
 * `coordinate-mapping`): a `highlight` is a filled polygon at `points × canvas size`;
 * a `text` is placed with its em-box top-left at `at` and a text height of
 * `size × height_cu` CU; an `underline` is a constant-width polyline.
 *
 * The renderer is opacity-authoritative: it paints the 70% itself, so the owning
 * agent [com.inkwell.data.LayerEntity] is created at full (1.0) opacity to avoid
 * compounding the transparency.
 */
class AnnotationRenderer(
    /** The space accent color (ARGB int), used when an annotation has no `color`. */
    private val accentColor: Int,
    private val widthCu: Int = CoordinateMapping.DEFAULT_WIDTH_CU,
    private val heightCu: Int = CoordinateMapping.DEFAULT_HEIGHT_CU,
) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH_CU
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = FALLBACK_STROKE_WIDTH_CU
        strokeJoin = Paint.Join.MITER
    }

    /** Constant weight: the normal sans-serif face, never bold. */
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        typeface = Typeface.SANS_SERIF
        isFakeBoldText = false
    }

    /**
     * Draw [annotations] into [outCanvas] through [transform] (the same pan/zoom
     * transform ink is drawn with, so agent marks stay aligned with the user's strokes).
     * Every annotation is drawn: natively for the supported types, otherwise as the
     * labelled fallback rect.
     */
    fun draw(outCanvas: Canvas, transform: CanvasTransform, annotations: List<Annotation>) {
        if (annotations.isEmpty()) return

        val matrix = android.graphics.Matrix().apply {
            setScale(transform.scale, transform.scale)
            postTranslate(transform.tx, transform.ty)
        }
        outCanvas.save()
        outCanvas.concat(matrix)
        for (a in annotations) {
            when (a) {
                is Highlight -> drawHighlight(outCanvas, a)
                is Text -> drawText(outCanvas, a)
                is Underline -> drawPolyline(outCanvas, a.points, colorOf(a.color))
                else -> drawFallback(outCanvas, a)
            }
        }
        outCanvas.restore()
    }

    private fun drawHighlight(canvas: Canvas, highlight: Highlight) {
        if (highlight.points.size < 2) return
        val color = colorOf(highlight.color)
        val path = Path()
        highlight.points.forEachIndexed { i, p ->
            val x = CoordinateMapping.nmToCuX(p[0], widthCu).toFloat()
            val y = CoordinateMapping.nmToCuY(p[1], heightCu).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close() // a highlight is a closed region

        // Fill-only at 70% opacity — a highlighter. No pressure variation; the bbox is
        // exactly points × canvas size (SPEC §6.3, contract `coordinate-mapping`).
        fillPaint.color = color
        fillPaint.alpha = FILL_ALPHA
        canvas.drawPath(path, fillPaint)
    }

    /**
     * `text`: em-box top-left at `at` (NM→CU), text height `size × height_cu` CU,
     * agent color at 70% opacity, constant weight. The baseline sits `-ascent` below
     * the anchor so the glyphs' top lands at `at.y`.
     */
    private fun drawText(canvas: Canvas, text: Text) {
        if (text.text.isEmpty()) return
        val placement = AnnotationGeometry.textPlacement(text, widthCu, heightCu)
        textPaint.color = colorOf(text.color)
        textPaint.alpha = FILL_ALPHA
        textPaint.textSize = placement.sizeCu.toFloat()
        val baseline = placement.yCu.toFloat() - textPaint.fontMetrics.ascent
        canvas.drawText(text.text, placement.xCu.toFloat(), baseline, textPaint)
    }

    /** `underline` (and any polyline mark): constant [STROKE_WIDTH_CU] width, 70% opacity. */
    private fun drawPolyline(canvas: Canvas, points: List<List<Double>>, color: Int) {
        if (points.size < 2) return
        val path = Path()
        AnnotationGeometry.polylineCu(points, widthCu, heightCu).forEachIndexed { i, (x, y) ->
            if (i == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
        }
        linePaint.color = color
        linePaint.alpha = FILL_ALPHA
        canvas.drawPath(path, linePaint)
    }

    /**
     * Contract fallback for a type not drawn natively yet: a thin labelled rectangle
     * around the annotation's bounding box ([AnnotationGeometry.boundsCu]), labelled with
     * its `label` or its type. A degenerate (zero-extent) box is widened to
     * [MIN_FALLBACK_EXTENT_CU] so a point-like annotation stays visible.
     */
    private fun drawFallback(canvas: Canvas, annotation: Annotation) {
        val b = AnnotationGeometry.boundsCu(annotation, widthCu, heightCu)
        var left = b[0].toFloat()
        var top = b[1].toFloat()
        var right = (b[0] + b[2]).toFloat()
        var bottom = (b[1] + b[3]).toFloat()
        if (right - left < MIN_FALLBACK_EXTENT_CU) {
            val cx = (left + right) / 2f
            left = cx - MIN_FALLBACK_EXTENT_CU / 2f
            right = cx + MIN_FALLBACK_EXTENT_CU / 2f
        }
        if (bottom - top < MIN_FALLBACK_EXTENT_CU) {
            val cy = (top + bottom) / 2f
            top = cy - MIN_FALLBACK_EXTENT_CU / 2f
            bottom = cy + MIN_FALLBACK_EXTENT_CU / 2f
        }
        val color = colorOf(annotation.color)
        fallbackPaint.color = color
        fallbackPaint.alpha = FILL_ALPHA
        canvas.drawRect(left, top, right, bottom, fallbackPaint)

        // Label above the box (inside it when the box touches the top edge).
        textPaint.color = color
        textPaint.alpha = FILL_ALPHA
        textPaint.textSize = FALLBACK_LABEL_SIZE_CU
        val fm = textPaint.fontMetrics
        val labelBaseline = if (top - FALLBACK_LABEL_SIZE_CU >= 0f) {
            top - FALLBACK_LABEL_GAP_CU - fm.descent
        } else {
            top + FALLBACK_LABEL_GAP_CU - fm.ascent
        }
        // Keep the label on-canvas for the right-edge margin_note box.
        val labelX = left.coerceAtMost(widthCu - textPaint.measureText(AnnotationGeometry.fallbackLabel(annotation)))
            .coerceAtLeast(0f)
        canvas.drawText(AnnotationGeometry.fallbackLabel(annotation), labelX, labelBaseline, textPaint)
    }

    /** Annotation `color` (`#RRGGBB`) if set and parseable, else the space accent. */
    private fun colorOf(hex: String?): Int {
        if (hex == null) return accentColor
        return try {
            Color.parseColor(hex)
        } catch (_: IllegalArgumentException) {
            accentColor
        }
    }

    companion object {
        /** 70% opacity (SPEC §6.3). */
        const val OPACITY = 0.70f
        val FILL_ALPHA = (255 * OPACITY).toInt() // 178
        /** Constant agent stroke width in canvas units (no pressure variation). */
        const val STROKE_WIDTH_CU = 6f
        /** Thin outline for the contract fallback rect. */
        const val FALLBACK_STROKE_WIDTH_CU = 3f
        /** Minimum side of a fallback rect so point-like annotations stay visible. */
        const val MIN_FALLBACK_EXTENT_CU = 24f
        /** Text height of the fallback label, in canvas units. */
        const val FALLBACK_LABEL_SIZE_CU = 28f
        const val FALLBACK_LABEL_GAP_CU = 4f

        /** Fallback accent when a space has no color yet. */
        const val DEFAULT_ACCENT = 0xFF3B6EA5.toInt()

        /** Parse a space accent `#RRGGBB`, falling back to [DEFAULT_ACCENT]. */
        fun accentFrom(hex: String?): Int = try {
            if (hex == null) DEFAULT_ACCENT else Color.parseColor(hex)
        } catch (_: IllegalArgumentException) {
            DEFAULT_ACCENT
        }
    }
}
