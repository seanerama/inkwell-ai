package com.inkwell

import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.inkwell.data.CanvasRepository
import com.inkwell.data.InkDatabase
import com.inkwell.data.StrokeCommitData
import com.inkwell.ink.InkView
import com.inkwell.ink.StrokeCommit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one Stage-3 instrumented test (emulator lane in release.yml, not a fast gate):
 * inject a synthetic stylus [MotionEvent] sequence *with batched history* into
 * [InkView] and assert it produces exactly one stroke with the expected point count,
 * then persist it through [CanvasRepository] and read it back (ink survives).
 *
 * Point-count arithmetic (§9.2(2): historical samples are never dropped):
 *   DOWN (1) + MOVE with historySize 2 (2 + 1 current = 3) + UP (1) = 5.
 *
 * Stage 31: the same synthetic stroke with the low-latency pen **on** persists exactly the
 * same points as with it **off** (contract `ink-storage`). This view is detached (no wet
 * layer), so the predictor may record the events but never predicts here: the stroke takes
 * the View path. The proof that the predictor really ran on the wet path and still changed
 * nothing stored is `LowLatencyInkInstrumentedTest` (stage 33).
 */
@RunWith(AndroidJUnit4::class)
class InkCaptureInstrumentedTest {

    private lateinit var db: InkDatabase
    private lateinit var repo: CanvasRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, InkDatabase::class.java).build()
        repo = CanvasRepository(
            spaceDao = db.spaceDao(),
            canvasDao = db.canvasDao(),
            layerDao = db.layerDao(),
            strokeDao = db.strokeDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun stylusProps() = arrayOf(
        MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_STYLUS
        },
    )

    private fun coords(x: Float, y: Float, pressure: Float, tilt: Float) =
        MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            this.pressure = pressure
            setAxisValue(MotionEvent.AXIS_TILT, tilt)
        }

    private fun event(
        downTime: Long,
        eventTime: Long,
        action: Int,
        c: MotionEvent.PointerCoords,
    ): MotionEvent = MotionEvent.obtain(
        downTime, eventTime, action, 1, stylusProps(), arrayOf(c),
        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0,
    )

    /** Inject the canonical DOWN + MOVE(history 2) + UP stroke and return its commit. */
    private fun captureSyntheticStroke(lowLatency: Boolean): StrokeCommit {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val instr = InstrumentationRegistry.getInstrumentation()
        var committed: StrokeCommit? = null
        lateinit var view: InkView
        instr.runOnMainSync {
            view = InkView(context).apply {
                setCanvasSize(2480, 3508)
                this.lowLatency = lowLatency
                onStrokeCommitted = { committed = it }
            }
        }
        val t0 = 1000L
        instr.runOnMainSync {
            view.onTouchEvent(event(t0, t0, MotionEvent.ACTION_DOWN, coords(100f, 100f, 0.5f, 0.1f)))
            val move = event(t0, t0 + 16, MotionEvent.ACTION_MOVE, coords(110f, 120f, 0.6f, 0.1f))
            move.addBatch(t0 + 32, arrayOf(coords(130f, 150f, 0.7f, 0.1f)), 0)
            move.addBatch(t0 + 48, arrayOf(coords(160f, 190f, 0.8f, 0.1f)), 0)
            view.onTouchEvent(move)
            view.onTouchEvent(event(t0, t0 + 64, MotionEvent.ACTION_UP, coords(200f, 240f, 0.4f, 0.1f)))
        }
        return requireNotNull(committed) { "a stroke must be committed on pen-up (lowLatency=$lowLatency)" }
    }

    @Test
    fun low_latency_on_persists_the_same_points_as_off() {
        val off = captureSyntheticStroke(lowLatency = false)
        val on = captureSyntheticStroke(lowLatency = true)

        assertEquals(5, off.stroke.pointCount)
        assertEquals("same point count with low-latency on", off.stroke.pointCount, on.stroke.pointCount)
        assertArrayEquals("same points with low-latency on", off.stroke.points, on.stroke.points, 0f)
        assertEquals(off.stroke, on.stroke)

        // Persisted with low-latency on: exactly one stroke, identical blob contents.
        runBlocking {
            val state = repo.openDefaultCanvas()
            repo.insertStroke(
                layerId = state.inkLayerId,
                commit = StrokeCommitData(on.stroke, on.tool, on.colorHex, on.widthCu),
            )
            val reloaded = repo.loadStrokes(state.inkLayerId)
            assertEquals(1, reloaded.size)
            assertEquals(5, reloaded.first().pointCount)
            val decoded = com.inkwell.data.PackedPoints.decode(reloaded.first().points, 5)
            assertArrayEquals(off.stroke.points, decoded, 0f)
        }
    }

    @Test
    fun synthetic_stylus_stroke_with_history_persists_one_stroke() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val instr = InstrumentationRegistry.getInstrumentation()

        var committed: StrokeCommit? = null
        lateinit var view: InkView
        instr.runOnMainSync {
            view = InkView(context).apply {
                setCanvasSize(2480, 3508)
                onStrokeCommitted = { committed = it }
            }
        }

        val t0 = 1000L
        instr.runOnMainSync {
            // DOWN — 1 sample.
            view.onTouchEvent(event(t0, t0, MotionEvent.ACTION_DOWN, coords(100f, 100f, 0.5f, 0.1f)))

            // MOVE with two batched historical samples → 3 samples.
            val move = event(t0, t0 + 16, MotionEvent.ACTION_MOVE, coords(110f, 120f, 0.6f, 0.1f))
            move.addBatch(t0 + 32, arrayOf(coords(130f, 150f, 0.7f, 0.1f)), 0)
            move.addBatch(t0 + 48, arrayOf(coords(160f, 190f, 0.8f, 0.1f)), 0)
            view.onTouchEvent(move)

            // UP — 1 sample.
            view.onTouchEvent(event(t0, t0 + 64, MotionEvent.ACTION_UP, coords(200f, 240f, 0.4f, 0.1f)))
        }

        val commit = committed
        assertNotNull("a stroke must be committed on pen-up", commit)
        commit!!
        assertEquals("expected 1 + 3 + 1 = 5 points", 5, commit.stroke.pointCount)
        assertEquals("pen", commit.tool)

        // Persist and read back.
        runBlocking {
            val state = repo.openDefaultCanvas()
            repo.insertStroke(
                layerId = state.inkLayerId,
                commit = StrokeCommitData(
                    stroke = commit.stroke,
                    tool = commit.tool,
                    colorHex = commit.colorHex,
                    widthCu = commit.widthCu,
                ),
            )
            val reloaded = repo.loadStrokes(state.inkLayerId)
            assertEquals(1, reloaded.size)
            assertEquals(5, reloaded.first().pointCount)
            // Invariant holds on the stored blob.
            assertEquals(5 * 5 * 4, reloaded.first().points.size)
        }
    }
}
