package com.inkwell.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.inkwell.data.CanvasEntity
import com.inkwell.data.LayerEntity
import com.inkwell.data.RasterEntity
import com.inkwell.data.SpaceEntity
import com.inkwell.data.StrokeEntity

@Dao
interface SpaceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(space: SpaceEntity)

    @Query("SELECT * FROM spaces ORDER BY position")
    suspend fun all(): List<SpaceEntity>

    @Query("SELECT * FROM spaces WHERE id = :id")
    suspend fun byId(id: String): SpaceEntity?
}

@Dao
interface CanvasDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(canvas: CanvasEntity)

    @Query("SELECT * FROM canvases WHERE space_id = :spaceId ORDER BY updated_at DESC")
    suspend fun forSpace(spaceId: String): List<CanvasEntity>

    @Query("SELECT * FROM canvases WHERE id = :id")
    suspend fun byId(id: String): CanvasEntity?
}

@Dao
interface LayerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(layer: LayerEntity)

    @Query("SELECT * FROM layers WHERE canvas_id = :canvasId ORDER BY z")
    suspend fun forCanvas(canvasId: String): List<LayerEntity>
}

@Dao
interface StrokeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stroke: StrokeEntity)

    @Query("SELECT * FROM strokes WHERE layer_id = :layerId ORDER BY created_at")
    suspend fun forLayer(layerId: String): List<StrokeEntity>

    @Query("SELECT COUNT(*) FROM strokes")
    suspend fun count(): Int
}

@Dao
interface RasterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(raster: RasterEntity)

    @Query("SELECT * FROM rasters WHERE layer_id = :layerId")
    suspend fun forLayer(layerId: String): List<RasterEntity>
}
