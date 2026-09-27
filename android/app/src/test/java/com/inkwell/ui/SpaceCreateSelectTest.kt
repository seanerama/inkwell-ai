package com.inkwell.ui

import com.inkwell.data.CanvasEntity
import com.inkwell.data.CanvasRepository
import com.inkwell.data.FolderEntity
import com.inkwell.data.LayerEntity
import com.inkwell.data.LibraryRepository
import com.inkwell.data.SpaceEntity
import com.inkwell.data.SpaceSync
import com.inkwell.data.StrokeEntity
import com.inkwell.data.dao.CanvasDao
import com.inkwell.data.dao.FolderDao
import com.inkwell.data.dao.LayerDao
import com.inkwell.data.dao.SpaceDao
import com.inkwell.data.dao.StrokeDao
import com.inkwell.net.DeviceApi
import com.inkwell.net.DeviceRepository
import com.inkwell.net.HealthResponse
import com.inkwell.net.Job
import com.inkwell.net.JobCreateRequest
import com.inkwell.net.Space
import com.inkwell.net.SpaceCreateRequest
import com.inkwell.net.SpacePatchRequest
import com.inkwell.net.SyncResponse
import com.inkwell.net.CardResponse
import com.inkwell.net.CardStateRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage 16 (bug #37) JVM regression guard, no emulator: the create-a-space-from-the-"+"-tab
 * flow through the REAL [SpaceSettingsViewModel] + [LibraryViewModel] + [SpaceSync], with fake
 * DAOs and a fake [DeviceApi] that mirrors the instrumented `SpacesDispatcher` (POST derives
 * `id = "space-$slug"`, records it, and `GET /spaces` returns it). Asserts that after a create
 * the tab list ([LibraryViewModel.spaces]) CONTAINS the new space AND it becomes
 * [LibraryViewModel.activeSpaceId].
 *
 * NOTE: this guards the create→reload→select VIEW-MODEL logic. The stage-16 bug itself lives in
 * the Compose "+"-tab click → dialog wiring ([SpaceTabBar]) which only the instrumented test
 * exercises (no Robolectric in this source set); that path is guarded on the emulator lane.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpaceCreateSelectTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    // --- fakes ------------------------------------------------------------------------------

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
        override suspend fun updatePageExtent(id: String, minCol: Int, maxCol: Int, minRow: Int, maxRow: Int) {
            store.replaceAll { if (it.id == id) it.copy(pageMinCol = minCol, pageMaxCol = maxCol, pageMinRow = minRow, pageMaxRow = maxRow) else it }
        }
    }

    private class FakeFolderDao : FolderDao {
        val store = mutableListOf<FolderEntity>()
        override suspend fun upsert(folder: FolderEntity) { store.removeAll { it.id == folder.id }; store.add(folder) }
        override suspend fun byId(id: String) = store.firstOrNull { it.id == id }
        override suspend fun childrenOf(spaceId: String, parentId: String?) = store.filter { it.spaceId == spaceId }
        override suspend fun allForSpace(spaceId: String) = store.filter { it.spaceId == spaceId }
        override suspend fun trashed(spaceId: String) = emptyList<FolderEntity>()
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

    /**
     * Mirrors the instrumented `SpacesDispatcher`: `GET /spaces` returns the current recs;
     * `POST /spaces` derives `slug`/`id = "space-$slug"`, records it, returns 201-equivalent.
     */
    private class SpacesApi(seed: List<Space>) : DeviceApi {
        val recs = seed.toMutableList()

        override suspend fun spaces(): List<Space> = recs.sortedBy { it.position }

        override suspend fun createSpace(body: SpaceCreateRequest): Space {
            val slug = body.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            val rec = Space(
                id = "space-$slug",
                name = body.name,
                slug = slug,
                systemPrompt = "",
                tools = emptyList(),
                model = body.model ?: "claude-sonnet-5",
                color = body.color ?: "#000000",
                position = (recs.maxOfOrNull { it.position } ?: -1) + 1,
                createdAt = "2026-09-16T00:00:00Z",
            )
            recs.add(rec)
            return rec
        }

        override suspend fun health() = HealthResponse("ok", "test", "device-api/v1")
        override suspend fun patchSpace(id: String, body: SpacePatchRequest): Space = error("unused")
        override suspend fun createJob(body: JobCreateRequest): Job = error("unused")
        override suspend fun getJob(id: String): Job = error("unused")
        override suspend fun cancelJob(id: String): Job = error("unused")
        override suspend fun sync(cursor: String?): SyncResponse = error("unused")
        override suspend fun patchCard(id: String, body: CardStateRequest): CardResponse = error("unused")
        override suspend fun runCardAction(id: String, actionId: String): CardResponse = error("unused")
        override suspend fun getCanvas(id: String) = error("unused")
        override suspend fun downloadBlob(url: String) = error("unused")
        override suspend fun getBrain(slug: String, q: String?, limit: Int?) = error("unused")
        override suspend fun postBrain(slug: String, body: com.inkwell.net.BrainCreate) = error("unused")
        override suspend fun deleteBrain(slug: String, id: String) = error("unused")
    }

    private fun space(slug: String, position: Int) = Space(
        id = "space-$slug", name = slug.replaceFirstChar { it.uppercase() }, slug = slug,
        systemPrompt = "", tools = emptyList(), model = "claude-sonnet-5",
        color = "#3B6EA5", position = position, createdAt = "2026-09-16T00:00:00Z",
    )

    /** The four seeded server spaces of the instrumented test. */
    private fun seededServer() = listOf(
        space("work", 0), space("home", 1), space("learning", 2), space("business", 3),
    )

    private class Harness(
        val libraryVm: LibraryViewModel,
        val settingsVm: SpaceSettingsViewModel,
    )

    private fun harness(): Harness {
        val spaceDao = FakeSpaceDao()
        val canvasDao = FakeCanvasDao()
        val folderDao = FakeFolderDao()
        val layerDao = FakeLayerDao()
        val canvasRepository = CanvasRepository(
            spaceDao, canvasDao, layerDao, FakeStrokeDao(),
            idGen = { "local-${System.nanoTime()}" }, clock = { 1L },
        )
        val library = LibraryRepository(folderDao, canvasDao, layerDao, runInTransaction = { it() })
        val dev = DeviceRepository(SpacesApi(seededServer()))
        val spaceSync = SpaceSync(spaceDao, canvasDao, folderDao, { dev }, runInTransaction = { it() })

        val prefs = mutableMapOf<String, String?>()
        val libraryVm = LibraryViewModel(
            library = library,
            canvasRepository = canvasRepository,
            spaceSync = spaceSync,
            loadActiveSpaceId = { prefs["active_space_id"] },
            saveActiveSpaceId = { prefs["active_space_id"] = it },
            spacesEnabled = true,
        )
        val settingsVm = SpaceSettingsViewModel(
            deviceRepositoryProvider = { dev },
            spaceSync = spaceSync,
            upsertSpace = { spaceDao.upsert(it) },
            loadSpaces = { spaceDao.all() },
            onSpacesChanged = { id -> libraryVm.reloadSpacesSelecting(id) },
        )
        return Harness(libraryVm, settingsVm)
    }

    @Test
    fun createSpace_surfaces_and_selects_the_new_tab() {
        val h = harness()
        // init ran eagerly under Unconfined: the four server tabs are present, Work is active.
        assertTrue(h.libraryVm.ready)
        assertEquals(listOf("space-work", "space-home", "space-learning", "space-business"), h.libraryVm.spaces.map { it.id })
        assertEquals("space-work", h.libraryVm.activeSpaceId)

        h.settingsVm.createSpace("Cooking", "#B58900")

        assertTrue("new tab must be in the tab list", h.libraryVm.spaces.any { it.id == "space-cooking" })
        assertEquals("new tab must become active", "space-cooking", h.libraryVm.activeSpaceId)
        assertFalse("dialog closes after a successful create", h.settingsVm.creating)
    }
}
