package com.inkwell.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure sync-cadence state machine (SPEC §8 / contract `device-api` §Sync cursor):
 * 5 s foreground+outstanding, 60 s foreground+idle, 5 min backgrounded.
 */
class SyncCadenceTest {

    @Test
    fun foreground_with_outstanding_job_polls_every_5s() {
        assertEquals(
            5_000L,
            SyncCadence.intervalMs(AppLifecycle.FOREGROUND, hasOutstandingJob = true),
        )
    }

    @Test
    fun foreground_idle_polls_every_60s() {
        assertEquals(
            60_000L,
            SyncCadence.intervalMs(AppLifecycle.FOREGROUND, hasOutstandingJob = false),
        )
    }

    @Test
    fun backgrounded_polls_every_5min_regardless_of_outstanding() {
        assertEquals(
            300_000L,
            SyncCadence.intervalMs(AppLifecycle.BACKGROUND, hasOutstandingJob = false),
        )
        // Background wins even if a job is outstanding.
        assertEquals(
            300_000L,
            SyncCadence.intervalMs(AppLifecycle.BACKGROUND, hasOutstandingJob = true),
        )
    }

    @Test
    fun state_overload_matches_boolean_overload() {
        assertEquals(
            SyncCadence.intervalMs(AppLifecycle.FOREGROUND, true),
            SyncCadence.intervalMs(SyncCadence.State(AppLifecycle.FOREGROUND, true)),
        )
    }
}
