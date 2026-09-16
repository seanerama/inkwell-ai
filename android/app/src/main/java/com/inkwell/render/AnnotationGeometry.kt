package com.inkwell.render

import com.inkwell.contracts.Annotation
import com.inkwell.contracts.Arrow
import com.inkwell.contracts.Ellipse
import com.inkwell.contracts.Highlight
import com.inkwell.contracts.MarginNote
import com.inkwell.contracts.Path
import com.inkwell.contracts.RectAnnotation
import com.inkwell.contracts.Strikethrough
import com.inkwell.contracts.Text
import com.inkwell.contracts.Underline
import kotlin.math.hypot

/**
 * Pure (Android-free) placement geometry for agent annotations, so the NM→CU mapping of
 * every annotation type is JVM-unit-testable independently of the drawing code in
 * [AnnotationRenderer]. Everything here is contract `coordinate-mapping` §Mapping back:
 * `cu_x = nm_x * width_cu`, `cu_y = nm_y * height_cu`, `text.size` → `size * height_cu`.
 *
 * Stage 9 adds the geometry for the full annotation vocabulary — arrow-head triangles,
 * label anchors, margin-note gutter stacking, and text-wrap width — all still pure so
 * they unit-test on the JVM. The `FULL_VOCABULARY` kill-switch is deliberately NOT
 * modelled here: the flag lives in [AnnotationRenderer]; this object only exposes which
 * wire `type`s each mode draws natively so tests can count fallbacks without Android.
 */
object AnnotationGeometry {

    // --- Stage 9 constants (all CU / fractions of canvas size) ---

    /** Arrow-head length in CU: ≈ 3 × [AnnotationRenderer.STROKE_WIDTH_CU] (6f). */
    const val ARROW_HEAD_LENGTH_CU = 18.0

    /** Arrow-head base half-width as a fraction of the head length (base = head length). */
    const val ARROW_HEAD_HALF_WIDTH_RATIO = 0.5

    /** Perpendicular offset (CU) of an arrow's midpoint label from the shaft. */
    const val ARROW_LABEL_OFFSET_CU = 24.0

    /** Right gutter width as a fraction of `width_cu` (SPEC §6.3; view-only, not exported). */
    const val MARGIN_GUTTER_FRACTION = 0.18

    /** `text` word-wrap width as a fraction of `width_cu`, so long answers stay on-page. */
    const val TEXT_WRAP_FRACTION = 0.35

    /** Types drawn natively before Stage 9 (kill-switch OFF); the other six fall back. */
    val STAGE7_NATIVE_TYPES: Set<String> = setOf("highlight", "text", "underline")

    /** Types drawn natively with `FULL_VOCABULARY` ON — the whole v1 vocabulary. */
    val FULL_NATIVE_TYPES: Set<String> = setOf(
        "highlight", "arrow", "ellipse", "rect", "underline",
        "strikethrough", "path", "text", "margin_note",
    )

    /** Where a `text` annotation is placed: its anchor in CU and its text height in CU. */
    data class TextPlacement(val xCu: Double, val yCu: Double, val sizeCu: Double)

    /** The three CU vertices of an arrow's filled triangular head (tip at the arrow's `to`). */
    data class ArrowHead(
        val tip: Pair<Double, Double>,
        val left: Pair<Double, Double>,
        val right: Pair<Double, Double>,
    )

    /**
     * `text.at` maps by NM→CU; `text.size` is a fraction of canvas height, so the text
     * height is `size * height_cu` CU (contract `coordinate-mapping`). The anchor is the
     * top-left of the text's em box.
     */
    fun textPlacement(
        text: Text,
        widthCu: Int = CoordinateMapping.DEFAULT_WIDTH_CU,
        heightCu: Int = CoordinateMapping.DEFAULT_HEIGHT_CU,
    ): TextPlacement = TextPlacement(
        xCu = CoordinateMapping.nmToCuX(text.at[0], widthCu),
        yCu = CoordinateMapping.nmToCuY(text.at[1], heightCu),
        sizeCu = CoordinateMapping.sizeToCu(text.size, heightCu),
    )

    /** A normalized polyline mapped to CU, point by point. */
    fun polylineCu(
        points: List<List<Double>>,
        widthCu: Int = CoordinateMapping.DEFAULT_WIDTH_CU,
        heightCu: Int = CoordinateMapping.DEFAULT_HEIGHT_CU,
    ): List<Pair<Double, Double>> = points.map { p ->
        CoordinateMapping.nmToCuX(p[0], widthCu) to CoordinateMapping.nmToCuY(p[1], heightCu)
    }

    /**
     * The axis-aligned bounding box `[x, y, w, h]` in CU of any annotation — the box the
     * contract's fallback rendering draws around a type the renderer does not yet draw
     * natively ("render an unknown annotation type as a labelled rect around its bounding
     * box", contract `agent-output` §Versioning). Point-based types are exactly
     * `points × canvas size`; `text` is a zero-width box of the text height at `at`;
     * `margin_note` is a point on the right edge at `y` (its gutter lies outside the
     * canvas bounds).
     */
    fun boundsCu(
        annotation: Annotation,
        widthCu: Int = CoordinateMapping.DEFAULT_WIDTH_CU,
        heightCu: Int = CoordinateMapping.DEFAULT_HEIGHT_CU,
    ): DoubleArray = when (annotation) {
        is Highlight -> CoordinateMapping.nmPointsBoundsCu(annotation.points, widthCu, heightCu)
        is Underline -> CoordinateMapping.nmPointsBoundsCu(annotation.points, widthCu, heightCu)
        is Strikethrough -> CoordinateMapping.nmPointsBoundsCu(annotation.points, widthCu, heightCu)
        is Path -> CoordinateMapping.nmPointsBoundsCu(annotation.points, widthCu, heightCu)
        is Arrow -> CoordinateMapping.nmPointsBoundsCu(listOf(annotation.from, annotation.to), widthCu, heightCu)
        is Ellipse -> {
            val cx = CoordinateMapping.nmToCuX(annotation.center[0], widthCu)
            val cy = CoordinateMapping.nmToCuY(annotation.center[1], heightCu)
            val rx = annotation.rx * widthCu
            val ry = annotation.ry * heightCu
            doubleArrayOf(cx - rx, cy - ry, 2 * rx, 2 * ry)
        }
        is RectAnnotation -> doubleArrayOf(
            annotation.x * widthCu,
            annotation.y * heightCu,
            annotation.w * widthCu,
            annotation.h * heightCu,
        )
        is Text -> {
            val p = textPlacement(annotation, widthCu, heightCu)
            doubleArrayOf(p.xCu, p.yCu, 0.0, p.sizeCu)
        }
        is MarginNote -> doubleArrayOf(
            widthCu.toDouble(),
            CoordinateMapping.nmToCuY(annotation.y, heightCu),
            0.0,
            0.0,
        )
    }

    /**
     * The CU vertices of an arrow's filled triangular head, tip at `to`. The head extends
     * [headLenCu] back along the shaft (`to`→`from` direction) and its base is
     * `headLenCu × 2 × [ARROW_HEAD_HALF_WIDTH_RATIO]` wide, perpendicular to the shaft.
     * A degenerate (`from == to`) arrow collapses to a point.
     */
    fun arrowHeadCu(
        from: List<Double>,
        to: List<Double>,
        headLenCu: Double = ARROW_HEAD_LENGTH_CU,
        widthCu: Int = CoordinateMapping.DEFAULT_WIDTH_CU,
        heightCu: Int = CoordinateMapping.DEFAULT_HEIGHT_CU,
    ): ArrowHead {
        val tipX = CoordinateMapping.nmToCuX(to[0], widthCu)
        val tipY = CoordinateMapping.nmToCuY(to[1], heightCu)
        val fromX = CoordinateMapping.nmToCuX(from[0], widthCu)
        val fromY = CoordinateMapping.nmToCuY(from[1], heightCu)
        val dx = tipX - fromX
        val dy = tipY - fromY
        val len = hypot(dx, dy)
        val tip = tipX to tipY
        if (len == 0.0) return ArrowHead(tip, tip, tip)
        val ux = dx / len
        val uy = dy / len
        val baseX = tipX - headLenCu * ux
        val baseY = tipY - headLenCu * uy
        val halfW = headLenCu * ARROW_HEAD_HALF_WIDTH_RATIO
        // Unit perpendicular to the shaft.
        val px = -uy
        val py = ux
        return ArrowHead(
            tip = tip,
            left = (baseX + halfW * px) to (baseY + halfW * py),
            right = (baseX - halfW * px) to (baseY - halfW * py),
        )
    }

    /**
     * The CU point where an arrow's midpoint `label` sits: the shaft midpoint offset
     * [offsetCu] perpendicular to the shaft (toward the up-left of the travel direction),
     * so the label clears the line and its white halo reads over ink.
     */
    fun arrowLabelAnchorCu(
        from: List<Double>,
        to: List<Double>,
        offsetCu: Double = ARROW_LABEL_OFFSET_CU,
        widthCu: Int = CoordinateMapping.DEFAULT_WIDTH_CU,
        heightCu: Int = CoordinateMapping.DEFAULT_HEIGHT_CU,
    ): Pair<Double, Double> {
        val fromX = CoordinateMapping.nmToCuX(from[0], widthCu)
        val fromY = CoordinateMapping.nmToCuY(from[1], heightCu)
        val toX = CoordinateMapping.nmToCuX(to[0], widthCu)
        val toY = CoordinateMapping.nmToCuY(to[1], heightCu)
        val midX = (fromX + toX) / 2.0
        val midY = (fromY + toY) / 2.0
        val dx = toX - fromX
        val dy = toY - fromY
        val len = hypot(dx, dy)
        if (len == 0.0) return midX to midY
        val px = -(dy / len)
        val py = dx / len
        // Subtract so a left→right shaft puts the label above the line.
        return (midX - offsetCu * px) to (midY - offsetCu * py)
    }

    /**
     * The CU point above a `rect`'s top-left corner where its `label` is anchored — the
     * corner itself; the renderer lifts the baseline by a small gap so text sits above.
     */
    fun rectLabelAnchorCu(
        rect: RectAnnotation,
        widthCu: Int = CoordinateMapping.DEFAULT_WIDTH_CU,
        heightCu: Int = CoordinateMapping.DEFAULT_HEIGHT_CU,
    ): Pair<Double, Double> =
        CoordinateMapping.nmToCuX(rect.x, widthCu) to CoordinateMapping.nmToCuY(rect.y, heightCu)

    /** The right gutter width in CU (`[MARGIN_GUTTER_FRACTION] × width_cu`). View-only. */
    fun marginGutterWidthCu(widthCu: Int = CoordinateMapping.DEFAULT_WIDTH_CU): Double =
        MARGIN_GUTTER_FRACTION * widthCu

    /** The `text` word-wrap width in CU (`[TEXT_WRAP_FRACTION] × width_cu`). */
    fun textWrapWidthCu(widthCu: Int = CoordinateMapping.DEFAULT_WIDTH_CU): Double =
        TEXT_WRAP_FRACTION * widthCu

    /**
     * Non-overlapping CU top-y for each margin note, **in input order**. Notes are placed
     * top-to-bottom at their requested `y × height_cu`; any note that would overlap the
     * previous one (closer than [lineHeightCu]) is pushed down to clear it, so notes whose
     * `y` values are within ~0.02 of each other stack instead of colliding.
     */
    fun stackMarginNotesCu(
        ysNm: List<Double>,
        lineHeightCu: Double,
        heightCu: Int = CoordinateMapping.DEFAULT_HEIGHT_CU,
    ): List<Double> {
        val order = ysNm.indices.sortedBy { ysNm[it] }
        val placed = DoubleArray(ysNm.size)
        var lastTop = Double.NEGATIVE_INFINITY
        for (idx in order) {
            val desired = CoordinateMapping.nmToCuY(ysNm[idx], heightCu)
            val top = maxOf(desired, lastTop + lineHeightCu)
            placed[idx] = top
            lastTop = top
        }
        return placed.toList()
    }

    /**
     * Greedy word-wrap: break [text] into lines no wider than [maxWidthCu], measured by
     * [measure] (the renderer passes `Paint.measureText`; tests pass a pure per-char
     * estimate). A single word wider than [maxWidthCu] takes its own line rather than
     * being dropped.
     */
    fun wrapWords(
        text: String,
        maxWidthCu: Double,
        measure: (String) -> Double,
    ): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            if (current.isEmpty()) {
                current.append(word)
            } else {
                val candidate = "$current $word"
                if (measure(candidate) <= maxWidthCu) {
                    current = StringBuilder(candidate)
                } else {
                    lines.add(current.toString())
                    current = StringBuilder(word)
                }
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }

    /**
     * How many of [annotations] would render as the fallback box given the set of
     * [nativeTypes] the renderer draws natively (e.g. [FULL_NATIVE_TYPES] with the
     * kill-switch ON, [STAGE7_NATIVE_TYPES] with it OFF). Pure mirror of the renderer's
     * routing so JVM tests can assert "zero fallbacks with the full vocabulary".
     */
    fun fallbackCountFor(annotations: List<Annotation>, nativeTypes: Set<String>): Int =
        annotations.count { typeName(it) !in nativeTypes }

    /** The wire `type` name of an annotation (the fallback label when it has no `label`). */
    fun typeName(annotation: Annotation): String = when (annotation) {
        is Highlight -> "highlight"
        is Arrow -> "arrow"
        is Ellipse -> "ellipse"
        is RectAnnotation -> "rect"
        is Underline -> "underline"
        is Strikethrough -> "strikethrough"
        is Path -> "path"
        is Text -> "text"
        is MarginNote -> "margin_note"
    }

    /** The label shown on a fallback rect: the annotation's `label`, else its type. */
    fun fallbackLabel(annotation: Annotation): String =
        annotation.label?.takeIf { it.isNotBlank() } ?: typeName(annotation)
}
