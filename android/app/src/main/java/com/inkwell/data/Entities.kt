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
