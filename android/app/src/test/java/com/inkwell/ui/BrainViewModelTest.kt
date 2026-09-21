package com.inkwell.ui

import com.inkwell.net.BrainCreate
import com.inkwell.net.BrainEntry
import com.inkwell.net.CardResponse
import com.inkwell.net.CardStateRequest
import com.inkwell.net.DeviceApi
import com.inkwell.net.DeviceRepository
import com.inkwell.net.HealthResponse
import com.inkwell.net.Job
import com.inkwell.net.JobCreateRequest
import com.inkwell.net.Space
import com.inkwell.net.SpaceCreateRequest
import com.inkwell.net.SpacePatchRequest
import com.inkwell.net.SyncResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Stage 27 JVM unit guard for [BrainViewModel] (no emulator). The brain is server-truth and never
 * mirrored (ADR-0013 §6): the model calls the REAL [DeviceRepository] over a fake [DeviceApi] that
 * mimics the frozen `/brain/{slug}` routes. Covers: initial load (newest first), search (`?q=`),
 * delete + undo (re-POST), add (insert + request body), and the offline "needs a connection" state
 * that keeps the last list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrainViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /** A fake `/brain/{slug}` server: newest-first store, `?q=` substring match, idempotent POST. */
    private class BrainApi(seed: List<BrainEntry>) : DeviceApi {
        // Newest first: index 0 is the newest.
        val store = ArrayDeque(seed)
        var lastQuery: String? = "UNSET"
        val posted = mutableListOf<BrainCreate>()
        val deleted = mutableListOf<String>()
        var failNext = false
        private var seq = 100

        override suspend fun getBrain(slug: String, q: String?, limit: Int?): List<BrainEntry> {
            if (failNext) { failNext = false; throw RuntimeException("network down") }
            lastQuery = q
            return if (q.isNullOrBlank()) {
                store.toList()
            } else {
                store.filter { it.text.contains(q, ignoreCase = true) }
            }
        }

        override suspend fun postBrain(slug: String, body: BrainCreate): BrainEntry {
            if (failNext) { failNext = false; throw RuntimeException("network down") }
            posted += body
            // Idempotent on text: return the existing row if present, else create + prepend.
            store.firstOrNull { it.text == body.text }?.let { return it }
            val entry = BrainEntry(
                id = "b${seq++}", spaceSlug = slug, kind = body.kind, text = body.text,
                tags = body.tags ?: emptyList(), sourceCanvasId = body.sourceCanvasId,
                createdAt = "2026-09-21T00:00:00Z",
            )
            store.addFirst(entry)
            return entry
        }

        override suspend fun deleteBrain(slug: String, id: String): Response<Unit> {
            if (failNext) { failNext = false; throw RuntimeException("network down") }
            deleted += id
            store.removeAll { it.id == id }
            return Response.success(Unit)
        }

        override suspend fun health() = HealthResponse("ok", "test", "device-api/v1")
        override suspend fun spaces(): List<Space> = error("unused")
        override suspend fun createSpace(body: SpaceCreateRequest): Space = error("unused")
        override suspend fun patchSpace(id: String, body: SpacePatchRequest): Space = error("unused")
        override suspend fun createJob(body: JobCreateRequest): Job = error("unused")
        override suspend fun getJob(id: String): Job = error("unused")
        override suspend fun cancelJob(id: String): Job = error("unused")
        override suspend fun sync(cursor: String?): SyncResponse = error("unused")
        override suspend fun patchCard(id: String, body: CardStateRequest): CardResponse = error("unused")
        override suspend fun runCardAction(id: String, actionId: String): CardResponse = error("unused")
        override suspend fun getCanvas(id: String) = error("unused")
        override suspend fun downloadBlob(url: String) = error("unused")
    }

    private fun entry(id: String, text: String, kind: String = "fact", canvasId: String? = null) =
        BrainEntry(
            id = id, spaceSlug = "work", kind = kind, text = text, tags = emptyList(),
            sourceCanvasId = canvasId, createdAt = "2026-09-21T00:00:00Z",
        )

    private fun vm(
        api: BrainApi,
        canvasExists: suspend (String) -> Boolean = { false },
    ): BrainViewModel = BrainViewModel(
        deviceRepositoryProvider = { DeviceRepository(api) },
        canvasExistsLocally = canvasExists,
        searchDebounceMs = 0L, // delay(0) returns immediately → debounce runs synchronously
        undoWindowMs = 60_000L,
    )

    @Test
    fun open_lists_newest_first() {
        val api = BrainApi(listOf(entry("b2", "newer"), entry("b1", "older")))
        val vm = vm(api)
        vm.open("work")
        assertEquals(listOf("b2", "b1"), vm.entries.map { it.id })
        assertFalse(vm.needsConnection)
    }

    @Test
    fun search_sets_query_and_filters() {
        val api = BrainApi(listOf(entry("b2", "Q3 offsite: Austin, 14 Oct"), entry("b1", "lunch menu")))
        val vm = vm(api)
        vm.open("work")
        vm.onQueryChange("austin")
        assertEquals("austin", api.lastQuery)
        assertEquals(listOf("b2"), vm.entries.map { it.id })
        // Empty query → newest again.
        vm.onQueryChange("")
        assertEquals(null, api.lastQuery)
        assertEquals(listOf("b2", "b1"), vm.entries.map { it.id })
    }

    @Test
    fun delete_removes_row_then_undo_readds() {
        val api = BrainApi(listOf(entry("b2", "keep"), entry("b1", "drop")))
        val vm = vm(api)
        vm.open("work")
        val target = vm.entries.first { it.id == "b1" }

        vm.deleteEntry(target)
        assertEquals(listOf("b2"), vm.entries.map { it.id })
        assertEquals(listOf("b1"), api.deleted)
        assertEquals("b1", vm.pendingUndo?.id)

        vm.undoDelete()
        // Re-POST re-creates the row (idempotent on text → a fresh id, but the text is back).
        assertTrue(vm.entries.any { it.text == "drop" })
        assertNull(vm.pendingUndo)
        assertEquals(1, api.posted.size)
        assertEquals("drop", api.posted.first().text)
    }

    @Test
    fun add_posts_body_and_inserts_at_top() {
        val api = BrainApi(listOf(entry("b1", "existing")))
        val vm = vm(api)
        vm.open("work")

        vm.addEntry(kind = "task", text = "  Book the venue ", tags = "ops, q3 ops")

        assertEquals(1, api.posted.size)
        val body = api.posted.first()
        assertEquals("task", body.kind)
        assertEquals("Book the venue", body.text) // trimmed
        assertEquals(listOf("ops", "q3"), body.tags) // split + de-duped
        assertEquals("Book the venue", vm.entries.first().text) // inserted at top
    }

    @Test
    fun offline_sets_needs_connection_and_keeps_last_list() {
        val api = BrainApi(listOf(entry("b2", "newer"), entry("b1", "older")))
        val vm = vm(api)
        vm.open("work")
        assertEquals(2, vm.entries.size)

        api.failNext = true
        vm.refresh()
        assertTrue(vm.needsConnection)
        assertEquals(listOf("b2", "b1"), vm.entries.map { it.id }) // last list retained

        // A subsequent success clears the flag.
        vm.refresh()
        assertFalse(vm.needsConnection)
    }

    @Test
    fun tap_opens_local_source_canvas_only_when_present() {
        val api = BrainApi(listOf(entry("b1", "on a canvas", canvasId = "cv-1")))
        val vm = vm(api, canvasExists = { it == "cv-1" })
        vm.open("work")
        var opened: String? = null
        vm.onEntryTapped(vm.entries.first()) { opened = it }
        assertEquals("cv-1", opened)

        // An entry whose canvas is not local does not navigate.
        val api2 = BrainApi(listOf(entry("b1", "no canvas", canvasId = "missing")))
        val vm2 = vm(api2, canvasExists = { false })
        vm2.open("work")
        var opened2: String? = null
        vm2.onEntryTapped(vm2.entries.first()) { opened2 = it }
        assertNull(opened2)
    }
}
