package com.inkwell.net

import android.content.Context

/**
 * Current inbox materialisation schema. Bump this constant whenever the on-device
 * inbox logic changes in a way that means an already-installed device (which has a
 * persisted cursor and therefore never runs the first-run backfill) should re-scan the
 * server once. On poll, a stored version older than this triggers a single `sync(null)`
 * backfill; dedupe protects against duplicates (Stage 29).
 *
 * v1 = Stage 29: upgrades from a build without a stored version (Stage 24 / v0.0.17)
 * backfill once so pushes that predate the upgrade are materialised.
 */
const val INBOX_SCHEMA_VERSION = 1

/**
 * Persists the `/sync` cursor (contract `device-api` §Sync cursor) so the device keeps
 * discovering pushed `to_user` jobs across process restarts (Stage 22). The cursor is an
 * opaque, non-empty server string; a null return means "never seeded", which is the signal
 * to BACKFILL on first run — page from `sync(null)` (the most recent 100 jobs) so a push
 * that landed before the app's first successful sync is materialised, not skipped (Stage 24).
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

    /**
     * Stage 29: forget the persisted cursor so the next poll backfills from `sync(null)`
     * (used by "Resync inbox"). A store that does not persist a cursor may no-op.
     */
    fun clear() {}

    /**
     * Stage 29: the persisted inbox schema version. A store that does not track a version
     * reports the CURRENT version (so it never spuriously backfills); the real
     * [PrefsSyncCursorStore] returns 0 until first set, so a genuine upgrade from a build
     * that never wrote this key backfills exactly once.
     */
    fun getSchemaVersion(): Int = INBOX_SCHEMA_VERSION

    /** Stage 29: persist the inbox schema version after a successful backfill pass. */
    fun setSchemaVersion(version: Int) {}
}

/** [SyncCursorStore] backed by the app's plain `inkwell_prefs` (not a secret). */
class PrefsSyncCursorStore(context: Context) : SyncCursorStore {
    private val prefs = context.getSharedPreferences("inkwell_prefs", Context.MODE_PRIVATE)

    override fun get(): String? = prefs.getString(KEY, null)

    override fun set(cursor: String) {
        prefs.edit().putString(KEY, cursor).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    // Default 0 so an install that predates this key (Stage 24 / v0.0.17) reads as "old"
    // and backfills once on the first poll after upgrade (Stage 29).
    override fun getSchemaVersion(): Int = prefs.getInt(KEY_SCHEMA, 0)

    override fun setSchemaVersion(version: Int) {
        prefs.edit().putInt(KEY_SCHEMA, version).apply()
    }

    private companion object {
        const val KEY = "sync_cursor"
        const val KEY_SCHEMA = "inbox_schema_version"
    }
}
