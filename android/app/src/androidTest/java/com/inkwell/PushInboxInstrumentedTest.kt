package com.inkwell

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inkwell.data.InkDatabase
import com.inkwell.data.LibraryRepository
import com.inkwell.data.RoomPushedCanvasStore
import com.inkwell.net.ApiClient
import com.inkwell.net.CachingBlobDownloader
import com.inkwell.net.DeviceRepository
import com.inkwell.net.PrefsSyncCursorStore
import com.inkwell.net.PushInbox
import com.inkwell.net.TokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented push-inbox test over MockWebServer (emulator lane in release.yml). A `/sync`
 * page carries one `to_user` `agent.push_document` job whose raster `url` serves a 1-page
 * PDF fixture; [PushInbox] materialises it into Room (an agent-origin canvas + a raster
 * layer + a raster row) and caches the blob on disk. It then checks the badge count is 1 and
 * that marking the canvas seen drops it to 0. Written to run on the emulator; the Stage
 * Manager executes the lane.
 */
@RunWith(AndroidJUnit4::class)
class PushInboxInstrumentedTest {

    private lateinit var server: MockWebServer
    private lateinit var db: InkDatabase
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var cacheDir: File

    private val canvasId = "srv-pushed-1"
    private val canvasId2 = "srv-pushed-2"
    private val spaceId = "space-1"
    private val jobId = "job-push-1"
    private val jobId2 = "job-push-2"
    private val blobPath = "/v1/blobs/push/abc.pdf"

    private class InMemoryTokenStore(private val base: String) : TokenStore {
        override fun getToken() = "test-token"
        override fun setToken(token: String?) {}
        override fun getBaseUrl() = base
        override fun setBaseUrl(url: String?) {}
    }

    private fun pdfFixtureBytes(): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("push/one-page.pdf").use { it.readBytes() }

    /** The `to_user` result: one agent canvas, one raster layer, one raster with a `url`. */
    private fun syncPageBody(blobUrl: String): String {
        val result = """
            {"canvas":{"id":"$canvasId","space_id":"$spaceId","title":"Brief","width_cu":2480,"height_cu":3508,"origin":"agent"},
             "canvases":[{"id":"$canvasId","space_id":"$spaceId","title":"Brief","width_cu":2480,"height_cu":3508,"origin":"agent"}],
             "layers":[{"id":"layer-r1","canvas_id":"$canvasId","z":-1,"owner":"agent","type":"raster","job_id":"$jobId"}],
             "rasters":[{"id":"raster-1","layer_id":"layer-r1","url":"$blobUrl","mime":"application/pdf","page":0,"x_cu":0,"y_cu":0,"w_cu":2480,"h_cu":3508}],
             "cards":[]}
        """.trimIndent().replace("\n", "")
        return """{"jobs":[{"id":"$jobId","space_id":"$spaceId","canvas_id":"$canvasId","direction":"to_user","type":"agent.push_document","status":"done","request":{},"result":$result,"created_at":"2026-09-18T00:00:00Z","updated_at":"2026-09-18T00:00:01Z"}],"cursor":"c1"}"""
    }

    /** One `to_user` job for [cvId]/[rId], blob served from [blobUrl] (Stage 24 backfill body). */
    private fun pushedJob(jId: String, cvId: String, rId: String, layerId: String, blobUrl: String): String {
        val result = """
            {"canvas":{"id":"$cvId","space_id":"$spaceId","title":"Brief","width_cu":2480,"height_cu":3508,"origin":"agent"},
             "canvases":[{"id":"$cvId","space_id":"$spaceId","title":"Brief","width_cu":2480,"height_cu":3508,"origin":"agent"}],
             "layers":[{"id":"$layerId","canvas_id":"$cvId","z":-1,"owner":"agent","type":"raster","job_id":"$jId"}],
             "rasters":[{"id":"$rId","layer_id":"$layerId","url":"$blobUrl","mime":"application/pdf","page":0,"x_cu":0,"y_cu":0,"w_cu":2480,"h_cu":3508}],
             "cards":[]}
        """.trimIndent().replace("\n", "")
        return """{"id":"$jId","space_id":"$spaceId","canvas_id":"$cvId","direction":"to_user","type":"agent.push_document","status":"done","request":{},"result":$result,"created_at":"2026-09-18T00:00:00Z","updated_at":"2026-09-18T00:00:01Z"}"""
    }

    /**
     * Stage 29: the `to_user` result exactly as the server ships it — the raster `url` is a
     * RELATIVE signed link (`/v1/blobs/...`, no scheme/host), which is the 2026-09-21 payload
     * shape. Used to prove the download path resolves it and fetches the blob end to end.
     */
    private fun syncPageRelBody(): String {
        val result = """
            {"canvas":{"id":"srv-pushed-rel","space_id":"$spaceId","title":"Brief","width_cu":2480,"height_cu":3508,"origin":"agent"},
             "canvases":[{"id":"srv-pushed-rel","space_id":"$spaceId","title":"Brief","width_cu":2480,"height_cu":3508,"origin":"agent"}],
             "layers":[{"id":"layer-rel","canvas_id":"srv-pushed-rel","z":-1,"owner":"agent","type":"raster","job_id":"job-push-rel"}],
             "rasters":[{"id":"raster-rel","layer_id":"layer-rel","url":"/v1/blobs/push/rel.pdf?sig=deadbeef&exp=1790000000","mime":"application/pdf","page":0,"x_cu":0,"y_cu":0,"w_cu":2480,"h_cu":3508}],
             "cards":[]}
        """.trimIndent().replace("\n", "")
        return """{"jobs":[{"id":"job-push-rel","space_id":"$spaceId","canvas_id":"srv-pushed-rel","direction":"to_user","type":"agent.push_document","status":"done","request":{},"result":$result,"created_at":"2026-09-21T13:01:00Z","updated_at":"2026-09-21T13:01:01Z"}],"cursor":"crel1"}"""
    }

    /** Fresh-install backfill: `sync` WITHOUT a cursor returns two pre-existing pushed jobs. */
    private fun backfillPageBody(base: String): String {
        val j1 = pushedJob(jobId, canvasId, "raster-1", "layer-r1", base + "v1/blobs/push/abc.pdf")
        val j2 = pushedJob(jobId2, canvasId2, "raster-2", "layer-r2", base + "v1/blobs/push/def.pdf")
        return """{"jobs":[$j1,$j2],"cursor":"c1backfill"}"""
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        val pdf = pdfFixtureBytes()
        val base = { server.url("/").toString() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    // First /sync seeds (empty); the app then persists "c0". Our test seeds the
                    // cursor manually, so the first real poll asks with cursor=c0 → the page.
                    path.startsWith("/v1/sync") && path.contains("cursor=c0") ->
                        MockResponse().setResponseCode(200).setBody(syncPageBody(base() + "v1/blobs/push/abc.pdf"))
                    // Stage 29: a page whose raster carries the server's RELATIVE signed link.
                    path.startsWith("/v1/sync") && path.contains("cursor=crel") ->
                        MockResponse().setResponseCode(200).setBody(syncPageRelBody())
                    // Fresh install: the first poll has no persisted cursor, so `sync` is called
                    // WITHOUT a cursor query → backfill the two pre-existing pushes (Stage 24).
                    path.startsWith("/v1/sync") && !path.contains("cursor=") ->
                        MockResponse().setResponseCode(200).setBody(backfillPageBody(base()))
                    path.startsWith("/v1/sync") ->
                        MockResponse().setResponseCode(200).setBody("""{"jobs":[],"cursor":"c1"}""")
                    path.startsWith("/v1/blobs/") -> {
                        val buf = Buffer().write(pdf)
                        MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/pdf").setBody(buf)
                    }
                    else -> MockResponse().setResponseCode(404).setBody("""{"error":{"code":"not_found","message":"no"}}""")
                }
            }
        }
        server.start()

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, InkDatabase::class.java).build()
        val url = server.url("/").toString()
        deviceRepository = DeviceRepository(ApiClient.create(url, InMemoryTokenStore(url)))
        cacheDir = File(context.cacheDir, "test-push-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
        cacheDir.deleteRecursively()
    }

    @Test
    fun sync_page_materialises_pushed_canvas_with_raster_and_badge() = runBlocking {
        val store = RoomPushedCanvasStore(db.canvasDao(), db.layerDao(), db.folderDao(), db.rasterDao())
        val downloader = CachingBlobDownloader({ deviceRepository }, cacheDir)
        val inbox = PushInbox(store, downloader)
        val library = LibraryRepository(db.folderDao(), db.canvasDao(), db.layerDao())

        // Seed the cursor at "c0" so the first real poll pulls the page (not a seeding call).
        val cursor = object : com.inkwell.net.SyncCursorStore {
            var v: String? = "c0"
            override fun get() = v
            override fun set(cursor: String) { v = cursor }
        }

        val materialised = inbox.poll(deviceRepository, cursor)
        assertEquals(1, materialised)
        assertEquals("cursor advanced after materialise", "c1", cursor.v)

        // Canvas + raster landed in Room.
        val canvas = db.canvasDao().byId(canvasId)
        assertNotNull(canvas)
        assertEquals("agent", canvas!!.origin)
        assertNull("pushed canvas is unread", canvas.seenAt)
        val rasterLayer = db.layerDao().forCanvas(canvasId).single { it.type == "raster" }
        val raster = db.rasterDao().forLayer(rasterLayer.id).single()

        // The blob was cached on disk and blob_uri points at the local file.
        val cached = File(raster.blobUri)
        assertTrue("cached blob exists", cached.exists() && cached.length() > 0)

        // Badge: 1 unread pushed canvas in the space; opening it drops it to 0.
        assertEquals(1, library.unseenPushedCount(spaceId))
        library.markSeen(canvasId)
        assertEquals(0, library.unseenPushedCount(spaceId))
    }

    @Test
    fun freshInstall_backfills_two_pushed_canvases_with_badges() = runBlocking {
        // Stage 24: a fresh install (empty cursor store) must BACKFILL — `sync` is called
        // without a cursor and both pre-existing pushes land in Room with the badge count.
        val store = RoomPushedCanvasStore(db.canvasDao(), db.layerDao(), db.folderDao(), db.rasterDao())
        val downloader = CachingBlobDownloader({ deviceRepository }, cacheDir)
        val inbox = PushInbox(store, downloader)
        val library = LibraryRepository(db.folderDao(), db.canvasDao(), db.layerDao())

        // Empty cursor store (get() == null): the first poll backfills from sync(null).
        val cursor = object : com.inkwell.net.SyncCursorStore {
            var v: String? = null
            override fun get() = v
            override fun set(cursor: String) { v = cursor }
        }

        val materialised = inbox.poll(deviceRepository, cursor)
        assertEquals("both pre-existing pushes backfilled", 2, materialised)
        assertEquals("cursor persisted after backfill", "c1backfill", cursor.v)

        // Both canvases landed in Room, agent-origin and unread.
        val c1 = db.canvasDao().byId(canvasId)
        val c2 = db.canvasDao().byId(canvasId2)
        assertNotNull(c1)
        assertNotNull(c2)
        assertEquals("agent", c1!!.origin)
        assertEquals("agent", c2!!.origin)
        assertNull(c1.seenAt)
        assertNull(c2.seenAt)

        // Both blobs cached on disk.
        for (cv in listOf(canvasId, canvasId2)) {
            val rasterLayer = db.layerDao().forCanvas(cv).single { it.type == "raster" }
            val raster = db.rasterDao().forLayer(rasterLayer.id).single()
            val cached = File(raster.blobUri)
            assertTrue("cached blob exists for $cv", cached.exists() && cached.length() > 0)
        }

        // Badge: two unread pushed canvases in the space.
        assertEquals(2, library.unseenPushedCount(spaceId))
    }

    @Test
    fun relative_signed_url_downloads_blob_end_to_end() = runBlocking {
        // Stage 29 regression for the 2026-09-21 silent failure: the server ships a RELATIVE
        // raster `url` (`/v1/blobs/...`). With the paired base URL threaded through, the whole
        // Library wiring (PushInbox -> CachingBlobDownloader -> DeviceRepository -> Retrofit)
        // resolves it and fetches the blob — no canvas ever opened (also covers the
        // "null-provider" lead the spec ruled out).
        val store = RoomPushedCanvasStore(db.canvasDao(), db.layerDao(), db.folderDao(), db.rasterDao())
        val url = server.url("/").toString()
        // The repository carries the base so the relative signed link resolves to absolute.
        val repo = DeviceRepository(ApiClient.create(url, InMemoryTokenStore(url)), url)
        val downloader = CachingBlobDownloader({ repo }, cacheDir)
        val inbox = PushInbox(store, downloader)

        val cursor = object : com.inkwell.net.SyncCursorStore {
            var v: String? = "crel"
            override fun get() = v
            override fun set(cursor: String) { v = cursor }
        }

        val materialised = inbox.poll(repo, cursor)
        assertEquals("the relative-url push materialised", 1, materialised)

        val canvas = db.canvasDao().byId("srv-pushed-rel")
        assertNotNull(canvas)
        val rasterLayer = db.layerDao().forCanvas("srv-pushed-rel").single { it.type == "raster" }
        val raster = db.rasterDao().forLayer(rasterLayer.id).single()
        val cached = File(raster.blobUri)
        assertTrue("the blob GET happened and was cached", cached.exists() && cached.length() > 0)
    }
}
