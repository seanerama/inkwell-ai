package com.inkwell.data

import com.inkwell.data.dao.CanvasDao
import com.inkwell.data.dao.FolderDao
import com.inkwell.data.dao.LayerDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [LibraryRepository] with in-memory fake DAOs (no emulator). The fakes
 * mirror the Room query semantics that matter here: null-scoped folder listing, Trash
 * filtering (`deleted_at IS NULL`), newest-first canvas ordering, and time-cutoff purge.
 */
class LibraryRepositoryTest {

    private val space = "space-1"

    private class FakeFolderDao : FolderDao {
        val store = mutableListOf<FolderEntity>()
        override suspend fun upsert(folder: FolderEntity) {
            store.removeAll { it.id == folder.id }; store.add(folder)
        }
        override suspend fun byId(id: String) = store.firstOrNull { it.id == id }
        override suspend fun childrenOf(spaceId: String, parentId: String?) =
            store.filter {
                it.spaceId == spaceId && it.deletedAt == null &&
                    ((parentId == null && it.parentId == null) || it.parentId == parentId)
            }.sortedBy { it.name }
        override suspend fun allForSpace(spaceId: String) = store.filter { it.spaceId == spaceId }
        override suspend fun trashed(spaceId: String) =
            store.filter { it.spaceId == spaceId && it.deletedAt != null }
                .sortedByDescending { it.deletedAt }
        override suspend fun rename(id: String, name: String, updatedAt: Long) {
            replace(id) { it.copy(name = name, updatedAt = updatedAt) }
        }
        override suspend fun move(id: String, parentId: String?, updatedAt: Long) {
            replace(id) { it.copy(parentId = parentId, updatedAt = updatedAt) }
        }
        override suspend fun moveToSpace(id: String, spaceId: String, parentId: String?, updatedAt: Long) {
            replace(id) { it.copy(spaceId = spaceId, parentId = parentId, updatedAt = updatedAt) }
        }
        override suspend fun setSpace(id: String, spaceId: String, updatedAt: Long) {
            replace(id) { it.copy(spaceId = spaceId, updatedAt = updatedAt) }
        }
        override suspend fun reassignSpace(oldSpaceId: String, newSpaceId: String) {
            store.indices.forEach { i -> if (store[i].spaceId == oldSpaceId) store[i] = store[i].copy(spaceId = newSpaceId) }
        }
        override suspend fun setDeletedAt(id: String, deletedAt: Long?) {
            replace(id) { it.copy(deletedAt = deletedAt) }
        }
        override suspend fun purgeTrashedBefore(cutoff: Long) {
            store.removeAll { it.deletedAt != null && it.deletedAt!! < cutoff }
        }
        override suspend fun hardDelete(id: String) { store.removeAll { it.id == id } }
        private inline fun replace(id: String, f: (FolderEntity) -> FolderEntity) {
            val i = store.indexOfFirst { it.id == id }
            if (i >= 0) store[i] = f(store[i])
        }
    }

    private class FakeCanvasDao : CanvasDao {
        val store = mutableListOf<CanvasEntity>()
        override suspend fun upsert(canvas: CanvasEntity) {
            store.removeAll { it.id == canvas.id }; store.add(canvas)
        }
        override suspend fun forSpace(spaceId: String) =
            store.filter { it.spaceId == spaceId }.sortedByDescending { it.updatedAt }
        override suspend fun byId(id: String) = store.firstOrNull { it.id == id }
        override suspend fun inFolder(spaceId: String, folderId: String?) =
            store.filter {
                it.spaceId == spaceId && it.deletedAt == null &&
                    ((folderId == null && it.folderId == null) || it.folderId == folderId)
            }.sortedByDescending { it.updatedAt }
        override suspend fun trashed(spaceId: String) =
            store.filter { it.spaceId == spaceId && it.deletedAt != null }
                .sortedByDescending { it.deletedAt }
        override suspend fun allForSpace(spaceId: String) = store.filter { it.spaceId == spaceId }
        override suspend fun rename(id: String, title: String, updatedAt: Long) {
            replace(id) { it.copy(title = title, updatedAt = updatedAt) }
        }
        override suspend fun move(id: String, folderId: String?, updatedAt: Long) {
            replace(id) { it.copy(folderId = folderId, updatedAt = updatedAt) }
        }
        override suspend fun moveToSpace(id: String, spaceId: String, updatedAt: Long) {
            replace(id) { it.copy(spaceId = spaceId, folderId = null, updatedAt = updatedAt) }
        }
        override suspend fun setSpace(id: String, spaceId: String, updatedAt: Long) {
            replace(id) { it.copy(spaceId = spaceId, updatedAt = updatedAt) }
        }
        override suspend fun reassignSpace(oldSpaceId: String, newSpaceId: String) {
            store.indices.forEach { i -> if (store[i].spaceId == oldSpaceId) store[i] = store[i].copy(spaceId = newSpaceId) }
        }
        override suspend fun setDeletedAt(id: String, deletedAt: Long?) {
            replace(id) { it.copy(deletedAt = deletedAt) }
        }
        override suspend fun trashCanvasesInFolder(spaceId: String, folderId: String, deletedAt: Long) {
            store.indices.forEach { i ->
                val c = store[i]
                if (c.spaceId == spaceId && c.deletedAt == null && c.folderId == folderId) {
                    store[i] = c.copy(deletedAt = deletedAt)
                }
            }
        }
        override suspend fun purgeTrashedBefore(cutoff: Long) {
            store.removeAll { it.deletedAt != null && it.deletedAt!! < cutoff }
        }
        override suspend fun hardDelete(id: String) { store.removeAll { it.id == id } }
        override suspend fun updatePageExtent(id: String, minCol: Int, maxCol: Int, minRow: Int, maxRow: Int) {
            store.replaceAll { if (it.id == id) it.copy(pageMinCol = minCol, pageMaxCol = maxCol, pageMinRow = minRow, pageMaxRow = maxRow) else it }
        }
        private inline fun replace(id: String, f: (CanvasEntity) -> CanvasEntity) {
            val i = store.indexOfFirst { it.id == id }
            if (i >= 0) store[i] = f(store[i])
        }
    }

    private class FakeLayerDao : LayerDao {
        val store = mutableListOf<LayerEntity>()
        override suspend fun upsert(layer: LayerEntity) {
            store.removeAll { it.id == layer.id }; store.add(layer)
        }
        override suspend fun forCanvas(canvasId: String) =
            store.filter { it.canvasId == canvasId }.sortedBy { it.z }
    }

    private class Fixture {
        val folderDao = FakeFolderDao()
        val canvasDao = FakeCanvasDao()
        val layerDao = FakeLayerDao()
        var now = 1_000L
        var seq = 0
        val repo = LibraryRepository(
            folderDao, canvasDao, layerDao,
            idGen = { "id-${seq++}" },
            clock = { now },
        )
    }

    @Test
    fun contents_lists_folders_then_canvases_newest_first_excluding_deleted() = runTest {
        val fx = Fixture()
        // Two folders (out of alpha order) and three canvases with different updated_at.
        fx.folderDao.upsert(FolderEntity("f-b", space, null, "Beta", 1, 1))
        fx.folderDao.upsert(FolderEntity("f-a", space, null, "Alpha", 1, 1))
        fx.canvasDao.upsert(canvas("c-old", updatedAt = 10))
        fx.canvasDao.upsert(canvas("c-new", updatedAt = 30))
        fx.canvasDao.upsert(canvas("c-mid", updatedAt = 20))
        // A deleted canvas at root must be excluded.
        fx.canvasDao.upsert(canvas("c-del", updatedAt = 40, deletedAt = 5))

        val contents = fx.repo.contents(space, null)

        assertEquals(listOf("Alpha", "Beta"), contents.folders.map { it.name })
        assertEquals(listOf("c-new", "c-mid", "c-old"), contents.canvases.map { it.id })
    }

    @Test
    fun createCanvas_seeds_one_user_ink_layer_and_unique_untitled_title() = runTest {
        val fx = Fixture()
        val first = fx.repo.createCanvas(space, folderId = null)
        val second = fx.repo.createCanvas(space, folderId = null)

        assertEquals("Untitled 1", first.title)
        assertEquals("Untitled 2", second.title)
        assertEquals(CanvasRepository.DEFAULT_WIDTH_CU, first.widthCu)
        assertEquals(CanvasRepository.DEFAULT_HEIGHT_CU, first.heightCu)

        val layers = fx.layerDao.forCanvas(first.id)
        assertEquals(1, layers.size)
        assertEquals("user", layers[0].owner)
        assertEquals("ink", layers[0].type)
    }

    @Test
    fun move_reparents_canvas_and_leaves_its_ink_layers_untouched() = runTest {
        val fx = Fixture()
        val folder = fx.repo.createFolder(space, null, "Network")
        val canvas = fx.repo.createCanvas(space, folderId = null)
        val layersBefore = fx.layerDao.forCanvas(canvas.id)

        fx.now = 5_000L
        fx.repo.moveCanvas(canvas.id, folder.id)

        val moved = fx.canvasDao.byId(canvas.id)!!
        assertEquals(folder.id, moved.folderId)
        assertEquals(5_000L, moved.updatedAt)
        // Ink (layers/strokes) is never touched by a move.
        assertEquals(layersBefore, fx.layerDao.forCanvas(canvas.id))
        // It now lists under the folder, not the root.
        assertTrue(fx.repo.contents(space, null).canvases.isEmpty())
        assertEquals(listOf(canvas.id), fx.repo.contents(space, folder.id).canvases.map { it.id })
    }

    @Test
    fun canvas_delete_to_trash_then_restore_round_trip() = runTest {
        val fx = Fixture()
        val canvas = fx.repo.createCanvas(space, folderId = null)

        fx.now = 2_000L
        fx.repo.deleteCanvas(canvas.id)
        assertTrue(fx.repo.contents(space, null).canvases.isEmpty())
        assertEquals(listOf(canvas.id), fx.repo.trash(space).canvases.map { it.id })

        fx.repo.restoreCanvas(canvas.id)
        assertEquals(listOf(canvas.id), fx.repo.contents(space, null).canvases.map { it.id })
        assertTrue(fx.repo.trash(space).canvases.isEmpty())
    }

    @Test
    fun deleteFolder_moves_subtree_canvases_to_trash() = runTest {
        val fx = Fixture()
        val parent = fx.repo.createFolder(space, null, "Network")
        val child = fx.repo.createFolder(space, parent.id, "Diagrams")
        val inParent = fx.repo.createCanvas(space, folderId = parent.id)
        val inChild = fx.repo.createCanvas(space, folderId = child.id)

        fx.now = 3_000L
        fx.repo.deleteFolder(space, parent.id)

        // Both folders and both canvases are now in Trash.
        val trash = fx.repo.trash(space)
        assertEquals(setOf(parent.id, child.id), trash.folders.map { it.id }.toSet())
        assertEquals(setOf(inParent.id, inChild.id), trash.canvases.map { it.id }.toSet())
        // The root no longer lists the deleted folder.
        assertTrue(fx.repo.contents(space, null).folders.isEmpty())
    }

    @Test
    fun purge_removes_trash_older_than_30_days_but_keeps_28_days() = runTest {
        val fx = Fixture()
        val survivor = fx.repo.createCanvas(space, folderId = null)
        val doomed = fx.repo.createCanvas(space, folderId = null)

        // Trash both "now".
        fx.now = 100_000_000L
        fx.repo.deleteCanvas(survivor.id)
        fx.repo.deleteCanvas(doomed.id)
        // Re-date one tombstone to 31 days old and the other to 28 days old.
        val day = 24L * 60 * 60 * 1000
        fx.canvasDao.setDeletedAt(survivor.id, fx.now - 28 * day)
        fx.canvasDao.setDeletedAt(doomed.id, fx.now - 31 * day)

        fx.repo.purgeExpiredTrash()

        val trashIds = fx.repo.trash(space).canvases.map { it.id }
        assertTrue("28-day-old survives", trashIds.contains(survivor.id))
        assertFalse("31-day-old purged", trashIds.contains(doomed.id))
        assertNull(fx.canvasDao.byId(doomed.id))
        assertNotNull(fx.canvasDao.byId(survivor.id))
    }

    @Test
    fun rename_bumps_updated_at() = runTest {
        val fx = Fixture()
        val folder = fx.repo.createFolder(space, null, "Old")
        assertEquals(1_000L, folder.updatedAt)

        fx.now = 9_999L
        fx.repo.renameFolder(folder.id, "New")
        val renamed = fx.folderDao.byId(folder.id)!!
        assertEquals("New", renamed.name)
        assertEquals(9_999L, renamed.updatedAt)
    }

    @Test
    fun nextUntitledTitle_is_one_past_the_highest_existing() {
        assertEquals("Untitled 1", LibraryRepository.nextUntitledTitle(emptyList()))
        assertEquals("Untitled 1", LibraryRepository.nextUntitledTitle(listOf("Notes", "Sketch")))
        assertEquals(
            "Untitled 4",
            LibraryRepository.nextUntitledTitle(listOf("Untitled 1", "Untitled 3", "Other")),
        )
    }

    // --- Stage 14: move between spaces (ADR-0010) ---

    @Test
    fun moveCanvasToSpace_sets_space_and_clears_folder_and_keeps_ink() = runTest {
        val fx = Fixture()
        val folder = fx.repo.createFolder(space, null, "Network")
        val canvas = fx.repo.createCanvas(space, folderId = folder.id)
        val layersBefore = fx.layerDao.forCanvas(canvas.id)

        fx.now = 7_000L
        fx.repo.moveCanvasToSpace(canvas.id, "space-learning")

        val moved = fx.canvasDao.byId(canvas.id)!!
        assertEquals("space-learning", moved.spaceId)
        assertNull("moves to the target space root", moved.folderId)
        assertEquals(7_000L, moved.updatedAt)
        // Ink rides along untouched.
        assertEquals(layersBefore, fx.layerDao.forCanvas(canvas.id))
        // It no longer lists in the source space, and shows at the target root.
        assertTrue(fx.repo.contents(space, folder.id).canvases.isEmpty())
        assertEquals(listOf(canvas.id), fx.repo.contents("space-learning", null).canvases.map { it.id })
    }

    @Test
    fun moveFolderToSpace_carries_the_whole_subtree_including_trashed() = runTest {
        val fx = Fixture()
        val parent = fx.repo.createFolder(space, null, "Network")
        val child = fx.repo.createFolder(space, parent.id, "Diagrams")
        val inParent = fx.repo.createCanvas(space, folderId = parent.id)
        val inChild = fx.repo.createCanvas(space, folderId = child.id)
        // A trashed canvas in the subtree must move too.
        fx.now = 2_000L
        fx.repo.deleteCanvas(inChild.id)

        fx.now = 8_000L
        fx.repo.moveFolderToSpace(space, parent.id, "space-business")

        // Every folder and canvas now belongs to the target space.
        val movedParent = fx.folderDao.byId(parent.id)!!
        val movedChild = fx.folderDao.byId(child.id)!!
        assertEquals("space-business", movedParent.spaceId)
        assertNull("moved root reparented to the target root", movedParent.parentId)
        assertEquals("space-business", movedChild.spaceId)
        assertEquals("subtree structure preserved", parent.id, movedChild.parentId)
        assertEquals("space-business", fx.canvasDao.byId(inParent.id)!!.spaceId)
        val movedTrashed = fx.canvasDao.byId(inChild.id)!!
        assertEquals("space-business", movedTrashed.spaceId)
        assertNotNull("still trashed after the move", movedTrashed.deletedAt)
        // Nothing is left behind in the source space.
        assertTrue(fx.folderDao.allForSpace(space).isEmpty())
        assertTrue(fx.canvasDao.allForSpace(space).isEmpty())
    }

    @Test
    fun moveFolderToSpace_onto_same_space_is_a_noop() = runTest {
        val fx = Fixture()
        val parent = fx.repo.createFolder(space, null, "Network")
        fx.repo.moveFolderToSpace(space, parent.id, space)
        assertEquals(space, fx.folderDao.byId(parent.id)!!.spaceId)
    }

    private fun canvas(
        id: String,
        updatedAt: Long,
        folderId: String? = null,
        deletedAt: Long? = null,
    ) = CanvasEntity(
        id = id,
        spaceId = space,
        title = id,
        createdAt = 1,
        updatedAt = updatedAt,
        origin = "user",
        folderId = folderId,
        deletedAt = deletedAt,
    )
}
