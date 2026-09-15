package com.inkwell.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Packed-points codec round-trip + invariant enforcement (contract ink-storage). */
class PackedPointsTest {

    @Test
    fun round_trips_stride_five_points() {
        // Two points: [x,y,p,tilt,t] each.
        val points = floatArrayOf(
            10.5f, 20.25f, 0.8f, 0.1f, 0f,
            11.0f, 21.0f, 0.9f, 0.12f, 16f,
        )
        val count = PackedPoints.pointCount(points)
        assertEquals(2, count)

        val blob = PackedPoints.encode(points)
        assertEquals(count * PackedPoints.BYTES_PER_POINT, blob.size)

        val decoded = PackedPoints.decode(blob, count)
        assertArrayEquals(points, decoded, 0.0f)
    }

    @Test
    fun rejects_length_mismatch_on_read() {
        val blob = PackedPoints.encode(floatArrayOf(1f, 2f, 3f, 4f, 5f)) // 1 point, 20 bytes
        // Claiming 2 points (40 bytes) against a 20-byte blob violates the invariant.
        val ex = assertThrows(IllegalStateException::class.java) {
            PackedPoints.decode(blob, 2)
        }
        assertEquals(true, ex.message?.contains("invariant"))
    }

    @Test
    fun rejects_non_stride_multiple_on_encode() {
        assertThrows(IllegalArgumentException::class.java) {
            PackedPoints.encode(floatArrayOf(1f, 2f, 3f)) // not a multiple of 5
        }
    }
}
