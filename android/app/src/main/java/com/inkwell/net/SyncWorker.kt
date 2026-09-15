package com.inkwell.net

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit

/**
 * The durable background lane of the poll/flush loop (WorkManager). Its cadence is the
 * `BACKGROUND` case of the pure [SyncCadence] state machine — 5 min target — while the
 * *foreground* 5 s / 60 s cadences are driven in-process by the ViewModel's poll loop
 * (WorkManager cannot fire faster than its 15-minute periodic floor, so sub-minute
 * polling can never be a Worker).
 *
 * Scheduling therefore uses two requests:
 *  - a **periodic** worker at WorkManager's 15-minute minimum with a CONNECTED
 *    constraint — the always-on safety net that keeps `/sync` fresh and drains the
 *    offline queue even if the app is killed;
 *  - a one-shot [flushNow] with a CONNECTED constraint — enqueued on a reconnect so
 *    offline-created jobs go out promptly (in order) rather than waiting for the tick.
 *
 * Each run: build a repository from the persisted pairing, flush
 * [LoopServices.offlineQueue] oldest-first, then do one best-effort `/sync` refresh.
 * A network failure returns [Result.retry] so WorkManager backs off and retries.
 */
class SyncWorker(
    appContext: Context,
    params: androidx.work.WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val tokenStore: TokenStore = EncryptedTokenStore(applicationContext)
        val repo = LoopServices.repositoryFrom(tokenStore) ?: return Result.success()
        return try {
            // Flush offline-created jobs in insertion order (SPEC §9.5).
            LoopServices.offlineQueue.flush { pending -> repo.submitAgentJob(pending.request) }
            // Best-effort background refresh so terminal jobs are observed even if the
            // foreground loop was not running when they completed.
            repo.sync(null)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val PERIODIC_NAME = "inkwell-sync-periodic"
        const val FLUSH_NAME = "inkwell-sync-flush"

        private val connectedOnly = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** The background cadence target from the pure state machine (documented intent). */
        val backgroundIntervalMs: Long =
            SyncCadence.intervalMs(AppLifecycle.BACKGROUND, hasOutstandingJob = false)

        /**
         * Register the always-on periodic safety net. WorkManager clamps the period to a
         * 15-minute minimum, so this runs at that floor rather than the 5-min
         * [backgroundIntervalMs] target; [flushNow] covers the "send promptly on
         * reconnect" case that the floor would otherwise delay. KEEP so re-scheduling on
         * every launch does not reset the timer.
         */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(connectedOnly)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Enqueue a one-shot CONNECTED flush (call on reconnect). */
        fun flushNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(connectedOnly)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                FLUSH_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
