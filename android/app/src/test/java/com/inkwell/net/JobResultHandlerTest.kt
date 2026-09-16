package com.inkwell.net

import com.inkwell.data.FormalizedCanvasStore
import com.inkwell.data.LayerEntity
import com.inkwell.data.LayerRepository
import com.inkwell.data.dao.LayerDao
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `done`/`failed` result handling of SPEC §9.4, driven purely on the JVM with an
 * in-memory fake [LayerDao]:
 *  - a `done` job creates EXACTLY ONE new agent layer and never mutates an existing one;
 *  - a `failed` job creates NONE and surfaces the error card body.
 */
class JobResultHandlerTest {

    private class FakeLayerDao : LayerDao {
        val store = mutableListOf<LayerEntity>()
        var upsertCount = 0
        override suspend fun upsert(layer: LayerEntity) {
            upsertCount++
            store.removeAll { it.id == layer.id }
            store.add(layer)
        }
        override suspend fun forCanvas(canvasId: String): List<LayerEntity> =
            store.filter { it.canvasId == canvasId }.sortedBy { it.z }
    }

    /** Records the args and returns a canvas-scoped agent layer (Stage 12 formalize seam). */
    private class FakeFormalizedCanvasStore : FormalizedCanvasStore {
        data class Call(
            val canvasId: String,
            val spaceId: String,
            val title: String,
            val widthCu: Int,
            val heightCu: Int,
            val sourceCanvasId: String?,
            val jobId: String,
        )
        val calls = mutableListOf<Call>()
        override suspend fun createFormalizedCanvas(
            canvasId: String,
            spaceId: String,
            title: String,
            widthCu: Int,
            heightCu: Int,
            sourceCanvasId: String?,
            jobId: String,
        ): LayerEntity {
            calls += Call(canvasId, spaceId, title, widthCu, heightCu, sourceCanvasId, jobId)
            return LayerEntity(
                id = "agent-layer", canvasId = canvasId, z = 0, owner = "agent", type = "annotation",
                visible = true, opacity = 1.0f, jobId = jobId, createdAt = 1L,
            )
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun resultObject(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    private fun job(status: String, result: JsonObject?) = Job(
        id = "job-1",
        spaceId = "space-1",
        canvasId = "canvas-1",
        direction = "to_agent",
        type = "canvas.annotate",
        status = status,
        request = JsonObject(emptyMap()),
        result = result,
        error = null,
        createdAt = "2026-09-15T00:00:00Z",
        updatedAt = "2026-09-15T00:00:01Z",
    )

    private val doneResult = resultObject(
        """
        {
          "summary": "Highlighted the middle box.",
          "annotations": [
            { "id": "h1", "type": "highlight",
              "points": [[0.40,0.45],[0.60,0.45],[0.60,0.55],[0.40,0.55]] }
          ],
          "cards": [ { "kind": "answer", "title": "The middle box", "body": "It is the key step." } ],
          "brain_writes": [],
          "contract_version": "agent-output/v1"
        }
        """.trimIndent(),
    )

    @Test
    fun done_job_creates_exactly_one_agent_layer_and_surfaces_summary() = runTest {
        val dao = FakeLayerDao()
        val handler = JobResultHandler(LayerRepository(dao, idGen = { "agent-layer" }, clock = { 1L }))

        val outcome = handler.handle(job("done", doneResult), canvasId = "canvas-1")

        assertFalse(outcome.isError)
        assertEquals("Highlighted the middle box.", outcome.summary)
        assertEquals(listOf("The middle box"), outcome.cardTitles)
        // Stage 7: the full card (kind, title, body) is surfaced for the panel.
        assertEquals(1, outcome.cards.size)
        assertEquals(com.inkwell.contracts.CardKind.ANSWER, outcome.cards[0].kind)
        assertEquals("It is the key step.", outcome.cards[0].body)
        assertEquals(1, outcome.annotations.size)
        // Exactly one insert; the created layer is an agent/annotation layer linked to the job.
        assertEquals(1, dao.upsertCount)
        assertEquals(1, dao.store.size)
        assertNotNull(outcome.agentLayer)
        assertEquals("agent", outcome.agentLayer!!.owner)
        assertEquals("annotation", outcome.agentLayer!!.type)
        assertEquals("job-1", outcome.agentLayer!!.jobId)
        assertEquals(1.0f, outcome.agentLayer!!.opacity, 0.0f)
    }

    @Test
    fun done_job_never_mutates_an_existing_layer() = runTest {
        val dao = FakeLayerDao()
        // Seed an existing user/ink layer.
        val ink = LayerEntity(
            id = "ink", canvasId = "canvas-1", z = 0, owner = "user", type = "ink",
            visible = true, opacity = 1.0f, jobId = null, createdAt = 0L,
        )
        dao.upsert(ink)
        val baseline = dao.upsertCount

        val handler = JobResultHandler(LayerRepository(dao, idGen = { "agent-layer" }, clock = { 1L }))
        handler.handle(job("done", doneResult), canvasId = "canvas-1")

        // The seed plus exactly one new insert; the ink layer is byte-for-byte unchanged.
        assertEquals(baseline + 1, dao.upsertCount)
        assertEquals(ink, dao.store.single { it.id == "ink" })
        assertEquals(2, dao.store.size)
        // The agent layer stacks above the ink layer.
        assertEquals(1, dao.store.single { it.owner == "agent" }.z)
    }

    @Test
    fun failed_job_creates_no_layer_and_shows_the_error_card_body() = runTest {
        val dao = FakeLayerDao()
        val handler = JobResultHandler(LayerRepository(dao, idGen = { "agent-layer" }, clock = { 1L }))

        val failedResult = resultObject(
            """
            {
              "summary": "",
              "annotations": [],
              "cards": [ { "kind": "error", "title": "Over cap", "body": "Daily cap reached." } ],
              "brain_writes": []
            }
            """.trimIndent(),
        )
        val outcome = handler.handle(job("failed", failedResult), canvasId = "canvas-1")

        assertTrue(outcome.isError)
        assertNull(outcome.agentLayer)
        assertEquals(0, dao.upsertCount) // no layer created on failure
        assertEquals(0, dao.store.size)
        assertEquals("Over cap", outcome.errorTitle)
        assertEquals("Daily cap reached.", outcome.errorBody)
    }

    // --- Stage 12: canvas.formalize ---

    private val formalizeResult = resultObject(
        """
        {
          "summary": "Redrew the topology as three aligned boxes.",
          "annotations": [
            { "id": "b1", "type": "rect", "x": 0.1, "y": 0.1, "w": 0.2, "h": 0.1, "label": "Web" }
          ],
          "cards": [ { "kind": "answer", "title": "Cleaned up", "body": "Aligned the nodes." } ],
          "brain_writes": [],
          "contract_version": "agent-output/v1",
          "canvas": {
            "id": "srv-canvas-9", "space_id": "space-1", "title": "Topology — formalized",
            "width_cu": 1600, "height_cu": 1200, "origin": "agent",
            "created_at": "2026-09-16T00:00:00Z"
          },
          "source_canvas_id": "canvas-1"
        }
        """.trimIndent(),
    )

    private fun formalizeJob() = Job(
        id = "job-f", spaceId = "space-1", canvasId = "canvas-1", direction = "to_agent",
        type = "canvas.formalize", status = "done", request = JsonObject(emptyMap()),
        result = formalizeResult, error = null,
        createdAt = "2026-09-16T00:00:00Z", updatedAt = "2026-09-16T00:00:01Z",
    )

    @Test
    fun formalize_result_creates_a_new_agent_canvas_with_the_server_id_and_no_source_layer() = runTest {
        val dao = FakeLayerDao()
        val store = FakeFormalizedCanvasStore()
        val handler = JobResultHandler(
            LayerRepository(dao, idGen = { "agent-layer" }, clock = { 1L }),
            formalizedCanvasStore = store,
        )

        val outcome = handler.handle(formalizeJob(), canvasId = "canvas-1")

        // The redraw was materialised through the store with the SERVER's identity + dims.
        val call = store.calls.single()
        assertEquals("srv-canvas-9", call.canvasId)
        assertEquals("space-1", call.spaceId)
        assertEquals("Topology — formalized", call.title)
        assertEquals(1600, call.widthCu)
        assertEquals(1200, call.heightCu)
        assertEquals("canvas-1", call.sourceCanvasId) // the source, for its folder
        assertEquals("job-f", call.jobId)

        // No agent layer was created on the SOURCE canvas (its ink is untouched).
        assertEquals(0, dao.upsertCount)
        // The outcome points the UI at the new canvas and carries the redraw + answer card.
        assertEquals("srv-canvas-9", outcome.newCanvasId)
        assertEquals("srv-canvas-9", outcome.openCanvasId)
        assertEquals(1, outcome.annotations.size)
        assertEquals(listOf("Cleaned up"), outcome.cardTitles)
        assertEquals("srv-canvas-9", outcome.agentLayer!!.canvasId)
        assertFalse(outcome.isError)
    }

    @Test
    fun formalize_result_without_a_store_falls_back_to_the_normal_source_layer_path() = runTest {
        val dao = FakeLayerDao()
        // No store wired (flag OFF): the canvas sibling key is ignored, normal path runs.
        val handler = JobResultHandler(LayerRepository(dao, idGen = { "agent-layer" }, clock = { 1L }))

        val outcome = handler.handle(formalizeJob(), canvasId = "canvas-1")

        assertNull(outcome.newCanvasId)
        assertNull(outcome.openCanvasId)
        assertEquals(1, dao.upsertCount) // one agent layer on the source canvas
        assertEquals("canvas-1", outcome.agentLayer!!.canvasId)
    }

    @Test
    fun failed_job_with_no_result_falls_back_to_job_error() = runTest {
        val dao = FakeLayerDao()
        val handler = JobResultHandler(LayerRepository(dao, idGen = { "agent-layer" }, clock = { 1L }))
        val outcome = handler.handle(job("failed", null), canvasId = "canvas-1")
        assertTrue(outcome.isError)
        assertEquals(0, dao.upsertCount)
        assertNotNull(outcome.errorBody)
    }
}
