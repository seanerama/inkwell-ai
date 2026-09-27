package com.inkwell.ui

import com.inkwell.contracts.CardKind
import com.inkwell.data.CanvasEntity
import com.inkwell.data.CanvasRepository
import com.inkwell.data.LayerEntity
import com.inkwell.data.LayerRepository
import com.inkwell.data.PackedPoints
import com.inkwell.data.PageExtent
import com.inkwell.data.SpaceEntity
import com.inkwell.data.StrokeEntity
import com.inkwell.data.dao.CanvasDao
import com.inkwell.data.dao.LayerDao
import com.inkwell.data.dao.SpaceDao
import com.inkwell.data.dao.StrokeDao
import com.inkwell.ink.BuiltStroke
import com.inkwell.ink.StrokeCommit
import com.inkwell.net.CardActionResponse
import com.inkwell.net.CardResponse
import com.inkwell.net.CardStateRequest
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for the Stage 7 send flow of [CanvasViewModel], driven end-to-end with
 * in-memory fakes (DAOs, a fake [DeviceApi] that records the `POST /jobs` body, and a
 * fake exporter) on an unconfined Main dispatcher — no emulator:
 *
 *  - `send()` with no note builds a `canvas.ask` body with `instruction` **absent**;
 *  - with a note, `instruction` is present (still `canvas.ask`);
 *  - "Mark it up instead" (`sendAnnotate()`) builds `canvas.annotate` (typed text, or the
 *    Stage-6 preset when blank);
 *  - a blank note never falls back to the preset for ask;
 *  - kill-switch OFF: Send opens the sheet first and sends `canvas.annotate`;
 *  - a `done` outcome fills the panel with `(kind, title, body)` cards, `answer` expanded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelSendTest {

    private val dispatcher = UnconfinedTestDispatcher()

    // --- In-memory Room fakes ---
    private class FakeSpaceDao : SpaceDao {
        val store = mutableListOf<SpaceEntity>()
        override suspend fun upsert(space: SpaceEntity) { store.removeAll { it.id == space.id }; store.add(space) }
        override suspend fun all() = store.sortedBy { it.position }
        override suspend fun byId(id: String) = store.firstOrNull { it.id == id }
        override suspend fun delete(id: String) { store.removeAll { it.id == id } }
    }

    private class FakeCanvasDao : CanvasDao {
        val store = mutableListOf<CanvasEntity>()
        override suspend fun upsert(canvas: CanvasEntity) { store.removeAll { it.id == canvas.id }; store.add(canvas) }
        override suspend fun forSpace(spaceId: String) = store.filter { it.spaceId == spaceId }.sortedByDescending { it.updatedAt }
        override suspend fun byId(id: String) = store.firstOrNull { it.id == id }
        // Stage 11 additions (unused by these Stage-7 send tests).
        override suspend fun inFolder(spaceId: String, folderId: String?) =
            store.filter {
                it.spaceId == spaceId && it.deletedAt == null &&
                    ((folderId == null && it.folderId == null) || it.folderId == folderId)
            }.sortedByDescending { it.updatedAt }
        override suspend fun trashed(spaceId: String) =
            store.filter { it.spaceId == spaceId && it.deletedAt != null }.sortedByDescending { it.deletedAt }
        override suspend fun allForSpace(spaceId: String) = store.filter { it.spaceId == spaceId }
        override suspend fun rename(id: String, title: String, updatedAt: Long) {
            val i = store.indexOfFirst { it.id == id }; if (i >= 0) store[i] = store[i].copy(title = title, updatedAt = updatedAt)
        }
        override suspend fun move(id: String, folderId: String?, updatedAt: Long) {
            val i = store.indexOfFirst { it.id == id }; if (i >= 0) store[i] = store[i].copy(folderId = folderId, updatedAt = updatedAt)
        }
        override suspend fun moveToSpace(id: String, spaceId: String, updatedAt: Long) {
            val i = store.indexOfFirst { it.id == id }; if (i >= 0) store[i] = store[i].copy(spaceId = spaceId, folderId = null, updatedAt = updatedAt)
        }
        override suspend fun setSpace(id: String, spaceId: String, updatedAt: Long) {
            val i = store.indexOfFirst { it.id == id }; if (i >= 0) store[i] = store[i].copy(spaceId = spaceId, updatedAt = updatedAt)
        }
        override suspend fun reassignSpace(oldSpaceId: String, newSpaceId: String) {
            store.indices.forEach { i -> if (store[i].spaceId == oldSpaceId) store[i] = store[i].copy(spaceId = newSpaceId) }
        }
        override suspend fun setDeletedAt(id: String, deletedAt: Long?) {
            val i = store.indexOfFirst { it.id == id }; if (i >= 0) store[i] = store[i].copy(deletedAt = deletedAt)
        }
        override suspend fun trashCanvasesInFolder(spaceId: String, folderId: String, deletedAt: Long) {
            store.indices.forEach { i ->
                val c = store[i]
                if (c.spaceId == spaceId && c.deletedAt == null && c.folderId == folderId) store[i] = c.copy(deletedAt = deletedAt)
            }
        }
        override suspend fun purgeTrashedBefore(cutoff: Long) { store.removeAll { it.deletedAt != null && it.deletedAt!! < cutoff } }
        override suspend fun hardDelete(id: String) { store.removeAll { it.id == id } }
        override suspend fun updatePageExtent(id: String, minCol: Int, maxCol: Int, minRow: Int, maxRow: Int) {
            store.replaceAll { if (it.id == id) it.copy(pageMinCol = minCol, pageMaxCol = maxCol, pageMinRow = minRow, pageMaxRow = maxRow) else it }
        }
        // Stage 34: INSERT OR IGNORE and the column-scoped seen_at stamp (CanvasDao).
        override suspend fun insertIfAbsent(canvas: CanvasEntity): Long {
            if (store.any { it.id == canvas.id }) return -1L
            store.add(canvas); return 1L
        }
        override suspend fun markSeen(id: String, seenAt: Long) {
            store.replaceAll { if (it.id == id && it.seenAt == null) it.copy(seenAt = seenAt) else it }
        }
    }

    private class FakeLayerDao : LayerDao {
        val store = mutableListOf<LayerEntity>()
        override suspend fun upsert(layer: LayerEntity) { store.removeAll { it.id == layer.id }; store.add(layer) }
        override suspend fun forCanvas(canvasId: String) = store.filter { it.canvasId == canvasId }.sortedBy { it.z }
    }

    private class FakeStrokeDao : StrokeDao {
        val store = mutableListOf<StrokeEntity>()
        override suspend fun insert(stroke: StrokeEntity) { store.removeAll { it.id == stroke.id }; store.add(stroke) }
        override suspend fun forLayer(layerId: String) = store.filter { it.layerId == layerId }.sortedBy { it.createdAt }
        override suspend fun byId(id: String) = store.firstOrNull { it.id == id }
        override suspend fun deleteById(id: String) { store.removeAll { it.id == id } }
        override suspend fun count() = store.size
    }

    /** Records every `POST /jobs` body; the first `/sync` after a submit reports it done. */
    private class FakeDeviceApi(private val resultJson: String) : DeviceApi {
        val submitted = mutableListOf<JobCreateRequest>()
        private val json = Json { ignoreUnknownKeys = true }

        override suspend fun health() = HealthResponse("ok", "test", "device-api/v1")
        override suspend fun spaces() = listOf(
            Space(
                id = "space-work", name = "Work", slug = "work", systemPrompt = "", tools = emptyList(),
                model = "claude-sonnet-5", color = "#3B6EA5", position = 0, createdAt = "2026-09-15T00:00:00Z",
            ),
        )
        override suspend fun createSpace(body: com.inkwell.net.SpaceCreateRequest): Space = error("unused")
        override suspend fun patchSpace(id: String, body: com.inkwell.net.SpacePatchRequest): Space = error("unused")
        override suspend fun createJob(body: JobCreateRequest): Job {
            submitted += body
            return job("job-${submitted.size}", body.type, "queued", null)
        }
        override suspend fun getJob(id: String): Job = error("unused")
        override suspend fun cancelJob(id: String): Job = error("unused")
        /** Server cards attached to a done job (Stage 10 additive `cards` field). */
        val serverCards = listOf(
            CardResponse(
                id = "sc1", kind = "answer", title = "1 + 9 = 10", body = "**10**.",
                anchors = emptyList(),
                actions = listOf(
                    CardActionResponse("act-confirm", "Looks right", "confirm"),
                    CardActionResponse("act-reject", "Not helpful", "reject"),
                ),
                state = "open", createdAt = "2026-09-15T00:00:00Z",
            ),
        )
        override suspend fun sync(cursor: String?): SyncResponse {
            val last = submitted.lastOrNull() ?: return SyncResponse(emptyList(), "c0")
            val done = job(
                "job-${submitted.size}", last.type, "done",
                json.parseToJsonElement(resultJson).jsonObject, serverCards,
            )
            return SyncResponse(listOf(done), "c1")
        }
        override suspend fun getCanvas(id: String) = error("unused")
        override suspend fun downloadBlob(url: String) = error("unused")
        override suspend fun getBrain(slug: String, q: String?, limit: Int?) = error("unused")
        override suspend fun postBrain(slug: String, body: com.inkwell.net.BrainCreate) = error("unused")
        override suspend fun deleteBrain(slug: String, id: String) = error("unused")
        val patched = mutableListOf<Pair<String, String>>()
        val actioned = mutableListOf<Pair<String, String>>()
        override suspend fun patchCard(id: String, body: CardStateRequest): CardResponse {
            patched += id to body.state
            return card(id, body.state)
        }
        override suspend fun runCardAction(id: String, actionId: String): CardResponse {
            actioned += id to actionId
            val state = if (actionId.contains("reject")) "dismissed" else "done"
            return card(id, state)
        }
        private fun card(id: String, state: String) = CardResponse(
            id = id, kind = "answer", title = "1 + 9 = 10", body = "**10**.",
            anchors = emptyList(), actions = emptyList(), state = state,
            createdAt = "2026-09-15T00:00:00Z",
        )

        private fun job(
            id: String,
            type: String,
            status: String,
            result: kotlinx.serialization.json.JsonObject?,
            cards: List<CardResponse> = emptyList(),
        ) = Job(
            id = id, spaceId = "space-work", canvasId = "canvas-1", direction = "to_agent", type = type,
            status = status, result = result, createdAt = "2026-09-15T00:00:00Z", updatedAt = "2026-09-15T00:00:01Z",
            cards = cards,
        )
    }

    private val askResult = """
        {
          "summary": "Answered the question on the note: 1 + 9 = 10.",
          "annotations": [ { "id": "t1", "type": "text", "at": [0.42, 0.18], "text": "10", "size": 0.02 } ],
          "cards": [
            { "kind": "answer", "title": "1 + 9 = 10", "body": "**10**. Nine plus one is ten.", "anchors": [], "actions": [] },
            { "kind": "fact", "title": "Arithmetic", "body": "Addition is commutative.", "anchors": [], "actions": [] }
          ],
          "brain_writes": [],
          "contract_version": "agent-output/v1"
        }
    """.trimIndent()

    private lateinit var api: FakeDeviceApi

    // Stage 35: the exporter is handed the region (viewport ∩ grid; the whole page here).
    private val fakeExporter: (CoordinateMapping.Region, List<ExportLayer>) -> CanvasExporter.Result = { region, _ ->
        CanvasExporter.Result.Success(CoordinateMapping.export(region), byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
    }

    // Uses explicitNulls=false like the production ApiClient, so a null instruction is omitted.
    private val wireJson = Json { explicitNulls = false }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = FakeDeviceApi(askResult)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // These tests exercise the Stage-7 one-tap-ask flow, so card actions (the Stage-10
    // picker) default OFF here; Stage-10 behaviour is covered by its own tests.
    /** Stage 35: the regions the exporter was asked for, in order. */
    private val exportedRegions = mutableListOf<CoordinateMapping.Region>()

    /** Stage 35 review: when set, the exporter throws this (an allocation failure, say). */
    private var exportFailure: Throwable? = null

    private fun viewModel(
        oneTapAsk: Boolean = true,
        cardActionsEnabled: Boolean = false,
        formalizeEnabled: Boolean = false,
        brainEnabled: Boolean = false,
        // Stage 35: seed the default canvas's page grid before it opens.
        grid: PageExtent? = null,
    ): CanvasViewModel {
        val layerDao = FakeLayerDao()
        val repo = CanvasRepository(
            spaceDao = FakeSpaceDao(), canvasDao = FakeCanvasDao(), layerDao = layerDao, strokeDao = FakeStrokeDao(),
            idGen = { "id-${System.nanoTime()}" }, clock = { 1L },
        )
        if (grid != null) {
            runBlocking {
                val id = repo.openDefaultCanvas().canvasId
                repo.updatePageExtent(id, grid)
            }
        }
        return CanvasViewModel(
            repository = repo,
            layerRepository = LayerRepository(layerDao, idGen = { "agent-layer" }, clock = { 1L }),
            deviceRepositoryProvider = { DeviceRepository(api) },
            sendEnabled = true,
            oneTapAsk = oneTapAsk,
            cardActionsEnabled = cardActionsEnabled,
            formalizeEnabled = formalizeEnabled,
            brainEnabled = brainEnabled,
            formalizedCanvasStore = repo,
            ioDispatcher = dispatcher,
            exporter = { region, layers ->
                exportedRegions += region
                exportFailure?.let { throw it }
                fakeExporter(region, layers)
            },
            // These Stage-7/10/12 send tests exercise the flag-OFF space path (resolve the
            // "work" space by slug). The Stage-14 canvas-space send path has its own test.
            spacesEnabled = false,
        ).also { assertTrue("canvas opened synchronously on the unconfined dispatcher", it.ready) }
    }

    private fun wireBody(req: JobCreateRequest) =
        wireJson.parseToJsonElement(wireJson.encodeToString(JobCreateRequest.serializer(), req)).jsonObject

    @Test
    fun send_with_no_note_posts_canvas_ask_with_instruction_absent() {
        val vm = viewModel()
        vm.send()

        val req = api.submitted.single()
        assertEquals("canvas.ask", req.type)
        assertNull(req.instruction)
        val body = wireBody(req)
        assertEquals("canvas.ask", body["type"]!!.jsonPrimitive.content)
        assertFalse("instruction must be absent from the body", body.containsKey("instruction"))
        assertEquals("space-work", body["space_id"]!!.jsonPrimitive.content)
        assertTrue(body.containsKey("image") && body.containsKey("export"))
        assertFalse(vm.showInstruction) // one tap: no sheet
        assertFalse(vm.jobInProgress)
    }

    @Test
    fun send_tapped_is_one_tap_when_the_flag_is_on() {
        val vm = viewModel()
        vm.onSendTapped()
        assertFalse("no sheet", vm.showInstruction)
        assertEquals("canvas.ask", api.submitted.single().type)
        assertNull(api.submitted.single().instruction)
    }

    @Test
    fun send_with_a_note_posts_canvas_ask_with_instruction_present() {
        val vm = viewModel()
        vm.openInstruction()
        assertTrue(vm.showInstruction)
        vm.onInstructionChange("Answer in French.")
        vm.send()

        val req = api.submitted.single()
        assertEquals("canvas.ask", req.type)
        assertEquals("Answer in French.", req.instruction)
        assertEquals("Answer in French.", wireBody(req)["instruction"]!!.jsonPrimitive.content)
        assertFalse(vm.showInstruction)
        assertEquals("the note is per-send and cleared afterwards", "", vm.instruction)
    }

    @Test
    fun blank_note_never_falls_back_to_the_preset_for_ask() {
        val vm = viewModel()
        vm.onInstructionChange("   ")
        vm.send()
        val req = api.submitted.single()
        assertEquals("canvas.ask", req.type)
        assertNull(req.instruction)
        assertFalse(wireBody(req).containsKey("instruction"))
    }

    @Test
    fun remember_posts_canvas_extract() {
        // Stage 27: the picker's fourth option (Remember, gated by BuildConfig.BRAIN) posts
        // canvas.extract with the optional note, or none when blank.
        val vm = viewModel(oneTapAsk = false, cardActionsEnabled = true, brainEnabled = true)
        vm.selectJobType("extract")
        assertEquals("extract", vm.jobType)
        assertEquals("Remember", vm.sendLabel)

        vm.onSendTapped()
        val req = api.submitted.single()
        assertEquals("canvas.extract", req.type)
        assertNull("no note → instruction omitted", req.instruction)
        assertEquals("canvas.extract", wireBody(req)["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun remember_is_not_selectable_when_brain_is_off() {
        // With BuildConfig.BRAIN off the extract option is inert: selectJobType ignores it.
        val vm = viewModel(oneTapAsk = false, cardActionsEnabled = true, brainEnabled = false)
        vm.selectJobType("extract")
        assertEquals("ask", vm.jobType)
    }

    @Test
    fun mark_it_up_instead_posts_canvas_annotate_with_the_typed_text() {
        val vm = viewModel()
        vm.openInstruction()
        vm.onInstructionChange("circle the total")
        vm.sendAnnotate()
        val req = api.submitted.single()
        assertEquals("canvas.annotate", req.type)
        assertEquals("circle the total", req.instruction)
    }

    @Test
    fun mark_it_up_instead_uses_the_stage6_preset_when_blank() {
        val vm = viewModel()
        vm.openInstruction()
        vm.sendAnnotate()
        val req = api.submitted.single()
        assertEquals("canvas.annotate", req.type)
        assertEquals(CanvasViewModel.PRESET_INSTRUCTION, req.instruction)
    }

    @Test
    fun flag_off_restores_the_stage6_sheet_first_annotate_flow() {
        val vm = viewModel(oneTapAsk = false)
        vm.onSendTapped()
        assertTrue("sheet opens first", vm.showInstruction)
        assertTrue("nothing sent yet", api.submitted.isEmpty())
        vm.send() // blank → the preset, as in Stage 6
        val req = api.submitted.single()
        assertEquals("canvas.annotate", req.type)
        assertEquals(CanvasViewModel.PRESET_INSTRUCTION, req.instruction)
    }

    @Test
    fun done_outcome_fills_the_panel_with_kind_title_body_and_answer_expanded() {
        val vm = viewModel()
        vm.send()

        val panel = vm.panel
        assertNotNull(panel)
        assertFalse(panel!!.isError)
        assertEquals("Answered the question on the note: 1 + 9 = 10.", panel.summary)
        assertEquals(
            listOf(
                PanelCard(CardKind.ANSWER, "1 + 9 = 10", "**10**. Nine plus one is ten."),
                PanelCard(CardKind.FACT, "Arithmetic", "Addition is commutative."),
            ),
            panel.cards,
        )
        assertTrue("answer card expanded by default", panel.cards[0].expandedByDefault)
        assertFalse("other kinds collapsed to their title", panel.cards[1].expandedByDefault)
        assertEquals(listOf("1 + 9 = 10", "Arithmetic"), panel.cardTitles)
        // The text annotation reached the agent layer (rendered, never dropped).
        assertEquals(1, vm.agentAnnotations.size)
        assertTrue(vm.agentLayerVisible)
    }

    // --- Stage 35: agent jobs export the visible region ---

    private val pageW = CoordinateMapping.DEFAULT_WIDTH_CU // 2480
    private val pageH = CoordinateMapping.DEFAULT_HEIGHT_CU // 3508

    /** A committed pen stroke through the given canvas-unit points. */
    private fun stroke(vararg xy: Pair<Float, Float>): StrokeCommit {
        val pts = FloatArray(xy.size * PackedPoints.STRIDE)
        xy.forEachIndexed { i, (x, y) ->
            val o = i * PackedPoints.STRIDE
            pts[o] = x; pts[o + 1] = y; pts[o + 2] = 0.5f; pts[o + 4] = i * 4f
        }
        val minX = xy.minOf { it.first }; val maxX = xy.maxOf { it.first }
        val minY = xy.minOf { it.second }; val maxY = xy.maxOf { it.second }
        return StrokeCommit(BuiltStroke(pts, xy.size, minX, minY, maxX - minX, maxY - minY), "pen", "#111111", 3f)
    }

    @Test
    fun single_page_seen_whole_exports_the_page_at_origin_zero() {
        val vm = viewModel()
        // The view shows the whole page and some surround.
        vm.onViewportChanged(-300.0, -200.0, 2700.0, 3700.0)
        vm.send()
        assertEquals(CoordinateMapping.Region(0, 0, pageW, pageH), exportedRegions.single())
        val export = wireBody(api.submitted.single())["export"]!!.jsonObject
        assertEquals(0, export["origin_x_cu"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, export["origin_y_cu"]!!.jsonPrimitive.content.toInt())
        assertEquals(pageW, export["width_cu"]!!.jsonPrimitive.content.toInt())
        assertEquals(pageH, export["height_cu"]!!.jsonPrimitive.content.toInt())
        assertEquals(CoordinateMapping.Region(0, 0, pageW, pageH), vm.agentRegion)
    }

    @Test
    fun before_the_view_reports_the_whole_grid_is_the_region() {
        val vm = viewModel(grid = PageExtent(0, 1, 0, 0))
        assertEquals(CoordinateMapping.Region(0, 0, 2 * pageW, pageH), vm.currentExportRegion())
        assertNull(vm.sendBlockedHint)
    }

    @Test
    fun scrolled_to_page_1_0_ask_exports_that_region_with_its_origin() {
        val vm = viewModel(grid = PageExtent(0, 1, 0, 0))
        // The viewport shows page (1,0) only (and a sliver of surround below it).
        vm.onViewportChanged(2480.0, 0.0, 4960.0, 3600.4)
        vm.send()

        val region = CoordinateMapping.Region(pageW, 0, pageW, pageH)
        assertEquals(region, exportedRegions.single())
        val export = wireBody(api.submitted.single())["export"]!!.jsonObject
        assertEquals(pageW, export["origin_x_cu"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, export["origin_y_cu"]!!.jsonPrimitive.content.toInt())
        assertEquals(1109, export["w"]!!.jsonPrimitive.content.toInt())
        assertEquals(1568, export["h"]!!.jsonPrimitive.content.toInt())
        // The job's region travels with its annotations.
        assertEquals(region, vm.agentRegion)
        assertEquals(1, vm.agentAnnotations.size)
    }

    @Test
    fun partial_viewport_on_a_single_page_exports_just_that_part() {
        val vm = viewModel()
        // Zoomed in on the page's middle: a 1000.4 x 700.2 CU window, snapped outward.
        vm.onViewportChanged(800.3, 1200.7, 1800.7, 1900.9)
        vm.send()
        assertEquals(CoordinateMapping.Region(800, 1200, 1001, 701), exportedRegions.single())
    }

    @Test
    fun past_the_legibility_floor_send_is_blocked_with_the_hint() {
        val vm = viewModel(grid = PageExtent(-1, 1, 0, 0))
        // The whole 3-page-wide grid is visible: 7440 CU > 2 x 3508 CU.
        vm.onViewportChanged(-3000.0, -500.0, 5500.0, 4000.0)
        assertEquals(CanvasViewModel.ZOOM_IN_HINT, vm.sendBlockedHint)
        assertFalse(vm.canSend)
        vm.onSendTapped()
        assertTrue("nothing exported or posted", exportedRegions.isEmpty() && api.submitted.isEmpty())
        assertEquals(CanvasViewModel.ZOOM_IN_HINT, vm.sendStatus)

        // Zooming back in (two of the three pages: 4960 <= 7016) re-enables Send.
        vm.onViewportChanged(-2480.0, 0.0, 2480.0, 3508.0)
        assertNull(vm.sendBlockedHint)
        assertTrue(vm.canSend)
        vm.onSendTapped()
        assertEquals(CoordinateMapping.Region(-pageW, 0, 2 * pageW, pageH), exportedRegions.single())
    }

    @Test
    fun a_viewport_showing_no_page_blocks_send() {
        val vm = viewModel()
        vm.onViewportChanged(5000.0, 0.0, 6000.0, 1000.0)
        assertEquals(CanvasViewModel.NO_REGION_HINT, vm.sendBlockedHint)
        vm.send()
        assertTrue(api.submitted.isEmpty())
    }

    @Test
    fun a_sliver_region_is_no_region_send_is_disabled_and_nothing_crashes() {
        val vm = viewModel()
        // The page's right edge is 0.8 CU inside the screen's left edge: region (2479,0,1,3508)
        // would export 0 px wide.
        vm.onViewportChanged(2479.2, -10.0, 4000.0, 3600.0)
        assertNull(vm.currentExportRegion())
        assertEquals(CanvasViewModel.NO_REGION_HINT, vm.sendBlockedHint)
        assertFalse(vm.canSend)
        vm.send()
        assertTrue(exportedRegions.isEmpty() && api.submitted.isEmpty())
        assertEquals(CanvasViewModel.NO_REGION_HINT, vm.sendStatus)
    }

    @Test
    fun the_sliver_threshold_is_16_export_px() {
        val vm = viewModel()
        vm.onViewportChanged(2446.0, -10.0, 4000.0, 3600.0) // 34 CU wide → 15 px
        assertEquals(CanvasViewModel.NO_REGION_HINT, vm.sendBlockedHint)
        vm.onViewportChanged(2445.0, -10.0, 4000.0, 3600.0) // 35 CU wide → 16 px
        assertNull(vm.sendBlockedHint)
        vm.send()
        assertEquals(CoordinateMapping.Region(2445, 0, 35, pageH), exportedRegions.single())
        assertEquals(16, wireBody(api.submitted.single())["export"]!!.jsonObject["w"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun an_export_failure_is_a_send_status_not_a_crash() {
        val vm = viewModel()
        exportFailure = IllegalArgumentException("width and height must be > 0")
        vm.send()
        assertTrue("nothing posted", api.submitted.isEmpty())
        assertEquals("Could not export the canvas: width and height must be > 0", vm.sendStatus)
        assertFalse(vm.jobInProgress)
        exportFailure = OutOfMemoryError("tile")
        vm.send()
        assertEquals("Could not export the canvas: tile", vm.sendStatus)
        // A later send works.
        exportFailure = null
        vm.send()
        assertEquals(1, api.submitted.size)
    }

    @Test
    fun a_formalize_redraw_maps_through_the_canvas_size_the_server_returned() {
        // The device exported the whole A4 page, but an older server sized the new canvas from
        // the (differently sized) source: the diagram maps through the returned 1600 × 1200.
        api = FakeDeviceApi(
            """
            {
              "summary": "Redrew it.",
              "annotations": [ { "id": "b1", "type": "rect", "x": 0.1, "y": 0.1, "w": 0.2, "h": 0.1 } ],
              "cards": [ { "kind": "answer", "title": "Cleaned up", "body": "Aligned.", "anchors": [], "actions": [] } ],
              "brain_writes": [],
              "contract_version": "agent-output/v1",
              "canvas": { "id": "srv-fmz-1", "space_id": "space-work", "title": "T — formalized",
                          "width_cu": 1600, "height_cu": 1200, "origin": "agent" },
              "source_canvas_id": null
            }
            """.trimIndent(),
        )
        val vm = viewModel(cardActionsEnabled = true, formalizeEnabled = true)
        vm.selectJobType("formalize")
        vm.onViewportChanged(0.0, 0.0, 2480.0, 3508.0)
        vm.onSendTapped()
        assertEquals(CoordinateMapping.Region(0, 0, pageW, pageH), exportedRegions.single())
        val newId = requireNotNull(vm.pendingOpenCanvasId)
        vm.openCanvas(newId)
        assertEquals(1, vm.agentAnnotations.size)
        assertEquals(CoordinateMapping.Region(0, 0, 1600, 1200), vm.agentRegion)
    }

    @Test
    fun a_later_grid_change_does_not_move_an_existing_jobs_markup() {
        val vm = viewModel(cardActionsEnabled = true, grid = PageExtent(0, 1, 0, 0))
        vm.onViewportChanged(2480.0, 0.0, 4960.0, 3508.0)
        vm.onSendTapped()
        val region = CoordinateMapping.Region(pageW, 0, pageW, pageH)
        assertEquals(region, vm.agentRegion)

        // The card anchored to the text annotation "t1" pulses at the job-region position.
        val card = PanelCard(CardKind.ANSWER, "1 + 9 = 10", "**10**.", anchors = listOf(PanelAnchor(annotationId = "t1")))
        vm.onCardTapped(card)
        val before = vm.anchorPulses.single().toList()
        assertEquals(pageW + 0.42 * pageW, before[0], 1e-9)
        assertEquals(0.18 * pageH, before[1], 1e-9)
        assertEquals(0.02 * pageH, before[3], 1e-9)

        // The canvas grows (a stroke up-left of the grid adds a page column and a row) and
        // the view moves on.
        fun markupCu() = vm.agentAnnotations.map { a ->
            val r = vm.agentRegion!!
            com.inkwell.render.AnnotationGeometry.boundsCu(a, r.widthCu, r.heightCu, r.originX, r.originY).toList()
        }
        val markupBefore = markupCu()
        assertEquals(pageW + 0.42 * pageW, markupBefore.single()[0], 1e-9)
        vm.onStrokeCommitted(stroke(-100f to -100f, -50f to -60f))
        assertEquals(PageExtent(-1, 1, -1, 0), vm.pageExtent)
        vm.onViewportChanged(-2480.0, -3508.0, 4960.0, 3508.0)
        assertEquals("the job's region is unchanged", region, vm.agentRegion)
        assertEquals("the markup's canvas-CU placement is unchanged by growth", markupBefore, markupCu())
        vm.onCardTapped(card)
        assertEquals(before, vm.anchorPulses.single().toList())
    }

    // --- Stage 10 ---

    @Test
    fun card_actions_enabled_uses_server_cards_with_identity_and_state() {
        val vm = viewModel(cardActionsEnabled = true)
        vm.send()
        val card = vm.panel!!.cards.single()
        assertEquals("sc1", card.id)
        assertEquals("open", card.state)
        assertEquals(2, card.actions.size)
        assertTrue(card.actions[0].supported) // confirm
    }

    @Test
    fun confirm_action_is_optimistic_then_reconciled_from_the_server() {
        val vm = viewModel(cardActionsEnabled = true)
        vm.send()
        val card = vm.panel!!.cards.single()
        val confirm = card.actions.first { it.kind == "confirm" }
        vm.onCardAction(card, confirm)
        // The route was called and the row settled on the server's state.
        assertEquals(listOf("sc1" to "act-confirm"), api.actioned)
        assertEquals("done", vm.panel!!.cards.single().state)
    }

    @Test
    fun job_type_picker_posts_the_chosen_type() {
        val vm = viewModel(cardActionsEnabled = true)
        vm.selectJobType("annotate")
        assertEquals("Mark up", vm.sendLabel)
        vm.onSendTapped()
        assertEquals("canvas.annotate", api.submitted.single().type)

        val vm2 = viewModel(cardActionsEnabled = true)
        vm2.selectJobType("ask")
        assertEquals("Send", vm2.sendLabel)
        vm2.onSendTapped()
        assertEquals("canvas.ask", api.submitted.last().type)
    }

    // --- Stage 12: Formalize picker ---

    @Test
    fun formalize_option_is_gated_by_its_flag() {
        // OFF: selecting formalize is ignored (option not offered).
        val off = viewModel(cardActionsEnabled = true, formalizeEnabled = false)
        off.selectJobType("formalize")
        assertEquals("ask", off.jobType)

        // ON: selectable, and the Send label follows it.
        val on = viewModel(cardActionsEnabled = true, formalizeEnabled = true)
        on.selectJobType("formalize")
        assertEquals("formalize", on.jobType)
        assertEquals("Formalize", on.sendLabel)
    }

    @Test
    fun formalize_posts_canvas_formalize_with_the_canvas_title_as_meta_title() {
        val vm = viewModel(cardActionsEnabled = true, formalizeEnabled = true)
        vm.selectJobType("formalize")
        vm.onSendTapped()

        val req = api.submitted.single()
        assertEquals("canvas.formalize", req.type)
        // meta.title carries the current canvas title (the seeded default "Canvas").
        val body = wireBody(req)
        assertEquals("Canvas", body["meta"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        // No instruction for formalize (the guidance drives it).
        assertNull(req.instruction)
    }
}
