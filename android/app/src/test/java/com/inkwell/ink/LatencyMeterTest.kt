package com.inkwell.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Stage 31: the debug latency readout is a rolling average that ignores outliers. */
class LatencyMeterTest {

    @Test
    fun rolling_average_over_the_window() {
        val m = LatencyMeter(window = 3)
        assertNull(m.averageMs())
        m.record(10); m.record(20); m.record(30)
        assertEquals(20.0, m.averageMs()!!, 1e-9)
        m.record(40) // evicts 10
        assertEquals(30.0, m.averageMs()!!, 1e-9)
    }

    @Test
    fun implausible_samples_are_ignored_and_reset_clears() {
        val m = LatencyMeter(window = 4)
        m.record(-5)
        m.record(LatencyMeter.MAX_PLAUSIBLE_MS + 1)
        assertNull(m.averageMs())
        m.record(8)
        assertEquals(8.0, m.averageMs()!!, 1e-9)
        m.reset()
        assertNull(m.averageMs())
    }
}
