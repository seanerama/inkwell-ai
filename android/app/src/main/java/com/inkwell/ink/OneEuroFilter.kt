package com.inkwell.ink

import kotlin.math.PI
import kotlin.math.abs

/**
 * A one-euro filter (Casiez, Roussel & Vogel, 2012) for a single scalar signal.
 *
 * The one-euro filter smooths jitter without the constant lag of a moving average:
 * when the signal is slow it filters aggressively (kills tremor), when the signal is
 * fast it filters lightly (keeps responsiveness). This is exactly the property SPEC
 * §9.2(4) requires — smooth a resting hand, but never lag a flick.
 *
 * Two parameters are tunable (SPEC §9.2 / contract `ink-storage` note that retuning
 * never touches stored data — the filter runs on the live stream before commit):
 *  - [minCutoff]: the cutoff frequency (Hz) at zero speed. Lower = smoother but laggier.
 *  - [beta]: how much the cutoff opens up with speed. Higher = less lag on fast moves.
 *
 * The class is pure Kotlin (no Android types) so it is exercised by JVM unit tests.
 * Instances are stateful and single-signal; use one per axis.
 */
class OneEuroFilter(
    private val minCutoff: Double = DEFAULT_MIN_CUTOFF,
    private val beta: Double = DEFAULT_BETA,
    private val dCutoff: Double = DEFAULT_D_CUTOFF,
) {
    private var initialized = false
    private var xPrev = 0.0
    private var dxPrev = 0.0
    private var tPrev = 0.0

    /** Drop all state so the next [filter] call re-seeds (call at stroke start). */
    fun reset() {
        initialized = false
        xPrev = 0.0
        dxPrev = 0.0
        tPrev = 0.0
    }

    /**
     * Filter one sample.
     *
     * @param value the raw sample.
     * @param timestampSeconds monotonic sample time in seconds. Must be non-decreasing;
     *   a zero or negative delta reuses the previous smoothing factor safely.
     * @return the smoothed value. The first sample after a [reset] passes through
     *   unchanged (nothing to smooth against yet).
     */
    fun filter(value: Double, timestampSeconds: Double): Double {
        if (!initialized) {
            initialized = true
            xPrev = value
            dxPrev = 0.0
            tPrev = timestampSeconds
            return value
        }
        val dt = timestampSeconds - tPrev
        tPrev = timestampSeconds
        if (dt <= 0.0) {
            // No time advanced (batched at the same instant): hold the estimate.
            return xPrev
        }
        // Filter the derivative, then set the position cutoff from its magnitude.
        val dx = (value - xPrev) / dt
        val aD = smoothingFactor(dt, dCutoff)
        val dxHat = aD * dx + (1.0 - aD) * dxPrev
        val cutoff = minCutoff + beta * abs(dxHat)
        val a = smoothingFactor(dt, cutoff)
        val xHat = a * value + (1.0 - a) * xPrev
        xPrev = xHat
        dxPrev = dxHat
        return xHat
    }

    private fun smoothingFactor(dt: Double, cutoff: Double): Double {
        val r = 2.0 * PI * cutoff * dt
        return r / (r + 1.0)
    }

    companion object {
        const val DEFAULT_MIN_CUTOFF = 1.0
        const val DEFAULT_BETA = 0.007
        const val DEFAULT_D_CUTOFF = 1.0
    }
}
