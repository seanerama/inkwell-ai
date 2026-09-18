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
    private val spaceId = "space-1"
    private val jobId = "job-push-1"
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
}
