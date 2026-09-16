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

/**
 * Pure (Android-free) placement geometry for agent annotations, so the NM→CU mapping of
 * every annotation type is JVM-unit-testable independently of the drawing code in
 * [AnnotationRenderer]. Everything here is contract `coordinate-mapping` §Mapping back:
 * `cu_x = nm_x * width_cu`, `cu_y = nm_y * height_cu`, `text.size` → `size * height_cu`.
 */
object AnnotationGeometry {

    /** Where a `text` annotation is placed: its anchor in CU and its text height in CU. */
    data class TextPlacement(val xCu: Double, val yCu: Double, val sizeCu: Double)

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
