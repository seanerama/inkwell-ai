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

    /**
     * Stage 15: create a space (a new agent). HTTP failures surface as [ApiException] carrying
     * the envelope code/message (e.g. `409 conflict` for a duplicate slug, `403 disabled` when
     * `SPACES_EDITABLE` is off).
     */
    suspend fun createSpace(body: SpaceCreateRequest): Space = apiCall { api.createSpace(body) }

    /**
     * Stage 15: partial-update a space. The [body] carries only the changed fields (unchanged
     * ones are null and omitted by `explicitNulls=false`). Errors surface as [ApiException]
     * (`403 disabled`, `404 not_found`, `422 validation`).
     */
    suspend fun patchSpace(id: String, body: SpacePatchRequest): Space = apiCall { api.patchSpace(id, body) }

    suspend fun submitPing(): Job = apiCall { api.createJob(JobCreateRequest(type = "system.ping")) }

    /** Submit a pre-built `to_agent` job body (e.g. `canvas.annotate`). */
    suspend fun submitAgentJob(request: JobCreateRequest): Job = apiCall { api.createJob(request) }

    /**
     * The seeded `work` space id, looked up by slug (SPEC Phase 1: one hardcoded space).
     * The slug is stable across deploys; the UUID is not, so we never hardcode the id.
     * Returns null when the space is not present.
     */
    suspend fun workSpaceId(): String? = spaces().firstOrNull { it.slug == "work" }?.id

    /** One job by id (contract device-api `GET /jobs/{id}`). */
    suspend fun getJob(id: String): Job = apiCall { api.getJob(id) }

    suspend fun sync(cursor: String?): SyncResponse = apiCall { api.sync(cursor) }

    /**
     * Stage 22: fetch a canvas with its layers + rasters (contract `GET /canvases/{id}`).
     * Used to obtain a FRESH signed raster `url` when a materialise download 403s on a stale
     * link (the rasters carry a 24 h link recomputed on every read).
     */
    suspend fun getCanvas(id: String): CanvasDetail = apiCall { api.getCanvas(id) }

    /**
     * Stage 22: download a blob by its full signed URL and return the raw bytes. Auth bearer
     * is added by the interceptor; the signature is already in the URL. HTTP failures (e.g.
     * `403` on an expired signature) surface as [ApiException] so the caller can retry with a
     * fresh `url` from [getCanvas].
     */
    suspend fun downloadBlob(url: String): ByteArray =
        apiCall { api.downloadBlob(url).use { it.bytes() } }

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
