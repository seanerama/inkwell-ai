package com.inkwell.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Packed-points codec for stroke geometry (contract `ink-storage`).
 *
 * Points are a little-endian `FloatArray` of stride 5 — `[x, y, p, tilt, t, ...]` —
 * stored as a BLOB. The invariant `point_count * 5 * 4 == length(points)` is enforced
 * on read (each float is 4 bytes, so 20 bytes per point).
 */
object PackedPoints {

    const val STRIDE = 5
    const val BYTES_PER_POINT = STRIDE * 4 // 20

    /** Number of points a stride-5 [points] array represents. */
    fun pointCount(points: FloatArray): Int {
        require(points.size % STRIDE == 0) {
            "points length ${points.size} is not a multiple of stride $STRIDE"
        }
        return points.size / STRIDE
    }

    /** Encode a stride-5 [points] array to a little-endian BLOB. */
    fun encode(points: FloatArray): ByteArray {
        require(points.size % STRIDE == 0) {
            "points length ${points.size} is not a multiple of stride $STRIDE"
        }
        val buffer = ByteBuffer.allocate(points.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (value in points) buffer.putFloat(value)
        return buffer.array()
    }

    /**
     * Decode a BLOB back to a stride-5 [FloatArray], enforcing the contract invariant
     * `pointCount * 5 * 4 == blob.size`.
     *
     * @throws IllegalStateException when the declared [pointCount] does not match the
     *   blob length (a corrupt or mis-stored stroke).
     */
    fun decode(blob: ByteArray, pointCount: Int): FloatArray {
        check(pointCount * BYTES_PER_POINT == blob.size) {
            "packed-points invariant violated: point_count=$pointCount implies " +
                "${pointCount * BYTES_PER_POINT} bytes but blob is ${blob.size}"
        }
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(pointCount * STRIDE) { buffer.float }
    }
}
