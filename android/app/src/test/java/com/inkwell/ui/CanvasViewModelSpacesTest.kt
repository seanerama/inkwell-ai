package com.inkwell.ui

import com.inkwell.data.CanvasEntity
import com.inkwell.data.CanvasRepository
import com.inkwell.data.LayerEntity
import com.inkwell.data.LayerRepository
import com.inkwell.data.SpaceEntity
import com.inkwell.data.SpaceSync
import com.inkwell.data.StrokeEntity
import com.inkwell.data.dao.CanvasDao
import com.inkwell.data.dao.FolderDao
import com.inkwell.data.dao.LayerDao
import com.inkwell.data.dao.SpaceDao
import com.inkwell.data.dao.StrokeDao
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for the Stage-14 (ADR-0010) send path of [CanvasViewModel] with the SPACES
 * flag ON: a job is posted with the CANVAS's own (server-reconciled) `space_id`, and a send
 * is blocked when the canvas's space is still a local placeholder the server does not know.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelSpacesTest {

    private val dispatcher = UnconfinedTestDispatcher()

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
        override suspend fun inFolder(spaceId: String, folderId: String?) = store.filter { it.spaceId == spaceId }
        override suspend fun trashed(spaceId: String) = emptyList<CanvasEntity>()
        override suspend fun allForSpace(spaceId: String) = store.filter { it.spaceId == spaceId }
        override suspend fun rename(id: String, title: String, updatedAt: Long) {}
        override suspend fun move(id: String, folderId: String?, updatedAt: Long) {}
        override suspend fun moveToSpace(id: String, spaceId: String, updatedAt: Long) {}
        override suspend fun setSpace(id: String, spaceId: String, updatedAt: Long) {}
        override suspend fun reassignSpace(oldSpaceId: String, newSpaceId: String) {
            store.indices.forEach { i -> if (store[i].spaceId == oldSpaceId) store[i] = store[i].copy(spaceId = newSpaceId) }
        }
        override suspend fun setDeletedAt(id: String, deletedAt: Long?) {}
        override suspend fun trashCanvasesInFolder(spaceId: String, folderId: String, deletedAt: Long) {}
        override suspend fun purgeTrashedBefore(cutoff: Long) {}
        override suspend fun hardDelete(id: String) { store.removeAll { it.id == id } }
    }

    private class FakeFolderDao : FolderDao {
        val store = mutableListOf<com.inkwell.data.FolderEntity>()
        override suspend fun upsert(folder: com.inkwell.data.FolderEntity) { store.removeAll { it.id == folder.id }; store.add(folder) }
        override suspend fun byId(id: String) = store.firstOrNull { it.id == id }
        override suspend fun childrenOf(spaceId: String, parentId: String?) = store.filter { it.spaceId == spaceId }
        override suspend fun allForSpace(spaceId: String) = store.filter { it.spaceId == spaceId }
        override suspend fun trashed(spaceId: String) = emptyList<com.inkwell.data.FolderEntity>()
        override suspend fun rename(id: String, name: String, updatedAt: Long) {}
        override suspend fun move(id: String, parentId: String?, updatedAt: Long) {}
        override suspend fun moveToSpace(id: String, spaceId: String, parentId: String?, updatedAt: Long) {}
        override suspend fun setSpace(id: String, spaceId: String, updatedAt: Long) {}
        override suspend fun reassignSpace(oldSpaceId: String, newSpaceId: String) {
            store.indices.forEach { i -> if (store[i].spaceId == oldSpaceId) store[i] = store[i].copy(spaceId = newSpaceId) }
        }
        override suspend fun setDeletedAt(id: String, deletedAt: Long?) {}
        override suspend fun purgeTrashedBefore(cutoff: Long) {}
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

    private val doneResult = """
        {
          "summary": "ok",
          "annotations": [],
          "cards": [],
          "brain_writes": [],
          "contract_version": "agent-output/v1"
        }
    """.trimIndent()

    /** Records every POST /jobs body; the first /sync after a submit reports it done. */
    private inner class FakeDeviceApi(private val serverSpaces: List<Space>) : DeviceApi {
        val submitted = mutableListOf<JobCreateRequest>()
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        override suspend fun health() = HealthResponse("ok", "test", "device-api/v1")
        override suspend fun spaces() = serverSpaces
        override suspend fun createJob(body: JobCreateRequest): Job {
            submitted += body
            return job("job-${submitted.size}", body.type, "queued", null)
        }
        override suspend fun getJob(id: String): Job = error("unused")
        override suspend fun cancelJob(id: String): Job = error("unused")
        override suspend fun sync(cursor: String?): SyncResponse {
            val last = submitted.lastOrNull() ?: return SyncResponse(emptyList(), "c0")
            val result = json.parseToJsonElement(doneResult) as kotlinx.serialization.json.JsonObject
            return SyncResponse(listOf(job("job-${submitted.size}", last.type, "done", result)), "c1")
        }
        override suspend fun patchCard(id: String, body: CardStateRequest): CardResponse = error("unused")
        override suspend fun runCardAction(id: String, actionId: String): CardResponse = error("unused")
        private fun job(id: String, type: String, status: String, result: kotlinx.serialization.json.JsonObject?) = Job(
            id = id, spaceId = "space-work", canvasId = "canvas-1", direction = "to_agent", type = type,
            status = status, result = result, createdAt = "2026-09-16T00:00:00Z", updatedAt = "2026-09-16T00:00:01Z",
        )
    }

    private val fakeExporter: (Int, Int, List<ExportLayer>) -> CanvasExporter.Result = { w, h, _ ->
        CanvasExporter.Result.Success(CoordinateMapping.export(w, h), byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
    }

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun space(id: String, slug: String) = Space(
        id = id, name = slug, slug = slug, systemPrompt = "", tools = emptyList(),
        model = "claude-sonnet-5", color = "#3B6EA5", position = 0, createdAt = "2026-09-16T00:00:00Z",
    )

    @Test
    fun send_posts_the_canvas_own_reconciled_space_id() {
        val spaceDao = FakeSpaceDao(); val canvasDao = FakeCanvasDao(); val folderDao = FakeFolderDao()
        val layerDao = FakeLayerDao()
        val repo = CanvasRepository(
            spaceDao, canvasDao, layerDao, FakeStrokeDao(),
            idGen = { "local-${System.nanoTime()}" }, clock = { 1L },
        )
        // Server has the `work` space; the placeholder (slug "work") reconciles onto it.
        val api = FakeDeviceApi(listOf(space("space-work", "work")))
        val dev = DeviceRepository(api)
        val spaceSync = SpaceSync(spaceDao, canvasDao, folderDao, { dev }, runInTransaction = { it() })

        val vm = CanvasViewModel(
            repository = repo,
            layerRepository = LayerRepository(layerDao, idGen = { "agent-layer" }, clock = { 1L }),
            deviceRepositoryProvider = { dev },
            sendEnabled = true, oneTapAsk = true, cardActionsEnabled = false,
            ioDispatcher = dispatcher, exporter = fakeExporter,
            spacesEnabled = true, spaceSync = spaceSync,
        )
        assertTrue(vm.ready)

        vm.send()

        assertEquals("space-work", api.submitted.single().spaceId)
        // The canvas was reconciled onto the server id.
        assertEquals("space-work", canvasDao.store.single().spaceId)
    }

    @Test
    fun send_is_blocked_when_the_space_is_an_unsynced_placeholder() {
        val spaceDao = FakeSpaceDao(); val canvasDao = FakeCanvasDao(); val folderDao = FakeFolderDao()
        val layerDao = FakeLayerDao()
        val repo = CanvasRepository(
            spaceDao, canvasDao, layerDao, FakeStrokeDao(),
            idGen = { "local-${System.nanoTime()}" }, clock = { 1L },
        )
        val api = FakeDeviceApi(listOf(space("space-work", "work")))
        val dev = DeviceRepository(api)
        // SpaceSync can't reach the server (unpaired) → refresh throws → no server ids cached.
        val spaceSync = SpaceSync(spaceDao, canvasDao, folderDao, { null }, runInTransaction = { it() })

        val vm = CanvasViewModel(
            repository = repo,
            layerRepository = LayerRepository(layerDao, idGen = { "agent-layer" }, clock = { 1L }),
            deviceRepositoryProvider = { dev },
            sendEnabled = true, oneTapAsk = true, cardActionsEnabled = false,
            ioDispatcher = dispatcher, exporter = fakeExporter,
            spacesEnabled = true, spaceSync = spaceSync,
        )
        assertTrue(vm.ready)

        vm.send()

        assertTrue("nothing posted while unsynced", api.submitted.isEmpty())
        assertEquals("Spaces not synced yet — check the connection and try again.", vm.sendStatus)
        assertNull(vm.panel)
    }
}
