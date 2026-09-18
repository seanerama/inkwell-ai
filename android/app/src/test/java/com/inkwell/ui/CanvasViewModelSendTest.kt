package com.inkwell.ui

import com.inkwell.contracts.CardKind
import com.inkwell.data.CanvasEntity
import com.inkwell.data.CanvasRepository
import com.inkwell.data.LayerEntity
import com.inkwell.data.LayerRepository
import com.inkwell.data.SpaceEntity
import com.inkwell.data.StrokeEntity
import com.inkwell.data.dao.CanvasDao
import com.inkwell.data.dao.LayerDao
import com.inkwell.data.dao.SpaceDao
import com.inkwell.data.dao.StrokeDao
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

    private val fakeExporter: (Int, Int, List<ExportLayer>) -> CanvasExporter.Result = { w, h, _ ->
        CanvasExporter.Result.Success(CoordinateMapping.export(w, h), byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
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
    private fun viewModel(
        oneTapAsk: Boolean = true,
        cardActionsEnabled: Boolean = false,
        formalizeEnabled: Boolean = false,
    ): CanvasViewModel {
        val layerDao = FakeLayerDao()
        val repo = CanvasRepository(
            spaceDao = FakeSpaceDao(), canvasDao = FakeCanvasDao(), layerDao = layerDao, strokeDao = FakeStrokeDao(),
            idGen = { "id-${System.nanoTime()}" }, clock = { 1L },
        )
        return CanvasViewModel(
            repository = repo,
            layerRepository = LayerRepository(layerDao, idGen = { "agent-layer" }, clock = { 1L }),
            deviceRepositoryProvider = { DeviceRepository(api) },
            sendEnabled = true,
            oneTapAsk = oneTapAsk,
            cardActionsEnabled = cardActionsEnabled,
            formalizeEnabled = formalizeEnabled,
            formalizedCanvasStore = repo,
            ioDispatcher = dispatcher,
            exporter = fakeExporter,
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
