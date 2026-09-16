package com.inkwell.render

import com.inkwell.contracts.AgentOutputContract
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * JVM unit tests for the pure NM→CU placement of every annotation type (Stage 7):
 *  - `text` placement maps `at` and `size` exactly for the default canvas
 *    (`at × canvas size`, `size × height_cu`; contract `coordinate-mapping`);
 *  - the fallback bounding box of each annotation in the frozen
 *    `valid-full-vocabulary.json` fixture lands within 1 CU of `points × canvas size`,
 *    so the labelled fallback rect never drifts from the agent's geometry.
 */
class AnnotationGeometryTest {

    private val w = CoordinateMapping.DEFAULT_WIDTH_CU // 2480
    private val h = CoordinateMapping.DEFAULT_HEIGHT_CU // 3508

    private val fixtureFile: File = run {
        val candidates = listOf(
            File("../../contracts/fixtures/agent-output/valid-full-vocabulary.json"), // cwd = android/app
            File("../contracts/fixtures/agent-output/valid-full-vocabulary.json"),
            File("contracts/fixtures/agent-output/valid-full-vocabulary.json"),
        )
        candidates.firstOrNull { it.isFile }
            ?: error("valid-full-vocabulary.json not found (looked in: ${candidates.joinToString { it.absolutePath }})")
    }

    private val fixture: List<Annotation> by lazy {
        AgentOutputContract.parse(fixtureFile.readText()).annotations
    }

    @Test
    fun text_placement_maps_at_and_size_exactly_for_the_default_canvas() {
        // Fixture a8: at [0.70, 0.20], size 0.02.
        val a8 = fixture.filterIsInstance<Text>().single { it.id == "a8" }
        val p = AnnotationGeometry.textPlacement(a8, w, h)
        assertEquals(0.70 * w, p.xCu, 1e-9) // 1736.0
        assertEquals(0.20 * h, p.yCu, 1e-9) // 701.6
        assertEquals(0.02 * h, p.sizeCu, 1e-9) // 70.16
        // Round-trips through the shared mapping helpers.
        assertEquals(CoordinateMapping.nmToCuX(0.70, w), p.xCu, 0.0)
        assertEquals(CoordinateMapping.nmToCuY(0.20, h), p.yCu, 0.0)
        assertEquals(CoordinateMapping.sizeToCu(0.02, h), p.sizeCu, 0.0)
    }

    @Test
    fun text_placement_for_the_owner_acceptance_answer() {
        // "10" beside "what is 1+9=?" at [0.42, 0.18], size 0.02 (the ask smoke fixture).
        val t = Text(id = "t1", at = listOf(0.42, 0.18), text = "10", size = 0.02)
        val p = AnnotationGeometry.textPlacement(t, w, h)
        assertEquals(1041.6, p.xCu, 1e-9)
        assertEquals(631.44, p.yCu, 1e-9)
        assertEquals(70.16, p.sizeCu, 1e-9)
    }

    @Test
    fun underline_polyline_maps_each_point_by_nm_to_cu() {
        val a5 = fixture.filterIsInstance<Underline>().single { it.id == "a5" }
        val line = AnnotationGeometry.polylineCu(a5.points, w, h)
        assertEquals(2, line.size)
        assertEquals(0.10 * w, line[0].first, 1e-9)
        assertEquals(0.50 * h, line[0].second, 1e-9)
        assertEquals(0.60 * w, line[1].first, 1e-9)
        assertEquals(0.50 * h, line[1].second, 1e-9)
    }

    /** The expected bbox straight from the fixture's geometry × canvas size. */
    private fun expectedBounds(a: Annotation): DoubleArray = when (a) {
        is Highlight -> bboxOf(a.points)
        is Underline -> bboxOf(a.points)
        is Strikethrough -> bboxOf(a.points)
        is Path -> bboxOf(a.points)
        is Arrow -> bboxOf(listOf(a.from, a.to))
        is Ellipse -> doubleArrayOf(
            (a.center[0] - a.rx) * w, (a.center[1] - a.ry) * h, 2 * a.rx * w, 2 * a.ry * h,
        )
        is RectAnnotation -> doubleArrayOf(a.x * w, a.y * h, a.w * w, a.h * h)
        is Text -> doubleArrayOf(a.at[0] * w, a.at[1] * h, 0.0, a.size * h)
        is MarginNote -> doubleArrayOf(w.toDouble(), a.y * h, 0.0, 0.0)
    }

    private fun bboxOf(points: List<List<Double>>): DoubleArray {
        val xs = points.map { it[0] * w }
        val ys = points.map { it[1] * h }
        return doubleArrayOf(xs.min(), ys.min(), xs.max() - xs.min(), ys.max() - ys.min())
    }

    @Test
    fun fallback_bbox_of_every_fixture_annotation_is_within_one_cu_of_points_times_canvas_size() {
        assertEquals("the full-vocabulary fixture has all nine types", 9, fixture.size)
        for (a in fixture) {
            val got = AnnotationGeometry.boundsCu(a, w, h)
            val exp = expectedBounds(a)
            for (i in 0 until 4) {
                assertTrue(
                    "${a.id} (${AnnotationGeometry.typeName(a)}) bbox[$i]: ${got[i]} vs ${exp[i]}",
                    abs(got[i] - exp[i]) <= 1.0,
                )
            }
        }
    }

    @Test
    fun fallback_label_is_the_label_or_else_the_type() {
        val a2 = fixture.single { it.id == "a2" } // arrow with label "blocks"
        assertEquals("blocks", AnnotationGeometry.fallbackLabel(a2))
        val a3 = fixture.single { it.id == "a3" } // ellipse, no label
        assertEquals("ellipse", AnnotationGeometry.fallbackLabel(a3))
        val a9 = fixture.single { it.id == "a9" }
        assertEquals("margin_note", AnnotationGeometry.fallbackLabel(a9))
    }

    // --- Stage 9: full-vocabulary geometry ---

    @Test
    fun arrow_head_triangle_is_exact_for_a_horizontal_arrow() {
        // A left→right horizontal arrow so the head vertices come out clean.
        val head = AnnotationGeometry.arrowHeadCu(
            from = listOf(0.0, 0.5),
            to = listOf(0.5, 0.5),
            headLenCu = 18.0,
            widthCu = w,
            heightCu = h,
        )
        // Tip at `to`.
        assertEquals(0.5 * w, head.tip.first, 1e-9) // 1240.0
        assertEquals(0.5 * h, head.tip.second, 1e-9) // 1754.0
        // Base is 18 CU back along the shaft; half-width 9 CU either side (perp = ±y).
        assertEquals(1240.0 - 18.0, head.left.first, 1e-9) // 1222.0
        assertEquals(1754.0 + 9.0, head.left.second, 1e-9) // 1763.0
        assertEquals(1240.0 - 18.0, head.right.first, 1e-9) // 1222.0
        assertEquals(1754.0 - 9.0, head.right.second, 1e-9) // 1745.0
    }

    @Test
    fun arrow_head_tip_is_always_the_arrow_to_point() {
        // Fixture a2: from [0.20,0.40] to [0.70,0.50] — the tip lands exactly at `to`.
        val a2 = fixture.filterIsInstance<Arrow>().single { it.id == "a2" }
        val head = AnnotationGeometry.arrowHeadCu(a2.from, a2.to, widthCu = w, heightCu = h)
        assertEquals(0.70 * w, head.tip.first, 1e-9)
        assertEquals(0.50 * h, head.tip.second, 1e-9)
    }

    @Test
    fun degenerate_arrow_head_collapses_to_the_point() {
        val head = AnnotationGeometry.arrowHeadCu(listOf(0.3, 0.3), listOf(0.3, 0.3), widthCu = w, heightCu = h)
        assertEquals(head.tip, head.left)
        assertEquals(head.tip, head.right)
    }

    @Test
    fun arrow_label_anchor_is_the_midpoint_offset_perpendicular() {
        // Horizontal arrow: midpoint (620, 1754); offset 24 CU "above" (−y).
        val anchor = AnnotationGeometry.arrowLabelAnchorCu(
            from = listOf(0.0, 0.5),
            to = listOf(0.5, 0.5),
            offsetCu = 24.0,
            widthCu = w,
            heightCu = h,
        )
        assertEquals(0.25 * w, anchor.first, 1e-9) // 620.0
        assertEquals(1754.0 - 24.0, anchor.second, 1e-9) // 1730.0
    }

    @Test
    fun rect_label_anchor_is_the_top_left_corner() {
        // Fixture a4: rect x 0.10, y 0.10.
        val a4 = fixture.filterIsInstance<RectAnnotation>().single { it.id == "a4" }
        val anchor = AnnotationGeometry.rectLabelAnchorCu(a4, w, h)
        assertEquals(0.10 * w, anchor.first, 1e-9) // 248.0
        assertEquals(0.10 * h, anchor.second, 1e-9) // 350.8
    }

    @Test
    fun margin_notes_within_two_hundredths_stack_without_overlapping() {
        val lineHeight = 60.0
        // Three notes within 0.02 of each other in unsorted order.
        val ys = listOf(0.35, 0.36, 0.355)
        val tops = AnnotationGeometry.stackMarginNotesCu(ys, lineHeight, h)
        assertEquals(3, tops.size)
        // The first note keeps its requested y; the returned list is in INPUT order.
        assertEquals(0.35 * h, tops[0], 1e-9)
        // Sorted top-to-bottom, consecutive notes never sit closer than one line height.
        val sorted = tops.sorted()
        for (i in 1 until sorted.size) {
            assertTrue(
                "notes overlap: ${sorted[i - 1]} then ${sorted[i]}",
                sorted[i] - sorted[i - 1] >= lineHeight - 1e-9,
            )
        }
    }

    @Test
    fun text_wrap_width_is_thirty_five_percent_of_width_cu() {
        assertEquals(0.35 * w, AnnotationGeometry.textWrapWidthCu(w), 1e-9) // 868.0
        assertEquals(0.18 * w, AnnotationGeometry.marginGutterWidthCu(w), 1e-9) // 446.4
    }

    @Test
    fun wrap_words_breaks_greedily_on_a_pure_measure() {
        val lines = AnnotationGeometry.wrapWords("This contradicts p.2", 120.0) { it.length * 10.0 }
        assertEquals(listOf("This", "contradicts", "p.2"), lines)
    }

    @Test
    fun full_vocabulary_fixture_renders_with_zero_fallbacks_when_the_flag_is_on() {
        // Pure mirror of the renderer's routing (the flag itself lives in
        // AnnotationRenderer; here we assert against the native-type sets).
        assertEquals(9, fixture.size)
        assertEquals(0, AnnotationGeometry.fallbackCountFor(fixture, AnnotationGeometry.FULL_NATIVE_TYPES))
        // With the kill-switch OFF, the six non-native types fall back.
        assertEquals(6, AnnotationGeometry.fallbackCountFor(fixture, AnnotationGeometry.STAGE7_NATIVE_TYPES))
    }
}
