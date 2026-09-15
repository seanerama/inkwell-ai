package com.inkwell.ink

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The one-euro filter must smooth without lag (SPEC §9.2(4)): monotone input stays
 * monotone (no ringing/overshoot on a slow line), and on a step it catches up within
 * one sample (a flick is not delayed). A moving average would fail the second test.
 */
class OneEuroFilterTest {

    private val dt = 1.0 / 60.0 // 60 Hz sampling

    @Test
    fun monotone_on_a_ramp() {
        val f = OneEuroFilter(minCutoff = 1.0, beta = 0.007)
        var prev = Double.NEGATIVE_INFINITY
        for (i in 0 until 40) {
            val out = f.filter(i.toDouble(), i * dt)
            assertTrue("output must be non-decreasing on a ramp (i=$i)", out >= prev - 1e-9)
            prev = out
        }
    }

    @Test
    fun does_not_lag_more_than_one_sample_on_a_step() {
        // beta opens the cutoff on fast motion so the step is tracked almost at once.
        val f = OneEuroFilter(minCutoff = 1.0, beta = 5.0)
        val input = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 10.0, 10.0, 10.0, 10.0)
        val out = DoubleArray(input.size)
        for (i in input.indices) out[i] = f.filter(input[i], i * dt)

        // Before the step (index 3) the filter still reads ~0.
        assertTrue("pre-step should be near 0 (was ${out[3]})", abs(out[3]) < 0.05)
        // One sample after the step (index 5) it must have essentially caught up.
        assertTrue(
            "must catch up within one sample of the step (was ${out[5]})",
            abs(out[5] - 10.0) < 0.1,
        )
    }

    @Test
    fun first_sample_passes_through() {
        val f = OneEuroFilter()
        assertTrue(f.filter(42.0, 0.0) == 42.0)
    }
}
