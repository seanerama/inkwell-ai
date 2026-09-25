package com.inkwell.ink

import com.inkwell.data.PackedPoints

/**
 * Stage 31: the one place live pen samples enter a stroke, separating **real** samples
 * from **predicted** ones (`MotionEventPredictor`).
 *
 *  - [addReal] feeds the [StrokeBuilder] — the one-euro filter and, at pen-up, storage.
 *  - [setPredicted] only replaces [predictedTail], which the wet layer draws ahead of the
 *    nib for one frame. Predicted points never reach the builder, so they are never
 *    persisted and never exported (contract `ink-storage`: stored points are the
 *    filtered real samples).
 *  - [finish] drops any tail before building, so no predicted ink survives pen-up.
 *
 * The builder is private and created here, so there is no other path into it. Pure
 * Kotlin: `LiveStrokeSinkTest` proves a stroke fed real + predicted samples builds the
 * exact same [BuiltStroke] as one fed only the real samples.
 */
class LiveStrokeSink(minCutoff: Double, beta: Double) {

    private val builder = StrokeBuilder(minCutoff, beta)

    /** The current predicted tail: stride-5 canvas-unit points, raw (unfiltered), or empty. */
    var predictedTail: FloatArray = EMPTY
        private set

    /**
     * Stage 33: how many predicted samples [setPredicted] has received over this stroke.
     * A test hook, surfaced by `InkView.lastStrokePredictedSamples` so the instrumented test
     * can prove the predictor really ran on the wet path; it never affects what is built
     * or stored.
     */
    var predictedSamplesReceived: Int = 0
        private set

    val isEmpty: Boolean get() = builder.isEmpty
    val pointCount: Int get() = builder.pointCount

    fun start(eventTimeMs: Long) {
        builder.start(eventTimeMs)
        predictedTail = EMPTY
    }

    /** A real digitizer sample (historical or current), in canvas units. */
    fun addReal(xCu: Float, yCu: Float, pressure: Float, tilt: Float, eventTimeMs: Long) {
        builder.add(xCu, yCu, pressure, tilt, eventTimeMs)
    }

    /**
     * Replace the predicted tail (stride 5, canvas units; the `t` slot is unused). Display
     * only: this never touches the builder.
     */
    fun setPredicted(points: FloatArray) {
        require(points.size % PackedPoints.STRIDE == 0) { "predicted tail must be stride 5" }
        predictedTail = points
        predictedSamplesReceived += points.size / PackedPoints.STRIDE
    }

    fun clearPredicted() {
        predictedTail = EMPTY
    }

    /** Copy of the real (filtered) points so far, for the View-overlay path. */
    fun snapshotPoints(): FloatArray = builder.snapshotPoints()

    /** Zero-copy view of the real (filtered) points so far, for the wet layer. */
    fun liveBuffer(): LivePoints = builder.liveBuffer()

    /** Finish the stroke: the tail is dropped and only real samples are built. */
    fun finish(): BuiltStroke? {
        clearPredicted()
        return builder.build()
    }

    private companion object {
        val EMPTY = FloatArray(0)
    }
}
