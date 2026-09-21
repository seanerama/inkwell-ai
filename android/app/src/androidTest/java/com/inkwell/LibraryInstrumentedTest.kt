package com.inkwell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkwell.data.CanvasRepository
import com.inkwell.data.InkDatabase
import com.inkwell.data.LibraryRepository
import com.inkwell.net.TokenStore
import com.inkwell.ui.LibraryScreen
import com.inkwell.ui.LibraryTags
import com.inkwell.ui.LibraryViewModel
import com.inkwell.ui.PairingScreen
import com.inkwell.ui.PairingTags
import com.inkwell.ui.PairingViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 11 Library instrumented test (emulator lane): with two folders and a canvas
 * seeded in the space, [LibraryScreen] renders their tiles; opening a folder shows its
 * child canvas and a breadcrumb tap returns to the root (open → back → same place); the
 * "+" FAB's **New canvas** creates a canvas and hands its id to the open callback.
 */
@RunWith(AndroidJUnit4::class)
class LibraryInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: InkDatabase
    private lateinit var library: LibraryRepository
    private lateinit var canvasRepository: CanvasRepository
    private lateinit var viewModel: LibraryViewModel

    private lateinit var networkFolderId: String
    private lateinit var rootCanvasId: String
    private lateinit var childCanvasId: String

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, InkDatabase::class.java).build()
        library = LibraryRepository(db.folderDao(), db.canvasDao(), db.layerDao())
        canvasRepository = CanvasRepository(
            db.spaceDao(), db.canvasDao(), db.layerDao(), db.strokeDao(),
        )
        runBlocking {
            val space = canvasRepository.ensureSeededSpaceId()
            val network = library.createFolder(space, null, "Network")
            library.createFolder(space, null, "Archive")
            networkFolderId = network.id
            rootCanvasId = library.createCanvas(space, folderId = null).id
            childCanvasId = library.createCanvas(space, folderId = network.id).id
        }
        viewModel = LibraryViewModel(library = library, canvasRepository = canvasRepository)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun renders_two_folders_and_a_canvas() {
        composeRule.setContent {
            LibraryScreen(viewModel = viewModel, onOpenCanvas = {})
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(LibraryTags.folderTile(networkFolderId))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(LibraryTags.folderTile(networkFolderId)).assertIsDisplayed()
        composeRule.onNodeWithText("Archive").assertIsDisplayed()
        composeRule.onNodeWithTag(LibraryTags.canvasTile(rootCanvasId)).assertIsDisplayed()
    }

    @Test
    fun open_folder_then_breadcrumb_back_returns_to_root() {
        composeRule.setContent {
            LibraryScreen(viewModel = viewModel, onOpenCanvas = {})
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(LibraryTags.folderTile(networkFolderId))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Open the "Network" folder → its child canvas shows, the root canvas does not.
        composeRule.onNodeWithTag(LibraryTags.folderTile(networkFolderId)).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(LibraryTags.canvasTile(childCanvasId))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(LibraryTags.canvasTile(childCanvasId)).assertIsDisplayed()

        // Breadcrumb "Space" returns to the root, where the folders live again.
        composeRule.onNodeWithText("Space").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(LibraryTags.folderTile(networkFolderId))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(LibraryTags.canvasTile(rootCanvasId)).assertIsDisplayed()
    }

    private class InMemoryTokenStore : TokenStore {
        private var token: String? = null
        private var baseUrl: String? = null
        override fun getToken() = token
        override fun setToken(token: String?) { this.token = token }
        override fun getBaseUrl() = baseUrl
        override fun setBaseUrl(url: String?) { this.baseUrl = url }
    }

    /**
     * Stage 28 regression: the Library's breadcrumb bar shows a Settings gear, and tapping
     * it routes to the pairing screen (the only place to enter the server URL + token).
     */
    @Test
    fun library_gear_opens_settings() {
        val pairingViewModel = PairingViewModel(InMemoryTokenStore())
        composeRule.setContent {
            var showSettings by remember { mutableStateOf(false) }
            if (showSettings) {
                PairingScreen(viewModel = pairingViewModel)
            } else {
                LibraryScreen(
                    viewModel = viewModel,
                    onOpenCanvas = {},
                    onOpenSettings = { showSettings = true },
                )
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(LibraryTags.SETTINGS).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(LibraryTags.SETTINGS).assertIsDisplayed()
        composeRule.onNodeWithTag(LibraryTags.SETTINGS).performClick()
        // The pairing screen's server-URL field is a stable node proving we routed there.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(PairingTags.SERVER_URL).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(PairingTags.SERVER_URL).assertIsDisplayed()
    }

    @Test
    fun new_canvas_creates_and_opens_it() {
        var opened: String? = null
        composeRule.setContent {
            LibraryScreen(viewModel = viewModel, onOpenCanvas = { opened = it })
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(LibraryTags.FAB).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(LibraryTags.FAB).performClick()
        composeRule.onNodeWithTag(LibraryTags.NEW_CANVAS).performClick()
        composeRule.waitUntil(5_000) { opened != null }
        assertNotNull("New canvas hands its id to the open callback", opened)
    }
}
