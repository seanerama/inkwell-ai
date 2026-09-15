package com.inkwell.net

/**
 * Orchestrates the send/poll/render loop of SPEC §9.4 for a single job, reusing the
 * Stage 2 spine ([DeviceRepository] for `POST /jobs` and `/sync` polling) and the
 * Stage 4/6 result handling ([JobResultHandler]).
 *
 * Android-free: the caller (the ViewModel) does the Android-only work — exporting the
 * PNG with `CanvasExporter` and building the request with [JobRequestBuilder] — and
 * hands the finished [JobCreateRequest] here. This keeps the loop itself exercisable by
 * the MockWebServer instrumented test without Compose.
 *
 * This is the reusable seam later job types (`canvas.ask`, `canvas.formalize`, …) build
 * on: only the request `type` and the result rendering differ.
 */
class LoopController(
    private val deviceRepository: DeviceRepository,
    private val jobResultHandler: JobResultHandler,
) {

    /**
     * Submit [request], then poll `/sync` until the job is terminal (SPEC §9.4 steps
     * 3–6), and produce the [LoopOutcome]. [onQueued] fires as soon as the job is
     * accepted (`queued`) so the UI can show its non-blocking indicator while the user
     * keeps drawing.
     *
     * The start cursor is captured *before* submitting (contract `device-api` §Sync
     * cursor) so the very first poll cannot miss a fast `queued → done` transition.
     */
    suspend fun run(
        request: JobCreateRequest,
        canvasId: String,
        onQueued: (Job) -> Unit = {},
    ): LoopOutcome {
        val startCursor = deviceRepository.sync(null).cursor
        val queued = deviceRepository.submitAgentJob(request)
        onQueued(queued)
        val terminal = deviceRepository.pollUntilTerminal(queued.id, startCursor)
        return jobResultHandler.handle(terminal, canvasId)
    }
}
