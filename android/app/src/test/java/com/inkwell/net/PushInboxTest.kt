package com.inkwell.net

import com.inkwell.data.CanvasEntity
import com.inkwell.data.LayerEntity
import com.inkwell.data.PushedCanvasStore
import com.inkwell.data.RasterEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [PushInbox] (Stage 22, ADR-0012) with in-memory fakes (no emulator,
 * no Room, no network). They prove the discovery contract: seed the cursor on first run
 * (no replay), advance the cursor ONLY after a page materialises, dedupe by canvas id, and
 * create one folder per multi-page document.
 */
class PushInboxTest {

    private val json = Json { ignoreUnknownKeys = true }

    private class FakeStore : PushedCanvasStore {
        val canvases = mutableListOf<CanvasEntity>()
        val layers = mutableListOf<LayerEntity>()
        val rasters = mutableListOf<RasterEntity>()
        val folders = mutableListOf<Pair<String, String>>() // (spaceId, name)
        private var folderSeq = 0
        override suspend fun canvasExists(canvasId: String) = canvases.any { it.id == canvasId }
        override suspend fun createFolder(spaceId: String, name: String): String {
            folders += spaceId to name
            return "folder-${folderSeq++}"
        }
        override suspend fun insertCanvas(canvas: CanvasEntity) {
            canvases.removeAll { it.id == canvas.id }
            canvases += canvas
        }
        override suspend fun insertLayer(layer: LayerEntity) { layers += layer }
        override suspend fun insertRaster(raster: RasterEntity) { rasters += raster }
    }

    private class FakeDownloader(val fail: Boolean = false) : BlobDownloader {
        val calls = mutableListOf<String>()
        override suspend fun download(url: String, canvasId: String, rasterId: String, page: Int?, mime: String): String {
            calls += url
            if (fail) throw ApiException(500, null)
            return "/cache/push/$rasterId.pdf"
        }
    }

    private class FakeCursorStore(var value: String? = null) : SyncCursorStore {
        val sets = mutableListOf<String>()
        override fun get(): String? = value
        override fun set(cursor: String) { value = cursor; sets += cursor }
    }

    /** A [DeviceApi] returning scripted `/sync` pages; every other route is unused. */
    private class FakeApi(private val pages: ArrayDeque<SyncResponse>) : DeviceApi {
        val cursorsSent = mutableListOf<String?>()
        override suspend fun health() = error("unused")
        override suspend fun spaces() = error("unused")
        override suspend fun createSpace(body: SpaceCreateRequest) = error("unused")
        override suspend fun patchSpace(id: String, body: SpacePatchRequest) = error("unused")
        override suspend fun createJob(body: JobCreateRequest) = error("unused")
        override suspend fun getJob(id: String) = error("unused")
        override suspend fun cancelJob(id: String) = error("unused")
        override suspend fun sync(cursor: String?): SyncResponse {
            cursorsSent += cursor
            return pages.removeFirst()
        }
        override suspend fun patchCard(id: String, body: CardStateRequest) = error("unused")
        override suspend fun runCardAction(id: String, actionId: String) = error("unused")
        override suspend fun getCanvas(id: String) = error("unused")
        override suspend fun downloadBlob(url: String) = error("unused")
    }

    private fun pushJob(id: String, resultJson: String) = Job(
        id = id,
        spaceId = "s1",
        canvasId = null,
        direction = "to_user",
        type = "agent.push_document",
        status = "done",
        request = kotlinx.serialization.json.JsonObject(emptyMap()),
        result = json.parseToJsonElement(resultJson).jsonObject,
        error = null,
        createdAt = "2026-09-18T00:00:00Z",
        updatedAt = "2026-09-18T00:00:01Z",
    )

    private fun singlePageResult(canvasId: String = "c1", title: String = "Brief") = """
        {
          "canvas": {"id":"$canvasId","space_id":"s1","title":"$title","width_cu":2480,"height_cu":3508,"origin":"agent"},
          "layers": [{"id":"l-$canvasId","canvas_id":"$canvasId","z":-1,"owner":"agent","type":"raster","job_id":"j1"}],
          "rasters": [{"id":"r-$canvasId","layer_id":"l-$canvasId","url":"https://blob/$canvasId?sig=x","mime":"application/pdf","page":0,"x_cu":0,"y_cu":0,"w_cu":2480,"h_cu":3508}],
          "cards": []
        }
    """.trimIndent()

    private fun multiPageResult(): String {
        fun page(n: Int) = """
            {"id":"c$n","space_id":"s1","title":"Brief — p$n","width_cu":2480,"height_cu":3508,"origin":"agent"}
        """.trimIndent()
        fun layer(n: Int) = """{"id":"l$n","canvas_id":"c$n","z":-1,"owner":"agent","type":"raster","job_id":"j1"}"""
        fun raster(n: Int) = """{"id":"r$n","layer_id":"l$n","url":"https://blob/c$n?sig=x","mime":"application/pdf","page":${n - 1},"x_cu":0,"y_cu":0,"w_cu":2480,"h_cu":3508}"""
        return """
            {
              "canvas": ${page(1)},
              "canvases": [${page(1)}, ${page(2)}, ${page(3)}],
              "layers": [${layer(1)}, ${layer(2)}, ${layer(3)}],
              "rasters": [${raster(1)}, ${raster(2)}, ${raster(3)}],
              "cards": []
            }
        """.trimIndent()
    }

    @Test
    fun firstRun_seeds_current_cursor_and_replays_nothing() = runTest {
        val store = FakeStore()
        val downloader = FakeDownloader()
        val cursor = FakeCursorStore(value = null) // never seeded
        val api = FakeApi(ArrayDeque(listOf(SyncResponse(jobs = emptyList(), cursor = "seed-cursor"))))
        val inbox = PushInbox(store, downloader)

        val n = inbox.poll(DeviceRepository(api), cursor)

        assertEquals(0, n)
        assertEquals("seed-cursor", cursor.value) // seeded with the CURRENT cursor
        assertEquals(listOf<String?>(null), api.cursorsSent) // sync(null) only
        assertTrue("no old history replayed", store.canvases.isEmpty())
    }

    @Test
    fun poll_materialises_pushed_job_and_advances_cursor() = runTest {
        val store = FakeStore()
        val downloader = FakeDownloader()
        val cursor = FakeCursorStore(value = "c0") // already seeded
        val api = FakeApi(
            ArrayDeque(listOf(SyncResponse(jobs = listOf(pushJob("j1", singlePageResult())), cursor = "c1"))),
        )
        val inbox = PushInbox(store, downloader)

        val n = inbox.poll(DeviceRepository(api), cursor)

        assertEquals(1, n)
        assertEquals(listOf<String?>("c0"), api.cursorsSent) // paged from the persisted cursor
        assertEquals("c1", cursor.value) // advanced only after materialise
        assertEquals(1, store.canvases.size)
        assertEquals("agent", store.canvases.single().origin)
        assertNull("pushed canvas is unread", store.canvases.single().seenAt)
        assertEquals("/cache/push/r-c1.pdf", store.rasters.single().blobUri)
        assertEquals(1, downloader.calls.size)
    }

    @Test
    fun materialise_is_idempotent_same_job_twice_one_canvas() = runTest {
        val store = FakeStore()
        val inbox = PushInbox(store, FakeDownloader())
        val job = pushJob("j1", singlePageResult())

        assertTrue(inbox.materialise(job))
        assertFalse("second time is a no-op (deduped by canvas id)", inbox.materialise(job))

        assertEquals(1, store.canvases.size)
        assertEquals(1, store.layers.size)
        assertEquals(1, store.rasters.size)
    }

    @Test
    fun materialise_multipage_creates_one_folder_and_three_canvases() = runTest {
        val store = FakeStore()
        val inbox = PushInbox(store, FakeDownloader())

        assertTrue(inbox.materialise(pushJob("j1", multiPageResult())))

        assertEquals(1, store.folders.size)
        assertEquals("s1", store.folders.single().first) // folder created in the job's space
        assertEquals("Brief", store.folders.single().second) // name derived from "Brief — pN"
        assertEquals(3, store.canvases.size)
        val folderIds = store.canvases.map { it.folderId }.toSet()
        assertEquals("all three pages share the one folder", 1, folderIds.size)
        assertEquals("folder-0", folderIds.single())
    }

    @Test
    fun cursor_not_advanced_when_materialise_fails() = runTest {
        val store = FakeStore()
        val downloader = FakeDownloader(fail = true) // download throws
        val cursor = FakeCursorStore(value = "c0")
        val api = FakeApi(
            ArrayDeque(listOf(SyncResponse(jobs = listOf(pushJob("j1", singlePageResult())), cursor = "c1"))),
        )
        val inbox = PushInbox(store, downloader)

        var threw = false
        try {
            inbox.poll(DeviceRepository(api), cursor)
        } catch (_: Exception) {
            threw = true
        }

        assertTrue("a materialise failure propagates", threw)
        assertEquals("cursor stays put so the page is retried", "c0", cursor.value)
        assertTrue("cursor was never advanced", cursor.sets.isEmpty())
    }

    @Test
    fun folderNameFor_strips_the_page_marker() {
        assertEquals("Brief", PushInbox.folderNameFor("Brief — p1"))
        assertEquals("Q3 Report", PushInbox.folderNameFor("Q3 Report — p12"))
        assertEquals("Notes", PushInbox.folderNameFor("Notes")) // single page, unchanged
    }
}
