package com.inkwell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkwell.data.PageExtent
import com.inkwell.render.CanvasTransform
import com.inkwell.render.InkFixtures
import com.inkwell.render.LayerRenderer
import com.inkwell.render.RenderStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 32: a canvas with a 3 × 3 page grid and ink on every page renders all its tiles,
 * and the tile cache stays under its byte budget while zooming and panning over it.
 */
@RunWith(AndroidJUnit4::class)
class TiledRendererBudgetInstrumentedTest {

    private val w = InkFixtures.PAGE_W
    private val h = InkFixtures.PAGE_H
    private val viewW = 1600
    private val viewH = 2560
    private val grid = PageExtent(-1, 1, -1, 1)

    /** Handwriting on every page, plus a solid bar through each page's centre. */
    private val strokes: List<RenderStroke> = buildList {
        for (row in -1..1) for (col in -1..1) {
            val x0 = col * w.toFloat()
            val y0 = row * h.toFloat()
            addAll(InkFixtures.handwriting(seed = 1000L + col * 10 + row, x0 = x0 + 100f, y0 = y0 + 100f, w = w - 200f, h = h - 200f, idPrefix = "p${col}_${row}_").take(120))
            add(
                InkFixtures.stroke(
                    "bar_${col}_$row", "pen", Color.BLACK, 300f,
                    floatArrayOf(x0 + w / 2f - 400f, y0 + h / 2f, x0 + w / 2f + 400f, y0 + h / 2f), 1f, 1f,
                ),
            )
        }
    }

    private fun renderer(budget: Long = LayerRenderer.DEFAULT_BUDGET_BYTES) = LayerRenderer(budgetBytes = budget).apply {
        setCanvasSize(w, h)
        setPageExtent(grid)
        setCommittedStrokes(strokes)
    }

    private fun frame(r: LayerRenderer, t: CanvasTransform): Bitmap {
        val bmp = Bitmap.createBitmap(viewW, viewH, Bitmap.Config.ARGB_8888)
        r.draw(Canvas(bmp), t)
        return bmp
    }

    private fun alphaAtCu(bmp: Bitmap, t: CanvasTransform, xCu: Float, yCu: Float): Int {
        val x = t.canvasToViewX(xCu).toInt()
        val y = t.canvasToViewY(yCu).toInt()
        return Color.alpha(bmp.getPixel(x, y))
    }

    @Test
    fun whole_grid_zoomed_out_draws_all_nine_page_tiles_under_budget() {
        val r = renderer()
        try {
            val s = 0.2f
            val t = CanvasTransform(s, w * s + 40f, h * s + 40f)
            val bmp = frame(r, t)
            assertEquals(2, r.lastFrameLod)
            assertEquals("one tile per page", 9, r.lastFrameTiles.size)
            assertEquals(9, r.lastFrameTiles.map { it.col to it.row }.toSet().size)
            assertTrue("cache ${r.cachedBytes} B", r.cachedBytes <= r.budgetBytes)
            for (row in -1..1) for (col in -1..1) {
                val a = alphaAtCu(bmp, t, col * w + w / 2f, row * h + h / 2f)
                assertTrue("page ($col,$row) shows its ink (alpha $a)", a > 200)
            }
            bmp.recycle()
        } finally {
            r.release()
        }
    }

    @Test
    fun zooming_and_panning_over_the_grid_stays_under_budget() {
        val r = renderer()
        try {
            var frames = 0
            var maxBytes = 0L
            for (scale in listOf(1f, 0.51f, 0.3f, 2f)) {
                for (row in -1..1) for (col in -1..1) {
                    // Centre the view on this page's centre, then on its bottom-right corner.
                    for ((fx, fy) in listOf(0.5f to 0.5f, 1f to 1f)) {
                        val cx = (col + fx) * w
                        val cy = (row + fy) * h
                        val t = CanvasTransform(scale, viewW / 2f - cx * scale, viewH / 2f - cy * scale)
                        val bmp = frame(r, t)
                        frames++
                        maxBytes = maxOf(maxBytes, r.cachedBytes)
                        assertTrue(
                            "scale $scale page ($col,$row): cache ${r.cachedBytes} B over ${r.budgetBytes} B",
                            r.cachedBytes <= r.budgetBytes,
                        )
                        if (fx == 0.5f) {
                            val a = alphaAtCu(bmp, t, cx, cy)
                            assertTrue("scale $scale page ($col,$row) centre ink (alpha $a)", a > 200)
                        }
                        bmp.recycle()
                    }
                }
            }
            Log.i("TiledBudget", "$frames frames, peak tile cache ${maxBytes / (1024 * 1024)} MB of ${r.budgetBytes / (1024 * 1024)} MB")
        } finally {
            r.release()
        }
    }

    @Test
    fun a_small_budget_evicts_instead_of_growing() {
        val budget = 12L * 1024 * 1024
        val r = renderer(budget)
        try {
            for (row in -1..1) for (col in -1..1) {
                val cx = (col + 0.5f) * w
                val cy = (row + 0.5f) * h
                val t = CanvasTransform(1f, viewW / 2f - cx, viewH / 2f - cy)
                val bmp = frame(r, t)
                assertTrue("cache ${r.cachedBytes} B over $budget B", r.cachedBytes <= budget)
                assertTrue(alphaAtCu(bmp, t, cx, cy) > 200)
                bmp.recycle()
            }
        } finally {
            r.release()
        }
    }
}
