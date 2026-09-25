package com.inkwell

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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 31: the low-latency pen with the front-buffered wet layer **attached** (an
 * [InkSurfaceHost] in a real window, as `CanvasScreen` hosts it).
 *
 * These assert the data path only, never front-buffer pixels (emulators may not present
 * front buffers). If the emulator never gives the wet layer a surface, the pen falls back
 * to the View path and the same assertions still hold — that fallback is part of the
 * contract too.
 *
 *  - a pen stroke persists exactly the points it persists with the switch off;
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

    /** Host an [InkSurfaceHost] and wait (bounded) for its wet surface to come up. */
    private fun host(): InkSurfaceHost {
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
        runCatching { composeRule.waitUntil(5_000) { h.wetLayer.isAvailable } }
        return h
    }

    /** DOWN + MOVE (history 2) + a second MOVE + UP, the synthetic stylus stroke. */
    private fun stroke(h: InkSurfaceHost, lowLatency: Boolean, tool: String, t0: Long): StrokeCommit {
        val instr = InstrumentationRegistry.getInstrumentation()
        var committed: StrokeCommit? = null
        instr.runOnMainSync {
            h.inkView.lowLatency = lowLatency
            h.inkView.tool = tool
            h.inkView.onStrokeCommitted = { committed = it }
            h.inkView.onTouchEvent(event(t0, t0, MotionEvent.ACTION_DOWN, coords(100f, 100f, 0.5f)))
            val move = event(t0, t0 + 4, MotionEvent.ACTION_MOVE, coords(110f, 120f, 0.6f))
            move.addBatch(t0 + 8, arrayOf(coords(130f, 150f, 0.7f)), 0)
            move.addBatch(t0 + 12, arrayOf(coords(160f, 190f, 0.8f)), 0)
            h.inkView.onTouchEvent(move)
            h.inkView.onTouchEvent(event(t0, t0 + 16, MotionEvent.ACTION_MOVE, coords(180f, 215f, 0.6f)))
            h.inkView.onTouchEvent(event(t0, t0 + 20, MotionEvent.ACTION_UP, coords(200f, 240f, 0.4f)))
        }
        composeRule.waitForIdle()
        return requireNotNull(committed) { "a $tool stroke must commit on pen-up (lowLatency=$lowLatency)" }
    }

    /**
     * Both strokes use the same event times ([t0]) so the one-euro filter sees identical
     * input; any difference in stored points would then come from the capture path.
     */
    private fun assertSameStroke(expected: StrokeCommit, actual: StrokeCommit) {
        assertEquals(expected.tool, actual.tool)
        assertEquals("same point count", expected.stroke.pointCount, actual.stroke.pointCount)
        assertArrayEquals("same stored points", expected.stroke.points, actual.stroke.points, 0f)
    }

    @Test
    fun pen_with_low_latency_on_persists_the_same_points_as_off() {
        val h = host()
        val t0 = android.os.SystemClock.uptimeMillis()
        val off = stroke(h, lowLatency = false, tool = "pen", t0 = t0)
        val on = stroke(h, lowLatency = true, tool = "pen", t0 = t0)
        assertEquals(1 + 3 + 1 + 1, off.stroke.pointCount)
        assertSameStroke(off, on)
        // The wet layer is used only when its surface exists; either way, same data.
        if (h.wetLayer.isAvailable) assertEquals(1, h.inkView.wetStrokesStarted)
    }

    @Test
    fun marker_with_low_latency_on_keeps_the_existing_path() {
        val h = host()
        val t0 = android.os.SystemClock.uptimeMillis()
        val off = stroke(h, lowLatency = false, tool = "marker", t0 = t0)
        val on = stroke(h, lowLatency = true, tool = "marker", t0 = t0)
        assertSameStroke(off, on)
        assertEquals("marker never draws on the wet layer", 0, h.inkView.wetStrokesStarted)
    }

    @Test
    fun eraser_with_low_latency_on_still_erases() {
        val h = host()
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
            val t0 = android.os.SystemClock.uptimeMillis()
            h.inkView.onTouchEvent(event(t0, t0, MotionEvent.ACTION_DOWN, coords(100f, 100f, 0.5f)))
            h.inkView.onTouchEvent(event(t0, t0 + 8, MotionEvent.ACTION_UP, coords(100f, 100f, 0.5f)))
        }
        assertNotNull("the eraser must still hit the stroke", erased)
        assertEquals("s1", erased)
        assertEquals(0, h.inkView.wetStrokesStarted)
    }
}
