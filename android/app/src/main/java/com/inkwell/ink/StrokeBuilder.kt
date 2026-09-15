package com.inkwell.ink

import com.inkwell.data.PackedPoints

/**
 * A finished stroke's geometry in canvas units, ready to pack for contract
 * `ink-storage` (stride 5: `x, y, p, tilt, t`).
 *
 * [points] are the one-euro-filtered positions with **raw** pressure preserved
 * (contract: pressure is stored raw, never pre-modulated — width modulation is a
 * render-time function of `p`). [bbox] is the denormalised bounding box used for
 * eraser hit-testing.
 */
data class BuiltStroke(
    val points: FloatArray,
    val pointCount: Int,
    val bboxX: Float,
    val bboxY: Float,
    val bboxW: Float,
    val bboxH: Float,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BuiltStroke) return false
        return pointCount == other.pointCount &&
            points.contentEquals(other.points) &&
            bboxX == other.bboxX && bboxY == other.bboxY &&
            bboxW == other.bboxW && bboxH == other.bboxH
    }

    override fun hashCode(): Int {
        var result = points.contentHashCode()
        result = 31 * result + pointCount
        return result
    }
}

/**
 * Accumulates raw digitizer samples for one stroke and emits contract `ink-storage`
 * points in **canvas units**.
 *
 * Responsibilities (SPEC §9.2):
 *  - run the one-euro filter on the live position stream (x and y independently) so
 *    the stored points are the filtered ones (contract), never a moving average;
 *  - preserve every historical sample in arrival order — callers must feed
 *    `event.historySize` samples before the current one (§9.2(2));
 *  - keep pressure raw and timestamps as ms since stroke start, non-decreasing.
 *
 * Coordinates are supplied already in canvas units; the [InkView] converts view
 * pixels through the pan/zoom transform before calling [add], which keeps this class
 * pure and JVM-testable.
 */
class StrokeBuilder(
    minCutoff: Double = OneEuroFilter.DEFAULT_MIN_CUTOFF,
    beta: Double = OneEuroFilter.DEFAULT_BETA,
) {
    private val filterX = OneEuroFilter(minCutoff, beta)
    private val filterY = OneEuroFilter(minCutoff, beta)

    private val values = ArrayList<Float>()
    private var count = 0
    private var startTimeMs = 0L
    private var started = false
    private var lastRelT = 0f

    private var minX = Float.POSITIVE_INFINITY
    private var minY = Float.POSITIVE_INFINITY
    private var maxX = Float.NEGATIVE_INFINITY
    private var maxY = Float.NEGATIVE_INFINITY

    val isEmpty: Boolean get() = count == 0
    val pointCount: Int get() = count

    /** Begin a new stroke; [eventTimeMs] anchors the relative timestamps at 0. */
    fun start(eventTimeMs: Long) {
        filterX.reset()
        filterY.reset()
        values.clear()
        count = 0
        startTimeMs = eventTimeMs
        started = true
        lastRelT = 0f
        minX = Float.POSITIVE_INFINITY
        minY = Float.POSITIVE_INFINITY
        maxX = Float.NEGATIVE_INFINITY
        maxY = Float.NEGATIVE_INFINITY
    }

    /**
     * Add one sample in canvas units.
     *
     * @param xCu,yCu position in canvas units (filtered before storage).
     * @param pressure raw digitizer pressure, stored as-is.
     * @param tilt radians, 0 = perpendicular.
     * @param eventTimeMs absolute sample time (ms); stored as ms since [start].
     */
    fun add(xCu: Float, yCu: Float, pressure: Float, tilt: Float, eventTimeMs: Long) {
        check(started) { "StrokeBuilder.add called before start()" }
        val tSeconds = eventTimeMs / 1000.0
        val fx = filterX.filter(xCu.toDouble(), tSeconds).toFloat()
        val fy = filterY.filter(yCu.toDouble(), tSeconds).toFloat()
        // Relative, non-decreasing timestamp in ms since stroke start.
        var relT = (eventTimeMs - startTimeMs).toFloat()
        if (relT < lastRelT) relT = lastRelT
        lastRelT = relT

        values.add(fx)
        values.add(fy)
        values.add(pressure)
        values.add(tilt)
        values.add(relT)
        count++

        if (fx < minX) minX = fx
        if (fy < minY) minY = fy
        if (fx > maxX) maxX = fx
        if (fy > maxY) maxY = fy
    }

    /** Snapshot the packed stride-5 array so far (for the live overlay). */
    fun snapshotPoints(): FloatArray = values.toFloatArray()

    /** Finish the stroke. Returns null if no samples were captured. */
    fun build(): BuiltStroke? {
        if (count == 0) return null
        val points = values.toFloatArray()
        require(points.size == count * PackedPoints.STRIDE) {
            "stride invariant broken: ${points.size} != $count * ${PackedPoints.STRIDE}"
        }
        return BuiltStroke(
            points = points,
            pointCount = count,
            bboxX = minX,
            bboxY = minY,
            bboxW = maxX - minX,
            bboxH = maxY - minY,
        )
    }
}
