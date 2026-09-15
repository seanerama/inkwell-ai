package com.inkwell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkwell.contracts.Highlight
import com.inkwell.render.AnnotationRenderer
import com.inkwell.render.CanvasTransform
import com.inkwell.render.CoordinateMapping
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
}
