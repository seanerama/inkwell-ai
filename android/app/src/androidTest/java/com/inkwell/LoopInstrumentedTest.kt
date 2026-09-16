package com.inkwell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkwell.contracts.CardKind
import com.inkwell.contracts.Highlight
import com.inkwell.contracts.Text
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * End-to-end loop over MockWebServer (emulator lane in release.yml), exercising every
 * seam of the loop: Send → `queued` → polled `/sync` → `done` → agent layer created →
 * annotation rendered at the fixture position → panel content.
 *
 * Stage 6: a three-box highlight response (the middle box, points centred on 0.5,0.5)
 * plays the role of the Stage 5 recorded response; the assertions prove the highlight
 * lands on the middle box and exactly one agent layer is created without mutating the
 * seeded ink layer.
 *
 * Stage 7: a recorded-shape `canvas.ask` response for the owner's note "what is 1+9=?"
 * — a `text` annotation "10" beside the question plus one `answer` card — replayed for
 * a one-tap send (type `canvas.ask`, **no** `instruction` on the wire); the text is
 * drawn at the fixture position and the panel gets the card's `(kind, title, body)`.
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

    // --- Stage 7: canvas.ask fixture ("what is 1+9=?" → "10") ---
    private val askJobId = "job-ask-1plus9"
    private val askSummary = "Answered the question on the note: 1 + 9 = 10."
    private val askAt = listOf(0.42, 0.18)
    private val askSize = 0.02
    private val askCardTitle = "1 + 9 = 10"
    private val askCardBody = "**10**. Nine plus one is ten."

    private fun askJobJson(status: String, withResult: Boolean): String {
        val result = if (withResult) {
            ""","result":{"summary":"$askSummary","annotations":[{"id":"t1","type":"text","at":[${askAt[0]},${askAt[1]}],"text":"10","size":$askSize}],"cards":[{"kind":"answer","title":"$askCardTitle","body":"$askCardBody","anchors":[{"annotation_id":"t1"}],"actions":[]}],"brain_writes":[],"contract_version":"agent-output/v1"}"""
        } else {
            ""
        }
        return """{"id":"$askJobId","space_id":"space-1","canvas_id":"canvas-1","direction":"to_agent","type":"canvas.ask","status":"$status","request":{}$result,"created_at":"2026-09-15T00:00:00Z","updated_at":"2026-09-15T00:00:01Z"}"""
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

    @Test
    fun one_tap_ask_send_poll_done_draws_text_at_fixture_position_and_panel_shows_card_body() = runBlocking {
        // Call order: sync(null) → createJob → sync(cursor). Enqueue in that order.
        server.enqueue(syncEmpty("c0"))
        server.enqueue(MockResponse().setResponseCode(202).setBody(askJobJson("queued", withResult = false)))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"jobs":[${askJobJson("done", withResult = true)}],"cursor":"c1"}"""),
        )

        // One tap: canvas.ask with NO instruction (never the Stage-6 preset).
        val request = JobRequestBuilder.build(
            type = "canvas.ask",
            spaceId = "space-1",
            canvasId = "canvas-1",
            pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
            export = CoordinateMapping.export(),
            instruction = null,
        )
        val controller = LoopController(deviceRepository, JobResultHandler(layerRepository))
        val outcome = controller.run(request, canvasId = "canvas-1")

        // Wire: the POST /jobs body carries type canvas.ask and no `instruction` key.
        server.takeRequest() // GET /v1/sync (start cursor)
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertTrue(post.path!!.endsWith("/v1/jobs"))
        val body = Json.parseToJsonElement(post.body.readUtf8()).jsonObject
        assertEquals("canvas.ask", body["type"]!!.jsonPrimitive.content)
        assertFalse("instruction must be absent on a one-tap send", body.containsKey("instruction"))

        // Job done; the panel gets the summary and the answer card's (kind, title, body).
        assertFalse(outcome.isError)
        assertEquals(askSummary, outcome.summary)
        val card = outcome.cards.single()
        assertEquals(CardKind.ANSWER, card.kind)
        assertEquals(askCardTitle, card.title)
        assertEquals(askCardBody, card.body)
        // Exactly one agent layer for the ask job.
        val agent = db.layerDao().forCanvas("canvas-1").single { it.owner == "agent" }
        assertEquals(askJobId, agent.jobId)

        // The `text` annotation "10" is drawn at the fixture position: paint at identity
        // (1 px = 1 CU) and find the painted bbox near `at × canvas size`.
        val text = outcome.annotations.filterIsInstance<Text>().single()
        assertEquals("10", text.text)
        val widthCu = CoordinateMapping.DEFAULT_WIDTH_CU
        val heightCu = CoordinateMapping.DEFAULT_HEIGHT_CU
        val atX = askAt[0] * widthCu // 1041.6
        val atY = askAt[1] * heightCu // 631.44
        val sizeCu = askSize * heightCu // 70.16
        val bmp = Bitmap.createBitmap(widthCu, heightCu, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        AnnotationRenderer(AnnotationRenderer.DEFAULT_ACCENT, widthCu, heightCu)
            .draw(canvas, CanvasTransform(scale = 1f, tx = 0f, ty = 0f), outcome.annotations)

        // Scan a window around the expected text box for painted pixels.
        val x0 = (atX - 20).toInt().coerceAtLeast(0)
        val y0 = (atY - 20).toInt().coerceAtLeast(0)
        val x1 = (atX + 4 * sizeCu).toInt().coerceAtMost(bmp.width)
        val y1 = (atY + 3 * sizeCu).toInt().coerceAtMost(bmp.height)
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                if (bmp.getPixel(x, y) != Color.WHITE) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        assertTrue("the answer text should be painted near $atX,$atY", maxX >= minX && maxY >= minY)
        // Left/top edges sit at `at` (glyph bearings allow a couple of CU of slack); the
        // glyphs are no taller than the em box (size × height_cu) and at most two ems wide.
        assertTrue("text left $minX vs at.x $atX", minX >= atX - 3 && minX <= atX + sizeCu)
        assertTrue("text top $minY vs at.y $atY", minY >= atY - 3 && minY <= atY + sizeCu)
        assertTrue("text bottom $maxY within one em of $atY", maxY <= atY + 1.5 * sizeCu)
        assertTrue("text right $maxX within two ems of $atX", maxX <= atX + 2 * sizeCu)
        // Nothing painted away from the question (the canvas corner stays white).
        assertTrue(bmp.getPixel(10, 10) == Color.WHITE)
        assertTrue(bmp.getPixel(widthCu / 2, heightCu - 10) == Color.WHITE)
        bmp.recycle()
    }

    private companion object {
        const val CoordinateMappingPreset = "Highlight the most important box"
    }
}
