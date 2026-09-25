package com.inkwell.ink

/**
 * Stage 31: the one-euro filter presets the owner can pick in Settings ("Smoothing:
 * Standard / Responsive"). Applied through [InkView.minCutoff] / [InkView.beta] when a
 * canvas opens.
 *
 * A preset changes the *geometry* of strokes captured afterwards (the stored points are
 * the filtered ones, contract `ink-storage`), never the storage *format*, and never
 * strokes already on disk.
 */
enum class SmoothingPreset(
    /** Stable key persisted by [InkPrefs]; never rename. */
    val key: String,
    val minCutoff: Double,
    val beta: Double,
) {
    /** The original tuning (SPEC §9.2(4)); the default until stage 33. */
    STANDARD("standard", OneEuroFilter.DEFAULT_MIN_CUTOFF, OneEuroFilter.DEFAULT_BETA),

    /**
     * Less lag at slow writing speeds. Chosen values: `minCutoff = 3.0 Hz`, `beta = 0.02`.
     *
     * Why: a simulated slow diagonal (60 CU/s, 240 Hz, 1 CU σ digitizer noise) lags
     * ~41 ms behind the nib with STANDARD and ~22 ms with these values, while the
     * perpendicular jitter left after filtering rises only from ~0.21 to ~0.29 CU RMS
     * (≈0.025 mm, about a tenth of the default 3 CU pen width), so the SPEC §9.2
     * slow-diagonal check stays clean. Pushing further (beta 0.03, or minCutoff 4.0)
     * buys ≤ 3 ms more and adds jitter, so we stop here. [OneEuroFilterTest]-style
     * coverage lives in `SmoothingPresetTest`; the owner confirms the feel on the tablet
     * (`smoke/android-low-latency-ink.md`). The default since stage 33 (the owner's pick
     * after the stage 31 feel test).
     */
    RESPONSIVE("responsive", 3.0, 0.02),
    ;

    companion object {
        val DEFAULT = RESPONSIVE

        /** Parse a persisted [key]; anything unknown (or null) falls back to [DEFAULT]. */
        fun fromKey(key: String?): SmoothingPreset = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
