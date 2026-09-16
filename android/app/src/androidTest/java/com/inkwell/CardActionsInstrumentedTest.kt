package com.inkwell

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkwell.net.ApiClient
import com.inkwell.net.DeviceRepository
import com.inkwell.net.TokenStore
import com.inkwell.render.AnchorHitTest
import com.inkwell.ui.PanelCard
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 10 instrumented (emulator lane): a MockWebServer replay of a done job carrying
 * two cards with actions/anchors, plus the card-state routes. Verifies the wire cards
 * flow through [PanelCard], that confirming a card issues the action route and reports
 * `done`, and that a card's anchor resolves to a CU rect (the pulse the canvas draws).
 */
@RunWith(AndroidJUnit4::class)
class CardActionsInstrumentedTest {

    private lateinit var server: MockWebServer
    private lateinit var deviceRepository: DeviceRepository

    private class InMemoryTokenStore(private val base: String) : TokenStore {
        override fun getToken() = "test-token"
        override fun setToken(token: String?) {}
        override fun getBaseUrl() = base
        override fun setBaseUrl(url: String?) {}
    }

    // Two server cards: an answer with a confirm+reject action and an anchored region,
    // and a task with an unsupported save_to_brain action.
    private val cardsJson = """
        [
          {"id":"c1","kind":"answer","title":"1 + 9 = 10","body":"**10**.",
           "anchors":[{"region":[0.4,0.4,0.2,0.1]}],
           "actions":[{"id":"a-ok","label":"Looks right","kind":"confirm","payload":{}},
                      {"id":"a-no","label":"No","kind":"reject","payload":{}}],
           "state":"open","created_at":"2026-09-15T00:00:00Z"},
          {"id":"c2","kind":"task","title":"Follow up","body":"Do the thing.",
           "anchors":[],
           "actions":[{"id":"a-save","label":"Save","kind":"save_to_brain","payload":{}}],
           "state":"open","created_at":"2026-09-15T00:00:01Z"}
        ]
    """.trimIndent()

    private fun jobJson(status: String, withCards: Boolean): String {
        val cards = if (withCards) ""","cards":$cardsJson""" else ""","cards":[]"""
        return """{"id":"job-1","space_id":"s1","canvas_id":"cv1","direction":"to_agent",""" +
            """"type":"canvas.ask","status":"$status","request":{},"result":null$cards,""" +
            """"created_at":"2026-09-15T00:00:00Z","updated_at":"2026-09-15T00:00:01Z"}"""
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        deviceRepository = DeviceRepository(
            ApiClient.create(server.url("/").toString(), InMemoryTokenStore(server.url("/").toString())),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun done_job_carries_two_cards_and_confirm_marks_done() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(jobJson("done", withCards = true)))
        val job = deviceRepository.getJob("job-1")
        assertEquals(2, job.cards.size)

        val cards = job.cards.map { PanelCard.from(it) }
        assertEquals("c1", cards[0].id)
        assertEquals("open", cards[0].state)
        assertTrue(cards[0].actions[0].supported) // confirm
        assertTrue(!cards[1].actions[0].supported) // save_to_brain → coming later

        // The anchored region resolves to a CU rect (the canvas pulse).
        val regions = cards[0].anchors.map { AnchorHitTest.AnchorRegion(it.annotationId, it.region) }
        val rects = AnchorHitTest.rectsForAnchors(regions, emptyMap())
        assertEquals(1, rects.size)

        // Tap confirm → POST the action route → the card comes back `done`.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"c1","kind":"answer","title":"1 + 9 = 10","body":"**10**.","anchors":[],""" +
                    """"actions":[],"state":"done","created_at":"2026-09-15T00:00:00Z"}""",
            ),
        )
        val updated = deviceRepository.runCardAction("c1", "a-ok")
        assertEquals("done", updated.state)

        val recordedGet = server.takeRequest()
        assertEquals("/v1/jobs/job-1", recordedGet.path)
        val recordedAction = server.takeRequest()
        assertEquals("POST", recordedAction.method)
        assertEquals("/v1/cards/c1/actions/a-ok", recordedAction.path)
    }

    @Test
    fun patch_card_state_is_a_patch_to_the_card_route() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"c1","kind":"answer","title":"t","body":"b","anchors":[],""" +
                    """"actions":[],"state":"dismissed","created_at":"2026-09-15T00:00:00Z"}""",
            ),
        )
        val updated = deviceRepository.patchCard("c1", "dismissed")
        assertEquals("dismissed", updated.state)

        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/v1/cards/c1", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("dismissed"))
    }
}
