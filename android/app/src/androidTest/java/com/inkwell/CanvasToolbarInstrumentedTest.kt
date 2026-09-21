package com.inkwell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkwell.data.CanvasRepository
import com.inkwell.data.InkDatabase
import com.inkwell.ui.CanvasScreen
import com.inkwell.ui.CanvasTags
import com.inkwell.ui.CanvasViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 28 regression: on a non-scrolling canvas toolbar the Settings entry was pushed off
 * the right edge at real widths. The toolbar now keeps its actions in a horizontally
 * scrollable strip and pins a trailing overflow (⋮) that carries Settings, so Settings is
 * reachable at both a portrait (~800 dp) and a landscape (~1280 dp) toolbar width. Composed
 * with [CanvasViewModel.sendEnabled] on so the full send-loop toolbar is present.
 */
@RunWith(AndroidJUnit4::class)
class CanvasToolbarInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: InkDatabase
    private lateinit var viewModel: CanvasViewModel

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, InkDatabase::class.java).build()
        val repository = CanvasRepository(
            db.spaceDao(), db.canvasDao(), db.layerDao(), db.strokeDao(),
        )
        viewModel = CanvasViewModel(
            repository = repository,
            sendEnabled = true,
            // The Library drives canvas opening; don't auto-load a default here.
            autoOpenDefault = false,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun assertSettingsReachableAt(widthDp: Int) {
        composeRule.setContent {
            Box(modifier = Modifier.size(width = widthDp.dp, height = 1000.dp)) {
                CanvasScreen(
                    viewModel = viewModel,
                    onOpenSettings = {},
                    debugEnabled = false,
                )
            }
        }
        // The pinned overflow is always displayed regardless of the toolbar's width.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(CanvasTags.OVERFLOW).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(CanvasTags.OVERFLOW).assertIsDisplayed()
        // Opening it reveals the Settings entry — the node the owner could not previously find.
        composeRule.onNodeWithTag(CanvasTags.OVERFLOW).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(CanvasTags.SETTINGS).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(CanvasTags.SETTINGS).assertIsDisplayed()
    }

    @Test
    fun settings_reachable_at_portrait_width() {
        assertSettingsReachableAt(800)
    }

    @Test
    fun settings_reachable_at_landscape_width() {
        assertSettingsReachableAt(1280)
    }
}
