package com.inkwell.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 31: the Ink switches persist what the owner picks. Stage 33: they default to
 * low-latency pen **on** and **Responsive** smoothing, an explicit stored choice (including
 * off / Standard) always wins, and reading never writes the default into the store.
 */
class InkPrefsTest {

    /** Records every write, so a test can prove reads never persist anything. */
    private class CountingStore : InkPrefs.Store {
        val inner = InkPrefs.InMemoryStore()
        var writes = 0
        override fun getBoolean(key: String, default: Boolean) = inner.getBoolean(key, default)
        override fun putBoolean(key: String, value: Boolean) { writes++; inner.putBoolean(key, value) }
        override fun getString(key: String) = inner.getString(key)
        override fun putString(key: String, value: String) { writes++; inner.putString(key, value) }
    }

    @Test
    fun defaults_are_low_latency_on_and_responsive_smoothing() {
        assertTrue(InkPrefs.DEFAULT_LOW_LATENCY_PEN)
        assertEquals(SmoothingPreset.RESPONSIVE, SmoothingPreset.DEFAULT)
        val prefs = InkPrefs(InkPrefs.InMemoryStore())
        assertTrue(prefs.lowLatencyPen)
        assertEquals(SmoothingPreset.RESPONSIVE, prefs.smoothing)
        assertEquals(InkSettings(lowLatencyPen = true, smoothing = SmoothingPreset.RESPONSIVE), InkSettings.from(prefs))
        assertEquals(InkSettings(), InkSettings.from(prefs))
    }

    @Test
    fun reading_the_defaults_never_writes_them() {
        val store = CountingStore()
        val prefs = InkPrefs(store)
        repeat(3) {
            prefs.lowLatencyPen
            prefs.smoothing
            InkSettings.from(prefs)
        }
        assertEquals(0, store.writes)
        assertNull(store.getString(InkPrefs.KEY_SMOOTHING))
    }

    @Test
    fun an_explicit_stored_off_and_standard_still_win() {
        val store = InkPrefs.InMemoryStore()
        store.putBoolean(InkPrefs.KEY_LOW_LATENCY_PEN, false)
        store.putString(InkPrefs.KEY_SMOOTHING, "standard")
        val prefs = InkPrefs(store)
        assertFalse(prefs.lowLatencyPen)
        assertEquals(SmoothingPreset.STANDARD, prefs.smoothing)
        assertEquals(InkSettings(lowLatencyPen = false, smoothing = SmoothingPreset.STANDARD), InkSettings.from(prefs))
    }

    @Test
    fun an_explicit_stored_on_and_responsive_are_unaffected() {
        val store = InkPrefs.InMemoryStore()
        store.putBoolean(InkPrefs.KEY_LOW_LATENCY_PEN, true)
        store.putString(InkPrefs.KEY_SMOOTHING, "responsive")
        val prefs = InkPrefs(store)
        assertTrue(prefs.lowLatencyPen)
        assertEquals(SmoothingPreset.RESPONSIVE, prefs.smoothing)
    }

    @Test
    fun choices_persist_in_the_store() {
        val store = InkPrefs.InMemoryStore()
        InkPrefs(store).apply {
            lowLatencyPen = false
            smoothing = SmoothingPreset.STANDARD
        }
        val reread = InkPrefs(store)
        assertFalse(reread.lowLatencyPen)
        assertEquals(SmoothingPreset.STANDARD, reread.smoothing)
        assertEquals("standard", store.getString(InkPrefs.KEY_SMOOTHING))
        assertEquals(false, store.getBoolean(InkPrefs.KEY_LOW_LATENCY_PEN, true))
    }

    @Test
    fun an_unknown_stored_preset_reads_as_the_default() {
        val store = InkPrefs.InMemoryStore()
        store.putString(InkPrefs.KEY_SMOOTHING, "legacy")
        assertEquals(SmoothingPreset.DEFAULT, InkPrefs(store).smoothing)
    }

    @Test
    fun the_kill_switch_settings_are_the_pre_stage_31_path() {
        assertEquals(InkSettings(lowLatencyPen = false, smoothing = SmoothingPreset.STANDARD), InkSettings.GATE_OFF)
    }
}
