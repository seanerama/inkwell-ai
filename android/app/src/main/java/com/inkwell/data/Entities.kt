package com.inkwell.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities for contract `ink-storage` v1, mirroring SPEC §4.1–4.5. Column names
 * are pinned with @ColumnInfo so the exported schema (android/app/schemas/) matches the
 * contract exactly. Ink is device-only; server ids (space/canvas) are stored as-is.
 *
 * Database version is 1 — the first, frozen schema. Every later change ships a
 * Migration; a destructive fallback is forbidden in release builds (contract).
 */

@Entity(tableName = "spaces")
data class SpaceEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "slug") val slug: String,
    @ColumnInfo(name = "system_prompt") val systemPrompt: String,
    @ColumnInfo(name = "tools") val tools: List<String>,
    @ColumnInfo(name = "model") val model: String,
    @ColumnInfo(name = "color") val color: String,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "canvases",
    indices = [Index("space_id")],
)
data class CanvasEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "space_id") val spaceId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "width_cu") val widthCu: Int = 2480,
    @ColumnInfo(name = "height_cu") val heightCu: Int = 3508,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "origin") val origin: String, // user | agent
    // Stage 11 (contract `ink-storage` v3, additive): the folder this canvas lives in
    // (null = the space root) and its Trash tombstone (null = live; set = soft-deleted,
    // purged 30 days later). Both nullable with a default so MIGRATION_2_3's `ADD COLUMN`
    // (no default) matches the entity — existing canvases stay at the root, undeleted.
    @ColumnInfo(name = "folder_id") val folderId: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    // Stage 22 (contract `ink-storage` v4, additive): the unread marker for a pushed
    // (agent-origin) canvas. `null` = never opened → shows a "New" dot / tab badge; set to
    // epoch-ms the first time the canvas is opened. Nullable with a default so
    // MIGRATION_3_4's `ADD COLUMN seen_at` (no default) aligns with the entity — every
    // existing canvas keeps `seen_at` NULL, which for a user-origin canvas is never read
    // (badges only count agent-origin unseen canvases). No v1/v2/v3 table is altered.
    @ColumnInfo(name = "seen_at") val seenAt: Long? = null,
)

/**
 * A folder inside a space (Stage 11, contract `ink-storage` v3, additive). Folders live
 * **inside** a space — one tree per space — so the file structure never leaks across
 * agent contexts (SPEC §2). [parentId] null = a root folder; [deletedAt] null = live, set
 * = soft-deleted (Trash, purged after 30 days). Added by [com.inkwell.data.InkDatabase]
 * MIGRATION_2_3; no v1/v2 table is altered destructively.
 */
@Entity(
    tableName = "folders",
    indices = [Index("space_id"), Index("parent_id")],
)
data class FolderEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "space_id") val spaceId: String,
    @ColumnInfo(name = "parent_id") val parentId: String? = null,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

@Entity(
    tableName = "layers",
    indices = [Index("canvas_id")],
)
data class LayerEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "canvas_id") val canvasId: String,
    @ColumnInfo(name = "z") val z: Int,
    @ColumnInfo(name = "owner") val owner: String, // user | agent
    @ColumnInfo(name = "type") val type: String, // ink | raster | annotation
    @ColumnInfo(name = "visible") val visible: Boolean = true,
    @ColumnInfo(name = "opacity") val opacity: Float = 1.0f,
    @ColumnInfo(name = "job_id") val jobId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "strokes",
    indices = [Index("layer_id")],
)
data class StrokeEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "layer_id") val layerId: String,
    @ColumnInfo(name = "tool") val tool: String, // pen | marker | eraser
    @ColumnInfo(name = "color") val color: String, // #RRGGBB
    @ColumnInfo(name = "width_cu") val widthCu: Float,
    @ColumnInfo(name = "points", typeAffinity = ColumnInfo.BLOB) val points: ByteArray,
    @ColumnInfo(name = "point_count") val pointCount: Int,
    @ColumnInfo(name = "bbox_x") val bboxX: Float,
    @ColumnInfo(name = "bbox_y") val bboxY: Float,
    @ColumnInfo(name = "bbox_w") val bboxW: Float,
    @ColumnInfo(name = "bbox_h") val bboxH: Float,
    @ColumnInfo(name = "created_at") val createdAt: Long, // epoch ms
) {
    // ByteArray needs value-based equals/hashCode for a data class.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StrokeEntity) return false
        return id == other.id &&
            layerId == other.layerId &&
            tool == other.tool &&
            color == other.color &&
            widthCu == other.widthCu &&
            points.contentEquals(other.points) &&
            pointCount == other.pointCount &&
            bboxX == other.bboxX && bboxY == other.bboxY &&
            bboxW == other.bboxW && bboxH == other.bboxH &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + layerId.hashCode()
        result = 31 * result + points.contentHashCode()
        result = 31 * result + pointCount
        return result
    }
}

/**
 * Per-card state persisted on the device (Stage 10, contract `ink-storage` v2, additive).
 * Lets a reopened canvas show done/dismissed cards correctly even offline. Keyed by the
 * server card [id]; [jobId] groups a job's cards; [updatedAt] is epoch ms of the last
 * local change. Added by [com.inkwell.data.InkDatabase] MIGRATION_1_2 — strokes and the
 * v1 tables are untouched.
 */
@Entity(
    tableName = "card_states",
    indices = [Index("job_id")],
)
data class CardStateEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "job_id") val jobId: String,
    @ColumnInfo(name = "state") val state: String, // open | done | dismissed
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "rasters",
    indices = [Index("layer_id")],
)
data class RasterEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "layer_id") val layerId: String,
    @ColumnInfo(name = "blob_uri") val blobUri: String,
    @ColumnInfo(name = "mime") val mime: String,
    @ColumnInfo(name = "page") val page: Int? = null,
    @ColumnInfo(name = "x_cu") val xCu: Float,
    @ColumnInfo(name = "y_cu") val yCu: Float,
    @ColumnInfo(name = "w_cu") val wCu: Float,
    @ColumnInfo(name = "h_cu") val hCu: Float,
)
