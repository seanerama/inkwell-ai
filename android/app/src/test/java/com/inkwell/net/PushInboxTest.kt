package com.inkwell.net

import com.inkwell.data.CanvasEntity
import com.inkwell.data.LayerEntity
import com.inkwell.data.PushedCanvasStore
import com.inkwell.data.RasterEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.File

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
        /** Stage 29: when true, insertCanvas throws BEFORE persisting (a persistent poison). */
        var insertFails = false
        override suspend fun canvasExists(canvasId: String) = canvases.any { it.id == canvasId }
        override suspend fun createFolder(spaceId: String, name: String): String {
            folders += spaceId to name
            return "folder-${folderSeq++}"
        }
        override suspend fun insertCanvas(canvas: CanvasEntity) {
            if (insertFails) throw IllegalStateException("insert boom for ${canvas.id}")
            canvases.removeAll { it.id == canvas.id }
            canvases += canvas
        }
        override suspend fun insertLayer(layer: LayerEntity) { layers += layer }
        override suspend fun insertRaster(raster: RasterEntity) { rasters += raster }
    }

    private class FakeDownloader(var fail: Boolean = false) : BlobDownloader {
        val calls = mutableListOf<String>()
        override suspend fun download(url: String, canvasId: String, rasterId: String, page: Int?, mime: String): String {
            calls += url
            if (fail) throw ApiException(500, null)
            return "/cache/push/$rasterId.pdf"
        }
    }

    private class FakeCursorStore(var value: String? = null, var schema: Int = INBOX_SCHEMA_VERSION) : SyncCursorStore {
        val sets = mutableListOf<String>()
        override fun get(): String? = value
        override fun set(cursor: String) { value = cursor; sets += cursor }
        override fun clear() { value = null }
        override fun getSchemaVersion(): Int = schema
        override fun setSchemaVersion(version: Int) { schema = version }
    }

    /** A [DeviceApi] that returns the same `/sync` page for every cursor (poison/resync tests). */
    private class RepeatingApi(private val page: SyncResponse) : UnusedApi() {
        val cursorsSent = mutableListOf<String?>()
        override suspend fun sync(cursor: String?): SyncResponse {
            cursorsSent += cursor
            return page
        }
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
        override suspend fun getBrain(slug: String, q: String?, limit: Int?) = error("unused")
        override suspend fun postBrain(slug: String, body: BrainCreate) = error("unused")
        override suspend fun deleteBrain(slug: String, id: String) = error("unused")
    }

    /** A [DeviceApi] whose every route errors; the two fakes below override only what they use. */
    private abstract class UnusedApi : DeviceApi {
        override suspend fun health() = error("unused")
        override suspend fun spaces() = error("unused")
        override suspend fun createSpace(body: SpaceCreateRequest) = error("unused")
        override suspend fun patchSpace(id: String, body: SpacePatchRequest) = error("unused")
        override suspend fun createJob(body: JobCreateRequest) = error("unused")
        override suspend fun getJob(id: String) = error("unused")
        override suspend fun cancelJob(id: String) = error("unused")
        override suspend fun sync(cursor: String?): SyncResponse = error("unused")
        override suspend fun patchCard(id: String, body: CardStateRequest) = error("unused")
        override suspend fun runCardAction(id: String, actionId: String) = error("unused")
        override suspend fun getCanvas(id: String): CanvasDetail = error("unused")
        override suspend fun downloadBlob(url: String): ResponseBody = error("unused")
        override suspend fun getBrain(slug: String, q: String?, limit: Int?) = error("unused")
        override suspend fun postBrain(slug: String, body: BrainCreate) = error("unused")
        override suspend fun deleteBrain(slug: String, id: String) = error("unused")
    }

    /**
     * Serves one `/sync` page; the first blob GET (stale url) 403s, `getCanvas` then hands back
     * a fresh url, and the second blob GET (that fresh url) returns the pdf bytes.
     */
    private class FakeBlobApi(
        private val page: SyncResponse,
        private val freshUrl: String,
        private val pdfBytes: ByteArray,
    ) : UnusedApi() {
        var blobCalls = 0
        var canvasCalls = 0
        override suspend fun sync(cursor: String?): SyncResponse = page
        override suspend fun getCanvas(id: String): CanvasDetail {
            canvasCalls++
            return CanvasDetail(
                id = id,
                spaceId = "s1",
                title = "Brief",
                layers = listOf(WireLayer(id = "l-$id", canvasId = id)),
                rasters = listOf(
                    WireRaster(id = "r-$id", layerId = "l-$id", url = freshUrl, mime = "application/pdf", page = 0),
                ),
            )
        }
        override suspend fun downloadBlob(url: String): ResponseBody {
            blobCalls++
            if (url != freshUrl) {
                // The signed link expired between sync and download → server 403.
                throw HttpException(
                    Response.error<ResponseBody>(
                        403,
                        """{"error":{"code":"forbidden","message":"expired signature"}}"""
                            .toResponseBody("application/json".toMediaType()),
                    ),
                )
            }
            return pdfBytes.toResponseBody("application/pdf".toMediaType())
        }
    }

    /** 422s on any non-null cursor (unknown/stale), then serves [page] on the re-seeded sync(null). */
    private class ReseedApi(private val page: SyncResponse) : UnusedApi() {
        val cursorsSent = mutableListOf<String?>()
        override suspend fun sync(cursor: String?): SyncResponse {
            cursorsSent += cursor
            if (cursor != null) {
                throw HttpException(
                    Response.error<ResponseBody>(
                        422,
                        """{"error":{"code":"validation","message":"unknown cursor"}}"""
                            .toResponseBody("application/json".toMediaType()),
                    ),
                )
            }
            return page
        }
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
    fun firstRun_backfills_pushes_that_predate_first_sync_and_dedupes() = runTest {
        // Stage 24: on first run (no persisted cursor) the inbox BACKFILLS from sync(null)
        // instead of only seeding the cursor — a push that landed before the first sync lands.
        val store = FakeStore()
        val downloader = FakeDownloader()
        val cursor = FakeCursorStore(value = null) // fresh install: never seeded
        val inbox = PushInbox(store, downloader)

        // One of the two old pushes is already local (materialised on an earlier pass/pairing).
        assertTrue(inbox.materialise(pushJob("seed", singlePageResult("c1"))))

        val page = SyncResponse(
            jobs = listOf(
                pushJob("j1", singlePageResult("c1")), // already local → deduped
                pushJob("j2", singlePageResult("c2")), // not local → materialised
            ),
            cursor = "c-new",
        )
        val api = FakeApi(ArrayDeque(listOf(page, page.copy(cursor = "c-new2"))))
        val repo = DeviceRepository(api)

        val first = inbox.poll(repo, cursor)
        assertEquals("exactly one old push backfilled (the other was already local)", 1, first)
        assertEquals("first run pages from sync(null)", null, api.cursorsSent.first())
        assertEquals("cursor persisted from the backfilled page", "c-new", cursor.value)
        assertEquals(2, store.canvases.size)

        val second = inbox.poll(repo, cursor)
        assertEquals("a second poll materialises nothing new (dedupe)", 0, second)
        assertEquals("c-new2", cursor.value)
    }

    @Test
    fun materialise_refreshes_an_expired_raster_url_and_downloads() = runTest {
        // Stage 24 regression: a pushed job whose signed raster url has expired (server 403)
        // still materialises — CachingBlobDownloader re-fetches a fresh url via getCanvas.
        val store = FakeStore()
        val cacheDir = File(System.getProperty("java.io.tmpdir"), "push-inbox-test-${System.nanoTime()}")
        val freshUrl = "https://blob/c1?sig=fresh"
        val page = SyncResponse(jobs = listOf(pushJob("j1", singlePageResult("c1"))), cursor = "c1cur")
        val api = FakeBlobApi(page, freshUrl, "%PDF-1.4\n%stub\n".toByteArray())
        val repo = DeviceRepository(api)
        val inbox = PushInbox(store, CachingBlobDownloader({ repo }, cacheDir))
        val cursor = FakeCursorStore(value = "c0")

        try {
            val n = inbox.poll(repo, cursor)
            assertEquals("the expired-url job materialises after one refresh", 1, n)
            assertEquals("blob fetched twice: stale (403) then fresh", 2, api.blobCalls)
            assertEquals("one canvas-detail refresh for the fresh url", 1, api.canvasCalls)
            assertEquals(1, store.canvases.size)
            val cached = File(store.rasters.single().blobUri)
            assertTrue("the refreshed blob is cached on disk", cached.exists() && cached.length() > 0)
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun unknownCursor_422_reseeds_and_backfills() = runTest {
        // Stage 24: a persisted cursor the server no longer knows (data reset) → 422 → the
        // inbox re-seeds by restarting the loop from null (backfill) instead of failing.
        val store = FakeStore()
        val downloader = FakeDownloader()
        val cursor = FakeCursorStore(value = "stale-cursor") // persisted but unknown to the server
        val page = SyncResponse(jobs = listOf(pushJob("j1", singlePageResult("c1"))), cursor = "fresh-cursor")
        val api = ReseedApi(page)
        val inbox = PushInbox(store, downloader)

        val n = inbox.poll(DeviceRepository(api), cursor)

        assertEquals("re-seed then backfill materialises the pushed job", 1, n)
        assertEquals("tried the stale cursor, then re-seeded from null", listOf<String?>("stale-cursor", null), api.cursorsSent)
        assertEquals("cursor persisted from the backfilled page", "fresh-cursor", cursor.value)
        assertEquals(1, store.canvases.size)
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

    // --- Stage 29 -----------------------------------------------------------------------

    @Test
    fun upgrade_with_old_schema_backfills_once_then_pages_normally() = runTest {
        // Finding (1): an already-installed device has a persisted cursor and so never runs the
        // first-run backfill; a build that never wrote a schema version reads 0, so the first
        // poll after upgrade backfills once from sync(null) and stores the current version.
        val store = FakeStore()
        val cursor = FakeCursorStore(value = "c-old", schema = 0) // upgraded from v0.0.17
        val api = FakeApi(
            ArrayDeque(
                listOf(
                    SyncResponse(jobs = listOf(pushJob("j1", singlePageResult("c1"))), cursor = "c-new"),
                    SyncResponse(jobs = emptyList(), cursor = "c-new2"),
                ),
            ),
        )
        val repo = DeviceRepository(api)
        val inbox = PushInbox(store, FakeDownloader())

        val first = inbox.poll(repo, cursor)
        assertEquals("the upgrade backfills the pre-upgrade push", 1, first)
        assertEquals("upgrade pages from sync(null), not the stored cursor", null, api.cursorsSent.first())
        assertEquals("current schema version stored after the backfill", INBOX_SCHEMA_VERSION, cursor.schema)
        assertEquals("c-new", cursor.value)

        val second = inbox.poll(repo, cursor)
        assertEquals("a second poll does not backfill again", 0, second)
        assertEquals("second poll pages from the persisted cursor", "c-new", api.cursorsSent[1])
    }

    @Test
    fun throwing_materialise_records_the_error_and_keeps_the_cursor() = runTest {
        // Finding (2): a materialise failure must NOT be swallowed — the cursor stays put and
        // the thrown InboxMaterialiseException names the offending job so the caller can log it.
        val store = FakeStore().apply { insertFails = true } // throws before persisting
        val failures = InMemoryInboxFailureStore()
        val cursor = FakeCursorStore(value = "c0")
        val api = FakeApi(ArrayDeque(listOf(SyncResponse(jobs = listOf(pushJob("j1", singlePageResult("c1"))), cursor = "c1"))))
        val inbox = PushInbox(store, FakeDownloader(), failures = failures)

        var caught: InboxMaterialiseException? = null
        try {
            inbox.poll(DeviceRepository(api), cursor)
        } catch (e: InboxMaterialiseException) {
            caught = e
        }

        assertEquals("the failure surfaces the offending job id", "j1", caught?.jobId)
        assertEquals("cursor stays put so the page is retried", "c0", cursor.value)
        assertTrue("cursor never advanced", cursor.sets.isEmpty())
        assertFalse("one failure does not skip the job yet", failures.isSkipped("j1"))
    }

    @Test
    fun third_consecutive_failure_skips_job_and_advances_cursor() = runTest {
        // Poison-page guard: the same job failing on 3 consecutive polls is skipped and the
        // cursor advances past it so later pushes are not blocked forever.
        val store = FakeStore().apply { insertFails = true } // persistent poison
        val failures = InMemoryInboxFailureStore()
        val cursor = FakeCursorStore(value = "c0")
        val page = SyncResponse(jobs = listOf(pushJob("poison", singlePageResult("c1"))), cursor = "c1")
        val repo = DeviceRepository(RepeatingApi(page))
        val inbox = PushInbox(store, FakeDownloader(), failures = failures)

        repeat(2) {
            try {
                inbox.poll(repo, cursor)
                fail("expected a materialise failure on poll ${it + 1}")
            } catch (_: InboxMaterialiseException) {
            }
        }
        assertEquals("cursor unchanged after two failures", "c0", cursor.value)
        assertFalse(failures.isSkipped("poison"))

        val n = inbox.poll(repo, cursor)
        assertEquals("nothing materialised on the skip pass", 0, n)
        assertTrue("the job is skipped after 3 consecutive failures", failures.isSkipped("poison"))
        assertEquals("cursor advances past the poison page", "c1", cursor.value)
    }

    @Test
    fun resync_retries_a_previously_skipped_job() = runTest {
        val store = FakeStore().apply { insertFails = true }
        val failures = InMemoryInboxFailureStore()
        val cursor = FakeCursorStore(value = "c0")
        val page = SyncResponse(jobs = listOf(pushJob("poison", singlePageResult("c1"))), cursor = "c1")
        val api = RepeatingApi(page)
        val repo = DeviceRepository(api)
        val inbox = PushInbox(store, FakeDownloader(), failures = failures)

        // Drive the job to the skip (two throws, then the skip pass).
        repeat(2) { try { inbox.poll(repo, cursor) } catch (_: InboxMaterialiseException) {} }
        inbox.poll(repo, cursor)
        assertTrue(failures.isSkipped("poison"))
        assertTrue("nothing landed while poisoned", store.canvases.isEmpty())

        // The transient clears; Resync forgets the skip + cursor and retries from null.
        store.insertFails = false
        val n = inbox.resync(repo, cursor)

        assertEquals("resync materialises the retried job", 1, n)
        assertFalse("the job is no longer skipped after a clean resync", failures.isSkipped("poison"))
        assertEquals(1, store.canvases.size)
        assertEquals("resync backfilled from null", null, api.cursorsSent.last())
    }

    @Test
    fun folderNameFor_strips_the_page_marker() {
        assertEquals("Brief", PushInbox.folderNameFor("Brief — p1"))
        assertEquals("Q3 Report", PushInbox.folderNameFor("Q3 Report — p12"))
        assertEquals("Notes", PushInbox.folderNameFor("Notes")) // single page, unchanged
    }
}
