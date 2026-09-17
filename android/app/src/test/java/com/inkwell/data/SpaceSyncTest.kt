package com.inkwell.data

import com.inkwell.data.dao.CanvasDao
import com.inkwell.data.dao.FolderDao
import com.inkwell.data.dao.SpaceDao
import com.inkwell.net.CardResponse
import com.inkwell.net.CardStateRequest
import com.inkwell.net.DeviceApi
import com.inkwell.net.DeviceRepository
import com.inkwell.net.HealthResponse
import com.inkwell.net.Job
import com.inkwell.net.JobCreateRequest
import com.inkwell.net.Space
import com.inkwell.net.SyncResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [SpaceSync] with in-memory fake DAOs and a fake [DeviceApi] (no
 * emulator). The transaction runner runs the block inline. They prove the Stage-14 (ADR-0010)
 * reconciliation: a locally invented placeholder space is repointed onto its server twin by
 * slug — canvases and folders carry the server id, the placeholder row is deleted — and that
 * a second refresh is a no-op, while a local space with no server slug match is left alone.
 */
class SpaceSyncTest {

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

    /** A [DeviceApi] that returns a fixed spaces list; everything else is unused. */
    private class FakeDeviceApi(private val spaces: List<Space>) : DeviceApi {
        override suspend fun health() = HealthResponse("ok", "test", "device-api/v1")
        override suspend fun spaces() = spaces
        override suspend fun createJob(body: JobCreateRequest): Job = error("unused")
        override suspend fun getJob(id: String): Job = error("unused")
        override suspend fun cancelJob(id: String): Job = error("unused")
        override suspend fun sync(cursor: String?): SyncResponse = error("unused")
        override suspend fun patchCard(id: String, body: CardStateRequest): CardResponse = error("unused")
        override suspend fun runCardAction(id: String, actionId: String): CardResponse = error("unused")
    }

    private fun space(id: String, slug: String, position: Int = 0) = Space(
        id = id, name = slug.replaceFirstChar { it.uppercase() }, slug = slug, systemPrompt = "",
        tools = emptyList(), model = "claude-sonnet-5", color = "#3B6EA5", position = position,
        createdAt = "2026-09-16T00:00:00Z",
    )

    private fun localSpace(id: String, slug: String) = SpaceEntity(
        id = id, name = slug, slug = slug, systemPrompt = "", tools = emptyList(),
        model = "claude-sonnet-5", color = "#3B6EA5", position = 0, createdAt = 0,
    )

    private fun canvas(id: String, spaceId: String) = CanvasEntity(
        id = id, spaceId = spaceId, title = id, createdAt = 1, updatedAt = 1, origin = "user",
    )

    private fun folder(id: String, spaceId: String) = FolderEntity(
        id = id, spaceId = spaceId, parentId = null, name = id, createdAt = 1, updatedAt = 1,
    )

    private fun setup(spaces: List<Space>): Triple<FakeSpaceDao, Pair<FakeCanvasDao, FakeFolderDao>, SpaceSync> {
        val spaceDao = FakeSpaceDao()
        val canvasDao = FakeCanvasDao()
        val folderDao = FakeFolderDao()
        val repo = DeviceRepository(FakeDeviceApi(spaces))
        val sync = SpaceSync(
            spaceDao = spaceDao, canvasDao = canvasDao, folderDao = folderDao,
            deviceRepositoryProvider = { repo },
            runInTransaction = { it() },
        )
        return Triple(spaceDao, canvasDao to folderDao, sync)
    }

    @Test
    fun reconciliation_repoints_ink_to_the_server_id_and_deletes_the_placeholder() = runTest {
        val (spaceDao, daos, sync) = setup(listOf(space("srv-work", "work")))
        val (canvasDao, folderDao) = daos
        spaceDao.upsert(localSpace("local-1", "work"))
        canvasDao.upsert(canvas("c1", "local-1"))
        folderDao.upsert(folder("f1", "local-1"))

        val serverIds = sync.refresh()

        assertEquals(setOf("srv-work"), serverIds)
        assertEquals("srv-work", canvasDao.byId("c1")!!.spaceId)
        assertEquals("srv-work", folderDao.byId("f1")!!.spaceId)
        assertNull("placeholder row deleted", spaceDao.byId("local-1"))
        assertNotNull("server row present", spaceDao.byId("srv-work"))
    }

    @Test
    fun a_second_refresh_is_a_noop() = runTest {
        val (spaceDao, daos, sync) = setup(listOf(space("srv-work", "work")))
        val (canvasDao, _) = daos
        spaceDao.upsert(localSpace("local-1", "work"))
        canvasDao.upsert(canvas("c1", "local-1"))

        sync.refresh()
        val afterFirst = canvasDao.byId("c1")!!.spaceId
        val serverIds = sync.refresh()

        assertEquals("srv-work", afterFirst)
        assertEquals("srv-work", canvasDao.byId("c1")!!.spaceId)
        assertEquals(setOf("srv-work"), serverIds)
        assertEquals("only the server space remains", listOf("srv-work"), spaceDao.all().map { it.id })
    }

    @Test
    fun a_local_space_with_no_server_slug_match_is_left_alone() = runTest {
        val (spaceDao, daos, sync) = setup(listOf(space("srv-work", "work")))
        val (canvasDao, _) = daos
        spaceDao.upsert(localSpace("orphan", "scratch"))
        canvasDao.upsert(canvas("c1", "orphan"))
        val warnings = mutableListOf<String>()
        val syncWithLog = SpaceSync(
            spaceDao = spaceDao, canvasDao = canvasDao, folderDao = FakeFolderDao(),
            deviceRepositoryProvider = { DeviceRepository(FakeDeviceApi(listOf(space("srv-work", "work")))) },
            runInTransaction = { it() },
            log = { warnings.add(it) },
        )

        syncWithLog.refresh()

        assertNotNull("unmatched local space kept", spaceDao.byId("orphan"))
        assertEquals("its canvas is not repointed", "orphan", canvasDao.byId("c1")!!.spaceId)
        assertTrue("the mismatch is logged", warnings.any { it.contains("scratch") })
    }

    @Test
    fun unpaired_refresh_throws_not_paired() = runTest {
        val sync = SpaceSync(
            spaceDao = FakeSpaceDao(), canvasDao = FakeCanvasDao(), folderDao = FakeFolderDao(),
            deviceRepositoryProvider = { null },
            runInTransaction = { it() },
        )
        try {
            sync.refresh()
            assertTrue("expected NotPairedException", false)
        } catch (_: SpaceSync.NotPairedException) {
            // expected
        }
    }

    @Test
    fun rfc3339_created_at_parses_to_epoch_ms_else_zero() {
        assertEquals(0L, SpaceSync.parseEpochMs("1970-01-01T00:00:00Z"))
        assertEquals(1000L, SpaceSync.parseEpochMs("1970-01-01T00:00:01Z"))
        assertEquals(0L, SpaceSync.parseEpochMs("not-a-date"))
    }
}
