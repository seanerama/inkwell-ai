package com.inkwell.net

import android.content.Context

/**
 * Persists the `/sync` cursor (contract `device-api` §Sync cursor) so the device keeps
 * discovering pushed `to_user` jobs across process restarts (Stage 22). The cursor is an
 * opaque, non-empty server string; a null return means "never seeded", which is the
 * signal to seed on first run with the *current* cursor (`sync(null).cursor`) so old
 * history is not replayed.
 *
 * A pure interface so [PushInbox] is JVM-unit-testable with an in-memory fake; the app
 * backs it with plain SharedPreferences ([PrefsSyncCursorStore]) — the cursor is not a
 * secret (it encodes `(updated_at, id)` of the last job, not credentials).
 */
interface SyncCursorStore {
    /** The persisted cursor, or null when it has never been seeded. */
    fun get(): String?

    /** Persist [cursor] as the new position (advanced only after a page is materialised). */
    fun set(cursor: String)
}

/** [SyncCursorStore] backed by the app's plain `inkwell_prefs` (not a secret). */
class PrefsSyncCursorStore(context: Context) : SyncCursorStore {
    private val prefs = context.getSharedPreferences("inkwell_prefs", Context.MODE_PRIVATE)

    override fun get(): String? = prefs.getString(KEY, null)

    override fun set(cursor: String) {
        prefs.edit().putString(KEY, cursor).apply()
    }

    private companion object {
        const val KEY = "sync_cursor"
    }
}
