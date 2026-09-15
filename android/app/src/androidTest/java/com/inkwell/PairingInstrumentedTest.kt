package com.inkwell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkwell.data.InkDatabase
import com.inkwell.net.TokenStore
import com.inkwell.ui.PairingScreen
import com.inkwell.ui.PairingTags
import com.inkwell.ui.PairingViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
    fun room_database_opens_at_version_1() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, InkDatabase::class.java).build()
        try {
            assertEquals(1, db.openHelper.readableDatabase.version)
            // Exercise a DAO to prove the v1 schema is real and queryable.
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
}
