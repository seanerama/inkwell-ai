package com.inkwell.ink

import android.content.Context

/**
 * Stage 31: the two runtime ink switches shown under "Ink" on the Settings screen, read by
 * `CanvasScreen` when a canvas opens:
 *  - [lowLatencyPen] — "Low-latency pen (experimental)": front-buffered wet layer, motion
 *    prediction and unbuffered input for the pen tool. Default **off**.
 *  - [smoothing] — "Smoothing: Standard / Responsive". Default [SmoothingPreset.STANDARD].
 *
 * Both are plain preferences, not secrets, so they live in `SharedPreferences` (never the
 * encrypted token store). The whole surface only exists when `BuildConfig.LOW_LATENCY_INK`
 * is true. Storage goes through [Store] so the defaults are JVM-unit-testable.
 */
class InkPrefs(private val store: Store) {

    /** Minimal key/value seam over `SharedPreferences`. */
    interface Store {
        fun getBoolean(key: String, default: Boolean): Boolean
        fun putBoolean(key: String, value: Boolean)
        fun getString(key: String): String?
        fun putString(key: String, value: String)
    }

    var lowLatencyPen: Boolean
        get() = store.getBoolean(KEY_LOW_LATENCY_PEN, DEFAULT_LOW_LATENCY_PEN)
        set(value) = store.putBoolean(KEY_LOW_LATENCY_PEN, value)

    var smoothing: SmoothingPreset
        get() = SmoothingPreset.fromKey(store.getString(KEY_SMOOTHING))
        set(value) = store.putString(KEY_SMOOTHING, value.key)

    /** In-memory [Store] for tests and previews. */
    class InMemoryStore : Store {
        private val values = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun getString(key: String) = values[key] as? String
        override fun putString(key: String, value: String) { values[key] = value }
    }

    companion object {
        const val PREFS_NAME = "inkwell_ink_prefs"
        const val KEY_LOW_LATENCY_PEN = "low_latency_pen"
        const val KEY_SMOOTHING = "smoothing_preset"
        const val DEFAULT_LOW_LATENCY_PEN = false

        /** The app's [InkPrefs], backed by a dedicated plain `SharedPreferences` file. */
        fun from(context: Context): InkPrefs {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return InkPrefs(
                object : Store {
                    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
                    override fun putBoolean(key: String, value: Boolean) {
                        prefs.edit().putBoolean(key, value).apply()
                    }
                    override fun getString(key: String): String? = prefs.getString(key, null)
                    override fun putString(key: String, value: String) {
                        prefs.edit().putString(key, value).apply()
                    }
                },
            )
        }
    }
}

/** Stage 31: the ink settings a canvas applies when it opens (a snapshot of [InkPrefs]). */
data class InkSettings(
    val lowLatencyPen: Boolean = InkPrefs.DEFAULT_LOW_LATENCY_PEN,
    val smoothing: SmoothingPreset = SmoothingPreset.DEFAULT,
) {
    companion object {
        fun from(prefs: InkPrefs) = InkSettings(prefs.lowLatencyPen, prefs.smoothing)
    }
}
