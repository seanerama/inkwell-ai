package com.inkwell.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.inkwell.BuildConfig
import com.inkwell.contracts.Annotation
import com.inkwell.contracts.Arrow
import com.inkwell.contracts.Ellipse
import com.inkwell.contracts.Highlight
import com.inkwell.contracts.MarginNote
import com.inkwell.contracts.RectAnnotation
import com.inkwell.contracts.Strikethrough
import com.inkwell.contracts.Text
import com.inkwell.contracts.Underline
import com.inkwell.contracts.Path as PathAnnotation

/**
 * Renders agent annotations onto an agent/annotation layer.
 *
 * With the [fullVocabulary] kill-switch **ON** (default, Stage 9) all nine
 * `agent-output` v1 annotation types draw natively; with it **OFF** only `highlight`,
 * `text` and `underline` draw natively and the other six render as the contract's
 * labelled fallback rect (the Stage-7 behaviour), so nothing the agent returns is ever
 * silently dropped. The fallback also remains for any future/unknown type.
 *
 * Rendering rules (SPEC §6.3, contract `agent-output` §Rendering rules) — agent marks
 * must be visually distinct from user ink:
 *  - **constant stroke width / constant weight** (no pressure variation, no bold);
 *  - **70% opacity** ([FILL_ALPHA]);
 *  - the space **accent color** unless the annotation's own `color` is set;
 *  - never pressure-tapered.
 *
 * Geometry comes from [AnnotationGeometry] (pure NM→CU mapping, contract
 * `coordinate-mapping`): a `highlight` is a filled polygon at `points × canvas size`;
 * `text` is placed with its em-box top-left at `at`, word-wrapped at
 * `0.35 × width_cu`; an `underline`/`strikethrough` is a constant-width polyline;
 * an `arrow` is a shaft plus a filled triangular head; `margin_note`s are laid out in
 * a right-hand gutter (`0.18 × width_cu`, view-only, never exported).
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
    /**
     * Stage 9 kill-switch. ON: all nine types draw natively. OFF: only the Stage-7
     * native trio (highlight/text/underline) draws natively; the other six use the
     * labelled fallback box. Defaulted from [BuildConfig.FULL_VOCABULARY] so existing
     * call sites (and the pure geometry) need not know about the flag.
     */
    private val fullVocabulary: Boolean = BuildConfig.FULL_VOCABULARY,
) {

    /**
     * Number of annotations drawn as the labelled fallback box during the last [draw].
     * Reset at the start of every [draw]. Tests assert this is 0 for the full-vocabulary
     * fixture when [fullVocabulary] is ON (and 6 when it is OFF).
     */
    var fallbackCount: Int = 0
        private set

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

    /** Label fill paint (arrow/rect labels). */
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        typeface = Typeface.SANS_SERIF
        isFakeBoldText = false
    }

    /** White halo stroked behind a label so it stays legible over ink. */
    private val labelHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LABEL_HALO_STROKE_CU
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.SANS_SERIF
        isFakeBoldText = false
        color = Color.WHITE
    }

    /** Faint tint filling the right gutter behind margin notes. */
    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Thin leader line from a margin note to the page edge. */
    private val leaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LEADER_STROKE_CU
    }

    private val marginTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        typeface = Typeface.SANS_SERIF
        isFakeBoldText = false
    }

    /** Distinct dash for `strikethrough` so it reads differently from a solid underline. */
    private val strikeDashEffect = DashPathEffect(
        floatArrayOf(STRIKE_DASH_ON_CU, STRIKE_DASH_OFF_CU), 0f,
    )

    /**
     * Draw [annotations] into [outCanvas] through [transform] (the same pan/zoom
     * transform ink is drawn with, so agent marks stay aligned with the user's strokes).
     * Every annotation is drawn: natively for the supported types, otherwise as the
     * labelled fallback rect. [fallbackCount] reflects this call afterwards.
     */
    fun draw(outCanvas: Canvas, transform: CanvasTransform, annotations: List<Annotation>) {
        fallbackCount = 0
        if (annotations.isEmpty()) return

        val matrix = android.graphics.Matrix().apply {
            setScale(transform.scale, transform.scale)
            postTranslate(transform.tx, transform.ty)
        }
        outCanvas.save()
        outCanvas.concat(matrix)
        val marginNotes = mutableListOf<MarginNote>()
        for (a in annotations) {
            when (a) {
                is Highlight -> drawHighlight(outCanvas, a)
                is Text -> drawText(outCanvas, a)
                is Underline -> drawPolyline(outCanvas, a.points, colorOf(a.color), dashed = false)
                is Arrow -> if (fullVocabulary) drawArrow(outCanvas, a) else drawFallback(outCanvas, a)
                is Ellipse -> if (fullVocabulary) drawEllipse(outCanvas, a) else drawFallback(outCanvas, a)
                is RectAnnotation -> if (fullVocabulary) drawRect(outCanvas, a) else drawFallback(outCanvas, a)
                is Strikethrough ->
                    if (fullVocabulary) drawPolyline(outCanvas, a.points, colorOf(a.color), dashed = true)
                    else drawFallback(outCanvas, a)
                is PathAnnotation -> if (fullVocabulary) drawPath(outCanvas, a) else drawFallback(outCanvas, a)
                is MarginNote -> if (fullVocabulary) marginNotes.add(a) else drawFallback(outCanvas, a)
            }
        }
        if (fullVocabulary && marginNotes.isNotEmpty()) drawMarginNotes(outCanvas, marginNotes)
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
     * agent color at 70% opacity, constant weight. Word-wrapped at `0.35 × width_cu`
     * ([AnnotationGeometry.textWrapWidthCu]) so long answers do not run off the page;
     * the first line's baseline sits `-ascent` below the anchor so glyph tops land at
     * `at.y`, and subsequent lines advance by the font's line spacing.
     */
    private fun drawText(canvas: Canvas, text: Text) {
        if (text.text.isEmpty()) return
        val placement = AnnotationGeometry.textPlacement(text, widthCu, heightCu)
        textPaint.color = colorOf(text.color)
        textPaint.alpha = FILL_ALPHA
        textPaint.textSize = placement.sizeCu.toFloat()
        val maxWidth = AnnotationGeometry.textWrapWidthCu(widthCu)
        val lines = AnnotationGeometry.wrapWords(text.text, maxWidth) {
            textPaint.measureText(it).toDouble()
        }
        val fm = textPaint.fontMetrics
        val lineSpacing = textPaint.fontSpacing
        var baseline = placement.yCu.toFloat() - fm.ascent
        val x = placement.xCu.toFloat()
        for (line in lines) {
            canvas.drawText(line, x, baseline, textPaint)
            baseline += lineSpacing
        }
    }

    /**
     * `underline` / `strikethrough` (and any polyline mark): constant [STROKE_WIDTH_CU]
     * width, 70% opacity. [dashed] draws the strikethrough dash so it reads differently
     * from a solid underline.
     */
    private fun drawPolyline(canvas: Canvas, points: List<List<Double>>, color: Int, dashed: Boolean) {
        if (points.size < 2) return
        val path = Path()
        AnnotationGeometry.polylineCu(points, widthCu, heightCu).forEachIndexed { i, (x, y) ->
            if (i == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
        }
        linePaint.color = color
        linePaint.alpha = FILL_ALPHA
        linePaint.pathEffect = if (dashed) strikeDashEffect else null
        canvas.drawPath(path, linePaint)
        linePaint.pathEffect = null
    }

    /**
     * `arrow`: constant-width shaft `from`→`to` with a filled triangular head at `to`
     * ([AnnotationGeometry.arrowHeadCu], head length ≈ 3 × [STROKE_WIDTH_CU] in CU so it
     * scales with zoom). An optional `label` is drawn at the shaft midpoint offset
     * perpendicular, with a white halo for legibility over ink.
     */
    private fun drawArrow(canvas: Canvas, arrow: Arrow) {
        val color = colorOf(arrow.color)
        val fromX = CoordinateMapping.nmToCuX(arrow.from[0], widthCu).toFloat()
        val fromY = CoordinateMapping.nmToCuY(arrow.from[1], heightCu).toFloat()
        val toX = CoordinateMapping.nmToCuX(arrow.to[0], widthCu).toFloat()
        val toY = CoordinateMapping.nmToCuY(arrow.to[1], heightCu).toFloat()

        linePaint.color = color
        linePaint.alpha = FILL_ALPHA
        linePaint.pathEffect = null
        canvas.drawLine(fromX, fromY, toX, toY, linePaint)

        val head = AnnotationGeometry.arrowHeadCu(arrow.from, arrow.to, ARROW_HEAD_LEN_CU, widthCu, heightCu)
        val tri = Path().apply {
            moveTo(head.tip.first.toFloat(), head.tip.second.toFloat())
            lineTo(head.left.first.toFloat(), head.left.second.toFloat())
            lineTo(head.right.first.toFloat(), head.right.second.toFloat())
            close()
        }
        fillPaint.color = color
        fillPaint.alpha = FILL_ALPHA
        canvas.drawPath(tri, fillPaint)

        val label = arrow.label?.takeIf { it.isNotBlank() }
        if (label != null) {
            val (ax, ay) = AnnotationGeometry.arrowLabelAnchorCu(
                arrow.from, arrow.to, AnnotationGeometry.ARROW_LABEL_OFFSET_CU, widthCu, heightCu,
            )
            drawLabelWithHalo(canvas, label, ax.toFloat(), ay.toFloat(), color, centered = true)
        }
    }

    /** `ellipse`: stroke-only oval at `center` with radii `rx × width_cu`, `ry × height_cu`. */
    private fun drawEllipse(canvas: Canvas, ellipse: Ellipse) {
        val cx = CoordinateMapping.nmToCuX(ellipse.center[0], widthCu)
        val cy = CoordinateMapping.nmToCuY(ellipse.center[1], heightCu)
        val rx = ellipse.rx * widthCu
        val ry = ellipse.ry * heightCu
        linePaint.color = colorOf(ellipse.color)
        linePaint.alpha = FILL_ALPHA
        linePaint.pathEffect = null
        canvas.drawOval(
            RectF((cx - rx).toFloat(), (cy - ry).toFloat(), (cx + rx).toFloat(), (cy + ry).toFloat()),
            linePaint,
        )
    }

    /** `rect`: stroke-only rectangle; optional `label` above the top-left corner. */
    private fun drawRect(canvas: Canvas, rect: RectAnnotation) {
        val color = colorOf(rect.color)
        val left = (rect.x * widthCu).toFloat()
        val top = (rect.y * heightCu).toFloat()
        val right = ((rect.x + rect.w) * widthCu).toFloat()
        val bottom = ((rect.y + rect.h) * heightCu).toFloat()
        linePaint.color = color
        linePaint.alpha = FILL_ALPHA
        linePaint.pathEffect = null
        canvas.drawRect(left, top, right, bottom, linePaint)

        val label = rect.label?.takeIf { it.isNotBlank() }
        if (label != null) {
            val (lx, ly) = AnnotationGeometry.rectLabelAnchorCu(rect, widthCu, heightCu)
            labelPaint.textSize = LABEL_SIZE_CU
            val fm = labelPaint.fontMetrics
            // Baseline above the corner when there is room, else just inside it.
            val baseline = if (ly - LABEL_SIZE_CU >= 0f) {
                (ly - LABEL_GAP_CU).toFloat() - fm.descent
            } else {
                (ly + LABEL_GAP_CU).toFloat() - fm.ascent
            }
            drawLabelWithHalo(canvas, label, lx.toFloat(), baseline, color, centered = false)
        }
    }

    /** `path`: constant-width polyline, closed when `closed` is true (stroke only, no fill). */
    private fun drawPath(canvas: Canvas, ann: PathAnnotation) {
        if (ann.points.size < 2) return
        val path = Path()
        AnnotationGeometry.polylineCu(ann.points, widthCu, heightCu).forEachIndexed { i, (x, y) ->
            if (i == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
        }
        if (ann.closed) path.close()
        linePaint.color = colorOf(ann.color)
        linePaint.alpha = FILL_ALPHA
        linePaint.pathEffect = null
        canvas.drawPath(path, linePaint)
    }

    /**
     * `margin_note`s: laid out in a right-hand gutter of `0.18 × width_cu` CU outside the
     * page (faint tint), each note wrapped at the gutter width and anchored at
     * `y × height_cu`, with a thin leader line back to the page edge. Notes at similar
     * `y` stack downward without overlapping ([AnnotationGeometry.stackMarginNotesCu]).
     * The gutter is view-only — [CanvasExporter] never includes it (or the agent layer)
     * in the exported PNG, so `width_cu`/export width stay unchanged.
     */
    private fun drawMarginNotes(canvas: Canvas, notes: List<MarginNote>) {
        val pageRight = widthCu.toFloat()
        val gutterWidth = AnnotationGeometry.marginGutterWidthCu(widthCu).toFloat()
        val gutterRight = pageRight + gutterWidth

        // Faint tint fills the gutter behind the notes.
        gutterPaint.color = accentColor
        gutterPaint.alpha = GUTTER_TINT_ALPHA
        canvas.drawRect(pageRight, 0f, gutterRight, heightCu.toFloat(), gutterPaint)

        marginTextPaint.textSize = MARGIN_NOTE_TEXT_SIZE_CU
        val lineSpacing = marginTextPaint.fontSpacing
        val fm = marginTextPaint.fontMetrics
        val textLeft = pageRight + MARGIN_NOTE_PADDING_CU
        val maxTextWidth = (gutterWidth - 2f * MARGIN_NOTE_PADDING_CU).toDouble()

        // Wrap each note first so the stacking slot clears the tallest note (no overlap).
        val wrapped = notes.map { note ->
            AnnotationGeometry.wrapWords(note.text, maxTextWidth) {
                marginTextPaint.measureText(it).toDouble()
            }.ifEmpty { listOf("") }
        }
        val maxLines = wrapped.maxOf { it.size }
        val slotHeight = lineSpacing.toDouble() * maxLines + MARGIN_NOTE_GAP_CU
        val tops = AnnotationGeometry.stackMarginNotesCu(notes.map { it.y }, slotHeight, heightCu)

        notes.forEachIndexed { i, note ->
            val top = tops[i].toFloat()
            val color = colorOf(note.color)

            // Leader line from the page edge to the note's text, at the first line's middle.
            val leaderY = top + lineSpacing / 2f
            leaderPaint.color = color
            leaderPaint.alpha = FILL_ALPHA
            canvas.drawLine(pageRight, leaderY, textLeft, leaderY, leaderPaint)

            marginTextPaint.color = color
            marginTextPaint.alpha = FILL_ALPHA
            var baseline = top - fm.ascent
            for (line in wrapped[i]) {
                canvas.drawText(line, textLeft, baseline, marginTextPaint)
                baseline += lineSpacing
            }
        }
    }

    /** Draw [label] with a white halo behind it; [centered] centers it on [xCu]. */
    private fun drawLabelWithHalo(canvas: Canvas, label: String, xCu: Float, yCu: Float, color: Int, centered: Boolean) {
        labelPaint.textSize = LABEL_SIZE_CU
        labelHaloPaint.textSize = LABEL_SIZE_CU
        val x = if (centered) xCu - labelPaint.measureText(label) / 2f else xCu
        labelHaloPaint.color = Color.WHITE
        labelHaloPaint.alpha = FILL_ALPHA
        canvas.drawText(label, x, yCu, labelHaloPaint)
        labelPaint.color = color
        labelPaint.alpha = FILL_ALPHA
        canvas.drawText(label, x, yCu, labelPaint)
    }

    /**
     * Contract fallback for a type not drawn natively (an unknown/future type, or one of
     * the six non-native types when [fullVocabulary] is OFF): a thin labelled rectangle
     * around the annotation's bounding box ([AnnotationGeometry.boundsCu]), labelled with
     * its `label` or its type. A degenerate (zero-extent) box is widened to
     * [MIN_FALLBACK_EXTENT_CU] so a point-like annotation stays visible.
     */
    private fun drawFallback(canvas: Canvas, annotation: Annotation) {
        fallbackCount++
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
        /** Arrow-head length in CU (≈ 3 × [STROKE_WIDTH_CU]); scales with zoom under the transform. */
        const val ARROW_HEAD_LEN_CU = 18.0
        /** Thin outline for the contract fallback rect. */
        const val FALLBACK_STROKE_WIDTH_CU = 3f
        /** Minimum side of a fallback rect so point-like annotations stay visible. */
        const val MIN_FALLBACK_EXTENT_CU = 24f
        /** Text height of the fallback label, in canvas units. */
        const val FALLBACK_LABEL_SIZE_CU = 28f
        const val FALLBACK_LABEL_GAP_CU = 4f

        /** Arrow/rect label text height (CU) and the white halo stroke behind it. */
        const val LABEL_SIZE_CU = 32f
        const val LABEL_HALO_STROKE_CU = 6f
        const val LABEL_GAP_CU = 8f

        /** Strikethrough dash pattern (CU), distinct from the solid underline. */
        const val STRIKE_DASH_ON_CU = 24f
        const val STRIKE_DASH_OFF_CU = 16f

        /** Margin-note gutter: text height, inner padding, inter-note gap, and tint alpha. */
        const val MARGIN_NOTE_TEXT_SIZE_CU = 40f
        const val MARGIN_NOTE_PADDING_CU = 16f
        const val MARGIN_NOTE_GAP_CU = 12.0
        const val GUTTER_TINT_ALPHA = 30
        const val LEADER_STROKE_CU = 2f

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
