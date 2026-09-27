package com.inkwell

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkwell.data.CanvasRepository
import com.inkwell.data.InkDatabase
import com.inkwell.data.LayerRepository
import com.inkwell.data.LibraryRepository
import com.inkwell.data.PageExtent
import com.inkwell.data.StrokeCommitData
import com.inkwell.ink.BuiltStroke
import com.inkwell.ink.InkSettings
import com.inkwell.ink.InkView
import com.inkwell.net.CardResponse
import com.inkwell.net.DeviceApi
import com.inkwell.net.DeviceRepository
import com.inkwell.net.HealthResponse
import com.inkwell.net.Job
import com.inkwell.net.JobCreateRequest
import com.inkwell.net.Space
import com.inkwell.net.SyncResponse
import com.inkwell.render.AnnotationGeometry
import com.inkwell.render.CoordinateMapping
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import kotlin.math.roundToInt

/**
 * Stage 35 (ADR-0014 §4–§5): agent jobs export the visible region, end to end — the real
 * [CanvasScreen] over a [CanvasViewModel], an in-memory Room database and a fake device API
 * that records the `POST /jobs` body and answers with a highlight fixture.
 *
 *  - On a 2 × 1 canvas scrolled to page (1,0), Ask exports exactly that region (origin
 *    `(2480, 0)`, page-sized), the PNG shows page (1,0)'s ink and not page (0,0)'s, and the
 *    highlight lands on page (1,0) at `origin + nm × size`.
 *  - Growing the canvas left afterwards (a stylus stroke into the ring) does not move it.
 *  - Zoomed out past the legibility floor, Send is disabled with the "Zoom in to send" hint.
 *
 * The view scale is a power of two, so page edges land on exact view pixels and the
 * viewport → CU conversion is exact.
 */
@RunWith(AndroidJUnit4::class)
class RegionExportInstrumentedTest {

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

    // The highlight fixture: a box centred on the exported image (nm 0.4..0.6 × 0.45..0.55).
    private val fixture = listOf(listOf(0.40, 0.45), listOf(0.60, 0.45), listOf(0.60, 0.55), listOf(0.40, 0.55))

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

    /** A filled horizontal bar (a 2-point stroke at full pressure, [BAR_CU] tall). */
    private fun bar(x0: Float, x1: Float, y: Float) = StrokeCommitData(
        stroke = BuiltStroke(
            floatArrayOf(x0, y, 1f, 0f, 0f, x1, y, 1f, 0f, 1f), 2, x0, y, x1 - x0, 0f,
        ),
        tool = "pen",
        colorHex = "#111111",
        widthCu = BAR_CU,
    )

    private fun open(grid: PageExtent, seedInk: Boolean = false) {
        runBlocking {
            repo.updatePageExtent(canvasId, grid)
            if (seedInk) {
                val layerId = requireNotNull(repo.openCanvas(canvasId)).inkLayerId
                // Asymmetric on purpose: page (0,0)'s bar spans local x 200..900 CU and page
                // (1,0)'s spans local x 920..1920 CU, so an export that ignored the origin
                // would show ink exactly where the right export shows paper, and vice versa.
                repo.insertStroke(layerId, bar(200f, 900f, BAR_Y)) // page (0,0)
                repo.insertStroke(layerId, bar(3400f, 4400f, BAR_Y)) // page (1,0)
            }
        }
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
            CanvasScreen(
                viewModel = vm,
                onOpenSettings = {},
                debugEnabled = false,
                inkSettings = InkSettings(lowLatencyPen = false),
            )
        }
        composeRule.runOnUiThread { vm.openCanvas(canvasId) }
        composeRule.waitUntil(TIMEOUT_MS) { vm.ready }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            inkView = requireNotNull(findInkView(composeRule.activity.window.decorView)) { "no InkView" }
            root = inkView
        }
    }

    private fun findInkView(v: View): InkView? {
        if (v is InkView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findInkView(v.getChildAt(i))?.let { return it }
        return null
    }

    /** The largest power-of-two scale at which one page fits the view (≤ 1/4). */
    private fun pageFitScale(): Float {
        var s = 0.25f
        while (pageW * s > inkView.width || pageH * s > inkView.height) s /= 2f
        return s
    }

    private fun setTransform(scale: Float, tx: Float, ty: Float) {
        composeRule.runOnUiThread { inkView.setTransform(scale, tx, ty) }
        composeRule.waitForIdle()
    }

    /**
     * Page (1,0) with its top-left corner at view (0,0): the viewport shows no other page.
     * Pass the [scale] explicitly when returning to the page: the side panel that opens after
     * a job shrinks the canvas view, so [pageFitScale] may pick a smaller scale then.
     */
    private fun scrollToPage10(scale: Float = pageFitScale()): Float {
        setTransform(scale, -pageW * scale, 0f)
        return scale
    }

    /**
     * The view pixel at canvas point ([cuX], [cuY]) through the view's CURRENT transform, read
     * after the UI is idle and drawn fresh. Fails with the CU, view coords, transform and view
     * size if the point is off the view, so a CI failure is diagnosable.
     */
    private fun pixelAtCu(label: String, cuX: Float, cuY: Float): Pair<Int, String> {
        composeRule.waitForIdle()
        var pixel = 0
        var info = ""
        composeRule.runOnUiThread {
            val (scale, tx, ty) = inkView.currentTransform.let { Triple(it[0], it[1], it[2]) }
            val vx = (cuX * scale + tx).roundToInt()
            val vy = (cuY * scale + ty).roundToInt()
            info = "$label: CU ($cuX, $cuY) → view ($vx, $vy) via scale=$scale tx=$tx ty=$ty; " +
                "view ${inkView.width}×${inkView.height}; agentRegion=${vm.agentRegion}; grid=${vm.pageExtent}"
            check(vx in 0 until inkView.width && vy in 0 until inkView.height) { "off the view — $info" }
            val bmp = Bitmap.createBitmap(inkView.width, inkView.height, Bitmap.Config.ARGB_8888)
            inkView.draw(Canvas(bmp))
            pixel = bmp.getPixel(vx, vy)
            bmp.recycle()
        }
        return pixel to "$info; pixel #${Integer.toHexString(pixel)}"
    }

    /** The highlight's canvas-CU bounds through the job's region (never the grid). */
    private fun highlightBoundsCu(): List<Double> {
        val region = requireNotNull(vm.agentRegion)
        val h = vm.agentAnnotations.single()
        return AnnotationGeometry.boundsCu(h, region.widthCu, region.heightCu, region.originX, region.originY).toList()
    }

    /** True for the accent at the agent's 70% over white paper (blue clearly dominant). */
    private fun isAccent(pixel: Int): Boolean =
        Color.blue(pixel) - Color.red(pixel) > 40 && Color.blue(pixel) < 240

    private fun isInk(pixel: Int): Boolean =
        Color.red(pixel) < 110 && Color.green(pixel) < 110 && Color.blue(pixel) < 110

    private fun stylusStroke(x0: Float, y0: Float, x1: Float, y1: Float) {
        val t0 = SystemClock.uptimeMillis() - SAMPLES * 4L - 100L
        composeRule.runOnUiThread {
            for (i in 0 until SAMPLES) {
                val f = i / (SAMPLES - 1f)
                val action = when (i) {
                    0 -> MotionEvent.ACTION_DOWN
                    SAMPLES - 1 -> MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_MOVE
                }
                val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
                val coords = arrayOf(
                    MotionEvent.PointerCoords().apply {
                        x = x0 + (x1 - x0) * f; y = y0 + (y1 - y0) * f; pressure = 0.8f
                    },
                )
                val e = MotionEvent.obtain(
                    t0, t0 + i * 4L, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0,
                )
                root.dispatchTouchEvent(e)
                e.recycle()
            }
        }
    }

    private fun askAndAwait() {
        composeRule.runOnUiThread { vm.onSendTapped() }
        composeRule.waitUntil(TIMEOUT_MS) { vm.agentAnnotations.isNotEmpty() && !vm.jobInProgress }
        composeRule.waitForIdle()
    }

    // --- Tests ---

    @Test
    fun ask_on_page_1_0_exports_that_region_and_the_highlight_lands_on_it_and_stays_after_growth() {
        open(PageExtent(0, 1, 0, 0), seedInk = true)
        val s = scrollToPage10()
        val region = CoordinateMapping.Region(pageW, 0, pageW, pageH)
        assertEquals(region, vm.currentExportRegion())
        assertNull(vm.sendBlockedHint)

        askAndAwait()

        // The request describes the region: origin (2480, 0), one page in size.
        val req = api.submitted.single()
        val export = requireNotNull(req.export)
        assertEquals(pageW, export["origin_x_cu"]!!.jsonPrimitive.int)
        assertEquals(0, export["origin_y_cu"]!!.jsonPrimitive.int)
        assertEquals(pageW, export["width_cu"]!!.jsonPrimitive.int)
        assertEquals(pageH, export["height_cu"]!!.jsonPrimitive.int)
        assertEquals(1109, export["w"]!!.jsonPrimitive.int)
        assertEquals(1568, export["h"]!!.jsonPrimitive.int)

        // The PNG is page (1,0): its bar (local x 920..1920 CU) is there, page (0,0)'s bar
        // (x 200..900 CU) is not.
        val png = Base64.getDecoder().decode(requireNotNull(req.image))
        val img = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertEquals(1109, img.width)
        assertEquals(1568, img.height)
        val k = CoordinateMapping.exportScale(pageW, pageH)
        val barY = (BAR_Y * k).roundToInt()
        assertTrue("page (1,0) ink is exported", isInk(img.getPixel((1500 * k).roundToInt(), barY)))
        assertEquals("page (0,0) ink is not exported", Color.WHITE, img.getPixel((500 * k).roundToInt(), barY))
        img.recycle()

        // The job's region travels with its annotations, and the highlight is drawn on page
        // (1,0) at origin + nm × size: the box spans x 3472..3968, y 1578.6..1929.4 CU. The
        // probes are canvas points mapped through the view's current transform.
        assertEquals(region, vm.agentRegion)
        val boundsBefore = highlightBoundsCu()
        assertEquals(3472.0, boundsBefore[0], 1e-6)
        assertEquals(1578.6, boundsBefore[1], 1e-6)
        val (inside1, insideInfo1) = pixelAtCu("inside, before growth", INSIDE_X, PROBE_Y)
        assertTrue("highlight on page (1,0) — $insideInfo1", isAccent(inside1))
        val (outside1, outsideInfo1) = pixelAtCu("outside, before growth", OUTSIDE_X, PROBE_Y)
        assertTrue("left of the box is not highlighted — $outsideInfo1", !isAccent(outside1))

        // Grow the canvas left: zoom out so the left ring is on screen and write into it.
        val z = 0.05f
        setTransform(z, 200f, 50f) // column −1 is view x 76..200, row 0 view y 50..225
        stylusStroke(180f, 100f, 90f, 140f)
        composeRule.waitUntil(TIMEOUT_MS) { vm.pageExtent == PageExtent(-1, 1, 0, 0) && vm.strokes.size == 3 }
        composeRule.waitForIdle()
        assertEquals("the job's region is unchanged", region, vm.agentRegion)

        // The highlight's canvas position (through the job's region) is unchanged by growth.
        assertEquals("highlight CU bounds unchanged by growth", boundsBefore, highlightBoundsCu())

        // Back on page (1,0) at the same scale: the highlight is exactly where it was, in
        // canvas CU (probed through the current transform, after the UI is idle).
        scrollToPage10(s)
        val (inside2, insideInfo2) = pixelAtCu("inside, after growth", INSIDE_X, PROBE_Y)
        assertTrue("highlight did not move after growth — $insideInfo2", isAccent(inside2))
        val (outside2, outsideInfo2) = pixelAtCu("outside, after growth", OUTSIDE_X, PROBE_Y)
        assertTrue("left of the box still not highlighted — $outsideInfo2", !isAccent(outside2))
    }

    @Test
    fun zoomed_out_past_the_legibility_floor_send_is_disabled_with_the_hint() {
        open(PageExtent(-1, 1, 0, 0)) // three pages wide: 7440 CU > 2 × 3508
        // Every page on screen.
        var s = pageFitScale()
        while (3 * pageW * s > inkView.width) s /= 2f
        setTransform(s, pageW * s, 0f)
        assertEquals(CoordinateMapping.Region(-pageW, 0, 3 * pageW, pageH), vm.currentExportRegion())
        assertEquals(CanvasViewModel.ZOOM_IN_HINT, vm.sendBlockedHint)
        composeRule.onNodeWithTag(CanvasTags.SEND).assertIsNotEnabled()
        composeRule.onNodeWithTag(CanvasTags.SEND_BLOCKED_HINT).assertTextEquals("Zoom in to send")
        composeRule.runOnUiThread { vm.onSendTapped() }
        composeRule.waitForIdle()
        assertTrue("nothing was sent", api.submitted.isEmpty())

        // Zoom in on one page: Send is back and the hint is gone.
        scrollToPage10()
        assertNull(vm.sendBlockedHint)
        composeRule.onNodeWithTag(CanvasTags.SEND).assertIsEnabled()
        assertTrue(composeRule.onAllNodesWithTag(CanvasTags.SEND_BLOCKED_HINT).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun single_page_seen_whole_sends_the_v1_export() {
        open(PageExtent.SINGLE, seedInk = false)
        val s = pageFitScale()
        setTransform(s, 0f, 0f)
        assertEquals(CoordinateMapping.Region(0, 0, pageW, pageH), vm.currentExportRegion())
        askAndAwait()
        val export = requireNotNull(api.submitted.single().export)
        assertEquals(0, export["origin_x_cu"]!!.jsonPrimitive.int)
        assertEquals(0, export["origin_y_cu"]!!.jsonPrimitive.int)
        assertEquals(pageW, export["width_cu"]!!.jsonPrimitive.int)
        assertEquals(pageH, export["height_cu"]!!.jsonPrimitive.int)
        assertEquals(CoordinateMapping.Region(0, 0, pageW, pageH), vm.agentRegion)
    }

    // --- Fake device API: records POST /jobs, answers the first poll with the highlight ---

    private inner class FakeDeviceApi : DeviceApi {
        val submitted = mutableListOf<JobCreateRequest>()
        private val json = Json { ignoreUnknownKeys = true }

        private fun job(id: String, type: String, status: String, result: JsonObject?, request: JsonObject) = Job(
            id = id, spaceId = "space-work", canvasId = canvasId, direction = "to_agent", type = type,
            status = status, request = request, result = result, error = null,
            createdAt = "2026-09-27T00:00:00Z", updatedAt = "2026-09-27T00:00:01Z",
        )

        private fun result(): JsonObject {
            val pts = fixture.joinToString(",") { "[${it[0]},${it[1]}]" }
            return json.parseToJsonElement(
                """{"summary":"Highlighted it.","annotations":[{"id":"h1","type":"highlight","points":[$pts]}],""" +
                    """"cards":[{"kind":"answer","title":"Here","body":"This one."}],"brain_writes":[],""" +
                    """"contract_version":"agent-output/v1"}""",
            ).jsonObject
        }

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
            // The server returns the stored request.export unchanged (device-api).
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
        const val TIMEOUT_MS = 10_000L
        const val SAMPLES = 24
        const val BAR_CU = 200f

        /** The seeded bars' row (CU): above the highlight fixture (y 1579..1929 CU). */
        const val BAR_Y = 800f

        /** Highlight probes (canvas CU): inside the box, and left of it on the same row. */
        const val INSIDE_X = 3720f
        const val OUTSIDE_X = 3224f
        const val PROBE_Y = 1740f
    }
}
