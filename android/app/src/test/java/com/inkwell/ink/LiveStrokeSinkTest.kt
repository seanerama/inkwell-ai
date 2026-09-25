package com.inkwell.ink

import com.inkwell.data.PackedPoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Stage 31: predicted points never reach the [StrokeBuilder]. A stroke fed real samples
 * interleaved with predicted tails builds exactly the stroke the same real samples build
 * with prediction off — same point count, same bytes (contract `ink-storage`: stored
 * points are the filtered real samples) — and no tail survives pen-up.
 */
class LiveStrokeSinkTest {

    private class Sample(val x: Float, val y: Float, val p: Float, val tilt: Float, val t: Long)

    private val real = (0 until 40).map { i ->
        Sample(100f + i * 3.5f, 200f + i * 2.25f, 0.3f + (i % 7) * 0.1f, 0.2f, 1000L + i * 4L)
    }

    private fun predictedTailAfter(s: Sample): FloatArray {
        val stride = PackedPoints.STRIDE
        val tail = FloatArray(3 * stride)
        for (k in 0 until 3) {
            tail[k * stride] = s.x + 50f * (k + 1) // wildly ahead: must never be stored
            tail[k * stride + 1] = s.y - 40f * (k + 1)
            tail[k * stride + 2] = 1f
        }
        return tail
    }

    @Test
    fun predicted_points_never_reach_the_builder() {
        val preset = SmoothingPreset.STANDARD

        // Prediction off: only real samples.
        val off = LiveStrokeSink(preset.minCutoff, preset.beta)
        off.start(real.first().t)
        for (s in real) off.addReal(s.x, s.y, s.p, s.tilt, s.t)
        val expected = off.finish()

        // Prediction on: a tail replaced after every real sample.
        val on = LiveStrokeSink(preset.minCutoff, preset.beta)
        on.start(real.first().t)
        for (s in real) {
            on.addReal(s.x, s.y, s.p, s.tilt, s.t)
            on.setPredicted(predictedTailAfter(s))
            assertEquals("the tail is display-only", 3 * PackedPoints.STRIDE, on.predictedTail.size)
        }
        assertEquals(real.size, on.pointCount)
        val actual = on.finish()

        assertNotNull(expected)
        assertEquals(expected!!.pointCount, actual!!.pointCount)
        assertEquals(expected, actual) // same points (bit-exact), count and bbox
        assertEquals(0, on.predictedTail.size) // no tail survives pen-up
    }

    @Test
    fun matches_a_bare_stroke_builder_with_the_same_input() {
        val sink = LiveStrokeSink(3.0, 0.02)
        val builder = StrokeBuilder(3.0, 0.02)
        sink.start(1000L)
        builder.start(1000L)
        for (s in real) {
            sink.setPredicted(predictedTailAfter(s))
            sink.addReal(s.x, s.y, s.p, s.tilt, s.t)
            builder.add(s.x, s.y, s.p, s.tilt, s.t)
        }
        assertEquals(builder.build(), sink.finish())
    }

    @Test
    fun counts_predicted_samples_received_without_touching_the_stroke() {
        val sink = LiveStrokeSink(3.0, 0.02)
        sink.start(1000L)
        assertEquals(0, sink.predictedSamplesReceived)
        for (s in real.take(5)) {
            sink.addReal(s.x, s.y, s.p, s.tilt, s.t)
            sink.setPredicted(predictedTailAfter(s)) // 3 samples each
        }
        sink.clearPredicted()
        assertEquals(15, sink.predictedSamplesReceived)
        assertEquals(5, sink.pointCount) // only the real samples were built
        sink.finish()
        assertEquals("the count survives pen-up for the test hook", 15, sink.predictedSamplesReceived)
    }

    @Test(expected = IllegalArgumentException::class)
    fun a_malformed_tail_is_rejected() {
        LiveStrokeSink(1.0, 0.007).setPredicted(FloatArray(7))
    }
}
