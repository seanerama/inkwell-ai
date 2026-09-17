package com.inkwell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkwell.data.CanvasRepository
import com.inkwell.data.InkDatabase
import com.inkwell.data.LibraryRepository
import com.inkwell.data.SpaceSync
import com.inkwell.data.StrokeEntity
import com.inkwell.net.ApiClient
import com.inkwell.net.DeviceRepository
import com.inkwell.net.TokenStore
import com.inkwell.render.AnnotationRenderer
import com.inkwell.ui.LibraryScreen
import com.inkwell.ui.LibraryTags
import com.inkwell.ui.LibraryViewModel
import com.inkwell.ui.SpaceTabTags
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 14 (ADR-0010) instrumented tests (emulator lane):
 *  - mirror round-trip through MockWebServer `GET /spaces` with the four Phase-3 spaces →
 *    four tabs, and the placeholder's ink survives reconciliation byte-for-byte;
 *  - a Compose tab switch re-scopes the Library content and resets the breadcrumb;
 *  - the space accent parses from `#RRGGBB` (falling back to DEFAULT_ACCENT).
 */
@RunWith(AndroidJUnit4::class)
class SpacesInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var db: InkDatabase
    private lateinit var canvasRepository: CanvasRepository
    private lateinit var library: LibraryRepository
    private lateinit var spaceSync: SpaceSync
    private lateinit var deviceRepository: DeviceRepository

    private lateinit var seededCanvasId: String
    private var strokeCountBefore = 0
    private lateinit var strokeBytesBefore: ByteArray

    private class InMemoryTokenStore(private val base: String) : TokenStore {
        override fun getToken() = "test-token"
        override fun setToken(token: String?) {}
        override fun getBaseUrl() = base
        override fun setBaseUrl(url: String?) {}
    }

    /** The four Phase-3 spaces (ordered by position); `work` matches the local placeholder. */
    private val spacesJson = """
        [
          {"id":"space-work","name":"Work","slug":"work","system_prompt":"","tools":[],"model":"claude-sonnet-5","color":"#3B6EA5","position":0,"created_at":"2026-09-16T00:00:00Z"},
          {"id":"space-home","name":"Home","slug":"home","system_prompt":"","tools":[],"model":"claude-sonnet-5","color":"#2E8B57","position":1,"created_at":"2026-09-16T00:00:00Z"},
          {"id":"space-learning","name":"Learning","slug":"learning","system_prompt":"","tools":[],"model":"claude-sonnet-5","color":"#E5484D","position":2,"created_at":"2026-09-16T00:00:00Z"},
          {"id":"space-business","name":"Business","slug":"business","system_prompt":"","tools":[],"model":"claude-sonnet-5","color":"#B58900","position":3,"created_at":"2026-09-16T00:00:00Z"}
        ]
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path?.endsWith("/v1/spaces") == true) {
                    MockResponse().setResponseCode(200).setBody(spacesJson)
                } else {
                    MockResponse().setResponseCode(404)
                }
        }
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

    private fun seedPlaceholderCanvasWithInk() = runBlocking {
        val spaceId = canvasRepository.ensureSeededSpaceId() // slug "work" placeholder
        val canvas = library.createCanvas(spaceId, folderId = null)
        seededCanvasId = canvas.id
        val inkLayer = db.layerDao().forCanvas(canvas.id).first { it.owner == "user" && it.type == "ink" }
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        db.strokeDao().insert(
            StrokeEntity(
                id = "stroke-1", layerId = inkLayer.id, tool = "pen", color = "#111111",
                widthCu = 3f, points = bytes, pointCount = 2,
                bboxX = 0f, bboxY = 0f, bboxW = 1f, bboxH = 1f, createdAt = 1L,
            ),
        )
        strokeCountBefore = db.strokeDao().count()
        strokeBytesBefore = db.strokeDao().byId("stroke-1")!!.points
    }

    @Test
    fun mirror_reconciles_the_placeholder_and_ink_survives_byte_for_byte() = runBlocking {
        seedPlaceholderCanvasWithInk()

        val serverIds = spaceSync.refresh()

        assertEquals(4, serverIds.size)
        // The placeholder ("work") is gone; the canvas now carries the server id.
        assertEquals("space-work", db.canvasDao().byId(seededCanvasId)!!.spaceId)
        assertEquals(4, db.spaceDao().all().size)
        // Ink is untouched: same count and the same bytes.
        assertEquals(strokeCountBefore, db.strokeDao().count())
        assertEquals(
            strokeBytesBefore.toList(),
            db.strokeDao().byId("stroke-1")!!.points.toList(),
        )
        // Idempotent: a second refresh changes nothing.
        spaceSync.refresh()
        assertEquals("space-work", db.canvasDao().byId(seededCanvasId)!!.spaceId)
        assertEquals(4, db.spaceDao().all().size)
    }

    @Test
    fun four_spaces_render_four_tabs() {
        seedPlaceholderCanvasWithInk()
        val prefs = mutableMapOf<String, String?>()
        val vm = LibraryViewModel(
            library = library,
            canvasRepository = canvasRepository,
            spaceSync = spaceSync,
            loadActiveSpaceId = { prefs["active_space_id"] },
            saveActiveSpaceId = { prefs["active_space_id"] = it },
        )
        composeRule.setContent { LibraryScreen(viewModel = vm, onOpenCanvas = {}) }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(SpaceTabTags.tab("space-business"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(SpaceTabTags.tab("space-work")).assertIsDisplayed()
        composeRule.onNodeWithTag(SpaceTabTags.tab("space-home")).assertIsDisplayed()
        composeRule.onNodeWithTag(SpaceTabTags.tab("space-learning")).assertIsDisplayed()
        composeRule.onNodeWithTag(SpaceTabTags.tab("space-business")).assertIsDisplayed()
    }

    @Test
    fun tab_switch_changes_content_and_resets_the_breadcrumb() {
        seedPlaceholderCanvasWithInk()
        // After reconcile, give Work and Learning each a distinct folder.
        val workFolderId: String
        val learningFolderId: String
        runBlocking {
            spaceSync.refresh()
            workFolderId = library.createFolder("space-work", null, "WorkFolder").id
            learningFolderId = library.createFolder("space-learning", null, "LearningFolder").id
        }
        val prefs = mutableMapOf<String, String?>()
        val vm = LibraryViewModel(
            library = library,
            canvasRepository = canvasRepository,
            spaceSync = spaceSync,
            loadActiveSpaceId = { prefs["active_space_id"] },
            saveActiveSpaceId = { prefs["active_space_id"] = it },
        )
        composeRule.setContent { LibraryScreen(viewModel = vm, onOpenCanvas = {}) }

        // Active tab is the first (Work, position 0): its folder shows.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(LibraryTags.folderTile(workFolderId))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Navigate into Work's folder so the breadcrumb has a crumb to reset.
        composeRule.onNodeWithTag(LibraryTags.folderTile(workFolderId)).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("WorkFolder").fetchSemanticsNodes().isNotEmpty()
        }

        // Switch to the Learning tab: its folder shows, Work's folder is gone, breadcrumb reset.
        composeRule.onNodeWithTag(SpaceTabTags.tab("space-learning")).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(LibraryTags.folderTile(learningFolderId))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(LibraryTags.folderTile(learningFolderId)).assertIsDisplayed()
        composeRule.onAllNodesWithTag(LibraryTags.folderTile(workFolderId))
            .fetchSemanticsNodes().let { assertEquals(0, it.size) }
        // Breadcrumb is back at the root ("Space" with no deeper crumb).
        composeRule.onNodeWithText("Space").assertIsDisplayed()
    }

    @Test
    fun accent_parses_from_hex_with_a_fallback() {
        assertEquals(android.graphics.Color.parseColor("#E5484D"), AnnotationRenderer.accentFrom("#E5484D"))
        assertEquals(AnnotationRenderer.DEFAULT_ACCENT, AnnotationRenderer.accentFrom(null))
        assertEquals(AnnotationRenderer.DEFAULT_ACCENT, AnnotationRenderer.accentFrom("not-a-color"))
    }
}
