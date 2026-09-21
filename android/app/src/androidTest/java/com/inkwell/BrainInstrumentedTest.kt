package com.inkwell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkwell.net.ApiClient
import com.inkwell.net.DeviceRepository
import com.inkwell.net.TokenStore
import com.inkwell.ui.BrainScreen
import com.inkwell.ui.BrainTags
import com.inkwell.ui.BrainViewModel
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * Stage 27 instrumented tests (emulator lane): the per-space Brain view over MockWebServer's
 * frozen `/brain/{slug}` routes. The brain is server-truth — fetched live, never mirrored
 * (ADR-0013 §6). Verifies the list renders, search issues `GET …?q=…`, long-press → Delete
 * calls `DELETE` and removes the row, and "+" → add POSTs and inserts.
 *
 * Compose is pinned to 1.6.8 (stage 16/22/28 lesson): no `waitUntil(conditionDescription,
 * timeoutMillis)` overload — use `waitUntil(timeoutMillis){ … fetchSemanticsNodes() }`.
 */
@RunWith(AndroidJUnit4::class)
class BrainInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var deviceRepository: DeviceRepository

    /** Records the path/method/body of every request the brain dispatcher served. */
    private val recorded = Collections.synchronizedList(mutableListOf<RecordedRequest>())

    private class InMemoryTokenStore(private val base: String) : TokenStore {
        override fun getToken() = "test-token"
        override fun setToken(token: String?) {}
        override fun getBaseUrl() = base
        override fun setBaseUrl(url: String?) {}
    }

    /** A mutable in-memory brain of one space, serialised to the frozen `BrainEntry` shape. */
    private class FakeBrain {
        // id → (kind, text). Newest first: index 0 is newest.
        val rows = mutableListOf<Triple<String, String, String>>() // id, kind, text
        var seq = 100
        fun toJson(filter: String?): String {
            val matched = if (filter.isNullOrBlank()) rows else rows.filter { it.third.contains(filter, true) }
            return matched.joinToString(prefix = "[", postfix = "]") { (id, kind, text) ->
                """{"id":"$id","space_slug":"work","kind":"$kind","text":${text.quote()},""" +
                    """"tags":[],"source_canvas_id":null,"source_region":null,"job_id":null,""" +
                    """"created_at":"2026-09-21T00:00:00Z"}"""
            }
        }
        fun entryJson(id: String, kind: String, text: String): String =
            """{"id":"$id","space_slug":"work","kind":"$kind","text":${text.quote()},""" +
                """"tags":[],"source_canvas_id":null,"source_region":null,"job_id":null,""" +
                """"created_at":"2026-09-21T00:00:00Z"}"""
    }

    private val brain = FakeBrain()

    @Before
    fun setUp() {
        brain.rows.clear()
        brain.rows.add(Triple("b2", "fact", "Q3 offsite: Austin, 14 Oct"))
        brain.rows.add(Triple("b1", "task", "lunch menu"))

        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded.add(request)
                val path = request.path ?: ""
                val method = request.method ?: ""
                return when {
                    method == "GET" && path.startsWith("/v1/brain/work") -> {
                        val q = request.requestUrl?.queryParameter("q")
                        MockResponse().setResponseCode(200).setBody(brain.toJson(q))
                    }
                    method == "POST" && path.startsWith("/v1/brain/work") -> {
                        val body = request.body.readUtf8()
                        val text = Regex(""""text"\s*:\s*"(.*?)"""").find(body)?.groupValues?.get(1) ?: "added"
                        val kind = Regex(""""kind"\s*:\s*"(.*?)"""").find(body)?.groupValues?.get(1) ?: "fact"
                        val id = "new-${brain.seq++}"
                        brain.rows.add(0, Triple(id, kind, text))
                        MockResponse().setResponseCode(201).setBody(brain.entryJson(id, kind, text))
                    }
                    method == "DELETE" && path.startsWith("/v1/brain/work/") -> {
                        val id = path.removePrefix("/v1/brain/work/").substringBefore('?')
                        brain.rows.removeAll { it.first == id }
                        MockResponse().setResponseCode(204)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        deviceRepository = DeviceRepository(
            ApiClient.create(server.url("/").toString(), InMemoryTokenStore(server.url("/").toString())),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun brainViewModel() = BrainViewModel(
        deviceRepositoryProvider = { deviceRepository },
        canvasExistsLocally = { false },
        searchDebounceMs = 0L, // delay(0) → immediate; no debounce race in the test
    )

    private fun setContent(vm: BrainViewModel) {
        composeRule.setContent {
            BrainScreen(
                viewModel = vm,
                slug = "work",
                spaceName = "Work",
                onBack = {},
                onOpenCanvas = {},
            )
        }
    }

    @Test
    fun list_renders_the_space_entries() {
        setContent(brainViewModel())
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(BrainTags.entryText("b2")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(BrainTags.entryText("b2")).assertIsDisplayed()
        composeRule.onNodeWithTag(BrainTags.entryText("b1")).assertIsDisplayed()
    }

    @Test
    fun typing_in_search_issues_a_get_with_the_q_parameter() {
        setContent(brainViewModel())
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(BrainTags.entry("b2")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(BrainTags.SEARCH).performTextInput("austin")
        // A GET carrying ?q=austin is issued and the list narrows to the matching row.
        composeRule.waitUntil(5_000) {
            recorded.any { it.method == "GET" && (it.path ?: "").contains("q=austin") }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(BrainTags.entry("b1")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag(BrainTags.entryText("b2")).assertIsDisplayed()
    }

    @Test
    fun long_press_deletes_the_row_and_calls_delete() {
        setContent(brainViewModel())
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(BrainTags.entry("b1")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(BrainTags.entry("b1")).performTouchInput { longClick() }
        composeRule.waitUntil(5_000) {
            recorded.any { it.method == "DELETE" && (it.path ?: "").startsWith("/v1/brain/work/b1") }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(BrainTags.entry("b1")).fetchSemanticsNodes().isEmpty()
        }
        // The undo affordance is offered.
        composeRule.onNodeWithTag(BrainTags.UNDO).assertIsDisplayed()
    }

    @Test
    fun add_dialog_posts_and_inserts_the_entry() {
        setContent(brainViewModel())
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(BrainTags.ADD).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(BrainTags.ADD).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(BrainTags.ADD_TEXT).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(BrainTags.ADD_TEXT).performTextInput("Book the venue")
        composeRule.onNodeWithTag(BrainTags.ADD_CONFIRM).performClick()

        // A POST to the space's brain route was issued (the dispatcher parsed the text out of it).
        composeRule.waitUntil(5_000) {
            recorded.any { it.method == "POST" && (it.path ?: "").startsWith("/v1/brain/work") }
        }
        // The inserted row appears (its id is the server-assigned new id).
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(BrainTags.entryText("new-100")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(BrainTags.entryText("new-100")).assertIsDisplayed()
    }
}

/** Minimal JSON string escaper for the fixture bodies. */
private fun String.quote(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
