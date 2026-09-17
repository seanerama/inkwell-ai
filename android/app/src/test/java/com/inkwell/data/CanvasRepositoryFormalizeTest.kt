package com.inkwell.data

import com.inkwell.data.dao.CanvasDao
import com.inkwell.data.dao.LayerDao
import com.inkwell.data.dao.SpaceDao
import com.inkwell.data.dao.StrokeDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit test for [CanvasRepository.createFormalizedCanvas] (Stage 12, Formalize) with
 * in-memory fake DAOs (no emulator). A `canvas.formalize` redraw becomes a LOCAL canvas
 * using the SERVER's id, `origin="agent"`, in the SAME folder as the source, with exactly
 * ONE `agent`/`annotation` layer and NO user/ink layer (SPEC §4.3). Idempotent by (canvas,
 * job): re-applying the same finished job never duplicates the row or the layer.
 */
class CanvasRepositoryFormalizeTest {

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
        override suspend fun forSpace(spaceId: String) = store.filter { it.spaceId == spaceId }
        override suspend fun byId(id: String) = store.firstOrNull { it.id == id }
        override suspend fun inFolder(spaceId: String, folderId: String?) = store.filter { it.spaceId == spaceId }
        override suspend fun trashed(spaceId: String) = emptyList<CanvasEntity>()
        override suspend fun allForSpace(spaceId: String) = store.filter { it.spaceId == spaceId }
        override suspend fun rename(id: String, title: String, updatedAt: Long) {}
        override suspend fun move(id: String, folderId: String?, updatedAt: Long) {}
        override suspend fun moveToSpace(id: String, spaceId: String, updatedAt: Long) {
            val i = store.indexOfFirst { it.id == id }; if (i >= 0) store[i] = store[i].copy(spaceId = spaceId, folderId = null, updatedAt = updatedAt)
        }
        override suspend fun setSpace(id: String, spaceId: String, updatedAt: Long) {
            val i = store.indexOfFirst { it.id == id }; if (i >= 0) store[i] = store[i].copy(spaceId = spaceId, updatedAt = updatedAt)
        }
        override suspend fun reassignSpace(oldSpaceId: String, newSpaceId: String) {
            store.indices.forEach { i -> if (store[i].spaceId == oldSpaceId) store[i] = store[i].copy(spaceId = newSpaceId) }
        }
        override suspend fun setDeletedAt(id: String, deletedAt: Long?) {}
        override suspend fun trashCanvasesInFolder(spaceId: String, folderId: String, deletedAt: Long) {}
        override suspend fun purgeTrashedBefore(cutoff: Long) {}
        override suspend fun hardDelete(id: String) { store.removeAll { it.id == id } }
    }

    private class FakeLayerDao : LayerDao {
        val store = mutableListOf<LayerEntity>()
        var upsertCount = 0
        override suspend fun upsert(layer: LayerEntity) {
            upsertCount++
            store.removeAll { it.id == layer.id }
            store.add(layer)
        }
        override suspend fun forCanvas(canvasId: String) = store.filter { it.canvasId == canvasId }.sortedBy { it.z }
    }

    private class FakeStrokeDao : StrokeDao {
        override suspend fun insert(stroke: StrokeEntity) {}
        override suspend fun forLayer(layerId: String) = emptyList<StrokeEntity>()
        override suspend fun byId(id: String): StrokeEntity? = null
        override suspend fun deleteById(id: String) {}
        override suspend fun count() = 0
    }

    private fun repo(canvasDao: FakeCanvasDao, layerDao: FakeLayerDao): CanvasRepository {
        var n = 0
        return CanvasRepository(
            spaceDao = FakeSpaceDao(),
            canvasDao = canvasDao,
            layerDao = layerDao,
            strokeDao = FakeStrokeDao(),
            idGen = { "gen-${n++}" },
            clock = { 42L },
        )
    }

    private fun sourceCanvas() = CanvasEntity(
        id = "canvas-1", spaceId = "space-1", title = "Topology", widthCu = 800, heightCu = 600,
        createdAt = 0L, updatedAt = 0L, origin = "user", folderId = "folder-A",
    )

    @Test
    fun creates_agent_canvas_with_server_id_in_source_folder_one_agent_layer_no_ink() = runTest {
        val canvasDao = FakeCanvasDao().apply { store.add(sourceCanvas()) }
        val layerDao = FakeLayerDao()
        val layer = repo(canvasDao, layerDao).createFormalizedCanvas(
            canvasId = "srv-9",
            spaceId = "space-1",
            title = "Topology — formalized",
            widthCu = 1600,
            heightCu = 1200,
            sourceCanvasId = "canvas-1",
            jobId = "job-f",
        )

        // The canvas uses the SERVER id, is agent-origin, inherits the source folder + dims.
        val created = canvasDao.byId("srv-9")!!
        assertEquals("agent", created.origin)
        assertEquals("folder-A", created.folderId)
        assertEquals("Topology — formalized", created.title)
        assertEquals(1600, created.widthCu)
        assertEquals(1200, created.heightCu)

        // Exactly one agent/annotation layer, linked to the job; NO ink layer.
        val layers = layerDao.forCanvas("srv-9")
        assertEquals(1, layers.size)
        assertEquals("agent", layers.single().owner)
        assertEquals("annotation", layers.single().type)
        assertEquals("job-f", layers.single().jobId)
        assertTrue("no ink layer until the user first draws", layers.none { it.type == "ink" })
        assertEquals("job-f", layer.jobId)

        // The source canvas is untouched (still user-origin, in its own folder).
        assertEquals("user", canvasDao.byId("canvas-1")!!.origin)
    }

    @Test
    fun is_idempotent_by_canvas_and_job() = runTest {
        val canvasDao = FakeCanvasDao().apply { store.add(sourceCanvas()) }
        val layerDao = FakeLayerDao()
        val r = repo(canvasDao, layerDao)
        val first = r.createFormalizedCanvas("srv-9", "space-1", "T — formalized", 1600, 1200, "canvas-1", "job-f")
        val second = r.createFormalizedCanvas("srv-9", "space-1", "T — formalized", 1600, 1200, "canvas-1", "job-f")

        assertEquals(first.id, second.id)
        assertEquals(1, layerDao.forCanvas("srv-9").size)
    }

    @Test
    fun unknown_source_lands_the_canvas_at_the_space_root() = runTest {
        val canvasDao = FakeCanvasDao() // no source seeded
        val layerDao = FakeLayerDao()
        repo(canvasDao, layerDao).createFormalizedCanvas(
            "srv-9", "space-1", "Formalized", 2480, 3508, sourceCanvasId = null, jobId = "job-f",
        )
        assertNull("no source → root folder", canvasDao.byId("srv-9")!!.folderId)
    }
}
