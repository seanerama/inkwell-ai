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
}
