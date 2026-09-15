package com.inkwell.data

import com.inkwell.data.dao.LayerDao
import java.util.UUID

/**
 * Device-local persistence for layers (contract `ink-storage`, Room v1 from Stage 2).
 * Reuses the existing `layers` table and [LayerDao] — **no Room version bump, no
 * migration** (the table already exists at v1).
 *
 * This stage adds agent/annotation layers, created from a finished `to_agent` job's
 * result. An agent layer is **created once and never mutated afterwards**: this
 * repository exposes only [createAgentLayer] (an insert) and read helpers — there is
 * deliberately no update/delete of an agent layer here, so immutability holds by
 * construction. Agent annotations are drawn by [com.inkwell.render.AnnotationRenderer].
 */
class LayerRepository(
    private val layerDao: LayerDao,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Insert a new `agent`/`annotation` layer for [canvasId] linked to [jobId], stacked
     * above every existing layer (highest `z + 1`), visible, and at full opacity — the
     * 70% agent transparency is applied per-annotation by
     * [com.inkwell.render.AnnotationRenderer], so the layer opacity is left at 1.0 to
     * avoid compounding it (SPEC §6.3).
     *
     * Returns the inserted [LayerEntity]. The layer is never mutated after this call.
     */
    suspend fun createAgentLayer(canvasId: String, jobId: String): LayerEntity {
        val existing = layerDao.forCanvas(canvasId)
        val nextZ = (existing.maxOfOrNull { it.z } ?: -1) + 1
        val layer = LayerEntity(
            id = idGen(),
            canvasId = canvasId,
            z = nextZ,
            owner = "agent",
            type = "annotation",
            visible = true,
            opacity = 1.0f,
            jobId = jobId,
            createdAt = clock(),
        )
        layerDao.upsert(layer)
        return layer
    }

    /** Layers for a canvas, ascending `z` (read-only helper). */
    suspend fun layersFor(canvasId: String): List<LayerEntity> = layerDao.forCanvas(canvasId)
}
