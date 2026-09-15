package com.inkwell.net

/**
 * Where the app is with respect to the user's attention. Drives the poll cadence
 * together with whether a job is still outstanding (contract `device-api` §Sync cursor,
 * SPEC §8).
 */
enum class AppLifecycle { FOREGROUND, BACKGROUND }

/**
 * The pure, testable sync-cadence state machine (SPEC §8 / contract `device-api`
 * §Sync cursor: "Poll cadence is device policy: 5 s while a job is outstanding in the
 * foreground, 60 s otherwise, 5 min backgrounded").
 *
 * This is intentionally free of Android, WorkManager, and coroutines so it can be
 * exercised by a plain JVM unit test. [SyncWorker] and [com.inkwell.ui.CanvasViewModel]
 * consume it; neither re-derives the numbers.
 */
object SyncCadence {

    /** 5 s: a job is outstanding and the app is in the foreground. */
    const val FOREGROUND_OUTSTANDING_MS = 5_000L

    /** 60 s: foreground, nothing outstanding. */
    const val FOREGROUND_IDLE_MS = 60_000L

    /** 5 min: the app is backgrounded (regardless of outstanding work). */
    const val BACKGROUND_MS = 300_000L

    /** The two inputs the cadence depends on. */
    data class State(
        val lifecycle: AppLifecycle,
        val hasOutstandingJob: Boolean,
    )

    /** Pick the poll interval in milliseconds for [state]. */
    fun intervalMs(state: State): Long = when {
        state.lifecycle == AppLifecycle.BACKGROUND -> BACKGROUND_MS
        state.hasOutstandingJob -> FOREGROUND_OUTSTANDING_MS
        else -> FOREGROUND_IDLE_MS
    }

    /** Convenience overload. */
    fun intervalMs(lifecycle: AppLifecycle, hasOutstandingJob: Boolean): Long =
        intervalMs(State(lifecycle, hasOutstandingJob))
}
