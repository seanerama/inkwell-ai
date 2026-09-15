package com.inkwell.net

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline queue holds jobs created while offline and flushes them **in insertion
 * order** on reconnect (SPEC §9.5). A mid-flush failure must not reorder or drop the
 * remaining jobs.
 */
class OfflineJobQueueTest {

    private fun req(type: String) = JobCreateRequest(type = "canvas.annotate", instruction = type)

    @Test
    fun flushes_in_insertion_order_on_reconnect() = runTest {
        val queue = OfflineJobQueue()
        queue.enqueue(req("first"))
        queue.enqueue(req("second"))
        queue.enqueue(req("third"))

        val submitted = mutableListOf<String>()
        val flushed = queue.flush { pending -> submitted += pending.request.instruction!! }

        assertEquals(3, flushed)
        assertEquals(listOf("first", "second", "third"), submitted)
        assertTrue("queue empty after a full flush", queue.isEmpty())
    }

    @Test
    fun failure_midway_keeps_the_failed_and_remaining_jobs_in_order() = runTest {
        val queue = OfflineJobQueue()
        queue.enqueue(req("a"))
        queue.enqueue(req("b")) // this one fails
        queue.enqueue(req("c"))

        val submitted = mutableListOf<String>()
        try {
            queue.flush { pending ->
                val name = pending.request.instruction!!
                if (name == "b") throw RuntimeException("network down")
                submitted += name
            }
        } catch (_: RuntimeException) {
            // expected
        }

        // Only "a" went out; "b" and "c" remain, still in order.
        assertEquals(listOf("a"), submitted)
        assertEquals(listOf("b", "c"), queue.snapshot().map { it.request.instruction })

        // A later reconnect resumes from "b".
        val resumed = mutableListOf<String>()
        val n = queue.flush { pending -> resumed += pending.request.instruction!! }
        assertEquals(2, n)
        assertEquals(listOf("b", "c"), resumed)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun insertion_sequence_is_monotonic() {
        val queue = OfflineJobQueue()
        val a = queue.enqueue(req("a"))
        val b = queue.enqueue(req("b"))
        assertTrue(b.seq > a.seq)
    }
}
