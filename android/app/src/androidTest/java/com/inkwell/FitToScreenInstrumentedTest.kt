package com.inkwell

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkwell.data.CanvasRepository
import com.inkwell.data.InkDatabase
import com.inkwell.data.LayerRepository
import com.inkwell.data.LibraryRepository
import com.inkwell.data.PackedPoints
import com.inkwell.data.PageExtent
import com.inkwell.data.StrokeCommitData
import com.inkwell.ink.BuiltStroke
import com.inkwell.ink.InkSettings
import com.inkwell.ink.InkSurfaceHost
import com.inkwell.ink.InkView
import com.inkwell.net.CardResponse
import com.inkwell.net.DeviceApi
import com.inkwell.net.DeviceRepository
import com.inkwell.net.HealthResponse
import com.inkwell.net.Job
import com.inkwell.net.JobCreateRequest
import com.inkwell.net.Space
import com.inkwell.net.SyncResponse
import com.inkwell.render.CanvasExporter
import com.inkwell.render.CoordinateMapping
import com.inkwell.render.ExportLayer
import com.inkwell.render.InkFixtures
import com.inkwell.render.LegacyLayerRenderer
import com.inkwell.ui.CanvasScreen
import com.inkwell.ui.CanvasTags
import com.inkwell.ui.CanvasViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import kotlin.math.abs

/**
 * Stage 36: every canvas opens fitted to its page grid, and the Fit control brings that view
 * back — end to end over the real [CanvasScreen], a [CanvasViewModel], an in-memory Room database
 * and a fake device API that records the `POST /jobs` body.
 *
 *  - A single-page note opens with the whole page on screen, and Ask's export is byte for byte
 *    the pre-stage-35 whole-page export (the stage 32/35 parity fixture through the legacy
 *    pipeline).
 *  - A 2 × 1 canvas opens with both pages on screen.
 *  - Growing the grid mid-session does not move the view; a Fit asked for mid-stroke waits for
 *    pen-up.
 *  - After a pan/zoom, Fit restores the fitted view.
 *  - With the low-latency pen on, strokes after a Fit commit at the right canvas positions.
 */
@RunWith(AndroidJUnit4::class)
class FitToScreenInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: InkDatabase
    private lateinit var repo: CanvasRepository
    private lateinit var canvasId: String
    private lateinit var vm: CanvasViewModel
    private lateinit var inkView: InkView
    private lateinit var root: View
    private val api = FakeDeviceApi()

    private val pageW = CoordinateMapping.DEFAULT_WIDTH_CU // 2480
    private val pageH = CoordinateMapping.DEFAULT_HEIGHT_CU // 3508

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, InkDatabase::class.java).build()
        repo = CanvasRepository(
            db.spaceDao(), db.canvasDao(), db.layerDao(), db.strokeDao(),
            runInTransaction = { block -> db.withTransaction { block() } },
        )
        runBlocking {
            val spaceId = repo.ensureSeededSpaceId()
            canvasId = LibraryRepository(db.folderDao(), db.canvasDao(), db.layerDao()).createCanvas(spaceId, null).id
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- Harness ---

    /** Seed the canvas with the stage 32/35 parity fixture (about 220 strokes, some off page). */
    private fun seedParityFixture() = runBlocking {
        val layerId = requireNotNull(repo.openCanvas(canvasId)).inkLayerId
        for (s in InkFixtures.handwriting()) {
            val data = StrokeCommitData(
                stroke = BuiltStroke(s.points, s.points.size / PackedPoints.STRIDE, s.bboxX, s.bboxY, s.bboxW, s.bboxH),
                tool = s.tool,
                colorHex = String.format("#%06X", s.color and 0xFFFFFF),
                widthCu = s.widthCu,
            )
            repo.insertStroke(layerId, data)
        }
    }

    /**
     * Open the canvas in the real screen and wait until the open-fit has been applied.
     *
     * [screenPx] pins the whole canvas screen (top bar, toolbar and canvas) to that size in px,
     * whatever the emulator's display — the canvas view is then its full width and that height
     * less the toolbar — so geometry assertions run on the product's target (tablet) size. The
     * screen is aligned top-end, so the pinned Fit control stays inside the window when the
     * screen is larger than the display. Null leaves the screen at the emulator's size.
     */
    private fun open(grid: PageExtent, lowLatency: Boolean = false, screenPx: Pair<Int, Int>? = TABLET_SCREEN_PX) {
        runBlocking { repo.updatePageExtent(canvasId, grid) }
        composeRule.runOnUiThread {
            vm = CanvasViewModel(
                repository = repo,
                layerRepository = LayerRepository(db.layerDao()),
                deviceRepositoryProvider = { DeviceRepository(api) },
                sendEnabled = true,
                oneTapAsk = true,
                cardActionsEnabled = false,
                spacesEnabled = false,
                autoOpenDefault = false,
                expandableCanvas = true,
            )
        }
        composeRule.setContent {
            val screen = @androidx.compose.runtime.Composable {
                CanvasScreen(
                    viewModel = vm,
                    onOpenSettings = {},
                    debugEnabled = false,
                    inkSettings = InkSettings(lowLatencyPen = lowLatency),
                )
            }
            if (screenPx == null) {
                screen()
            } else {
                val density = LocalDensity.current
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .wrapContentSize(Alignment.TopEnd, unbounded = true)
                        .requiredSize(
                            with(density) { screenPx.first.toDp() },
                            with(density) { screenPx.second.toDp() },
                        ),
                ) { screen() }
            }
        }
        composeRule.runOnUiThread { vm.openCanvas(canvasId) }
        composeRule.waitUntil(TIMEOUT_MS) { vm.ready }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            inkView = requireNotNull(findInkView(composeRule.activity.window.decorView)) { "no InkView" }
            root = (inkView.parent as? InkSurfaceHost) ?: inkView
        }
        if (screenPx != null) {
            onUi {
                // Pinned: the canvas view spans the screen's width (±1 px of dp rounding).
                assertEquals("pinned canvas width", screenPx.first.toFloat(), inkView.width.toFloat(), 1f)
                assertTrue("pinned canvas height ${inkView.height}", inkView.height in (screenPx.second / 2) until screenPx.second)
            }
        }
        awaitFitted()
    }

    private fun findInkView(v: View): InkView? {
        if (v is InkView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findInkView(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun onUi(block: () -> Unit) = composeRule.runOnUiThread(block)

    private fun transform(): FloatArray {
        var t = FloatArray(3)
        onUi { t = inkView.currentTransform }
        return t
    }

    private fun fitted(): FloatArray {
        var t = FloatArray(3)
        onUi { t = inkView.fittedTransform() }
        return t
    }

    private fun sameTransform(a: FloatArray, b: FloatArray): Boolean =
        abs(a[0] - b[0]) <= 1e-6f && abs(a[1] - b[1]) <= 1e-3f && abs(a[2] - b[2]) <= 1e-3f

    /** Wait until the requested fit has landed (at the view's laid-out size). */
    private fun awaitFitted() {
        composeRule.waitUntil(TIMEOUT_MS) {
            var done = false
            onUi {
                done = inkView.width > 0 && inkView.height > 0 && !inkView.fitPending &&
                    sameTransform(inkView.currentTransform, inkView.fittedTransform())
            }
            done
        }
        composeRule.waitForIdle()
    }

    /** The page grid's rect in view px through the current transform. */
    private fun gridInView(): FloatArray {
        val (s, tx, ty) = transform().let { Triple(it[0], it[1], it[2]) }
        val g = vm.pageExtent
        return floatArrayOf(
            g.leftCu(pageW).toFloat() * s + tx,
            g.topCu(pageH).toFloat() * s + ty,
            g.rightCu(pageW).toFloat() * s + tx,
            g.bottomCu(pageH).toFloat() * s + ty,
        )
    }

    /** Every page of the grid is on screen, clear of the view's edges. */
    private fun assertWholeGridOnScreen() {
        val r = gridInView()
        var w = 0
        var h = 0
        onUi { w = inkView.width; h = inkView.height }
        val info = "grid ${vm.pageExtent} at view ${r.toList()} in ${w}×$h, transform ${transform().toList()}"
        assertTrue("left edge on screen — $info", r[0] > 0f)
        assertTrue("top edge on screen — $info", r[1] > 0f)
        assertTrue("right edge on screen — $info", r[2] < w)
        assertTrue("bottom edge on screen — $info", r[3] < h)
        val g = vm.pageExtent
        assertEquals(
            "the export region is the whole grid — $info",
            CoordinateMapping.Region(g.minCol * pageW, g.minRow * pageH, g.cols * pageW, g.rows * pageH),
            vm.currentExportRegion(),
        )
        assertNull("Send is not blocked — $info", vm.sendBlockedHint)
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
     * Dispatch stylus samples [from] until [until] (exclusive) of a straight [SAMPLES]-sample
     * stroke from view ([x0], [y0]) to ([x1], [y1]) — DOWN at 0, UP at the last — timed in the
     * recent past from [t0] (so the predictor runs on the wet path).
     */
    private fun strokePart(t0: Long, x0: Float, y0: Float, x1: Float, y1: Float, from: Int, until: Int) {
        onUi {
            for (i in from until until) {
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

    private fun pastT0(): Long = SystemClock.uptimeMillis() - SAMPLES * DT_MS - 100L

    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float) =
        strokePart(pastT0(), x0, y0, x1, y1, 0, SAMPLES)

    private fun strokeCount(): Int = runBlocking { db.strokeDao().count() }

    private fun awaitStrokes(n: Int) {
        composeRule.waitUntil(TIMEOUT_MS) { strokeCount() == n }
        composeRule.waitUntil(TIMEOUT_MS) { vm.strokes.size == n }
        composeRule.waitForIdle()
    }

    private fun lastStoredPoints(): FloatArray = runBlocking {
        val layer = db.layerDao().forCanvas(canvasId).first { it.type == "ink" }
        val stroke = db.strokeDao().forLayer(layer.id).maxBy { it.createdAt }
        PackedPoints.decode(stroke.points, stroke.pointCount)
    }

    /**
     * A horizontal stroke at canvas y [yCu] from x [x0Cu] to [x1Cu], placed through the view's
     * current transform, must be stored at those canvas positions: every point on y = [yCu], the
     * first at x = [x0Cu] (the filter passes its first sample through), all within the span.
     */
    private fun assertStrokeCommitsAt(x0Cu: Float, x1Cu: Float, yCu: Float, expectedCount: Int) {
        val (s, tx, ty) = transform().let { Triple(it[0], it[1], it[2]) }
        stroke(x0Cu * s + tx, yCu * s + ty, x1Cu * s + tx, yCu * s + ty)
        awaitStrokes(expectedCount)
        val pts = lastStoredPoints()
        val n = pts.size / PackedPoints.STRIDE
        assertEquals("every sample is stored", SAMPLES, n)
        val info = "transform s=$s tx=$tx ty=$ty; stored ${(0 until n).map { pts[it * PackedPoints.STRIDE] to pts[it * PackedPoints.STRIDE + 1] }}"
        assertEquals("first point x — $info", x0Cu, pts[0], TOL_CU)
        for (i in 0 until n) {
            val x = pts[i * PackedPoints.STRIDE]
            val y = pts[i * PackedPoints.STRIDE + 1]
            assertEquals("point $i y — $info", yCu, y, TOL_CU)
            assertTrue("point $i x in span — $info", x >= x0Cu - TOL_CU && x <= x1Cu + TOL_CU)
        }
    }

    // --- Tests ---

    @Test
    fun a_single_page_note_opens_whole_and_ask_sends_the_pre_stage_35_whole_page_export_byte_for_byte() {
        seedParityFixture()
        open(PageExtent.SINGLE)
        assertWholeGridOnScreen()
        assertEquals(CoordinateMapping.Region(0, 0, pageW, pageH), vm.currentExportRegion())

        composeRule.runOnUiThread { vm.onSendTapped() }
        composeRule.waitUntil(TIMEOUT_MS) { api.submitted.isNotEmpty() && !vm.jobInProgress && vm.panel != null }
        composeRule.waitForIdle()

        val req = api.submitted.single()
        val export = requireNotNull(req.export)
        assertEquals(0, export["origin_x_cu"]!!.jsonPrimitive.int)
        assertEquals(0, export["origin_y_cu"]!!.jsonPrimitive.int)
        assertEquals(pageW, export["width_cu"]!!.jsonPrimitive.int)
        assertEquals(pageH, export["height_cu"]!!.jsonPrimitive.int)
        assertEquals(1109, export["w"]!!.jsonPrimitive.int)
        assertEquals(1568, export["h"]!!.jsonPrimitive.int)

        // Byte for byte the whole-page export Ask sent before stage 35: the pre-stage-32 pipeline
        // (the parity test's reference) and the page API over the same stored strokes.
        val png = Base64.getDecoder().decode(requireNotNull(req.image))
        val layers = listOf(ExportLayer(z = 0, visible = true, strokes = vm.strokes.toList()))
        assertTrue("the fixture is on the canvas", layers.single().strokes.size > 200)
        val legacy = LegacyLayerRenderer.legacyExportPng(pageW, pageH, layers, emptyList())
        assertArrayEquals("Ask's PNG == the pre-stage-32 whole-page export", legacy, png)
        val page = CanvasExporter.export(pageW, pageH, layers) as CanvasExporter.Result.Success
        assertArrayEquals("Ask's PNG == the page API's whole-page export", page.png, png)
    }

    @Test
    fun a_2x1_canvas_opens_showing_both_pages() {
        open(PageExtent(0, 1, 0, 0))
        assertWholeGridOnScreen()
        assertEquals(CoordinateMapping.Region(0, 0, 2 * pageW, pageH), vm.currentExportRegion())
    }

    @Test
    fun growing_the_grid_mid_session_does_not_change_the_view() {
        open(PageExtent.SINGLE)
        val before = transform()
        val fitRequest = vm.fitRequest
        var w = 0
        var h = 0
        onUi { w = inkView.width; h = inkView.height }
        // Write from inside the page out past its edge, on the side with the most room (the
        // margin at least): the page grows that way.
        val r = gridInView()
        val freeRight = w - r[2]
        val freeBottom = h - r[3]
        val expected = if (freeRight >= freeBottom) {
            val y = (r[1] + r[3]) / 2f
            stroke(r[2] - 40f, y, r[2] + freeRight * 0.9f, y)
            PageExtent(0, 1, 0, 0)
        } else {
            val x = (r[0] + r[2]) / 2f
            stroke(x, r[3] - 40f, x, r[3] + freeBottom * 0.9f)
            PageExtent(0, 0, 0, 1)
        }
        awaitStrokes(1)
        composeRule.waitUntil(TIMEOUT_MS) { vm.pageExtent == expected }
        composeRule.waitForIdle()
        SystemClock.sleep(SETTLE_MS)
        composeRule.waitForIdle()

        assertEquals("the grid grew", expected, vm.pageExtent)
        assertEquals("growth requests no fit", fitRequest, vm.fitRequest)
        assertArrayEquals("the view did not move", before, transform(), 0f)
        onUi { assertTrue(!inkView.fitPending) }
    }

    @Test
    fun a_fit_asked_for_while_writing_waits_for_pen_up() {
        open(PageExtent.SINGLE)
        // A user view (zoomed in on the page's top-left), then a stroke whose first half is
        // written before Fit is tapped.
        val user = floatArrayOf(0.5f, -100f, -100f)
        onUi { inkView.setTransform(user[0], user[1], user[2]) }
        composeRule.waitForIdle()
        val t0 = pastT0()
        val x0 = 400f * user[0] + user[1]
        val x1 = 700f * user[0] + user[1]
        val y = 800f * user[0] + user[2]
        strokePart(t0, x0, y, x1, y, 0, SAMPLES / 2)
        composeRule.runOnUiThread { vm.fitToScreen() }
        composeRule.waitForIdle()
        assertArrayEquals("no fit while writing", user, transform(), 0f)
        onUi { assertTrue("the fit waits", inkView.fitPending) }

        strokePart(t0, x0, y, x1, y, SAMPLES / 2, SAMPLES)
        awaitStrokes(1)
        awaitFitted()
        assertNotEquals(user.toList(), transform().toList())
        // The whole stroke was captured through the user's view: stored at x 400.., y 800 CU.
        val pts = lastStoredPoints()
        assertEquals(SAMPLES, pts.size / PackedPoints.STRIDE)
        assertEquals("first point x", 400f, pts[0], TOL_CU)
        for (i in 0 until pts.size / PackedPoints.STRIDE) {
            assertEquals("point $i y", 800f, pts[i * PackedPoints.STRIDE + 1], TOL_CU)
        }
    }

    @Test
    fun after_pan_and_zoom_fit_restores_the_fitted_view() {
        open(PageExtent(0, 1, 0, 0))
        val fittedView = transform()
        assertTrue(sameTransform(fittedView, fitted()))

        onUi { inkView.setTransform(2f, -700f, -900f) }
        composeRule.waitForIdle()
        assertNotEquals(
            CoordinateMapping.Region(0, 0, 2 * pageW, pageH),
            vm.currentExportRegion(),
        )

        composeRule.onNodeWithTag(CanvasTags.FIT).performClick()
        composeRule.waitForIdle()
        awaitFitted()
        assertTrue("Fit restores the fitted view", sameTransform(fittedView, transform()))
        assertWholeGridOnScreen()
    }

    @Test
    fun on_a_small_view_a_2x1_grid_clamps_at_min_scale_and_is_centred() {
        // A phone-sized canvas (as the CI emulator's 320 × 552 px): fitting 4960 CU into 320 px
        // needs scale ≈ 0.058, below minScale — the spec's clamp: minScale, centred.
        open(PageExtent(0, 1, 0, 0), screenPx = SMALL_SCREEN_PX)
        val (s, tx, ty) = transform().let { Triple(it[0], it[1], it[2]) }
        var w = 0
        var h = 0
        onUi { w = inkView.width; h = inkView.height }
        val info = "transform [$s, $tx, $ty] in ${w}×$h"
        assertEquals("clamped at minScale — $info", 0.1f, s, 0f)
        assertEquals("grid centre x == view centre x — $info", w / 2f, 2480f * s + tx, 1f)
        assertEquals("grid centre y == view centre y — $info", h / 2f, 1754f * s + ty, 1f)
        // The clamp keeps the grid wider than the view: the region is what is on screen.
        val r = gridInView()
        assertTrue("wider than the view — $info", r[0] < 0f && r[2] > w)
    }

    @Test
    fun low_latency_pen_strokes_after_a_fit_commit_at_the_right_canvas_positions() {
        // Not pinned: the front-buffered wet surface stays inside the window. It asserts only a
        // single page fitted whole, which holds on any view ≥ ~300 px wide (scale > minScale).
        open(PageExtent.SINGLE, lowLatency = true, screenPx = null)
        val host = requireNotNull(root as? InkSurfaceHost) { "the low-latency canvas hosts an InkSurfaceHost" }
        runCatching { composeRule.waitUntil(WET_SURFACE_TIMEOUT_MS) { host.wetLayer.isAvailable } }
        if (!host.wetLayer.isAvailable) {
            fail("WetInkLayer front-buffer surface never became available; this test needs the wet path (see stage 33)")
        }

        // Somewhere else first (the wet layer draws at that transform), then Fit.
        onUi { inkView.setTransform(1.5f, -400f, -600f) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CanvasTags.FIT).performClick()
        composeRule.waitForIdle()
        awaitFitted()
        assertWholeGridOnScreen()

        assertStrokeCommitsAt(600f, 1800f, 1500f, expectedCount = 1)
        onUi { assertEquals("the stroke was drawn on the wet layer", 1, inkView.wetStrokesStarted) }
        Log.i(TAG, "after fit: transform ${transform().toList()}")

        // A second stroke lower on the page, still at the fitted view.
        assertStrokeCommitsAt(400f, 2000f, 3000f, expectedCount = 2)
        onUi { assertEquals(2, inkView.wetStrokesStarted) }
        assertEquals("writing inside the page does not grow it", PageExtent.SINGLE, vm.pageExtent)
    }

    // --- Fake device API: records POST /jobs, answers the first poll with a summary ---

    private inner class FakeDeviceApi : DeviceApi {
        val submitted = mutableListOf<JobCreateRequest>()
        private val json = Json { ignoreUnknownKeys = true }

        private fun job(id: String, type: String, status: String, result: JsonObject?, request: JsonObject) = Job(
            id = id, spaceId = "space-work", canvasId = canvasId, direction = "to_agent", type = type,
            status = status, request = request, result = result, error = null,
            createdAt = "2026-09-27T00:00:00Z", updatedAt = "2026-09-27T00:00:01Z",
        )

        private fun result(): JsonObject = json.parseToJsonElement(
            """{"summary":"Read the whole page.","annotations":[],""" +
                """"cards":[{"kind":"answer","title":"Here","body":"All of it."}],"brain_writes":[],""" +
                """"contract_version":"agent-output/v1"}""",
        ).jsonObject

        override suspend fun health() = HealthResponse("ok", "test", "device-api/v1")
        override suspend fun spaces() = listOf(
            Space(
                id = "space-work", name = "Work", slug = "work", systemPrompt = "", tools = emptyList(),
                model = "claude-sonnet-5", color = "#3B6EA5", position = 0, createdAt = "2026-09-27T00:00:00Z",
            ),
        )
        override suspend fun createSpace(body: com.inkwell.net.SpaceCreateRequest): Space = error("unused")
        override suspend fun patchSpace(id: String, body: com.inkwell.net.SpacePatchRequest): Space = error("unused")
        override suspend fun createJob(body: JobCreateRequest): Job {
            submitted += body
            return job("job-${submitted.size}", body.type, "queued", null, JsonObject(emptyMap()))
        }
        override suspend fun getJob(id: String): Job = error("unused")
        override suspend fun cancelJob(id: String): Job = error("unused")
        override suspend fun sync(cursor: String?): SyncResponse {
            val last = submitted.lastOrNull() ?: return SyncResponse(emptyList(), "c0")
            val request = JsonObject(mapOf("export" to requireNotNull(last.export)))
            return SyncResponse(listOf(job("job-${submitted.size}", last.type, "done", result(), request)), "c1")
        }
        override suspend fun getCanvas(id: String) = error("unused")
        override suspend fun downloadBlob(url: String) = error("unused")
        override suspend fun patchCard(id: String, body: com.inkwell.net.CardStateRequest): CardResponse = error("unused")
        override suspend fun runCardAction(id: String, actionId: String): CardResponse = error("unused")
        override suspend fun getBrain(slug: String, q: String?, limit: Int?) = error("unused")
        override suspend fun postBrain(slug: String, body: com.inkwell.net.BrainCreate) = error("unused")
        override suspend fun deleteBrain(slug: String, id: String) = error("unused")
    }

    private companion object {
        const val TAG = "FitToScreenTest"
        const val TIMEOUT_MS = 10_000L
        const val WET_SURFACE_TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 400L
        const val SAMPLES = 24
        const val DT_MS = 4L

        /** The product's target: a landscape tablet canvas screen (px), whatever the emulator. */
        val TABLET_SCREEN_PX = 1280 to 880

        /** A phone-sized canvas screen (px), where a 2 × 1 grid's fit clamps at minScale. */
        val SMALL_SCREEN_PX = 320 to 640

        /** Stored-point tolerance (CU): view px ↔ CU through a float transform is ~1e-3 CU. */
        const val TOL_CU = 0.5f
    }
}
