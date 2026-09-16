package com.inkwell.net

import kotlinx.coroutines.delay
import retrofit2.HttpException

/**
 * Thin repository over [DeviceApi]: maps HTTP failures to [ApiException] via the
 * error envelope, and drives the `system.ping` round-trip by polling `/sync`.
 */
class DeviceRepository(private val api: DeviceApi) {

    /** Default foreground poll cadence while a job is outstanding (contract device-api §Sync). */
    val pollIntervalMs: Long = 5_000

    suspend fun health(): HealthResponse = apiCall { api.health() }

    suspend fun spaces(): List<Space> = apiCall { api.spaces() }

    suspend fun submitPing(): Job = apiCall { api.createJob(JobCreateRequest(type = "system.ping")) }

    /** Submit a pre-built `to_agent` job body (e.g. `canvas.annotate`). */
    suspend fun submitAgentJob(request: JobCreateRequest): Job = apiCall { api.createJob(request) }

    /**
     * The seeded `work` space id, looked up by slug (SPEC Phase 1: one hardcoded space).
     * The slug is stable across deploys; the UUID is not, so we never hardcode the id.
     * Returns null when the space is not present.
     */
    suspend fun workSpaceId(): String? = spaces().firstOrNull { it.slug == "work" }?.id

    suspend fun sync(cursor: String?): SyncResponse = apiCall { api.sync(cursor) }

    /** Stage 10: set a card's state (open|done|dismissed); returns the updated card. */
    suspend fun patchCard(cardId: String, state: String): CardResponse =
        apiCall { api.patchCard(cardId, CardStateRequest(state = state)) }

    /** Stage 10: invoke a card action by id; returns the updated card. */
    suspend fun runCardAction(cardId: String, actionId: String): CardResponse =
        apiCall { api.runCardAction(cardId, actionId) }

    /**
     * Poll `/sync` on [pollIntervalMs] cadence until the job with [jobId] is terminal
     * (`done`/`failed`/`cancelled`), starting from [startCursor]. The server cursor is
     * passed back verbatim on every poll (contract device-api §Sync cursor). [onPoll]
     * observes each returned cursor (used by the UI/tests).
     */
    suspend fun pollUntilTerminal(
        jobId: String,
        startCursor: String?,
        maxPolls: Int = Int.MAX_VALUE,
        onPoll: (String) -> Unit = {},
    ): Job {
        var cursor = startCursor
        var polls = 0
        while (polls < maxPolls) {
            val resp = sync(cursor)
            cursor = resp.cursor // verbatim, opaque
            onPoll(cursor)
            val match = resp.jobs.lastOrNull { it.id == jobId }
            if (match != null && match.isTerminal) return match
            polls++
            delay(pollIntervalMs)
        }
        throw IllegalStateException("job $jobId did not reach a terminal state within $maxPolls polls")
    }

    private suspend fun <T> apiCall(block: suspend () -> T): T =
        try {
            block()
        } catch (e: HttpException) {
            val raw = e.response()?.errorBody()?.string()
            throw ApiException(e.code(), ErrorEnvelopeParser.parse(raw))
        }
}
