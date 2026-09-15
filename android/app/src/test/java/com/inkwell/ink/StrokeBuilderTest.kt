package com.inkwell.ink

import com.inkwell.data.PackedPoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stroke builder must keep every sample in arrival order (SPEC §9.2(2): historical
 * samples are never dropped) with non-decreasing timestamps, and the points it emits
 * must survive the packed-points codec unchanged (contract `ink-storage` round-trip).
 */
class StrokeBuilderTest {

    @Test
    fun preserves_samples_in_order_with_non_decreasing_timestamps() {
        val b = StrokeBuilder(minCutoff = 1.0, beta = 0.007)
        b.start(eventTimeMs = 1000L)
        // Feed 5 "historical" samples then a current one, all in time order.
        val times = longArrayOf(1000L, 1016L, 1032L, 1048L, 1064L, 1080L)
        for ((i, t) in times.withIndex()) {
            b.add(xCu = i.toFloat(), yCu = i.toFloat(), pressure = 0.5f, tilt = 0f, eventTimeMs = t)
        }
        val built = b.build()
        assertNotNull(built)
        built!!
        assertEquals(times.size, built.pointCount)

        // Timestamps (stride index 4) are relative to start and non-decreasing.
        val stride = PackedPoints.STRIDE
        var prevT = Float.NEGATIVE_INFINITY
        for (i in 0 until built.pointCount) {
            val t = built.points[i * stride + 4]
            assertTrue("timestamps must be non-decreasing", t >= prevT)
            prevT = t
        }
        // First relative timestamp is 0, last is 80 ms.
        assertEquals(0f, built.points[4], 0.0001f)
        assertEquals(80f, built.points[(times.size - 1) * stride + 4], 0.0001f)
    }

    @Test
    fun out_of_order_timestamp_is_clamped_non_decreasing() {
        val b = StrokeBuilder()
        b.start(0L)
        b.add(0f, 0f, 0.5f, 0f, 0L)
        b.add(1f, 1f, 0.5f, 0f, 32L)
        b.add(2f, 2f, 0.5f, 0f, 16L) // out of order
        val built = b.build()!!
        val stride = PackedPoints.STRIDE
        assertEquals(0f, built.points[4], 0f)
        assertEquals(32f, built.points[stride + 4], 0f)
        assertEquals(32f, built.points[2 * stride + 4], 0f) // clamped up to 32
    }

    @Test
    fun raw_pressure_is_preserved_not_modulated() {
        val b = StrokeBuilder()
        b.start(0L)
        b.add(0f, 0f, 0.42f, 0.1f, 0L)
        val built = b.build()!!
        assertEquals(0.42f, built.points[2], 0f) // p stored raw
        assertEquals(0.1f, built.points[3], 0f) // tilt stored raw
    }

    @Test
    fun stored_points_read_back_equal_the_filtered_input() {
        // Build a stroke, pack it as the repository would, decode it, and require
        // exact equality: the stored points are the filtered ones (contract).
        val b = StrokeBuilder(minCutoff = 1.0, beta = 0.5)
        b.start(0L)
        val raw = arrayOf(
            floatArrayOf(10f, 20f, 0.3f, 0.0f),
            floatArrayOf(12f, 23f, 0.5f, 0.05f),
            floatArrayOf(30f, 40f, 0.9f, 0.1f),
            floatArrayOf(31f, 42f, 0.8f, 0.1f),
        )
        for ((i, s) in raw.withIndex()) {
            b.add(s[0], s[1], s[2], s[3], (i * 16).toLong())
        }
        val built = b.build()!!
        val filtered = built.points

        val blob = PackedPoints.encode(filtered)
        val decoded = PackedPoints.decode(blob, built.pointCount)

        assertEquals(filtered.size, decoded.size)
        for (i in filtered.indices) {
            assertEquals("point[$i] must round-trip exactly", filtered[i], decoded[i], 0f)
        }
        // And the contract invariant holds by construction.
        assertTrue(built.pointCount * PackedPoints.BYTES_PER_POINT == blob.size)
    }

    @Test
    fun empty_stroke_builds_null() {
        val b = StrokeBuilder()
        b.start(0L)
        assertTrue(b.build() == null)
    }
}
