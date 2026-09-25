package com.inkwell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkwell.render.CanvasTransform
import com.inkwell.render.InkFixtures
import com.inkwell.render.LayerRenderer
import com.inkwell.render.LegacyLayerRenderer
import com.inkwell.render.RenderStroke
import com.inkwell.render.TileKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Stage 32: the tiled level-of-detail renderer against the **pre-stage-32** renderer
 * ([LegacyLayerRenderer], a verbatim test-only copy), on a single-page canvas.
 *
 *  - At scale 1 and at scale 0.5 the output must be **pixel-identical** (zero differing
 *    pixels, every channel including alpha), across integer and fractional pans and pans
 *    that put the page partly off-screen. By construction the single-page tile at these
 *    scales is LOD 0: the old page bitmap, rasterised and blitted the same way.
 *  - Per-tile invalidation (append on pen-up, erase, undo, re-add) must end in the same
 *    pixels as a full rebuild.
 *  - Below scale 0.5 the tiled renderer uses LOD 1 (rasterised at 0.5 px/CU, then scaled)
 *    where the old one scaled a 1 px/CU bitmap: not bit-identical by design. The measured
 *    difference is logged and bounded loosely (see [lod1_below_half_scale_is_close]).
 */
@RunWith(AndroidJUnit4::class)
class TiledRendererParityInstrumentedTest {

    private val viewW = 1600
    private val viewH = 2560
    private val strokes = InkFixtures.handwriting()

    private fun pixels(draw: (Canvas) -> Unit): IntArray {
        val bmp = Bitmap.createBitmap(viewW, viewH, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp))
        val px = IntArray(viewW * viewH)
        bmp.getPixels(px, 0, viewW, 0, 0, viewW, viewH)
        bmp.recycle()
        return px
    }

    private fun legacy(strokes: List<RenderStroke>, t: CanvasTransform): IntArray {
        val r = LegacyLayerRenderer()
        r.setCanvasSize(InkFixtures.PAGE_W, InkFixtures.PAGE_H)
        r.setCommittedStrokes(strokes)
        return try { pixels { r.draw(it, t) } } finally { r.release() }
    }

    private fun tiled(r: LayerRenderer, t: CanvasTransform): IntArray = pixels { r.draw(it, t) }

    private fun newTiled(strokes: List<RenderStroke>): LayerRenderer = LayerRenderer().apply {
        setCanvasSize(InkFixtures.PAGE_W, InkFixtures.PAGE_H)
        setCommittedStrokes(strokes)
    }

    private class Diff(val pixels: Int, val maxChannel: Int, val meanAbs: Double, val massOld: Long, val massNew: Long)

    private fun diff(a: IntArray, b: IntArray): Diff {
        var n = 0
        var max = 0
        var sum = 0L
        var massA = 0L
        var massB = 0L
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            massA += Color.alpha(x)
            massB += Color.alpha(y)
            if (x == y) continue
            n++
            for (shift in intArrayOf(24, 16, 8, 0)) {
                val d = abs(((x ushr shift) and 0xFF) - ((y ushr shift) and 0xFF))
                sum += d
                if (d > max) max = d
            }
        }
        return Diff(n, max, sum.toDouble() / (a.size * 4.0), massA, massB)
    }

    private fun assertIdentical(label: String, t: CanvasTransform, old: IntArray, new: IntArray) {
        val d = diff(old, new)
        assertEquals(
            "$label scale=${t.scale} tx=${t.tx} ty=${t.ty}: ${d.pixels} pixels differ (max channel diff ${d.maxChannel})",
            0, d.pixels,
        )
        assertTrue("$label: the fixture must actually draw ink", d.massOld > 0)
    }

    private val scale1 = listOf(
        CanvasTransform(1f, 0f, 0f),
        CanvasTransform(1f, -300f, -500f),
        CanvasTransform(1f, 13.25f, -7.5f),
        CanvasTransform(1f, -1200.5f, -1900.75f),
        CanvasTransform(1f, 400f, 300f),
    )
    private val scaleHalf = listOf(
        CanvasTransform(0.5f, 0f, 0f),
        CanvasTransform(0.5f, 100.5f, 37.25f),
        CanvasTransform(0.5f, -600f, -800f),
        CanvasTransform(0.5f, 250.3f, 11.7f),
    )

    @Test
    fun single_page_is_pixel_identical_at_scale_1() {
        val r = newTiled(strokes)
        try {
            for (t in scale1) {
                assertIdentical("scale 1", t, legacy(strokes, t), tiled(r, t))
                assertEquals(0, r.lastFrameLod)
                assertEquals(listOf(TileKey(LayerRenderer.DEFAULT_LAYER_ID, 0, 0, 0)), r.lastFrameTiles)
            }
        } finally {
            r.release()
        }
    }

    @Test
    fun single_page_is_pixel_identical_at_scale_half() {
        val r = newTiled(strokes)
        try {
            for (t in scaleHalf) {
                assertIdentical("scale 0.5", t, legacy(strokes, t), tiled(r, t))
                assertEquals(0, r.lastFrameLod)
            }
        } finally {
            r.release()
        }
    }

    @Test
    fun per_tile_invalidation_matches_a_full_rebuild() {
        val t1 = CanvasTransform(1f, -150.5f, -220.25f)
        val tHalf = CanvasTransform(0.5f, 30f, 12.5f)
        val r = LayerRenderer().apply { setCanvasSize(InkFixtures.PAGE_W, InkFixtures.PAGE_H) }
        try {
            // Pen-ups: append in chunks, drawing (at both LOD-0 scales) between them.
            var current = emptyList<RenderStroke>()
            for (chunk in strokes.chunked(37)) {
                current = current + chunk
                r.setCommittedStrokes(current.toList())
                tiled(r, t1)
                tiled(r, tHalf)
            }
            assertIdentical("appended", t1, legacy(current, t1), tiled(r, t1))
            // Erase a few strokes from the middle, then undo the last one.
            current = current.filterIndexed { i, _ -> i % 17 != 3 }
            r.setCommittedStrokes(current.toList())
            assertIdentical("erased", t1, legacy(current, t1), tiled(r, t1))
            current = current.dropLast(1)
            r.setCommittedStrokes(current.toList())
            assertIdentical("undone", tHalf, legacy(current, tHalf), tiled(r, tHalf))
            // Re-adding (a different order than the original) rebuilds correctly too.
            current = current + strokes.filterIndexed { i, _ -> i % 17 == 3 }.reversed()
            r.setCommittedStrokes(current.toList())
            assertIdentical("re-added", t1, legacy(current, t1), tiled(r, t1))
            // An unchanged list (a recomposition) changes nothing.
            r.setCommittedStrokes(current.toList())
            assertIdentical("unchanged", tHalf, legacy(current, tHalf), tiled(r, tHalf))
        } finally {
            r.release()
        }
    }

    /**
     * Below 0.5 the LOD-1 tile is rasterised at 0.5 px/CU and scaled by `scale / 0.5`; the
     * old renderer bilinear-sampled a 1 px/CU bitmap at `scale`. Anti-aliased edges
     * therefore differ (the new output is, if anything, less aliased). Accepted bounds:
     * total ink coverage (sum of alpha) within ±25 % of the old output, and a mean absolute
     * channel difference over the whole view below 4/255. The max per-channel difference is
     * logged, not bounded: a 1-2 px pen edge can legitimately move by most of a pixel.
     */
    @Test
    fun lod1_below_half_scale_is_close() {
        val r = newTiled(strokes)
        try {
            for (t in listOf(CanvasTransform(0.4f, 20f, 15f), CanvasTransform(0.3f, 120.5f, 60.25f))) {
                val old = legacy(strokes, t)
                val new = tiled(r, t)
                assertEquals(1, r.lastFrameLod)
                val d = diff(old, new)
                val ratio = d.massNew.toDouble() / d.massOld
                Log.i(
                    TAG,
                    "scale ${t.scale}: ${d.pixels} px differ, max channel diff ${d.maxChannel}, " +
                        "mean abs ${"%.3f".format(d.meanAbs)}, ink mass ratio ${"%.3f".format(ratio)}",
                )
                assertTrue("scale ${t.scale}: ink mass ratio $ratio", ratio in 0.75..1.25)
                assertTrue("scale ${t.scale}: mean abs diff ${d.meanAbs}", d.meanAbs < 4.0)
            }
        } finally {
            r.release()
        }
    }

    /** Stage 31 interplay: the dry copy counts as drawn only after a frame drew its tile. */
    @Test
    fun a_new_stroke_is_drawn_only_after_the_next_frame_draws_its_tile() {
        val t = CanvasTransform(1f, 0f, 0f)
        val r = newTiled(strokes.take(20))
        try {
            tiled(r, t)
            val wet = InkFixtures.stroke("wet", "pen", Color.BLACK, 3f, floatArrayOf(300f, 300f, 400f, 380f), 0.5f, 0.7f)
            r.setCommittedStrokes(strokes.take(20) + wet)
            assertFalse(r.wasDrawnInLastFrame(wet.points, "pen", 3f))
            tiled(r, t)
            assertTrue(r.wasDrawnInLastFrame(wet.points, "pen", 3f))
            // Off the page grid there is no tile to wait for.
            val off = InkFixtures.stroke("off", "pen", Color.BLACK, 3f, floatArrayOf(3000f, 300f, 3100f, 380f), 0.5f, 0.7f)
            r.setCommittedStrokes(strokes.take(20) + wet + off)
            tiled(r, t)
            assertTrue(r.wasDrawnInLastFrame(off.points, "pen", 3f))
        } finally {
            r.release()
        }
    }

    private companion object {
        const val TAG = "TiledParity"
    }
}
