package com.inkwell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkwell.contracts.Arrow
import com.inkwell.contracts.Ellipse
import com.inkwell.contracts.Highlight
import com.inkwell.contracts.MarginNote
import com.inkwell.contracts.RectAnnotation
import com.inkwell.contracts.Strikethrough
import com.inkwell.contracts.Text
import com.inkwell.contracts.Underline
import com.inkwell.contracts.Path as PathAnnotation
import com.inkwell.render.AnnotationGeometry
import com.inkwell.render.AnnotationRenderer
import com.inkwell.render.CanvasExporter
import com.inkwell.render.CanvasTransform
import com.inkwell.render.CoordinateMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Instrumented fixture-render test (emulator lane in release.yml): render the
 * `valid-full-vocabulary` highlight (a1) and assert its painted bounding box lands at
 * `points × canvas size` within one canvas unit.
 *
 * Rendered at identity transform onto a canvas-unit-sized bitmap, so 1 px = 1 CU and
 * the measured painted bbox is directly comparable to
 * [CoordinateMapping.nmPointsBoundsCu].
 */
@RunWith(AndroidJUnit4::class)
class AnnotationRenderInstrumentedTest {

    private val widthCu = CoordinateMapping.DEFAULT_WIDTH_CU // 2480
    private val heightCu = CoordinateMapping.DEFAULT_HEIGHT_CU // 3508

    // contracts/fixtures/agent-output/valid-full-vocabulary.json → annotation a1.
    private val a1Points = listOf(
        listOf(0.10, 0.20), listOf(0.40, 0.20), listOf(0.40, 0.30), listOf(0.10, 0.30),
    )

    @Test
    fun highlight_bbox_lands_at_points_times_canvas_size_within_one_cu() {
        val bmp = Bitmap.createBitmap(widthCu, heightCu, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val highlight = Highlight(id = "a1", points = a1Points)
        AnnotationRenderer(AnnotationRenderer.DEFAULT_ACCENT, widthCu, heightCu)
            .draw(canvas, CanvasTransform(scale = 1f, tx = 0f, ty = 0f), listOf(highlight))

        val expected = CoordinateMapping.nmPointsBoundsCu(a1Points, widthCu, heightCu)
        val expMinX = expected[0]
        val expMinY = expected[1]
        val expMaxX = expected[0] + expected[2]
        val expMaxY = expected[1] + expected[3]

        // Scan a window around the expected bbox for painted (non-white) pixels.
        val x0 = (expMinX - 20).toInt().coerceAtLeast(0)
        val y0 = (expMinY - 20).toInt().coerceAtLeast(0)
        val x1 = (expMaxX + 20).toInt().coerceAtMost(bmp.width)
        val y1 = (expMaxY + 20).toInt().coerceAtMost(bmp.height)
        val winW = x1 - x0
        val winH = y1 - y0
        val px = IntArray(winW * winH)
        bmp.getPixels(px, 0, winW, x0, y0, winW, winH)

        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (j in 0 until winH) {
            for (i in 0 until winW) {
                if (px[j * winW + i] != Color.WHITE) {
                    val gx = x0 + i
                    val gy = y0 + j
                    if (gx < minX) minX = gx
                    if (gy < minY) minY = gy
                    if (gx > maxX) maxX = gx
                    if (gy > maxY) maxY = gy
                }
            }
        }
        bmp.recycle()

        assertTrue("no painted pixels found", maxX >= minX && maxY >= minY)

        val tol = 1.0 + 1e-6 // one canvas unit (1 px = 1 CU at identity)
        assertTrue("minX $minX vs $expMinX", abs(minX - expMinX) <= tol)
        assertTrue("minY $minY vs $expMinY", abs(minY - expMinY) <= tol)
        assertTrue("maxX $maxX vs $expMaxX", abs(maxX - expMaxX) <= tol)
        assertTrue("maxY $maxY vs $expMaxY", abs(maxY - expMaxY) <= tol)
    }

    // --- Stage 12: agent-origin canvas renders opaque + ink-black ---

    private fun highlightCenter(): Pair<Int, Int> =
        (((0.10 + 0.40) / 2) * widthCu).toInt() to (((0.20 + 0.30) / 2) * heightCu).toInt()

    @Test
    fun agent_origin_canvas_renders_the_agent_layer_opaque_and_ink_black() {
        // Transparent bitmap so the read-back pixel reflects the PAINT's alpha directly.
        val bmp = Bitmap.createBitmap(widthCu, heightCu, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val highlight = Highlight(id = "a1", points = a1Points) // no explicit color
        AnnotationRenderer(AnnotationRenderer.DEFAULT_ACCENT, widthCu, heightCu, agentOriginCanvas = true)
            .draw(canvas, CanvasTransform(scale = 1f, tx = 0f, ty = 0f), listOf(highlight))

        val (cx, cy) = highlightCenter()
        val p = bmp.getPixel(cx, cy)
        bmp.recycle()

        assertEquals("opaque (alpha 255) on an agent-origin canvas", 255, Color.alpha(p))
        assertTrue(
            "ink-black, not the space accent",
            Color.red(p) < 0x30 && Color.green(p) < 0x30 && Color.blue(p) < 0x30,
        )
    }

    @Test
    fun user_canvas_agent_layer_stays_at_70_percent() {
        val bmp = Bitmap.createBitmap(widthCu, heightCu, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val highlight = Highlight(id = "a1", points = a1Points)
        // Default (agentOriginCanvas = false): the Stage-6 70% agent alpha.
        AnnotationRenderer(AnnotationRenderer.DEFAULT_ACCENT, widthCu, heightCu)
            .draw(canvas, CanvasTransform(scale = 1f, tx = 0f, ty = 0f), listOf(highlight))

        val (cx, cy) = highlightCenter()
        val alpha = Color.alpha(bmp.getPixel(cx, cy))
        bmp.recycle()

        assertEquals("70% agent alpha on a user canvas", AnnotationRenderer.FILL_ALPHA, alpha)
    }

    // --- Stage 9: full-vocabulary rendering ---

    // The nine `valid-full-vocabulary.json` annotations, inline so the on-device test
    // needs no filesystem access.
    private val fullVocabulary = listOf(
        Highlight(id = "a1", points = a1Points),
        Arrow(id = "a2", from = listOf(0.20, 0.40), to = listOf(0.70, 0.50), label = "blocks", color = "#D9480F"),
        Ellipse(id = "a3", center = listOf(0.50, 0.50), rx = 0.12, ry = 0.08),
        RectAnnotation(id = "a4", x = 0.10, y = 0.10, w = 0.30, h = 0.20),
        Underline(id = "a5", points = listOf(listOf(0.10, 0.50), listOf(0.60, 0.50))),
        Strikethrough(id = "a6", points = listOf(listOf(0.10, 0.55), listOf(0.60, 0.55))),
        PathAnnotation(id = "a7", points = listOf(listOf(0.10, 0.10), listOf(0.20, 0.30), listOf(0.40, 0.20)), closed = false),
        Text(id = "a8", at = listOf(0.70, 0.20), text = "check units", size = 0.02),
        MarginNote(id = "a9", y = 0.35, text = "This contradicts p.2"),
    )

    @Test
    fun full_vocabulary_fixture_renders_every_type_with_zero_fallbacks_when_flag_on() {
        val bmp = Bitmap.createBitmap(widthCu, heightCu, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val renderer = AnnotationRenderer(AnnotationRenderer.DEFAULT_ACCENT, widthCu, heightCu, fullVocabulary = true)
        renderer.draw(canvas, CanvasTransform(scale = 1f, tx = 0f, ty = 0f), fullVocabulary)
        bmp.recycle()

        assertEquals("all nine types draw natively — no fallback boxes", 0, renderer.fallbackCount)
    }

    @Test
    fun kill_switch_off_falls_back_to_labelled_boxes_for_the_six_non_native_types() {
        val bmp = Bitmap.createBitmap(widthCu, heightCu, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val renderer = AnnotationRenderer(AnnotationRenderer.DEFAULT_ACCENT, widthCu, heightCu, fullVocabulary = false)
        renderer.draw(canvas, CanvasTransform(scale = 1f, tx = 0f, ty = 0f), fullVocabulary)
        bmp.recycle()

        assertEquals("six non-native types fall back with the flag OFF", 6, renderer.fallbackCount)
    }

    @Test
    fun pixel_probe_finds_the_arrow_head_near_its_to_point() {
        val bmp = Bitmap.createBitmap(widthCu, heightCu, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val arrow = fullVocabulary.filterIsInstance<Arrow>().single()
        AnnotationRenderer(AnnotationRenderer.DEFAULT_ACCENT, widthCu, heightCu, fullVocabulary = true)
            .draw(canvas, CanvasTransform(scale = 1f, tx = 0f, ty = 0f), listOf(arrow))

        // The head's tip lands at the arrow's `to` = [0.70, 0.50] in CU.
        val head = AnnotationGeometry.arrowHeadCu(arrow.from, arrow.to, widthCu = widthCu, heightCu = heightCu)
        val tipX = head.tip.first.toInt()
        val tipY = head.tip.second.toInt()

        // Scan a small window around the tip for painted (non-white) pixels.
        val r = 30
        val x0 = (tipX - r).coerceAtLeast(0)
        val y0 = (tipY - r).coerceAtLeast(0)
        val x1 = (tipX + r).coerceAtMost(bmp.width)
        val y1 = (tipY + r).coerceAtMost(bmp.height)
        var found = false
        outer@ for (y in y0 until y1) {
            for (x in x0 until x1) {
                if (bmp.getPixel(x, y) != Color.WHITE) {
                    found = true
                    break@outer
                }
            }
        }
        bmp.recycle()
        assertTrue("arrow head not found near its `to` point ($tipX,$tipY)", found)
    }

    @Test
    fun gutter_tint_is_present_to_the_right_of_the_page() {
        val gutterWidth = AnnotationGeometry.marginGutterWidthCu(widthCu).toInt()
        val bmp = Bitmap.createBitmap(widthCu + gutterWidth, heightCu, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val note = fullVocabulary.filterIsInstance<MarginNote>().single()
        AnnotationRenderer(AnnotationRenderer.DEFAULT_ACCENT, widthCu, heightCu, fullVocabulary = true)
            .draw(canvas, CanvasTransform(scale = 1f, tx = 0f, ty = 0f), listOf(note))

        // A pixel in the middle of the gutter carries the faint tint, so it is not white.
        val gx = widthCu + gutterWidth / 2
        val gy = heightCu / 2
        val tinted = bmp.getPixel(gx, gy)
        bmp.recycle()
        assertTrue("gutter tint missing at ($gx,$gy)", tinted != Color.WHITE)
    }

    @Test
    fun export_png_width_is_still_1109_with_the_gutter_excluded() {
        // The gutter is a view-only concept: the exporter flattens only ink layers, so
        // the export width is unchanged (round(2480 * 1568/3508) = 1109).
        val result = CanvasExporter.export(widthCu, heightCu, emptyList())
        assertTrue(result is CanvasExporter.Result.Success)
        assertEquals(1109, result.export.w)
        assertEquals(1568, result.export.h)
        assertEquals(widthCu, result.export.widthCu)
    }
}
