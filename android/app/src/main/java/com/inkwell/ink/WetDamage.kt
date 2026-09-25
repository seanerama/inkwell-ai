package com.inkwell.ink

import com.inkwell.data.PackedPoints
import kotlin.math.max
import kotlin.math.min

/**
 * Stage 31: works out, for each front-buffer frame of the wet pen stroke, the region
 * (canvas units) the wet layer must clear and redraw.
 *
 * The front buffer keeps its pixels between frames, so real ink is drawn incrementally.
 * The predicted tail is not: last frame's tail must disappear and this frame's must
 * appear. The damage rect is therefore the union of
 *  - the real segments added since the previous frame,
 *  - the previous frame's predicted tail (to erase it), and
 *  - this frame's predicted tail (anchored at the newest real point),
 * inflated by half the widest possible stroke plus an anti-aliasing margin. The wet
 * layer clears exactly this rect and redraws every real segment that touches it, so
 * nothing real is lost and nothing is drawn twice.
 *
 * Pure Kotlin (JVM-tested in `WetDamageTest`); one instance per stroke, UI thread only.
 */
class WetDamage {
    private var prevCount = 0
    private var prevTail: FloatArray? = null // l, t, r, b of the last tail, or null

    /** Forget the previous frame (call at stroke start). */
    fun reset() {
        prevCount = 0
        prevTail = null
    }

    /**
     * The damage rect `[l, t, r, b]` (canvas units) for a frame showing the first
     * [count] real points of [points] plus the predicted [tail] (stride 5), or null when
     * there is nothing to draw. [inflateCu] is half the widest stroke plus the AA margin.
     */
    fun next(points: FloatArray, count: Int, tail: FloatArray, inflateCu: Float): FloatArray? {
        val stride = PackedPoints.STRIDE
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        fun add(x: Float, y: Float) {
            l = min(l, x); t = min(t, y); r = max(r, x); b = max(b, y)
        }
        // New real segments: from the last point already drawn to the newest one.
        val from = max(prevCount - 1, 0)
        for (i in from until count) add(points[i * stride], points[i * stride + 1])
        // The previous tail, so it is erased.
        prevTail?.let { add(it[0], it[1]); add(it[2], it[3]) }
        // This frame's tail, joined to the newest real point.
        var tailRect: FloatArray? = null
        if (count > 0 && tail.size >= stride) {
            val last = (count - 1) * stride
            var tl = points[last]
            var tt = points[last + 1]
            var tr = tl
            var tb = tt
            for (i in 0 until tail.size / stride) {
                val x = tail[i * stride]
                val y = tail[i * stride + 1]
                tl = min(tl, x); tt = min(tt, y); tr = max(tr, x); tb = max(tb, y)
            }
            tailRect = floatArrayOf(tl, tt, tr, tb)
            add(tl, tt); add(tr, tb)
        }
        prevCount = count
        prevTail = tailRect
        if (l > r || t > b) return null
        return floatArrayOf(l - inflateCu, t - inflateCu, r + inflateCu, b + inflateCu)
    }
}
