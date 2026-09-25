package com.inkwell

import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inkwell.ink.InkSurfaceHost
import com.inkwell.ink.StrokeCommit
import com.inkwell.render.RenderStroke
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 31/33: the low-latency pen with the front-buffered wet layer **attached** (an
 * [InkSurfaceHost] in a real activity window, as `CanvasScreen` hosts it).
 *
 * These assert the data path only, never front-buffer pixels (emulators may not present
 * front buffers).
 *
 *  - Stage 33: a pen stroke on the wet path **really runs the predictor** (its sink
 *    received predicted samples) and still persists exactly the points it persists with
 *    the switch off. This test requires the wet surface and fails loudly without it;
 *  - the marker still commits through the existing path (never the wet layer) with the
 *    same points;
 *  - the eraser still removes the stroke under it.
 */
@RunWith(AndroidJUnit4::class)
class LowLatencyInkInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun stylusProps() = arrayOf(
        MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_STYLUS
        },
    )

    private fun coords(x: Float, y: Float, pressure: Float) = MotionEvent.PointerCoords().apply {
        this.x = x
        this.y = y
        this.pressure = pressure
        setAxisValue(MotionEvent.AXIS_TILT, 0.1f)
    }

    private fun event(downTime: Long, eventTime: Long, action: Int, c: MotionEvent.PointerCoords): MotionEvent =
        MotionEvent.obtain(
            downTime, eventTime, action, 1, stylusProps(), arrayOf(c),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0,
        )

    /**
     * Host an [InkSurfaceHost] in the compose rule's activity window and wait (bounded) for
     * its wet surface. With [requireWet], a surface that never comes up fails the test
     * loudly instead of silently falling back to the View path.
     */
    private fun host(requireWet: Boolean): InkSurfaceHost {
        var host: InkSurfaceHost? = null
        composeRule.setContent {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    InkSurfaceHost(ctx).also {
                        it.inkView.setCanvasSize(2480, 3508)
                        host = it
                    }
                },
            )
        }
        composeRule.waitForIdle()
        val h = requireNotNull(host)
        runCatching { composeRule.waitUntil(WET_SURFACE_TIMEOUT_MS) { h.wetLayer.isAvailable } }
        if (requireWet && !h.wetLayer.isAvailable) {
            fail(
                "WetInkLayer front-buffer surface never became available " +
                    "(isAvailable=false after ${WET_SURFACE_TIMEOUT_MS} ms, API ${Build.VERSION.SDK_INT}). " +
                    "The prediction-isolation test needs the wet path and must not skip: if this " +
                    "emulator genuinely cannot create a front-buffered surface, decide with the " +
                    "reviewer (stage 33 spec) whether to exercise the predictor without the surface.",
            )
        }
        return h
    }

    /**
     * A realistic synthetic stylus gesture: a straight diagonal at constant speed
     * (5 px per 4 ms sample, 250 Hz) and constant pressure, [SAMPLES] samples long.
     * DOWN, one MOVE carrying two batched historical samples (a vsync-batched frame), then
     * one MOVE per sample (unbuffered dispatch), then UP.
     *
     * Designed so `MotionEventPredictor` (androidx.input 1.0.0) really predicts:
     *  - stylus tool type and `SOURCE_STYLUS`, a single pointer: the API 34 system
     *    predictor only handles stylus sources, else the library's Kalman predictor runs;
     *  - every sample time lies in the recent past ([t0] is chosen by the caller so the
     *    last sample is before "now"). The system predictor only predicts from the last
     *    sample up to now + ≤32 ms, so a gesture timed in the future gets no prediction;
     *  - enough steady samples: the Kalman predictor needs ≥ 4 filter iterations and a
     *    low "jank" estimate (< 0.2), which a constant-velocity line reaches after ~10
     *    samples; the rest of the stroke predicts on every MOVE.
     */
    private fun sample(i: Int) = coords(100f + 4f * i, 100f + 3f * i, 0.5f)

    private fun gesture(h: InkSurfaceHost, lowLatency: Boolean, tool: String, t0: Long): StrokeCommit {
        val instr = InstrumentationRegistry.getInstrumentation()
        var committed: StrokeCommit? = null
        instr.runOnMainSync {
            h.inkView.lowLatency = lowLatency
            h.inkView.tool = tool
            h.inkView.onStrokeCommitted = { committed = it }
            fun send(e: MotionEvent) {
                // Through the host's real dispatch (the wet layer above must not eat it).
                h.dispatchTouchEvent(e)
                e.recycle()
            }
            send(event(t0, t0, MotionEvent.ACTION_DOWN, sample(0)))
            val batched = event(t0, t0 + DT_MS, MotionEvent.ACTION_MOVE, sample(1))
            batched.addBatch(t0 + 2 * DT_MS, arrayOf(sample(2)), 0)
            batched.addBatch(t0 + 3 * DT_MS, arrayOf(sample(3)), 0)
            send(batched)
            for (i in 4 until SAMPLES - 1) {
                send(event(t0, t0 + i * DT_MS, MotionEvent.ACTION_MOVE, sample(i)))
            }
            val last = SAMPLES - 1
            send(event(t0, t0 + last * DT_MS, MotionEvent.ACTION_UP, sample(last)))
        }
        composeRule.waitForIdle()
        return requireNotNull(committed) { "a $tool stroke must commit on pen-up (lowLatency=$lowLatency)" }
    }

    /** A start time that puts the whole gesture in the recent past (see [gesture]). */
    private fun pastT0(): Long = SystemClock.uptimeMillis() - SAMPLES * DT_MS - 100L

    /**
     * Both strokes use the same event times so the one-euro filter sees identical input;
     * any difference in stored points would then come from the capture path.
     */
    private fun assertSameStroke(expected: StrokeCommit, actual: StrokeCommit) {
        assertEquals(expected.tool, actual.tool)
        assertEquals("same point count", expected.stroke.pointCount, actual.stroke.pointCount)
        assertArrayEquals("same stored points", expected.stroke.points, actual.stroke.points, 0f)
        assertEquals("same built stroke (points, count, bbox)", expected.stroke, actual.stroke)
    }

    @Test
    fun pen_on_the_wet_path_runs_the_predictor_and_persists_the_same_points_as_off() {
        val h = host(requireWet = true)
        val t0 = pastT0()

        val on = gesture(h, lowLatency = true, tool = "pen", t0 = t0)
        assertEquals("the stroke was drawn on the wet layer", 1, h.inkView.wetStrokesStarted)
        val predicted = h.inkView.lastStrokePredictedSamples
        Log.i(TAG, "wet path: API ${Build.VERSION.SDK_INT}, predicted samples received = $predicted")
        assertTrue(
            "MotionEventPredictor must produce predicted samples on the wet path " +
                "(got $predicted); otherwise this test proves nothing about prediction isolation",
            predicted > 0,
        )

        val off = gesture(h, lowLatency = false, tool = "pen", t0 = t0)
        assertEquals("the off stroke never used the wet layer", 1, h.inkView.wetStrokesStarted)
        assertEquals("no prediction with the switch off", 0, h.inkView.lastStrokePredictedSamples)

        assertEquals(SAMPLES, off.stroke.pointCount)
        assertSameStroke(off, on)
    }

    @Test
    fun marker_with_low_latency_on_keeps_the_existing_path() {
        val h = host(requireWet = false)
        val t0 = pastT0()
        val off = gesture(h, lowLatency = false, tool = "marker", t0 = t0)
        val on = gesture(h, lowLatency = true, tool = "marker", t0 = t0)
        assertSameStroke(off, on)
        assertEquals("marker never draws on the wet layer", 0, h.inkView.wetStrokesStarted)
    }

    @Test
    fun eraser_with_low_latency_on_still_erases() {
        val h = host(requireWet = false)
        val instr = InstrumentationRegistry.getInstrumentation()
        var erased: String? = null
        instr.runOnMainSync {
            val pts = floatArrayOf(90f, 90f, 0.5f, 0f, 0f, 110f, 110f, 0.5f, 0f, 16f)
            h.inkView.setCommittedStrokes(
                listOf(RenderStroke("s1", pts, "pen", android.graphics.Color.BLACK, 3f, 90f, 90f, 20f, 20f)),
            )
            h.inkView.lowLatency = true
            h.inkView.tool = "eraser"
            h.inkView.onEraseStroke = { erased = it }
            val t0 = SystemClock.uptimeMillis()
            h.inkView.onTouchEvent(event(t0, t0, MotionEvent.ACTION_DOWN, coords(100f, 100f, 0.5f)))
            h.inkView.onTouchEvent(event(t0, t0 + 8, MotionEvent.ACTION_UP, coords(100f, 100f, 0.5f)))
        }
        assertNotNull("the eraser must still hit the stroke", erased)
        assertEquals("s1", erased)
        assertEquals(0, h.inkView.wetStrokesStarted)
    }

    private companion object {
        const val TAG = "LowLatencyInkTest"
        const val SAMPLES = 40
        const val DT_MS = 4L
        const val WET_SURFACE_TIMEOUT_MS = 10_000L
    }
}
