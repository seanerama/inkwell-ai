package com.inkwell.render

import com.inkwell.data.PackedPoints
import kotlin.math.max
import kotlin.math.min

/**
 * Stroke-level eraser hit-testing (SPEC §9 / stage: "eraser as a stroke-level hit
 * test on the denormalised bbox then segment distance").
 *
 * The eraser removes whole strokes, not pixels. The test is two-phase for speed:
 *  1. a cheap reject against the stroke's denormalised bounding box (stored on
 *     [com.inkwell.data.StrokeEntity]), padded by the hit radius;
 *  2. an exact test of the eraser point's distance to each stroke segment.
 *
 * All coordinates are canvas units. Pure Kotlin, so it is unit-tested on the JVM.
 */
object EraserHitTest {

    /**
     * @param points stride-5 packed points of the candidate stroke (canvas units).
     * @param bboxX,bboxY,bboxW,bboxH the stroke's denormalised bbox (canvas units).
     * @param ex,ey the eraser point (canvas units).
     * @param radius hit radius (canvas units); typically the eraser width plus the
     *   stroke half-width.
     * @return true if the eraser point is within [radius] of the stroke.
     */
    fun hits(
        points: FloatArray,
        bboxX: Float,
        bboxY: Float,
        bboxW: Float,
        bboxH: Float,
        ex: Float,
        ey: Float,
        radius: Float,
    ): Boolean {
        // Phase 1: bbox reject (padded by radius).
        if (ex < bboxX - radius || ex > bboxX + bboxW + radius ||
            ey < bboxY - radius || ey > bboxY + bboxH + radius
        ) {
            return false
        }

        val stride = PackedPoints.STRIDE
        val count = points.size / stride
        if (count == 0) return false
        val r2 = radius * radius

        // A single-point stroke (a dot): distance to the point.
        if (count == 1) {
            return distanceSquaredToPoint(ex, ey, points[0], points[1]) <= r2
        }

        // Phase 2: distance to each segment.
        var i = 0
        while (i < count - 1) {
            val ax = points[i * stride]
            val ay = points[i * stride + 1]
            val bx = points[(i + 1) * stride]
            val by = points[(i + 1) * stride + 1]
            if (distanceSquaredToSegment(ex, ey, ax, ay, bx, by) <= r2) return true
            i++
        }
        return false
    }

    private fun distanceSquaredToPoint(px: Float, py: Float, ax: Float, ay: Float): Float {
        val dx = px - ax
        val dy = py - ay
        return dx * dx + dy * dy
    }

    private fun distanceSquaredToSegment(
        px: Float, py: Float,
        ax: Float, ay: Float,
        bx: Float, by: Float,
    ): Float {
        val abx = bx - ax
        val aby = by - ay
        val lenSq = abx * abx + aby * aby
        if (lenSq == 0f) return distanceSquaredToPoint(px, py, ax, ay)
        // Projection factor of P onto AB, clamped to the segment.
        var t = ((px - ax) * abx + (py - ay) * aby) / lenSq
        t = max(0f, min(1f, t))
        val cx = ax + t * abx
        val cy = ay + t * aby
        return distanceSquaredToPoint(px, py, cx, cy)
    }
}
