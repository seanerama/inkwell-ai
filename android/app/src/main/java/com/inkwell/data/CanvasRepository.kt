package com.inkwell.data

import com.inkwell.data.dao.CanvasDao
import com.inkwell.data.dao.LayerDao
import com.inkwell.data.dao.SpaceDao
import com.inkwell.data.dao.StrokeDao
import com.inkwell.ink.BuiltStroke
import java.util.UUID

/**
 * The default canvas and its loaded strokes (Phase 0: one canvas, no server).
 */
data class CanvasState(
    val spaceId: String,
    val canvasId: String,
    val inkLayerId: String,
    val widthCu: Int,
    val heightCu: Int,
    val strokes: List<StrokeEntity>,
    // Stage 11: the canvas title (shown/edited in the canvas top bar) and the folder it
    // lives in (null = space root), so Back returns to the folder it was opened from.
    val title: String = "",
    val folderId: String? = null,
    // Stage 12: the canvas origin ("user"|"agent"). An agent-origin canvas (a Formalize
    // redraw) renders its agent layer opaque/ink-black rather than 70%/accent (SPEC §6.3).
    val origin: String = "user",
    // Stage 14: the accent colour of the canvas's space (#RRGGBB), for agent marks (SPEC §6.3).
    // Null when the space row is missing → the renderer falls back to DEFAULT_ACCENT.
    val spaceColor: String? = null,
)

/**
 * Stage 12 (Formalize): the seam [com.inkwell.net.JobResultHandler] uses to materialise
 * an agent redraw as a local canvas, kept as an interface so the handler stays JVM
 * unit-testable with a tiny fake (no Room). [com.inkwell.data.CanvasRepository]
 * implements it against Room.
 */
interface FormalizedCanvasStore {
    /**
     * Create a LOCAL canvas for a `canvas.formalize` redraw using the SERVER's [canvasId]
     * (so device and server agree on identity), `origin="agent"`, in the SAME folder as
     * [sourceCanvasId] (null → space root), with exactly ONE `agent`/`annotation` layer
     * linked to [jobId] and NO user/ink layer (SPEC §4.3). Idempotent by (canvas, job):
     * re-applying the same finished job returns the existing agent layer. Returns the
     * created (or existing) agent layer.
     */
    suspend fun createFormalizedCanvas(
        canvasId: String,
        spaceId: String,
        title: String,
        widthCu: Int,
        heightCu: Int,
        sourceCanvasId: String?,
        jobId: String,
    ): LayerEntity
}

/**
 * Device-local persistence for ink (contract `ink-storage`, Room v1 from Stage 2).
 *
 * On first launch it creates a default space, a default 2480×3508 canvas, and one
 * `user`/`ink` layer (SPEC §4.2–4.3: a canvas always has at least one user/ink
 * layer). Strokes are inserted on pen-up and reloaded when the canvas opens, so ink
 * survives an app restart. No Room version bump — the frozen v1 schema is reused.
 */
class CanvasRepository(
    private val spaceDao: SpaceDao,
    private val canvasDao: CanvasDao,
    private val layerDao: LayerDao,
    private val strokeDao: StrokeDao,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : FormalizedCanvasStore {

    /** Ensure the default space/canvas/ink-layer exist, then load the canvas + strokes. */
    suspend fun openDefaultCanvas(): CanvasState {
        val space = ensureDefaultSpace()
        val canvas = ensureDefaultCanvas(space.id)
        val inkLayer = ensureInkLayer(canvas.id)
        val strokes = strokeDao.forLayer(inkLayer.id)
        return CanvasState(
            spaceId = space.id,
            canvasId = canvas.id,
            inkLayerId = inkLayer.id,
            widthCu = canvas.widthCu,
            heightCu = canvas.heightCu,
            strokes = strokes,
            title = canvas.title,
            folderId = canvas.folderId,
            origin = canvas.origin,
            spaceColor = spaceDao.byId(space.id)?.color,
        )
    }

    /**
     * Open a specific canvas by id (Stage 11 Library flow): ensure it has a `user`/`ink`
     * layer, then load the canvas + its strokes. Returns null when no such canvas exists.
     * The flag-OFF path keeps using [openDefaultCanvas]; this is the tile-open path.
     */
    suspend fun openCanvas(canvasId: String): CanvasState? {
        val canvas = canvasDao.byId(canvasId) ?: return null
        val inkLayer = ensureInkLayer(canvas.id)
        val strokes = strokeDao.forLayer(inkLayer.id)
        return CanvasState(
            spaceId = canvas.spaceId,
            canvasId = canvas.id,
            inkLayerId = inkLayer.id,
            widthCu = canvas.widthCu,
            heightCu = canvas.heightCu,
            strokes = strokes,
            title = canvas.title,
            folderId = canvas.folderId,
            origin = canvas.origin,
            spaceColor = spaceDao.byId(canvas.spaceId)?.color,
        )
    }

    /**
     * Stage 12 (Formalize): materialise an agent redraw as a local canvas — see
     * [FormalizedCanvasStore.createFormalizedCanvas]. Uses the server's [canvasId], sets
     * `origin="agent"`, inherits the source canvas's folder, and creates exactly one
     * `agent`/`annotation` layer (no ink layer). Idempotent by (canvas, job).
     */
    override suspend fun createFormalizedCanvas(
        canvasId: String,
        spaceId: String,
        title: String,
        widthCu: Int,
        heightCu: Int,
        sourceCanvasId: String?,
        jobId: String,
    ): LayerEntity {
        val now = clock()
        val folderId = sourceCanvasId?.let { canvasDao.byId(it)?.folderId }
        canvasDao.upsert(
            CanvasEntity(
                id = canvasId,
                spaceId = spaceId,
                title = title,
                widthCu = widthCu,
                heightCu = heightCu,
                createdAt = now,
                updatedAt = now,
                origin = "agent",
                folderId = folderId,
            ),
        )
        // Idempotent: reuse an existing agent layer for this job rather than duplicating.
        val existing = layerDao.forCanvas(canvasId)
        existing.firstOrNull { it.owner == "agent" && it.jobId == jobId }?.let { return it }
        val layer = LayerEntity(
            id = idGen(),
            canvasId = canvasId,
            z = (existing.maxOfOrNull { it.z } ?: -1) + 1,
            owner = "agent",
            type = "annotation",
            visible = true,
            opacity = 1.0f,
            jobId = jobId,
            createdAt = now,
        )
        layerDao.upsert(layer)
        return layer
    }

    /** Rename a canvas (Stage 11 title edit); bumps `updated_at` via the injected clock. */
    suspend fun renameCanvas(canvasId: String, title: String) {
        canvasDao.rename(canvasId, title, clock())
    }

    suspend fun loadStrokes(layerId: String): List<StrokeEntity> = strokeDao.forLayer(layerId)

    /** Insert one committed stroke on pen-up; returns the persisted entity. */
    suspend fun insertStroke(
        layerId: String,
        commit: StrokeCommitData,
    ): StrokeEntity {
        val entity = toStrokeEntity(
            id = idGen(),
            layerId = layerId,
            built = commit.stroke,
            tool = commit.tool,
            colorHex = commit.colorHex,
            widthCu = commit.widthCu,
            createdAt = clock(),
        )
        strokeDao.insert(entity)
        return entity
    }

    /** Remove a stroke by id (undo-last-stroke and eraser). */
    suspend fun deleteStroke(id: String) = strokeDao.deleteById(id)

    /**
     * Ensure the single seeded space exists and return its id (Stage 11 Library flow).
     * Reuses [ensureDefaultSpace] so the Library and the flag-OFF canvas path share one
     * seeded space rather than each creating its own.
     */
    suspend fun ensureSeededSpaceId(): String = ensureDefaultSpace().id

    /** Stage 14: all spaces ordered by position (the tab bar's source). */
    suspend fun allSpaces(): List<SpaceEntity> = spaceDao.all()

    /** Stage 14: the current `space_id` of a canvas (reflects any reconciliation). */
    suspend fun spaceIdForCanvas(canvasId: String): String? = canvasDao.byId(canvasId)?.spaceId

    private suspend fun ensureDefaultSpace(): SpaceEntity {
        spaceDao.all().firstOrNull()?.let { return it }
        val space = SpaceEntity(
            id = idGen(),
            name = "Work",
            slug = "work",
            systemPrompt = "",
            tools = emptyList(),
            model = "claude-sonnet-5",
            color = "#3B6EA5",
            position = 0,
            createdAt = clock(),
        )
        spaceDao.upsert(space)
        return space
    }

    private suspend fun ensureDefaultCanvas(spaceId: String): CanvasEntity {
        canvasDao.forSpace(spaceId).firstOrNull()?.let { return it }
        val now = clock()
        val canvas = CanvasEntity(
            id = idGen(),
            spaceId = spaceId,
            title = "Canvas",
            widthCu = DEFAULT_WIDTH_CU,
            heightCu = DEFAULT_HEIGHT_CU,
            createdAt = now,
            updatedAt = now,
            origin = "user",
        )
        canvasDao.upsert(canvas)
        return canvas
    }

    private suspend fun ensureInkLayer(canvasId: String): LayerEntity {
        layerDao.forCanvas(canvasId).firstOrNull { it.owner == "user" && it.type == "ink" }
            ?.let { return it }
        val layer = LayerEntity(
            id = idGen(),
            canvasId = canvasId,
            z = 0,
            owner = "user",
            type = "ink",
            visible = true,
            opacity = 1.0f,
            jobId = null,
            createdAt = clock(),
        )
        layerDao.upsert(layer)
        return layer
    }

    companion object {
        const val DEFAULT_WIDTH_CU = 2480
        const val DEFAULT_HEIGHT_CU = 3508

        /**
         * Pure mapping from a built stroke to a contract `ink-storage` [StrokeEntity].
         * Points are packed stride-5 little-endian so the invariant
         * `point_count * 5 * 4 == length(points)` holds by construction.
         */
        fun toStrokeEntity(
            id: String,
            layerId: String,
            built: BuiltStroke,
            tool: String,
            colorHex: String,
            widthCu: Float,
            createdAt: Long,
        ): StrokeEntity = StrokeEntity(
            id = id,
            layerId = layerId,
            tool = tool,
            color = colorHex,
            widthCu = widthCu,
            points = PackedPoints.encode(built.points),
            pointCount = built.pointCount,
            bboxX = built.bboxX,
            bboxY = built.bboxY,
            bboxW = built.bboxW,
            bboxH = built.bboxH,
            createdAt = createdAt,
        )
    }
}

/** Plain data carried from the capture layer to persistence (mirror of StrokeCommit). */
data class StrokeCommitData(
    val stroke: BuiltStroke,
    val tool: String,
    val colorHex: String,
    val widthCu: Float,
)
