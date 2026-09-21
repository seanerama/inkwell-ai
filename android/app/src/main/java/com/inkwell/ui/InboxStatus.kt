package com.inkwell.ui

/**
 * Stage 29: the observable outcome of the push-inbox poll, surfaced on [LibraryViewModel]
 * and shown in Settings so a failure is VISIBLE instead of swallowed (the 2026-09-21
 * silent-failure fix). All fields are in-memory only — no persistence, no Room.
 *
 * @property lastPollAt epoch millis of the most recent poll attempt, or null before the first
 * @property lastMaterialised how many pushed jobs the last successful poll materialised
 * @property lastPollError the class + message of the last poll error, or null when clean
 * @property lastPollErrorAt epoch millis when [lastPollError] was recorded
 * @property skipped jobs abandoned by the poison-page guard, as `jobId -> reason`
 */
data class InboxStatus(
    val lastPollAt: Long? = null,
    val lastMaterialised: Int = 0,
    val lastPollError: String? = null,
    val lastPollErrorAt: Long? = null,
    val skipped: Map<String, String> = emptyMap(),
)
