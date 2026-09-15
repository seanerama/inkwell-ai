package com.inkwell.net

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
