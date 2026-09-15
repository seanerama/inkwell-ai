package com.inkwell.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.inkwell.contracts.Annotation
import com.inkwell.contracts.Highlight

/**
 * Renders agent annotations onto an agent/annotation layer. **Stage 4 handles the
 * `highlight` type only** — the first agent geometry, so Stage 6 wires rather than
 * draws. Other annotation types are ignored here and land in later stages.
 *
 * Rendering rules (SPEC §6.3, contract `agent-output` §Rendering rules) — agent marks
 * must be visually distinct from user ink:
 *  - **constant stroke width** (no pressure variation);
 *  - **70% opacity** (slight transparency);
 *  - the space **accent color** unless the annotation's own `color` is set.
 *
 * A `highlight` is a freeform region: its normalized polygon points are mapped to
 * canvas units ([CoordinateMapping.nmToCu]) and drawn as a filled, translucent region
 * (like a highlighter). Fill-only keeps its bounding box exactly at
 * `points × canvas size` — no outward stroke that would grow the bbox. Constant stroke
 * width ([STROKE_WIDTH_CU]) is reserved for the line-type annotations added later.
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

    /**
     * Draw the `highlight` annotations in [annotations] into [outCanvas] through
     * [transform] (the same pan/zoom transform ink is drawn with, so agent marks stay
     * aligned with the user's strokes). Non-highlight annotations are skipped.
     */
    fun draw(outCanvas: Canvas, transform: CanvasTransform, annotations: List<Annotation>) {
        val highlights = annotations.filterIsInstance<Highlight>()
        if (highlights.isEmpty()) return

        val matrix = android.graphics.Matrix().apply {
            setScale(transform.scale, transform.scale)
            postTranslate(transform.tx, transform.ty)
        }
        outCanvas.save()
        outCanvas.concat(matrix)
        for (h in highlights) drawHighlight(outCanvas, h)
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
        /** Constant agent stroke width in canvas units (no pressure variation); reserved
         *  for the line-type annotations added in later stages. */
        const val STROKE_WIDTH_CU = 6f

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
