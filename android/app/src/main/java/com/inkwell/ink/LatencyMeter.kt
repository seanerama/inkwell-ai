package com.inkwell.ink

/**
 * Stage 31 (debug overlay only): a rolling average of "ink reached the compositor" minus
 * `MotionEvent.eventTime`, in ms, over the last [window] frames of the live stroke.
 *
 * It is a number to compare settings with, not a precise measurement. Both paths sample
 * when the frame is handed to the compositor: the wet layer when its front buffer is
 * submitted (it is scanned out almost at once), the View path at frame commit (the
 * compositor still adds about a vsync after that). So the real gap between the two paths
 * is, if anything, larger than the readout shows. Thread-safe; pure Kotlin.
 */
class LatencyMeter(private val window: Int = DEFAULT_WINDOW) {
    private val samples = LongArray(window)
    private var next = 0
    private var size = 0
    private var sum = 0L

    @Synchronized
    fun record(latencyMs: Long) {
        if (latencyMs < 0 || latencyMs > MAX_PLAUSIBLE_MS) return
        if (size == window) sum -= samples[next] else size++
        samples[next] = latencyMs
        sum += latencyMs
        next = (next + 1) % window
    }

    /** The rolling average in ms, or null before any sample. */
    @Synchronized
    fun averageMs(): Double? = if (size == 0) null else sum.toDouble() / size

    @Synchronized
    fun reset() {
        next = 0
        size = 0
        sum = 0L
    }

    companion object {
        const val DEFAULT_WINDOW = 60

        /** Ignore outliers such as a stroke resumed after the app was backgrounded. */
        const val MAX_PLAUSIBLE_MS = 1_000L
    }
}
