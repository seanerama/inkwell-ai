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
 * Stage 27 instrumented (emulator lane): with `BuildConfig.BRAIN` on (modelled here by the
 * `brainEnabled` constructor flag), the Ask sheet's job-type picker offers a FOURTH option —
 * **Remember** (posts `canvas.extract`) — alongside Ask / Mark up / Formalize.
 *
 * BuildConfig cannot be toggled at test time, so this asserts the four-with-on shape (the
 * flag-off shape is covered by the JVM `remember_is_not_selectable_when_brain_is_off` guard).
 * Compose is pinned to 1.6.8: use `waitUntil(timeoutMillis){ … fetchSemanticsNodes() }`.
 */
@RunWith(AndroidJUnit4::class)
class BrainPickerInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: InkDatabase
    private lateinit var viewModel: CanvasViewModel

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, InkDatabase::class.java).build()
        val repository = CanvasRepository(db.spaceDao(), db.canvasDao(), db.layerDao(), db.strokeDao())
        viewModel = CanvasViewModel(
            repository = repository,
            sendEnabled = true,
            oneTapAsk = false,
            cardActionsEnabled = true,
            formalizeEnabled = true,
            brainEnabled = true, // BuildConfig.BRAIN on
            autoOpenDefault = false,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun picker_shows_four_options_with_brain_on() {
        composeRule.setContent {
            Box(modifier = Modifier.size(width = 1000.dp, height = 1000.dp)) {
                CanvasScreen(viewModel = viewModel, onOpenSettings = {}, debugEnabled = false)
            }
        }
        // Open the note sheet (which hosts the job-type picker).
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(CanvasTags.ADD_NOTE).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(CanvasTags.ADD_NOTE).performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(CanvasTags.INSTRUCTION_REMEMBER).fetchSemanticsNodes().isNotEmpty()
        }
        // Four job-type options: Ask, Mark up, Formalize, Remember.
        composeRule.onNodeWithTag(CanvasTags.INSTRUCTION_ASK).assertIsDisplayed()
        composeRule.onNodeWithTag(CanvasTags.INSTRUCTION_MARKUP).assertIsDisplayed()
        composeRule.onNodeWithTag(CanvasTags.INSTRUCTION_FORMALIZE).assertIsDisplayed()
        composeRule.onNodeWithTag(CanvasTags.INSTRUCTION_REMEMBER).assertIsDisplayed()

        // Selecting Remember makes it the primary send (label follows the picker).
        composeRule.onNodeWithTag(CanvasTags.INSTRUCTION_REMEMBER).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(CanvasTags.INSTRUCTION_SEND).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(CanvasTags.INSTRUCTION_SEND).assertIsDisplayed()
    }
}
