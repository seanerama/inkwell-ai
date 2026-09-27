package com.inkwell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkwell.data.CanvasRepository
import com.inkwell.data.InkDatabase
import com.inkwell.data.LibraryRepository
import com.inkwell.data.PackedPoints
import com.inkwell.data.PageExtent
import com.inkwell.ink.InkSettings
import com.inkwell.ink.InkSurfaceHost
import com.inkwell.ink.InkView
import com.inkwell.ui.CanvasScreen
import com.inkwell.ui.CanvasTags
import com.inkwell.ui.CanvasViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 34 (ADR-0014 §2): the expandable canvas end to end — the real [CanvasScreen] over a
 * [CanvasViewModel] and an in-memory Room database (stroke + growth in one
 * `withTransaction`), with synthetic stylus gestures, each case run with the low-latency pen
 * **on** (the wet layer; stage 33 default) and **off**.
 *
 * View geometry: scale [S] view px per canvas unit, page (0,0) at view ([TX], [TY]). A4 pages
 * (2480 × 3508 CU) are 124 × 175 px, so column −2 is view x < 76, column −1 is 76..200,
 * column 0 is 200..324 and column 1 is 324..448 (row 0 is view y 50..225). This fits any
 * test screen.
 *
 * Regression (the v0.0.20 report, [regression_ink_across_the_old_page_edge_stays_after_pen_up_on]):
 * a stroke written across the page edge must still be drawn after pen-up. It draws the
 * [InkView] into a bitmap and checks a pixel of the committed stroke beyond the old edge. The
 * committed ink renders only from tiles of pages inside the grid
 * ([com.inkwell.render.TileMath.pagesTouching] clamps to the extent), so with the grid left at
 * one page — the pre-stage behaviour — that pixel is the page surround, not ink.
 * [switch_off_the_same_stroke_is_not_drawn_beyond_the_page] runs the same probe with the grid
 * kept at one page and asserts exactly that, which shows the probe fails on pre-stage
 * behaviour.
 */
@RunWith(AndroidJUnit4::class)
class ExpandableCanvasInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: InkDatabase
    private lateinit var repo: CanvasRepository
    private lateinit var canvasId: String
    private lateinit var vm: CanvasViewModel
    private lateinit var inkView: InkView
    private lateinit var root: View

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, InkDatabase::class.java).build()
        repo = newRepository()
        runBlocking {
            val spaceId = repo.ensureSeededSpaceId()
            canvasId = LibraryRepository(db.folderDao(), db.canvasDao(), db.layerDao()).createCanvas(spaceId, null).id
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun newRepository() = CanvasRepository(
        db.spaceDao(), db.canvasDao(), db.layerDao(), db.strokeDao(),
        runInTransaction = { block -> db.withTransaction { block() } },
    )

    // --- Harness ---

    private fun open(lowLatency: Boolean, expandable: Boolean = true, seed: PageExtent? = null, scale: Float = S, tx: Float = TX, ty: Float = TY) {
        seed?.let { runBlocking { repo.updatePageExtent(canvasId, it) } }
        composeRule.runOnUiThread {
            vm = CanvasViewModel(repository = repo, autoOpenDefault = false, expandableCanvas = expandable)
        }
        composeRule.setContent {
            CanvasScreen(
                viewModel = vm,
                onOpenSettings = {},
                debugEnabled = false,
                inkSettings = InkSettings(lowLatencyPen = lowLatency),
            )
        }
        composeRule.runOnUiThread { vm.openCanvas(canvasId) }
        composeRule.waitUntil(TIMEOUT_MS) { vm.ready }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            inkView = requireNotNull(findInkView(composeRule.activity.window.decorView)) { "no InkView in the canvas" }
            root = (inkView.parent as? InkSurfaceHost) ?: inkView
        }
        val host = root as? InkSurfaceHost
        if (host != null) {
            runCatching { composeRule.waitUntil(WET_SURFACE_TIMEOUT_MS) { host.wetLayer.isAvailable } }
            Log.i(TAG, "wet layer available = ${host.wetLayer.isAvailable}")
        }
        composeRule.runOnUiThread {
            assertEquals(expandable, inkView.expandableCanvas)
            inkView.setTransform(scale, tx, ty)
        }
    }

    private fun findInkView(v: View): InkView? {
        if (v is InkView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findInkView(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun stylus(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x; this.y = y; pressure = 0.8f
                setAxisValue(MotionEvent.AXIS_TILT, 0.1f)
            },
        )
        return MotionEvent.obtain(downTime, eventTime, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
    }

    /**
     * A straight stylus stroke from ([x0], [y0]) to ([x1], [y1]) in view px: DOWN, a MOVE per
     * sample, UP — [SAMPLES] samples in all, timed in the recent past (so the predictor runs on
     * the wet path), dispatched through the canvas host as the window would.
     */
    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float) {
        val t0 = SystemClock.uptimeMillis() - SAMPLES * DT_MS - 100L
        composeRule.runOnUiThread {
            for (i in 0 until SAMPLES) {
                val f = i / (SAMPLES - 1f)
                val action = when (i) {
                    0 -> MotionEvent.ACTION_DOWN
                    SAMPLES - 1 -> MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_MOVE
                }
                val e = stylus(t0, t0 + i * DT_MS, action, x0 + (x1 - x0) * f, y0 + (y1 - y0) * f)
                root.dispatchTouchEvent(e)
                e.recycle()
            }
        }
    }

    private fun tap(x: Float, y: Float) {
        val t0 = SystemClock.uptimeMillis()
        composeRule.runOnUiThread {
            for ((i, action) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).withIndex()) {
                val e = stylus(t0, t0 + i * 8L, action, x, y)
                root.dispatchTouchEvent(e)
                e.recycle()
            }
        }
    }

    private fun strokeCount(): Int = runBlocking { db.strokeDao().count() }

    private fun storedExtent(): PageExtent = runBlocking {
        val c = requireNotNull(db.canvasDao().byId(canvasId))
        PageExtent(c.pageMinCol, c.pageMaxCol, c.pageMinRow, c.pageMaxRow)
    }

    /** Wait until [n] strokes are stored and the view model (so the view) holds them. */
    private fun awaitStrokes(n: Int) {
        composeRule.waitUntil(TIMEOUT_MS) { strokeCount() == n }
        composeRule.waitUntil(TIMEOUT_MS) { vm.strokes.size == n }
        composeRule.waitForIdle()
    }

    private fun storedPoints(): FloatArray = runBlocking {
        val layer = db.layerDao().forCanvas(canvasId).first { it.type == "ink" }
        val stroke = db.strokeDao().forLayer(layer.id).single()
        PackedPoints.decode(stroke.points, stroke.pointCount)
    }

    private fun vx(cu: Float) = cu * S + TX

    // --- A stroke started in the left ring ---

    private fun ringLeftStrokeGrowsTheGrid(lowLatency: Boolean) {
        open(lowLatency)
        // Entirely in column −1 (view x 76..200).
        stroke(180f, 100f, 90f, 140f)
        awaitStrokes(1)

        val points = storedPoints()
        assertEquals("one stroke with every sample", SAMPLES, points.size / PackedPoints.STRIDE)
        val expected = PageExtent(-1, 0, 0, 0)
        assertEquals("page_min_col = -1", expected, storedExtent())
        assertEquals(expected, vm.pageExtent)
        composeRule.runOnUiThread { assertEquals(expected, inkView.currentPageExtent) }

        // Reopening the canvas keeps the grid (fresh repository = a fresh process's view of Room).
        val reopened = runBlocking { newRepository().openCanvas(canvasId) }
        assertEquals(expected, reopened?.pageExtent)
    }

    @Test
    fun ring_left_stroke_commits_whole_and_grows_the_grid_low_latency_on() = ringLeftStrokeGrowsTheGrid(lowLatency = true)

    @Test
    fun ring_left_stroke_commits_whole_and_grows_the_grid_low_latency_off() = ringLeftStrokeGrowsTheGrid(lowLatency = false)

    // --- A stroke started beyond the ring ---

    private fun strokeBeyondTheRingDoesNothing(lowLatency: Boolean) {
        open(lowLatency)
        // Starts in column −2 (beyond the ring) and moves into the ring: still nothing.
        stroke(40f, 100f, 150f, 100f)
        composeRule.waitForIdle()
        SystemClock.sleep(SETTLE_MS)

        assertEquals("nothing committed", 0, strokeCount())
        assertEquals(PageExtent.SINGLE, storedExtent())
        assertEquals(PageExtent.SINGLE, vm.pageExtent)
        composeRule.runOnUiThread {
            assertEquals("the start was refused", 1, inkView.strokeStartsRefused)
            assertEquals(0, inkView.wetStrokesStarted)
        }
    }

    @Test
    fun stroke_started_beyond_the_ring_commits_nothing_low_latency_on() = strokeBeyondTheRingDoesNothing(lowLatency = true)

    @Test
    fun stroke_started_beyond_the_ring_commits_nothing_low_latency_off() = strokeBeyondTheRingDoesNothing(lowLatency = false)

    // --- At the cap ---

    private fun strokeAtTheCapCommitsAndKeepsEight(lowLatency: Boolean) {
        val full = PageExtent(-7, 0, 0, 0) // eight columns: no ring left or right
        open(lowLatency, seed = full)
        // From column 0 across the grid's right edge into column 1 (view x 324..448).
        stroke(250f, 120f, 420f, 130f)
        awaitStrokes(1)

        val points = storedPoints()
        assertEquals("all real points are stored", SAMPLES, points.size / PackedPoints.STRIDE)
        val maxX = (0 until SAMPLES).maxOf { points[it * PackedPoints.STRIDE] }
        assertTrue("points beyond the grid stay stored (max x = $maxX)", maxX > 2480f)
        assertEquals("the grid stays at 8 columns", full, storedExtent())
        assertEquals(full, vm.pageExtent)
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(CanvasTags.CANVAS_NOTICE).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(CanvasViewModel.CAP_NOTICE, vm.canvasNotice)
    }

    @Test
    fun stroke_at_the_cap_commits_and_the_grid_stays_eight_low_latency_on() = strokeAtTheCapCommitsAndKeepsEight(lowLatency = true)

    @Test
    fun stroke_at_the_cap_commits_and_the_grid_stays_eight_low_latency_off() = strokeAtTheCapCommitsAndKeepsEight(lowLatency = false)

    // --- Eraser across pages; undo and erase never shrink the grid ---

    private fun eraserWorksAcrossPages(lowLatency: Boolean) {
        open(lowLatency)
        // Horizontal (constant y, so the filtered points lie on the line) from column 0 into
        // column −1: the grid grows left and the stroke spans two pages.
        stroke(290f, 150f, 100f, 150f)
        awaitStrokes(1)
        assertEquals(PageExtent(-1, 0, 0, 0), storedExtent())

        // Erase it by touching its part on the new page (column −1).
        composeRule.runOnUiThread { vm.selectTool("eraser") }
        composeRule.waitForIdle()
        tap(150f, 150f)
        awaitStrokes(0)
        assertEquals("erase never shrinks the grid", PageExtent(-1, 0, 0, 0), storedExtent())

        // A stroke into the right ring, then undo it: the grid keeps both new pages.
        composeRule.runOnUiThread { vm.selectTool("pen") }
        composeRule.waitForIdle()
        stroke(300f, 100f, 380f, 110f)
        awaitStrokes(1)
        assertEquals(PageExtent(-1, 1, 0, 0), storedExtent())
        composeRule.runOnUiThread { vm.undoLast() }
        awaitStrokes(0)
        assertEquals("undo never shrinks the grid", PageExtent(-1, 1, 0, 0), storedExtent())
        assertEquals(PageExtent(-1, 1, 0, 0), vm.pageExtent)
    }

    @Test
    fun eraser_works_across_pages_low_latency_on() = eraserWorksAcrossPages(lowLatency = true)

    @Test
    fun eraser_works_across_pages_low_latency_off() = eraserWorksAcrossPages(lowLatency = false)

    // --- Regression: ink across the old page edge no longer vanishes on pen-up ---

    /**
     * Zoomed in (4 px per CU) on the right edge of page (0,0), which sits at view x [EDGE_X]:
     * write a horizontal stroke from inside the page across the edge, wait for its dry copy,
     * and return whether the pixel at view ([PROBE_X], [PROBE_Y]) — on the stroke, beyond the
     * old edge — is drawn as ink by the committed renderer.
     */
    private fun inkBeyondTheOldEdgeAfterPenUp(lowLatency: Boolean, expandable: Boolean): Boolean {
        val scale = 4f
        open(lowLatency, expandable = expandable, scale = scale, tx = EDGE_X - 2480f * scale, ty = PROBE_Y - 400f * scale)
        stroke(EDGE_X - 80f, PROBE_Y, EDGE_X + 120f, PROBE_Y)
        awaitStrokes(1)
        SystemClock.sleep(SETTLE_MS)
        composeRule.waitForIdle()
        var pixel = 0
        composeRule.runOnUiThread {
            val bmp = Bitmap.createBitmap(inkView.width, inkView.height, Bitmap.Config.ARGB_8888)
            inkView.draw(Canvas(bmp))
            // The darkest pixel within ±3 px vertically (the stroke is ~10 px wide here).
            pixel = (-3..3).map { bmp.getPixel(PROBE_X.toInt(), PROBE_Y.toInt() + it) }
                .minBy { Color.red(it) + Color.green(it) + Color.blue(it) }
            bmp.recycle()
        }
        Log.i(TAG, "probe pixel lowLatency=$lowLatency expandable=$expandable: #${Integer.toHexString(pixel)}")
        return Color.red(pixel) < INK_MAX && Color.green(pixel) < INK_MAX && Color.blue(pixel) < INK_MAX
    }

    @Test
    fun regression_ink_across_the_old_page_edge_stays_after_pen_up_on() {
        assertTrue(
            "ink written across the page edge must still show after pen-up (low-latency on)",
            inkBeyondTheOldEdgeAfterPenUp(lowLatency = true, expandable = true),
        )
        assertEquals(PageExtent(0, 1, 0, 0), storedExtent())
    }

    @Test
    fun regression_ink_across_the_old_page_edge_stays_after_pen_up_off() {
        assertTrue(
            "ink written across the page edge must still show after pen-up (low-latency off)",
            inkBeyondTheOldEdgeAfterPenUp(lowLatency = false, expandable = true),
        )
        assertEquals(PageExtent(0, 1, 0, 0), storedExtent())
    }

    /**
     * The same probe with the grid left at one page (the switch off: the pre-stage behaviour
     * for committed ink). The pixel beyond the edge is NOT ink — so the regression assertion
     * above fails on pre-stage behaviour.
     */
    @Test
    fun switch_off_the_same_stroke_is_not_drawn_beyond_the_page() {
        assertFalse(inkBeyondTheOldEdgeAfterPenUp(lowLatency = false, expandable = false))
        assertEquals("no growth with the switch off", PageExtent.SINGLE, storedExtent())
        assertEquals("the stroke is still stored", 1, strokeCount())
    }

    @Test
    fun switch_off_a_stroke_cannot_start_in_the_ring() {
        open(lowLatency = false, expandable = false)
        stroke(180f, 100f, 90f, 140f) // column −1: the ring when the switch is on
        composeRule.waitForIdle()
        SystemClock.sleep(SETTLE_MS)
        assertEquals(0, strokeCount())
        assertEquals(PageExtent.SINGLE, storedExtent())
        composeRule.runOnUiThread { assertEquals(1, inkView.strokeStartsRefused) }
        // ...but it still starts on the page.
        stroke(vx(1000f), 100f, vx(2000f), 120f)
        awaitStrokes(1)
        assertEquals(PageExtent.SINGLE, storedExtent())
    }

    private companion object {
        const val TAG = "ExpandableCanvasTest"
        const val S = 0.05f
        const val TX = 200f
        const val TY = 50f
        const val SAMPLES = 24
        const val DT_MS = 4L
        const val TIMEOUT_MS = 5_000L
        const val WET_SURFACE_TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 400L

        /** Regression probe: page (0,0)'s right edge on screen, and a point on the stroke beyond it. */
        const val EDGE_X = 200f
        const val PROBE_X = 260f
        const val PROBE_Y = 150f
        const val INK_MAX = 110
    }
}
