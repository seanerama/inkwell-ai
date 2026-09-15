package com.inkwell.net

import java.util.concurrent.atomic.AtomicLong

/** One job created while offline, tagged with a monotonic insertion sequence. */
data class PendingJob(
    val seq: Long,
    val request: JobCreateRequest,
)

/**
 * Holds jobs created while offline and flushes them **in insertion order** on reconnect
 * (SPEC §9.5: "Queued jobs created offline are held locally and flushed on reconnect").
 *
 * Pure and Android-free so a JVM unit test can prove the ordering invariant. The queue
 * is a process singleton in production ([LoopServices.offlineQueue]) so both the
 * foreground send path and the background [SyncWorker] flush the same items.
 *
 * Thread-safety: all mutation of the backing deque happens inside `synchronized(this)`
 * blocks, and the lock is never held across a suspension point, so [flush] can safely
 * suspend while [enqueue] runs concurrently.
 */
class OfflineJobQueue {

    private val pending = ArrayDeque<PendingJob>()
    private val seqGen = AtomicLong(0)

    /** Append [request] to the tail; returns the enqueued [PendingJob]. */
    fun enqueue(request: JobCreateRequest): PendingJob {
        val job = PendingJob(seqGen.getAndIncrement(), request)
        synchronized(this) { pending.addLast(job) }
        return job
    }

    fun size(): Int = synchronized(this) { pending.size }

    fun isEmpty(): Boolean = synchronized(this) { pending.isEmpty() }

    /** Current contents in insertion order (read-only snapshot). */
    fun snapshot(): List<PendingJob> = synchronized(this) { pending.toList() }

    /**
     * Submit queued jobs oldest-first via [submit], removing each only after it succeeds.
     * If [submit] throws, the offending job stays at the head (and the rest keep their
     * order) and the exception propagates, so a later reconnect resumes from exactly
     * where this attempt stopped — no reordering, no loss. Returns the number flushed.
     */
    suspend fun flush(submit: suspend (PendingJob) -> Unit): Int {
        var flushed = 0
        while (true) {
            val head = synchronized(this) { pending.firstOrNull() } ?: break
            submit(head) // may throw → head remains queued; order preserved
            synchronized(this) {
                // Remove only if the head is still the one we just submitted.
                if (pending.firstOrNull()?.seq == head.seq) pending.removeFirst()
            }
            flushed++
        }
        return flushed
    }
}
