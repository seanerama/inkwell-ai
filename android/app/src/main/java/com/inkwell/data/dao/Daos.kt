package com.inkwell.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.inkwell.data.CanvasEntity
import com.inkwell.data.CardStateEntity
import com.inkwell.data.FolderEntity
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

    // --- Stage 11 (Library): folder-scoped listing, Trash, move/rename/purge ---

    /**
     * Live canvases (deleted_at IS NULL) in a folder of a space, newest first.
     * A null [folderId] scopes to the space root (folder_id IS NULL).
     */
    @Query(
        "SELECT * FROM canvases WHERE space_id = :spaceId AND deleted_at IS NULL AND " +
            "((:folderId IS NULL AND folder_id IS NULL) OR folder_id = :folderId) " +
            "ORDER BY updated_at DESC",
    )
    suspend fun inFolder(spaceId: String, folderId: String?): List<CanvasEntity>

    /** Soft-deleted (Trash) canvases for a space, most-recently-trashed first. */
    @Query(
        "SELECT * FROM canvases WHERE space_id = :spaceId AND deleted_at IS NOT NULL " +
            "ORDER BY deleted_at DESC",
    )
    suspend fun trashed(spaceId: String): List<CanvasEntity>

    /** All canvases for a space, deleted or not — used to pick a unique "Untitled N". */
    @Query("SELECT * FROM canvases WHERE space_id = :spaceId")
    suspend fun allForSpace(spaceId: String): List<CanvasEntity>

    @Query("UPDATE canvases SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)

    @Query("UPDATE canvases SET folder_id = :folderId, updated_at = :updatedAt WHERE id = :id")
    suspend fun move(id: String, folderId: String?, updatedAt: Long)

    @Query("UPDATE canvases SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun setDeletedAt(id: String, deletedAt: Long?)

    /** Soft-delete every live canvas in a folder (used when a folder is trashed). */
    @Query(
        "UPDATE canvases SET deleted_at = :deletedAt WHERE space_id = :spaceId AND " +
            "deleted_at IS NULL AND folder_id = :folderId",
    )
    suspend fun trashCanvasesInFolder(spaceId: String, folderId: String, deletedAt: Long)

    @Query("DELETE FROM canvases WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
    suspend fun purgeTrashedBefore(cutoff: Long)

    /** Hard-delete a canvas row (Trash "Delete forever"). */
    @Query("DELETE FROM canvases WHERE id = :id")
    suspend fun hardDelete(id: String)
}

@Dao
interface FolderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: FolderEntity)

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun byId(id: String): FolderEntity?

    /**
     * Live subfolders (deleted_at IS NULL) of a folder in a space, by name.
     * A null [parentId] scopes to the space root (parent_id IS NULL).
     */
    @Query(
        "SELECT * FROM folders WHERE space_id = :spaceId AND deleted_at IS NULL AND " +
            "((:parentId IS NULL AND parent_id IS NULL) OR parent_id = :parentId) " +
            "ORDER BY name",
    )
    suspend fun childrenOf(spaceId: String, parentId: String?): List<FolderEntity>

    /** All folders for a space, deleted or not — used to walk a subtree. */
    @Query("SELECT * FROM folders WHERE space_id = :spaceId")
    suspend fun allForSpace(spaceId: String): List<FolderEntity>

    /** Soft-deleted (Trash) folders for a space, most-recently-trashed first. */
    @Query(
        "SELECT * FROM folders WHERE space_id = :spaceId AND deleted_at IS NOT NULL " +
            "ORDER BY deleted_at DESC",
    )
    suspend fun trashed(spaceId: String): List<FolderEntity>

    @Query("UPDATE folders SET name = :name, updated_at = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, name: String, updatedAt: Long)

    @Query("UPDATE folders SET parent_id = :parentId, updated_at = :updatedAt WHERE id = :id")
    suspend fun move(id: String, parentId: String?, updatedAt: Long)

    @Query("UPDATE folders SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun setDeletedAt(id: String, deletedAt: Long?)

    @Query("DELETE FROM folders WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
    suspend fun purgeTrashedBefore(cutoff: Long)

    /** Hard-delete a folder row (Trash "Delete forever"). */
    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun hardDelete(id: String)
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

    @Query("SELECT * FROM strokes WHERE id = :id")
    suspend fun byId(id: String): StrokeEntity?

    @Query("DELETE FROM strokes WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM strokes")
    suspend fun count(): Int
}

@Dao
interface CardStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cardState: CardStateEntity)

    @Query("SELECT * FROM card_states WHERE job_id = :jobId")
    suspend fun forJob(jobId: String): List<CardStateEntity>

    @Query("SELECT * FROM card_states WHERE id = :id")
    suspend fun byId(id: String): CardStateEntity?
}

@Dao
interface RasterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(raster: RasterEntity)

    @Query("SELECT * FROM rasters WHERE layer_id = :layerId")
    suspend fun forLayer(layerId: String): List<RasterEntity>
}
