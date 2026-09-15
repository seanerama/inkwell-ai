package com.inkwell.data

import com.inkwell.data.dao.LayerDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit test for [LayerRepository.createAgentLayer] with an in-memory fake
 * [LayerDao] (no emulator needed). Verifies the agent layer is inserted as
 * `agent`/`annotation`, linked to the job, stacked above existing layers, at full
 * opacity (the 70% is applied per-annotation by AnnotationRenderer), and that the
 * repository only ever inserts an agent layer — never mutates it.
 */
class LayerRepositoryTest {

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

    private fun repo(dao: LayerDao, seedId: Int = 0): LayerRepository {
        var n = seedId
        return LayerRepository(dao, idGen = { "layer-${n++}" }, clock = { 1_000L })
    }

    @Test
    fun createAgentLayer_inserts_agent_annotation_linked_to_job() = runTest {
        val dao = FakeLayerDao()
        val layer = repo(dao).createAgentLayer(canvasId = "canvas-1", jobId = "job-1")

        assertEquals("agent", layer.owner)
        assertEquals("annotation", layer.type)
        assertEquals("job-1", layer.jobId)
        assertEquals("canvas-1", layer.canvasId)
        assertTrue(layer.visible)
        // Layer opacity stays 1.0; AnnotationRenderer applies the 70% (avoids compounding).
        assertEquals(1.0f, layer.opacity, 0.0f)
        // Exactly one write: an insert.
        assertEquals(1, dao.upsertCount)
        assertEquals(1, dao.store.size)
    }

    @Test
    fun agent_layer_stacks_above_existing_layers() = runTest {
        val dao = FakeLayerDao()
        // Seed an existing user/ink layer at z=0 (as CanvasRepository would).
        dao.upsert(
            LayerEntity(
                id = "ink", canvasId = "canvas-1", z = 0, owner = "user", type = "ink",
                visible = true, opacity = 1.0f, jobId = null, createdAt = 0L,
            ),
        )
        val baseline = dao.upsertCount

        val agent = repo(dao).createAgentLayer("canvas-1", "job-1")
        assertEquals("stacks above z=0", 1, agent.z)
        // The seed plus exactly one new insert.
        assertEquals(baseline + 1, dao.upsertCount)
    }

    @Test
    fun agent_layer_is_never_mutated_after_creation() = runTest {
        val dao = FakeLayerDao()
        val r = repo(dao)
        val created = r.createAgentLayer("canvas-1", "job-1")

        // The only writing entry point is createAgentLayer (an insert). Reloading the
        // layer returns exactly what was inserted — unchanged.
        val reloaded = dao.forCanvas("canvas-1").single { it.owner == "agent" }
        assertEquals(created, reloaded)
        assertEquals(1, dao.upsertCount) // no further writes occurred

        // The repository surface exposes no update/delete of an agent layer.
        val writeMethods = LayerRepository::class.java.declaredMethods
            .map { it.name }
            .filter { it.startsWith("update") || it.startsWith("delete") || it.startsWith("mutate") }
        assertEquals(emptyList<String>(), writeMethods)
    }
}
