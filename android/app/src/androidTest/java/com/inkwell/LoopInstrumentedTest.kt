package com.inkwell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkwell.contracts.Highlight
import com.inkwell.data.InkDatabase
import com.inkwell.data.LayerEntity
import com.inkwell.data.LayerRepository
import com.inkwell.net.ApiClient
import com.inkwell.net.DeviceRepository
import com.inkwell.net.JobRequestBuilder
import com.inkwell.net.JobResultHandler
import com.inkwell.net.LoopController
import com.inkwell.net.TokenStore
import com.inkwell.render.AnnotationRenderer
import com.inkwell.render.CanvasTransform
import com.inkwell.render.CoordinateMapping
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * End-to-end loop over MockWebServer (emulator lane in release.yml), exercising every
 * seam of Stage 6: Send → `queued` → polled `/sync` → `done` → agent layer created →
 * highlight rendered at the fixture's bbox → panel shows the `summary`.
 *
 * A three-box highlight response (the middle box, points centred on 0.5,0.5) plays the
 * role of the Stage 5 recorded response; the assertions prove the highlight lands on the
 * middle box and exactly one agent layer is created without mutating the seeded ink layer.
 */
@RunWith(AndroidJUnit4::class)
class LoopInstrumentedTest {

    private lateinit var server: MockWebServer
    private lateinit var db: InkDatabase
    private lateinit var layerRepository: LayerRepository
    private lateinit var deviceRepository: DeviceRepository

    private val jobId = "job-3box"
    private val summary = "Highlighted the middle box."

    // The middle box: a highlight centred on (0.5, 0.5).
    private val midPoints = listOf(
        listOf(0.40, 0.45), listOf(0.60, 0.45), listOf(0.60, 0.55), listOf(0.40, 0.55),
    )

    private class InMemoryTokenStore(private val base: String) : TokenStore {
        override fun getToken() = "test-token"
        override fun setToken(token: String?) {}
        override fun getBaseUrl() = base
        override fun setBaseUrl(url: String?) {}
    }

    private fun syncEmpty(cursor: String) =
        MockResponse().setResponseCode(200).setBody("""{"jobs":[],"cursor":"$cursor"}""")

    private fun jobJson(status: String, withResult: Boolean): String {
        val result = if (withResult) {
            val pts = midPoints.joinToString(",") { "[${it[0]},${it[1]}]" }
            ""","result":{"summary":"$summary","annotations":[{"id":"h1","type":"highlight","points":[$pts]}],"cards":[{"kind":"answer","title":"The middle box","body":"It is the key step."}],"brain_writes":[],"contract_version":"agent-output/v1"}"""
        } else {
            ""
        }
        return """{"id":"$jobId","space_id":"space-1","canvas_id":"canvas-1","direction":"to_agent","type":"canvas.annotate","status":"$status","request":{}$result,"created_at":"2026-09-15T00:00:00Z","updated_at":"2026-09-15T00:00:01Z"}"""
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, InkDatabase::class.java).build()
        layerRepository = LayerRepository(db.layerDao())
        deviceRepository = DeviceRepository(
            ApiClient.create(server.url("/").toString(), InMemoryTokenStore(server.url("/").toString())),
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    @Test
    fun send_poll_done_renders_highlight_on_middle_box_and_panel_shows_summary() = runBlocking {
        // Seed an existing user/ink layer to prove the done path never mutates it.
        val ink = LayerEntity(
            id = "ink", canvasId = "canvas-1", z = 0, owner = "user", type = "ink",
            visible = true, opacity = 1.0f, jobId = null, createdAt = 0L,
        )
        db.layerDao().upsert(ink)

        // Call order: sync(null) → createJob → sync(cursor). Enqueue in that order.
        server.enqueue(syncEmpty("c0"))                                  // start cursor
        server.enqueue(MockResponse().setResponseCode(202).setBody(jobJson("queued", withResult = false)))
        server.enqueue(                                                  // first poll → done
            MockResponse().setResponseCode(200)
                .setBody("""{"jobs":[${jobJson("done", withResult = true)}],"cursor":"c1"}"""),
        )

        val request = JobRequestBuilder.build(
            type = "canvas.annotate",
            spaceId = "space-1",
            canvasId = "canvas-1",
            pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
            export = CoordinateMapping.export(),
            instruction = CoordinateMappingPreset,
        )

        val controller = LoopController(deviceRepository, JobResultHandler(layerRepository))
        val outcome = controller.run(request, canvasId = "canvas-1")

        // Panel shows the summary; the job is done (not an error).
        assertTrue(!outcome.isError)
        assertEquals(summary, outcome.summary)
        assertEquals(listOf("The middle box"), outcome.cardTitles)

        // Exactly one NEW agent layer; the seeded ink layer is untouched.
        val layers = db.layerDao().forCanvas("canvas-1")
        assertEquals(2, layers.size)
        assertEquals(ink, layers.single { it.id == "ink" })
        val agent = layers.single { it.owner == "agent" }
        assertEquals("annotation", agent.type)
        assertEquals(jobId, agent.jobId)

        // The returned annotation is the middle-box highlight.
        val highlights = outcome.annotations.filterIsInstance<Highlight>()
        assertEquals(1, highlights.size)

        // Render the highlight through AnnotationRenderer at identity (1 px = 1 CU) and
        // assert it lands on the middle box: painted at the centre, white at a corner.
        val widthCu = CoordinateMapping.DEFAULT_WIDTH_CU
        val heightCu = CoordinateMapping.DEFAULT_HEIGHT_CU
        val bmp = Bitmap.createBitmap(widthCu, heightCu, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        AnnotationRenderer(AnnotationRenderer.DEFAULT_ACCENT, widthCu, heightCu)
            .draw(canvas, CanvasTransform(scale = 1f, tx = 0f, ty = 0f), highlights)

        val centerX = (0.5 * widthCu).roundToInt()
        val centerY = (0.5 * heightCu).roundToInt()
        assertTrue(
            "middle-box centre should be painted",
            bmp.getPixel(centerX, centerY) != Color.WHITE,
        )
        assertTrue(
            "top-left corner should stay white (highlight is on the middle box only)",
            bmp.getPixel(10, 10) == Color.WHITE,
        )
    }

    private companion object {
        const val CoordinateMappingPreset = "Highlight the most important box"
    }
}
