package com.inkwell.ink

import com.inkwell.data.PackedPoints
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 31: the wet layer's damage rect covers the new real segments and both the old
 * and the new predicted tail, so a stale tail is always erased on the next frame.
 */
class WetDamageTest {

    private fun pts(vararg xy: Float): FloatArray {
        val stride = PackedPoints.STRIDE
        val out = FloatArray(xy.size / 2 * stride)
        for (i in 0 until xy.size / 2) {
            out[i * stride] = xy[i * 2]
            out[i * stride + 1] = xy[i * 2 + 1]
            out[i * stride + 2] = 0.5f
        }
        return out
    }

    @Test
    fun first_frame_covers_all_points_inflated() {
        val d = WetDamage()
        val r = d.next(pts(10f, 10f, 20f, 30f), 2, FloatArray(0), 2f)
        assertArrayEquals(floatArrayOf(8f, 8f, 22f, 32f), r, 0f)
    }

    @Test
    fun a_previous_tail_is_included_so_it_gets_erased() {
        val d = WetDamage()
        val points = pts(10f, 10f, 20f, 20f, 21f, 21f)
        d.next(points, 2, pts(90f, 90f), 0f) // tail far ahead
        val r = d.next(points, 3, FloatArray(0), 0f)!!
        // New segment 20→21 plus the old tail back to 90.
        assertTrue(r[0] <= 20f && r[1] <= 20f)
        assertTrue(r[2] >= 90f && r[3] >= 90f)
    }

    @Test
    fun only_new_segments_after_the_first_frame() {
        val d = WetDamage()
        val points = pts(0f, 0f, 10f, 10f, 20f, 20f)
        d.next(points, 2, FloatArray(0), 0f)
        val r = d.next(points, 3, FloatArray(0), 0f)
        assertArrayEquals(floatArrayOf(10f, 10f, 20f, 20f), r, 0f)
    }

    @Test
    fun nothing_to_draw_is_null() {
        assertNull(WetDamage().next(FloatArray(0), 0, FloatArray(0), 1f))
    }
}
