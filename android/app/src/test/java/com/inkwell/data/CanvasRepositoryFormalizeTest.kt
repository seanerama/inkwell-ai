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
 *
 * Stage 34: a re-delivered formalize job never shrinks a grown page grid (the row is
 * insert-or-ignore, not REPLACE), and [CanvasRepository.insertStrokeWithGrowth] writes the
 * stroke and the grown grid inside one transaction.
 */
class CanvasRepositoryFormalizeTest {

    private class FakeSpaceDao : SpaceDao {
        val store = mutableListOf<SpaceEntity>()
        override suspend fun upsert(space: SpaceEntity) { store.removeAll { it.id == space.id }; store.add(space) }
        override suspend fun all() = store.sortedBy { it.position }
        override suspend fun byId(id: String) = store.firstOrNull { it.id == id }
        override suspend fun delete(id: String) { store.removeAll { it.id == id } }
    }

    private class FakeCanvasDao(private val log: MutableList<String>? = null) : CanvasDao {
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
        // Mirrors the monotonic SQL (MIN / MAX with the stored bounds, stage 34).
        override suspend fun updatePageExtent(id: String, minCol: Int, maxCol: Int, minRow: Int, maxRow: Int) {
            log?.add("grid")
            store.replaceAll {
                if (it.id == id) {
                    it.copy(
                        pageMinCol = minOf(it.pageMinCol, minCol), pageMaxCol = maxOf(it.pageMaxCol, maxCol),
                        pageMinRow = minOf(it.pageMinRow, minRow), pageMaxRow = maxOf(it.pageMaxRow, maxRow),
                    )
                } else {
                    it
                }
            }
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
        var upsertCount = 0
        override suspend fun upsert(layer: LayerEntity) {
            upsertCount++
            store.removeAll { it.id == layer.id }
            store.add(layer)
        }
        override suspend fun forCanvas(canvasId: String) = store.filter { it.canvasId == canvasId }.sortedBy { it.z }
    }

    private class FakeStrokeDao(private val log: MutableList<String>? = null) : StrokeDao {
        val store = mutableListOf<StrokeEntity>()
        override suspend fun insert(stroke: StrokeEntity) { log?.add("stroke"); store.add(stroke) }
        override suspend fun forLayer(layerId: String) = store.filter { it.layerId == layerId }
        override suspend fun byId(id: String): StrokeEntity? = null
        override suspend fun deleteById(id: String) {}
        override suspend fun count() = 0
    }

    private fun repo(
        canvasDao: FakeCanvasDao,
        layerDao: FakeLayerDao,
        strokeDao: FakeStrokeDao = FakeStrokeDao(),
        runInTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
    ): CanvasRepository {
        var n = 0
        return CanvasRepository(
            spaceDao = FakeSpaceDao(),
            canvasDao = canvasDao,
            layerDao = layerDao,
            strokeDao = strokeDao,
            idGen = { "gen-${n++}" },
            clock = { 42L },
            runInTransaction = runInTransaction,
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

    // --- Stage 34: the grid never shrinks; growth is atomic with the stroke ---

    @Test
    fun a_re_delivered_formalize_job_keeps_a_grown_page_grid() = runTest {
        val canvasDao = FakeCanvasDao().apply { store.add(sourceCanvas()) }
        val layerDao = FakeLayerDao()
        val r = repo(canvasDao, layerDao)
        r.createFormalizedCanvas("srv-9", "space-1", "T — formalized", 1600, 1200, "canvas-1", "job-f")
        r.updatePageExtent("srv-9", PageExtent(-1, 2, 0, 1))

        // The same finished job arrives again (a re-delivered poll result).
        r.createFormalizedCanvas("srv-9", "space-1", "T — formalized", 1600, 1200, "canvas-1", "job-f")

        assertEquals(PageExtent(-1, 2, 0, 1), canvasDao.byId("srv-9")!!.pageExtent)
        assertEquals(1, canvasDao.store.count { it.id == "srv-9" })
        assertEquals(1, layerDao.forCanvas("srv-9").size)
    }

    @Test
    fun a_stroke_and_its_grid_growth_are_written_in_one_transaction() = runTest {
        val log = mutableListOf<String>()
        val canvasDao = FakeCanvasDao(log).apply { store.add(sourceCanvas()) } // 800 × 600 pages
        val strokeDao = FakeStrokeDao(log)
        val r = repo(
            canvasDao, FakeLayerDao(), strokeDao,
            runInTransaction = { block -> log.add("begin"); block(); log.add("end") },
        )
        // A stroke from page (0,0) into the ring page to the left.
        val points = floatArrayOf(100f, 100f, 0.5f, 0f, 0f, -300f, 120f, 0.6f, 0f, 16f)
        val built = com.inkwell.ink.BuiltStroke(points, 2, -300f, 100f, 400f, 20f)

        val result = r.insertStrokeWithGrowth("canvas-1", "ink-1", StrokeCommitData(built, "pen", "#111111", 3f))

        assertEquals(listOf("begin", "stroke", "grid", "end"), log)
        assertEquals(PageExtent(-1, 0, 0, 0), result.pageExtent)
        assertEquals(PageExtent(-1, 0, 0, 0), canvasDao.byId("canvas-1")!!.pageExtent)
        assertEquals(false, result.capped)
        val stored = strokeDao.store.single()
        assertEquals(2, stored.pointCount)
        assertTrue(PackedPoints.decode(stored.points, 2).contentEquals(points))
    }

    @Test
    fun a_stroke_past_the_cap_is_stored_whole_and_reported_capped() = runTest {
        val canvasDao = FakeCanvasDao().apply {
            store.add(sourceCanvas().copy(pageMinCol = -7, pageMaxCol = 0))
        }
        val strokeDao = FakeStrokeDao()
        val r = repo(canvasDao, FakeLayerDao(), strokeDao)
        val points = floatArrayOf(700f, 100f, 0.5f, 0f, 0f, 1000f, 100f, 0.5f, 0f, 16f)
        val built = com.inkwell.ink.BuiltStroke(points, 2, 700f, 100f, 300f, 0f)

        val result = r.insertStrokeWithGrowth("canvas-1", "ink-1", StrokeCommitData(built, "pen", "#111111", 3f))

        assertTrue(result.capped)
        assertEquals(PageExtent(-7, 0, 0, 0), canvasDao.byId("canvas-1")!!.pageExtent)
        assertEquals(2, strokeDao.store.single().pointCount)
        assertTrue(PackedPoints.decode(strokeDao.store.single().points, 2).contentEquals(points))
    }

    @Test
    fun update_page_extent_never_shrinks_the_grid() = runTest {
        val canvasDao = FakeCanvasDao().apply { store.add(sourceCanvas().copy(pageMinCol = -2, pageMaxRow = 3)) }
        repo(canvasDao, FakeLayerDao()).updatePageExtent("canvas-1", PageExtent(0, 1, 0, 0))
        assertEquals(PageExtent(-2, 1, 0, 3), canvasDao.byId("canvas-1")!!.pageExtent)
    }
}
