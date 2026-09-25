package com.inkwell.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 31: the Ink switches default to off / Standard and persist what the owner picks. */
class InkPrefsTest {

    @Test
    fun defaults_are_low_latency_off_and_standard_smoothing() {
        val prefs = InkPrefs(InkPrefs.InMemoryStore())
        assertFalse(prefs.lowLatencyPen)
        assertEquals(SmoothingPreset.STANDARD, prefs.smoothing)
        assertEquals(InkSettings(lowLatencyPen = false, smoothing = SmoothingPreset.STANDARD), InkSettings.from(prefs))
        assertEquals(InkSettings(), InkSettings.from(prefs))
    }

    @Test
    fun choices_persist_in_the_store() {
        val store = InkPrefs.InMemoryStore()
        InkPrefs(store).apply {
            lowLatencyPen = true
            smoothing = SmoothingPreset.RESPONSIVE
        }
        val reread = InkPrefs(store)
        assertTrue(reread.lowLatencyPen)
        assertEquals(SmoothingPreset.RESPONSIVE, reread.smoothing)
        assertEquals("responsive", store.getString(InkPrefs.KEY_SMOOTHING))
    }

    @Test
    fun an_unknown_stored_preset_reads_as_standard() {
        val store = InkPrefs.InMemoryStore()
        store.putString(InkPrefs.KEY_SMOOTHING, "legacy")
        assertEquals(SmoothingPreset.STANDARD, InkPrefs(store).smoothing)
    }
}
