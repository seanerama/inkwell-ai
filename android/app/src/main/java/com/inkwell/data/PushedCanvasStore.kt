package com.inkwell.data

import com.inkwell.data.dao.CanvasDao
import com.inkwell.data.dao.FolderDao
import com.inkwell.data.dao.LayerDao
import com.inkwell.data.dao.RasterDao
import java.util.UUID

/**
 * The device-local persistence seam a pushed (`to_user`) job is materialised through
 * (Stage 22). Kept as an interface — the same pattern as [FormalizedCanvasStore] — so
 * [com.inkwell.net.PushInbox] stays JVM-unit-testable with a tiny in-memory fake (no
 * Room). [RoomPushedCanvasStore] implements it against the existing DAOs; there is **no
 * new table** (the v4 migration only adds `canvases.seen_at`).
 *
 * All writes are plain upserts keyed by the SERVER id, so re-materialising the same job
 * is idempotent by construction: [canvasExists] lets [com.inkwell.net.PushInbox] skip a
 * canvas (and its layers/rasters) it has already created, which is how dedupe-by-canvas-id
 * avoids a second dedupe table (contract note in the stage spec).
 */
interface PushedCanvasStore {
    /** True when a canvas with [canvasId] already exists (already materialised). */
    suspend fun canvasExists(canvasId: String): Boolean

    /** Create a folder named [name] at the root of [spaceId]; returns its new id. */
    suspend fun createFolder(spaceId: String, name: String): String

    /** Upsert a pushed (agent-origin) canvas by its server id. */
    suspend fun insertCanvas(canvas: CanvasEntity)

    /** Upsert a raster layer (`type=raster`, `z=-1`) by its server id. */
    suspend fun insertLayer(layer: LayerEntity)

    /** Upsert a raster row (its `blob_uri` is the LOCAL cache path after download). */
    suspend fun insertRaster(raster: RasterEntity)
}

/**
 * [PushedCanvasStore] over Room (contract `ink-storage` v4). Folder ids are generated
 * locally ([idGen]); canvas/layer/raster ids come from the server, so the device and
 * server agree on identity and re-materialisation is idempotent.
 */
class RoomPushedCanvasStore(
    private val canvasDao: CanvasDao,
    private val layerDao: LayerDao,
    private val folderDao: FolderDao,
    private val rasterDao: RasterDao,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : PushedCanvasStore {

    override suspend fun canvasExists(canvasId: String): Boolean =
        canvasDao.byId(canvasId) != null

    override suspend fun createFolder(spaceId: String, name: String): String {
        val now = clock()
        val id = idGen()
        folderDao.upsert(
            FolderEntity(
                id = id,
                spaceId = spaceId,
                parentId = null,
                name = name,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            ),
        )
        return id
    }

    override suspend fun insertCanvas(canvas: CanvasEntity) = canvasDao.upsert(canvas)

    override suspend fun insertLayer(layer: LayerEntity) = layerDao.upsert(layer)

    override suspend fun insertRaster(raster: RasterEntity) = rasterDao.upsert(raster)
}
