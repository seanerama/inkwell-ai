package com.inkwell.net

import android.content.Context

/**
 * Tracks per-job consecutive materialise failures and the set of jobs that have been
 * skipped (the "poison-page guard", Stage 29). If the SAME `to_user` job fails to
 * materialise on [PushInbox.MAX_MATERIALISE_FAILURES] consecutive polls it is skipped and
 * the cursor is allowed to advance past it, so one bad push cannot wedge the queue forever;
 * a manual Resync clears the counts and skips and retries everything.
 *
 * A pure interface (no Android types) so [PushInbox] stays JVM-unit-testable with the
 * in-memory [InMemoryInboxFailureStore]; the app wires [PrefsInboxFailureStore], backed by
 * the same plain `inkwell_prefs` as the cursor (additive keys, no Room migration).
 */
interface InboxFailureStore {
    /** Increment the consecutive-failure count for [jobId] and return the NEW count. */
    fun recordFailure(jobId: String): Int

    /** Reset the consecutive-failure count for [jobId] (called after it materialises). */
    fun clearFailure(jobId: String)

    /** Reset every consecutive-failure count (used by Resync). */
    fun clearAllFailures()

    /** True when [jobId] was skipped by the poison-page guard and should not be retried. */
    fun isSkipped(jobId: String): Boolean

    /** Record [jobId] as skipped with a human-readable [reason] (shown in Inbox status). */
    fun markSkipped(jobId: String, reason: String)

    /** The skipped jobs as `jobId -> reason` (surfaced in `InboxStatus.skipped`). */
    fun skipped(): Map<String, String>

    /** Forget every skipped job so Resync retries them. */
    fun clearSkipped()
}

/** In-memory [InboxFailureStore] — the default for [PushInbox] in JVM tests and the worker. */
class InMemoryInboxFailureStore : InboxFailureStore {
    private val counts = mutableMapOf<String, Int>()
    private val skips = mutableMapOf<String, String>()

    override fun recordFailure(jobId: String): Int {
        val next = (counts[jobId] ?: 0) + 1
        counts[jobId] = next
        return next
    }

    override fun clearFailure(jobId: String) { counts.remove(jobId) }
    override fun clearAllFailures() { counts.clear() }
    override fun isSkipped(jobId: String): Boolean = skips.containsKey(jobId)
    override fun markSkipped(jobId: String, reason: String) { skips[jobId] = reason }
    override fun skipped(): Map<String, String> = skips.toMap()
    override fun clearSkipped() { skips.clear() }
}

/** [InboxFailureStore] backed by the app's plain `inkwell_prefs` (not a secret). */
class PrefsInboxFailureStore(context: Context) : InboxFailureStore {
    private val prefs = context.getSharedPreferences("inkwell_prefs", Context.MODE_PRIVATE)

    override fun recordFailure(jobId: String): Int {
        val next = prefs.getInt(failKey(jobId), 0) + 1
        prefs.edit().putInt(failKey(jobId), next).apply()
        return next
    }

    override fun clearFailure(jobId: String) {
        prefs.edit().remove(failKey(jobId)).apply()
    }

    override fun clearAllFailures() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(FAIL_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
    }

    override fun isSkipped(jobId: String): Boolean = prefs.contains(skipKey(jobId))

    override fun markSkipped(jobId: String, reason: String) {
        prefs.edit().putString(skipKey(jobId), reason).apply()
    }

    override fun skipped(): Map<String, String> =
        prefs.all.entries
            .filter { it.key.startsWith(SKIP_PREFIX) }
            .mapNotNull { (k, v) -> (v as? String)?.let { k.removePrefix(SKIP_PREFIX) to it } }
            .toMap()

    override fun clearSkipped() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(SKIP_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
    }

    private companion object {
        const val FAIL_PREFIX = "inbox_fail_"
        const val SKIP_PREFIX = "inbox_skip_"
        fun failKey(jobId: String) = FAIL_PREFIX + jobId
        fun skipKey(jobId: String) = SKIP_PREFIX + jobId
    }
}
