package com.inkwell.render

/**
 * The eraser is a stroke-level hit test: bbox reject, then distance to each segment
 * (stage requirement). It must find a stroke that passes near the eraser point and
 * miss one that does not.
 */
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EraserHitTestTest {

    // A horizontal stroke from (0,0) to (100,0): stride-5 points, p/tilt/t filler.
    private val stroke = floatArrayOf(
        0f, 0f, 0.5f, 0f, 0f,
        50f, 0f, 0.5f, 0f, 10f,
        100f, 0f, 0.5f, 0f, 20f,
    )
    private val bboxX = 0f
    private val bboxY = 0f
    private val bboxW = 100f
    private val bboxH = 0f

    @Test
    fun hits_a_stroke_crossing_the_eraser_point() {
        // Eraser at (50, 3) with radius 5: within 3 of the segment → hit.
        assertTrue(
            EraserHitTest.hits(stroke, bboxX, bboxY, bboxW, bboxH, ex = 50f, ey = 3f, radius = 5f),
        )
    }

    @Test
    fun misses_a_stroke_that_does_not_cross() {
        // Eraser at (50, 40), radius 5: bbox reject / far from the segment → miss.
        assertFalse(
            EraserHitTest.hits(stroke, bboxX, bboxY, bboxW, bboxH, ex = 50f, ey = 40f, radius = 5f),
        )
    }

    @Test
    fun hits_at_an_endpoint_within_radius() {
        assertTrue(
            EraserHitTest.hits(stroke, bboxX, bboxY, bboxW, bboxH, ex = 102f, ey = 0f, radius = 5f),
        )
    }

    @Test
    fun misses_just_beyond_an_endpoint() {
        assertFalse(
            EraserHitTest.hits(stroke, bboxX, bboxY, bboxW, bboxH, ex = 120f, ey = 0f, radius = 5f),
        )
    }

    @Test
    fun single_point_stroke_is_hit_within_radius() {
        val dot = floatArrayOf(10f, 10f, 0.5f, 0f, 0f)
        assertTrue(EraserHitTest.hits(dot, 10f, 10f, 0f, 0f, ex = 12f, ey = 10f, radius = 5f))
        assertFalse(EraserHitTest.hits(dot, 10f, 10f, 0f, 0f, ex = 30f, ey = 10f, radius = 5f))
    }
}
