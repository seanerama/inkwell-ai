package com.inkwell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkwell.data.InkDatabase
import com.inkwell.ink.InkPrefs
import com.inkwell.ink.SmoothingPreset
import com.inkwell.net.TokenStore
import com.inkwell.ui.PairingScreen
import com.inkwell.ui.PairingTags
import com.inkwell.ui.PairingViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The single instrumented test class for the walking skeleton (emulator, API 34):
 * the Room database opens at version 1 and the pairing screen renders. Kept to one
 * class so the emulator lane stays as short as possible.
 */
@RunWith(AndroidJUnit4::class)
class PairingInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class InMemoryTokenStore : TokenStore {
        private var token: String? = null
        private var baseUrl: String? = null
        override fun getToken() = token
        override fun setToken(token: String?) { this.token = token }
        override fun getBaseUrl() = baseUrl
        override fun setBaseUrl(url: String?) { this.baseUrl = url }
    }

    @Test
    fun room_database_opens_at_the_current_version() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, InkDatabase::class.java).build()
        try {
            // v1 (stage 2) -> v2 (stage 10, card state) -> v3 (stage 11, folders +
            // canvas folder_id/deleted_at) -> v4 (stage 22, canvas seen_at). The upgrade
            // paths are covered by InkDatabaseMigrationTest; this proves a fresh open lands
            // on the current schema.
            assertEquals(4, db.openHelper.readableDatabase.version)
            // Exercise a DAO to prove the schema is real and queryable.
            runBlocking { assertEquals(0, db.strokeDao().count()) }
        } finally {
            db.close()
        }
    }

    @Test
    fun pairing_screen_renders() {
        val vm = PairingViewModel(InMemoryTokenStore())
        composeRule.setContent {
            PairingScreen(viewModel = vm, pingEnabled = true)
        }
        composeRule.onNodeWithTag(PairingTags.SERVER_URL).assertIsDisplayed()
        composeRule.onNodeWithTag(PairingTags.TOKEN).assertIsDisplayed()
        composeRule.onNodeWithTag(PairingTags.CHECK).assertIsDisplayed()
        composeRule.onNodeWithTag(PairingTags.PING).assertIsDisplayed()
        composeRule.onNodeWithTag(PairingTags.STATUS).assertIsDisplayed()
    }

    // Stage 31/33: the Settings "Ink" section. On a fresh install (no stored ink prefs)
    // both switches show ON — low-latency pen on, Responsive selected — and showing them
    // writes nothing. Turning each off is persisted (applied the next time a canvas opens).
    @Test
    fun ink_section_fresh_install_shows_both_on_and_persists_turning_them_off() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = context.getSharedPreferences(InkPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        file.edit().clear().commit() // a fresh install
        try {
            val vm = PairingViewModel(InMemoryTokenStore())
            val prefs = InkPrefs.from(context)
            composeRule.setContent {
                PairingScreen(viewModel = vm, pingEnabled = true, inkPrefs = prefs)
            }
            composeRule.onNodeWithTag(PairingTags.INK_SECTION).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag(PairingTags.INK_LOW_LATENCY).assertIsOn()
            composeRule.onNodeWithTag(PairingTags.inkSmoothing(SmoothingPreset.RESPONSIVE)).assertIsSelected()
            composeRule.onNodeWithTag(PairingTags.inkSmoothing(SmoothingPreset.STANDARD)).assertIsNotSelected()
            assertTrue("showing the defaults must not store them", file.all.isEmpty())

            composeRule.onNodeWithTag(PairingTags.INK_LOW_LATENCY).performClick()
            composeRule.onNodeWithTag(PairingTags.INK_LOW_LATENCY).assertIsOff()
            composeRule.onNodeWithTag(PairingTags.inkSmoothing(SmoothingPreset.STANDARD)).performClick()
            composeRule.onNodeWithTag(PairingTags.inkSmoothing(SmoothingPreset.STANDARD)).assertIsSelected()
            composeRule.waitForIdle()

            // An explicit off / Standard is stored and wins over the on / Responsive defaults.
            val reread = InkPrefs.from(context)
            assertFalse(reread.lowLatencyPen)
            assertEquals(SmoothingPreset.STANDARD, reread.smoothing)
            assertEquals(false, file.getBoolean(InkPrefs.KEY_LOW_LATENCY_PEN, true))
            assertEquals(SmoothingPreset.STANDARD.key, file.getString(InkPrefs.KEY_SMOOTHING, null))
        } finally {
            file.edit().clear().commit()
        }
    }
}
