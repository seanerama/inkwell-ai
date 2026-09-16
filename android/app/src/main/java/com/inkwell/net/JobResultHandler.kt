package com.inkwell.net

import com.inkwell.contracts.AgentOutput
import com.inkwell.contracts.AgentOutputContract
import com.inkwell.contracts.Annotation
import com.inkwell.contracts.Card
import com.inkwell.contracts.CardKind
import com.inkwell.data.LayerEntity
import com.inkwell.data.LayerRepository

/**
 * Everything the UI needs after a job reaches a terminal state (SPEC §9.4 step 6 and the
 * `failed` path). Android-free so [JobResultHandler] is a JVM unit test.
 */
data class LoopOutcome(
    val status: String,
    /** done: the agent summary shown in the side panel; null on failure. */
    val summary: String?,
    /** done: the agent's cards (`kind`, `title`, `body`) for the panel; empty otherwise. */
    val cards: List<Card>,
    /**
     * The server's cards for this job (Stage 10, device-api additive `cards` field):
     * they carry identity + state + actions the device can change. Present on both the
     * `done` and `failed` paths (the server surfaces the error card here too), empty when
     * the server predates the field. The UI prefers these over [cards] when non-empty.
     */
    val serverCards: List<CardResponse> = emptyList(),
    /** done: annotations to render through [com.inkwell.render.AnnotationRenderer]. */
    val annotations: List<Annotation>,
    /** done: the single agent layer that was created; null when none was created. */
    val agentLayer: LayerEntity?,
    /** failed: the error card title. */
    val errorTitle: String?,
    /** failed: the error card body (Markdown); shown in the panel, no layer rendered. */
    val errorBody: String?,
    val isError: Boolean,
) {
    /** Card titles only (the Stage-6 surface), derived from [cards]. */
    val cardTitles: List<String> get() = cards.map { it.title }
}

/**
 * Turns a terminal [Job] into a [LoopOutcome], applying SPEC §9.4:
 *
 *  - **done**: parse the `agent-output` result (contract `agent-output`), create
 *    **exactly one** agent layer via [LayerRepository.createAgentLayer] (insert-only —
 *    an agent layer is never mutated after creation), and surface the summary, cards
 *    (kind/title/body), and annotations for the panel and the renderer.
 *  - **failed** (or any non-`done` terminal): surface the error card body and create
 *    **no** layer.
 *
 * The `done` path performs one and only one write to the layer store, so the
 * "exactly one new agent layer, never mutate an existing one" invariant holds by
 * construction and is asserted by `JobResultHandlerTest`.
 */
class JobResultHandler(private val layerRepository: LayerRepository) {

    suspend fun handle(job: Job, canvasId: String): LoopOutcome =
        if (job.status == "done") handleDone(job, canvasId) else handleFailed(job)

    private suspend fun handleDone(job: Job, canvasId: String): LoopOutcome {
        val output = parseResult(job)
        // The ONLY write on the done path: a single insert linked to the job.
        val layer = layerRepository.createAgentLayer(canvasId = canvasId, jobId = job.id)
        return LoopOutcome(
            status = "done",
            summary = output?.summary ?: "",
            cards = output?.cards ?: emptyList(),
            annotations = output?.annotations ?: emptyList(),
            agentLayer = layer,
            errorTitle = null,
            errorBody = null,
            isError = false,
            serverCards = job.cards,
        )
    }

    private fun handleFailed(job: Job): LoopOutcome {
        val output = parseResult(job)
        // Prefer an explicit error card; otherwise the first card, then the job error.
        val errorCard = output?.cards?.firstOrNull { it.kind == CardKind.ERROR }
            ?: output?.cards?.firstOrNull()
        return LoopOutcome(
            status = job.status,
            summary = null,
            cards = emptyList(),
            annotations = emptyList(),
            agentLayer = null, // no layer on failure
            errorTitle = errorCard?.title ?: "Job ${job.status}",
            errorBody = errorCard?.body ?: job.error ?: "The job did not complete.",
            isError = true,
            serverCards = job.cards,
        )
    }

    /** Parse `job.result` through the real contract parser; null when absent/invalid. */
    private fun parseResult(job: Job): AgentOutput? {
        val result = job.result ?: return null
        return try {
            // result carries the validated object plus the server's contract_version
            // sibling key; the parser ignores unknown keys (contract agent-output).
            AgentOutputContract.parse(result.toString())
        } catch (_: Exception) {
            null
        }
    }
}
