package com.inkwell.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Stage 31: the smoothing presets map to the documented one-euro parameters, STANDARD is
 * exactly the original tuning, RESPONSIVE is the default since stage 33, and RESPONSIVE really lags less on a slow diagonal while its
 * leftover jitter stays far below a stroke width (SPEC §9.2 slow-diagonal check).
 */
class SmoothingPresetTest {

    @Test
    fun standard_is_the_original_tuning_and_responsive_is_the_default() {
        assertEquals(1.0, SmoothingPreset.STANDARD.minCutoff, 0.0)
        assertEquals(0.007, SmoothingPreset.STANDARD.beta, 0.0)
        assertEquals(OneEuroFilter.DEFAULT_MIN_CUTOFF, SmoothingPreset.STANDARD.minCutoff, 0.0)
        assertEquals(OneEuroFilter.DEFAULT_BETA, SmoothingPreset.STANDARD.beta, 0.0)
        assertEquals(SmoothingPreset.RESPONSIVE, SmoothingPreset.DEFAULT)
    }

    @Test
    fun responsive_maps_to_the_chosen_values() {
        assertEquals(3.0, SmoothingPreset.RESPONSIVE.minCutoff, 0.0)
        assertEquals(0.02, SmoothingPreset.RESPONSIVE.beta, 0.0)
    }

    @Test
    fun keys_round_trip_and_unknown_falls_back_to_the_default() {
        for (p in SmoothingPreset.entries) assertEquals(p, SmoothingPreset.fromKey(p.key))
        assertEquals(SmoothingPreset.RESPONSIVE, SmoothingPreset.fromKey(null))
        assertEquals(SmoothingPreset.RESPONSIVE, SmoothingPreset.fromKey("turbo"))
        // An explicit stored "standard" is never mistaken for "unset".
        assertEquals(SmoothingPreset.STANDARD, SmoothingPreset.fromKey("standard"))
    }

    @Test
    fun responsive_lags_less_on_a_slow_diagonal_with_invisible_jitter() {
        val standard = slowDiagonal(SmoothingPreset.STANDARD)
        val responsive = slowDiagonal(SmoothingPreset.RESPONSIVE)
        assertTrue(
            "RESPONSIVE must lag less (std=${standard.lagCu}, resp=${responsive.lagCu})",
            responsive.lagCu < standard.lagCu * 0.7,
        )
        // Leftover jitter well under a tenth of a millimetre (1 CU ≈ 0.085 mm) and far
        // below the 3 CU default pen width, so the line shows no segmentation.
        assertTrue("jitter ${responsive.jitterCu} CU too high", responsive.jitterCu < 0.5)
    }

    private class Result(val lagCu: Double, val jitterCu: Double)

    /** 60 CU/s diagonal sampled at 240 Hz with 1 CU of deterministic digitizer noise. */
    private fun slowDiagonal(preset: SmoothingPreset): Result {
        val fx = OneEuroFilter(preset.minCutoff, preset.beta)
        val fy = OneEuroFilter(preset.minCutoff, preset.beta)
        val rnd = java.util.Random(1)
        val hz = 240.0
        val n = 480
        val v = 60.0 / sqrt(2.0)
        var lag = 0.0
        var perpSq = 0.0
        var m = 0
        for (i in 0 until n) {
            val t = i / hz
            val x = v * t
            val ox = fx.filter(x + rnd.nextGaussian(), t)
            val oy = fy.filter(x + rnd.nextGaussian(), t)
            if (i > n / 2) {
                lag += ((x - ox) + (x - oy)) / sqrt(2.0)
                perpSq += ((ox - oy) / sqrt(2.0)).let { it * it }
                m++
            }
        }
        return Result(abs(lag / m), sqrt(perpSq / m))
    }
}
