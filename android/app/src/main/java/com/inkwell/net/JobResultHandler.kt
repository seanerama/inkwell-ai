package com.inkwell.net

import com.inkwell.contracts.AgentOutput
import com.inkwell.contracts.AgentOutputContract
import com.inkwell.contracts.Annotation
import com.inkwell.contracts.Card
import com.inkwell.contracts.CardKind
import com.inkwell.data.FormalizedCanvasStore
import com.inkwell.data.LayerEntity
import com.inkwell.data.LayerRepository
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

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
    /**
     * Stage 12 (`canvas.formalize`): the id of the NEW agent-origin canvas the redraw was
     * materialised into (the server's canvas id), or null for annotate/ask. The agent
     * layer above ([agentLayer]) belongs to THIS canvas, not the source.
     */
    val newCanvasId: String? = null,
    /**
     * Stage 12: the canvas the UI should open/navigate to after this outcome — the new
     * redraw canvas for `canvas.formalize`, null otherwise (stay on the current canvas).
     */
    val openCanvasId: String? = null,
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
class JobResultHandler(
    private val layerRepository: LayerRepository,
    /**
     * Stage 12 (Formalize): the seam that materialises a `canvas.formalize` redraw as a
     * local canvas. Injected the same way as [layerRepository], defaulted to null so
     * existing call sites and `JobResultHandlerTest` compile. When null (or the flag is
     * OFF — the ViewModel passes null then), a result that carries the server's `canvas`
     * sibling key falls through to the normal single-agent-layer path.
     */
    private val formalizedCanvasStore: FormalizedCanvasStore? = null,
) {

    suspend fun handle(job: Job, canvasId: String): LoopOutcome =
        if (job.status == "done") handleDone(job, canvasId) else handleFailed(job)

    private suspend fun handleDone(job: Job, canvasId: String): LoopOutcome {
        val output = parseResult(job)

        // Stage 12: a `canvas.formalize` result carries the server-added `canvas` sibling
        // key (NOT part of AgentOutput, so read from job.result directly). When present
        // and a store is wired, materialise the redraw as a NEW agent-origin canvas with
        // one agent layer (and no ink layer), and open it — the source is left untouched.
        val formalized = formalizedCanvasStore?.let { parseFormalizeCanvas(job) }
        if (formalized != null) {
            val layer = formalizedCanvasStore!!.createFormalizedCanvas(
                canvasId = formalized.id,
                spaceId = formalized.spaceId,
                title = formalized.title,
                widthCu = formalized.widthCu,
                heightCu = formalized.heightCu,
                sourceCanvasId = formalized.sourceCanvasId ?: job.canvasId,
                jobId = job.id,
            )
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
                newCanvasId = formalized.id,
                openCanvasId = formalized.id,
            )
        }

        // The ONLY write on the (non-formalize) done path: a single insert linked to the job.
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

    /** The server-added `canvas` / `source_canvas_id` sibling keys (Stage 12, formalize). */
    private data class FormalizedCanvas(
        val id: String,
        val spaceId: String,
        val title: String,
        val widthCu: Int,
        val heightCu: Int,
        val sourceCanvasId: String?,
    )

    /**
     * Read the server-added formalize sibling keys straight from `job.result`. These are
     * NOT part of [AgentOutput] (the model never produces them), so
     * [AgentOutputContract.parse] does not surface them — the raw `JsonObject` is read
     * here. Returns null when the `canvas` key is absent or malformed (annotate/ask, or a
     * server that predates formalize), so the caller falls back to the normal path.
     */
    private fun parseFormalizeCanvas(job: Job): FormalizedCanvas? {
        val result = job.result ?: return null
        val canvas = result["canvas"] as? JsonObject ?: return null
        return try {
            val sourceEl = result["source_canvas_id"]
            val sourceId =
                if (sourceEl == null || sourceEl is JsonNull) null else sourceEl.jsonPrimitive.content
            FormalizedCanvas(
                id = canvas.getValue("id").jsonPrimitive.content,
                spaceId = canvas.getValue("space_id").jsonPrimitive.content,
                title = canvas["title"]?.jsonPrimitive?.content ?: "",
                widthCu = canvas.getValue("width_cu").jsonPrimitive.int,
                heightCu = canvas.getValue("height_cu").jsonPrimitive.int,
                sourceCanvasId = sourceId,
            )
        } catch (_: Exception) {
            null
        }
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
