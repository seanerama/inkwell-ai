package com.inkwell

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkwell.data.CanvasRepository
import com.inkwell.data.InkDatabase
import com.inkwell.data.LibraryRepository
import com.inkwell.data.SpaceSync
import com.inkwell.net.ApiClient
import com.inkwell.net.DeviceRepository
import com.inkwell.net.TokenStore
import com.inkwell.ui.LibraryScreen
import com.inkwell.ui.LibraryViewModel
import com.inkwell.ui.SpaceEdits
import com.inkwell.ui.SpaceSettingsTags
import com.inkwell.ui.SpaceSettingsViewModel
import com.inkwell.ui.SpaceTabTags
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 15 instrumented tests (emulator lane):
 *  - a prompt edit → the recorded `PATCH /spaces/{id}` body carries ONLY `system_prompt`
 *    (unchanged name/model/colour omitted by `explicitNulls=false`), and after `refresh()`
 *    the Room mirror shows the new prompt;
 *  - the "+" tab creates a space → `POST /spaces` → the new tab is present and selected;
 *  - the settings sheet opens from a tab's long-press menu and Save is disabled until a field
 *    changes.
 */
@RunWith(AndroidJUnit4::class)
class SpaceSettingsInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var db: InkDatabase
    private lateinit var canvasRepository: CanvasRepository
    private lateinit var library: LibraryRepository
    private lateinit var spaceSync: SpaceSync
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var dispatcher: SpacesDispatcher

    private class InMemoryTokenStore(private val base: String) : TokenStore {
        override fun getToken() = "test-token"
        override fun setToken(token: String?) {}
        override fun getBaseUrl() = base
        override fun setBaseUrl(url: String?) {}
    }

    /** A tiny mutable holder for a wire `Space`. */
    private class Rec(
        val id: String,
        var name: String,
        val slug: String,
        var systemPrompt: String,
        var model: String,
        var color: String,
        var position: Int,
    ) {
        fun toJson(): String = JSONObject()
            .put("id", id).put("name", name).put("slug", slug)
            .put("system_prompt", systemPrompt).put("tools", org.json.JSONArray())
            .put("model", model).put("color", color).put("position", position)
            .put("created_at", "2026-09-16T00:00:00Z")
            .toString()
    }

    /** Stateful `GET/POST /spaces` + `PATCH /spaces/{id}`; records the last PATCH body. */
    private class SpacesDispatcher : Dispatcher() {
        val recs = mutableListOf(
            Rec("space-work", "Work", "work", "", "claude-sonnet-5", "#3B6EA5", 0),
            Rec("space-home", "Home", "home", "", "claude-sonnet-5", "#2E8B57", 1),
            Rec("space-learning", "Learning", "learning", "", "claude-sonnet-5", "#E5484D", 2),
            Rec("space-business", "Business", "business", "", "claude-sonnet-5", "#B58900", 3),
        )
        @Volatile var lastPatchBody: String? = null
        @Volatile var lastPatchId: String? = null

        private fun listJson() = "[" + recs.sortedBy { it.position }.joinToString(",") { it.toJson() } + "]"

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                request.method == "GET" && path.endsWith("/v1/spaces") ->
                    MockResponse().setResponseCode(200).setBody(listJson())

                request.method == "POST" && path.endsWith("/v1/spaces") -> {
                    val body = JSONObject(request.body.readUtf8())
                    val name = body.getString("name")
                    val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                    val rec = Rec(
                        id = "space-$slug", name = name, slug = slug, systemPrompt = "",
                        model = body.optString("model", "claude-sonnet-5"),
                        color = body.optString("color", "#000000"),
                        position = (recs.maxOf { it.position }) + 1,
                    )
                    recs.add(rec)
                    MockResponse().setResponseCode(201).setBody(rec.toJson())
                }

                request.method == "PATCH" && path.contains("/v1/spaces/") -> {
                    val id = path.substringAfterLast("/")
                    val raw = request.body.readUtf8()
                    lastPatchBody = raw
                    lastPatchId = id
                    val body = JSONObject(raw)
                    val rec = recs.firstOrNull { it.id == id }
                        ?: return MockResponse().setResponseCode(404)
                            .setBody("""{"error":{"code":"not_found","message":"unknown space"}}""")
                    if (body.has("name")) rec.name = body.getString("name")
                    if (body.has("system_prompt")) rec.systemPrompt = body.getString("system_prompt")
                    if (body.has("model")) rec.model = body.getString("model")
                    if (body.has("color")) rec.color = body.getString("color")
                    if (body.has("position")) rec.position = body.getInt("position")
                    MockResponse().setResponseCode(200).setBody(rec.toJson())
                }

                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        dispatcher = SpacesDispatcher()
        server.dispatcher = dispatcher
        server.start()

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, InkDatabase::class.java).build()
        canvasRepository = CanvasRepository(db.spaceDao(), db.canvasDao(), db.layerDao(), db.strokeDao())
        library = LibraryRepository(
            db.folderDao(), db.canvasDao(), db.layerDao(),
            runInTransaction = { block -> db.withTransaction { block() } },
        )
        deviceRepository = DeviceRepository(
            ApiClient.create(server.url("/").toString(), InMemoryTokenStore(server.url("/").toString())),
        )
        spaceSync = SpaceSync.create(db, deviceRepositoryProvider = { deviceRepository })
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun libraryVm(): LibraryViewModel {
        val prefs = mutableMapOf<String, String?>()
        return LibraryViewModel(
            library = library,
            canvasRepository = canvasRepository,
            spaceSync = spaceSync,
            loadActiveSpaceId = { prefs["active_space_id"] },
            saveActiveSpaceId = { prefs["active_space_id"] = it },
        )
    }

    private fun settingsVm(libraryVm: LibraryViewModel) = SpaceSettingsViewModel(
        deviceRepositoryProvider = { deviceRepository },
        spaceSync = spaceSync,
        upsertSpace = { db.spaceDao().upsert(it) },
        loadSpaces = { db.spaceDao().all() },
        onSpacesChanged = { id -> libraryVm.reloadSpacesSelecting(id) },
    )

    /**
     * Wait for a node with [tag] to exist, rethrowing a timeout with [because] so the failure
     * report names the stalled step. compose-ui-test 1.6.8 has no `waitUntil(conditionDescription,…)`
     * overload, so this wrapper supplies the per-wait message the diagnosis needs.
     */
    private fun ComposeContentTestRule.awaitTag(
        tag: String,
        because: String,
        timeoutMillis: Long = 5_000,
    ) {
        try {
            waitUntil(timeoutMillis) {
                onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(
                "stage-16: timed out after ${timeoutMillis}ms — $because (waiting for tag=$tag)",
                e,
            )
        }
    }

    @Test
    fun prompt_patch_sends_only_the_changed_field_and_the_mirror_updates() = runBlocking {
        spaceSync.refresh()
        val learning = db.spaceDao().all().first { it.slug == "learning" }

        val patch = SpaceEdits.diff(
            learning,
            SpaceEdits.draftOf(learning).copy(systemPrompt = "Always answer in French."),
        )
        val updated = deviceRepository.patchSpace(learning.id, patch)
        assertEquals("Always answer in French.", updated.systemPrompt)

        // The recorded PATCH body carries system_prompt and NOT the unchanged fields.
        val body = dispatcher.lastPatchBody
        assertNotNull(body)
        assertEquals(learning.id, dispatcher.lastPatchId)
        assertTrue(body!!.contains("system_prompt"))
        assertTrue(body.contains("Always answer in French."))
        assertFalse("unchanged name omitted", body.contains("\"name\""))
        assertFalse("unchanged model omitted", body.contains("\"model\""))
        assertFalse("unchanged color omitted", body.contains("\"color\""))

        // After re-mirroring, the Room row shows the new prompt.
        spaceSync.refresh()
        assertEquals("Always answer in French.", db.spaceDao().byId(learning.id)!!.systemPrompt)
    }

    @Test
    fun plus_tab_creates_a_space_that_becomes_the_selected_tab() {
        val libraryVm = libraryVm()
        val settingsVm = settingsVm(libraryVm)
        composeRule.setContent {
            LibraryScreen(viewModel = libraryVm, onOpenCanvas = {}, settingsViewModel = settingsVm)
        }

        // Stage 16: each wait carries a distinct message so a ComposeTimeoutException names
        // WHICH step stalled (compose-ui-test 1.6.8 has no waitUntil(description,…) overload).
        composeRule.awaitTag(
            SpaceTabTags.tab("space-business"),
            because = "initial spaces mirror did not surface the seeded tabs",
        )
        composeRule.onNodeWithTag(SpaceTabTags.ADD).performClick()
        composeRule.awaitTag(
            SpaceSettingsTags.NEW_NAME_FIELD,
            because = "the '+' tab did not open the New space dialog",
        )
        composeRule.onNodeWithTag(SpaceSettingsTags.NEW_NAME_FIELD).performTextInput("Cooking")
        composeRule.onNodeWithTag(SpaceSettingsTags.NEW_CONFIRM).performClick()

        composeRule.awaitTag(
            SpaceTabTags.tab("space-cooking"),
            because = "created space (POST /spaces → upsert → refresh) never surfaced its tab",
        )
        composeRule.runOnIdle {
            assertEquals("space-cooking", libraryVm.activeSpaceId)
        }
    }

    @Test
    fun settings_sheet_opens_from_the_tab_menu_and_save_is_disabled_until_a_field_changes() {
        val libraryVm = libraryVm()
        val settingsVm = settingsVm(libraryVm)
        composeRule.setContent {
            LibraryScreen(viewModel = libraryVm, onOpenCanvas = {}, settingsViewModel = settingsVm)
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(SpaceTabTags.tab("space-work"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Long-press the Work tab → its menu → Space settings.
        composeRule.onNodeWithTag(SpaceTabTags.tab("space-work")).performTouchInput { longClick() }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(SpaceTabTags.settings("space-work"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(SpaceTabTags.settings("space-work")).performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(SpaceSettingsTags.SAVE).fetchSemanticsNodes().isNotEmpty()
        }
        // No field changed yet → Save disabled.
        composeRule.onNodeWithTag(SpaceSettingsTags.SAVE).assertIsNotEnabled()
        // Change the name → Save enabled.
        composeRule.onNodeWithTag(SpaceSettingsTags.NAME_FIELD).performTextInput("X")
        composeRule.onNodeWithTag(SpaceSettingsTags.SAVE).assertIsEnabled()
    }
}
