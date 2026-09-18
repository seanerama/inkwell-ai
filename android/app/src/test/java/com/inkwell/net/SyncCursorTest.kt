package com.inkwell.net

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The opaque sync cursor is passed back to the server verbatim on each poll
 * (contract device-api §Sync cursor), and the poller stops at the first terminal
 * state for its job.
 */
class SyncCursorTest {

    private fun job(id: String, status: String) = Job(
        id = id,
        spaceId = "s1",
        canvasId = null,
        direction = "to_agent",
        type = "system.ping",
        status = status,
        request = JsonObject(emptyMap()),
        result = if (status == "done") JsonObject(emptyMap()) else null,
        error = null,
        createdAt = "2026-09-15T00:00:00Z",
        updatedAt = "2026-09-15T00:00:01Z",
    )

    /** Records the cursor sent on each `/sync` call and replays scripted responses. */
    private class FakeApi(private val responses: ArrayDeque<SyncResponse>) : DeviceApi {
        val cursorsSent = mutableListOf<String?>()
        override suspend fun health() = error("unused")
        override suspend fun spaces() = error("unused")
        override suspend fun createSpace(body: SpaceCreateRequest): Space = error("unused")
        override suspend fun patchSpace(id: String, body: SpacePatchRequest): Space = error("unused")
        override suspend fun createJob(body: JobCreateRequest) = error("unused")
        override suspend fun getJob(id: String) = error("unused")
        override suspend fun cancelJob(id: String) = error("unused")
        override suspend fun sync(cursor: String?): SyncResponse {
            cursorsSent += cursor
            return responses.removeFirst()
        }
        override suspend fun patchCard(id: String, body: CardStateRequest) = error("unused")
        override suspend fun runCardAction(id: String, actionId: String) = error("unused")
        override suspend fun getCanvas(id: String) = error("unused")
        override suspend fun downloadBlob(url: String) = error("unused")
    }

    @Test
    fun poller_passes_cursor_back_verbatim_and_stops_at_terminal() = runTest {
        val fake = FakeApi(
            ArrayDeque(
                listOf(
                    SyncResponse(jobs = listOf(job("j1", "running")), cursor = "cursor-1"),
                    SyncResponse(jobs = listOf(job("j1", "done")), cursor = "cursor-2"),
                ),
            ),
        )
        val repo = DeviceRepository(fake)

        val terminal = repo.pollUntilTerminal(jobId = "j1", startCursor = "cursor-0")

        assertEquals("done", terminal.status)
        // First poll used the start cursor; the second used the first response's cursor
        // exactly as returned (verbatim), not a re-derived or mutated value.
        assertEquals(listOf("cursor-0", "cursor-1"), fake.cursorsSent)
    }

    @Test
    fun sync_returns_server_cursor_unchanged() = runTest {
        val fake = FakeApi(ArrayDeque(listOf(SyncResponse(jobs = emptyList(), cursor = "opaque=="))))
        val repo = DeviceRepository(fake)
        assertEquals("opaque==", repo.sync("prev").cursor)
        assertEquals(listOf("prev"), fake.cursorsSent)
    }
}
