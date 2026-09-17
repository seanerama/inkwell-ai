package com.inkwell.data

import com.inkwell.data.dao.CanvasDao
import com.inkwell.data.dao.FolderDao
import com.inkwell.data.dao.LayerDao
import java.util.UUID

/** A folder's live contents for the Library grid: subfolders first, then canvases. */
data class LibraryContents(
    val spaceId: String,
    val folderId: String?,
    val folders: List<FolderEntity>,
    val canvases: List<CanvasEntity>,
)

/** The space's Trash: soft-deleted folders and canvases, newest-trashed first. */
data class TrashContents(
    val folders: List<FolderEntity>,
    val canvases: List<CanvasEntity>,
)

/**
 * Device-local Library persistence (Stage 11, contract `ink-storage` v3, additive).
 *
 * Folders live **inside** a space (one tree per space, SPEC §2). Everything is scoped to
 * a `spaceId` passed by the caller (resolved once via
 * [CanvasRepository.ensureSeededSpaceId]). Deletes are soft — a `deleted_at` tombstone
 * (Trash) that [purgeExpiredTrash] hard-deletes 30 days later. Ink is never touched here:
 * a canvas keeps its layers and strokes across move / trash / restore (contract invariant
 * 1: ink is vectors). Constructor-injected [idGen]/[clock] mirror [CanvasRepository] so
 * JVM tests can control ids and time.
 */
class LibraryRepository(
    private val folderDao: FolderDao,
    private val canvasDao: CanvasDao,
    private val layerDao: LayerDao,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * Runs a block atomically (Stage 14 move-between-spaces). The app wires
     * `db.withTransaction { }`; the default runs the block inline so JVM tests with fake
     * DAOs need no real database (the move is a small, ordered set of row updates).
     */
    private val runInTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
) {

    /** List a folder's live contents (null [folderId] = the space root). */
    suspend fun contents(spaceId: String, folderId: String?): LibraryContents =
        LibraryContents(
            spaceId = spaceId,
            folderId = folderId,
            folders = folderDao.childrenOf(spaceId, folderId),
            canvases = canvasDao.inFolder(spaceId, folderId),
        )

    /** Every live (non-deleted) folder in the space — used to build Move… targets. */
    suspend fun liveFolders(spaceId: String): List<FolderEntity> =
        folderDao.allForSpace(spaceId).filter { it.deletedAt == null }.sortedBy { it.name }

    /** The space's Trash (soft-deleted folders and canvases). */
    suspend fun trash(spaceId: String): TrashContents =
        TrashContents(
            folders = folderDao.trashed(spaceId),
            canvases = canvasDao.trashed(spaceId),
        )

    /**
     * Create a canvas in [folderId] (null = root): default A4 (2480×3508), one seeded
     * `user`/`ink` layer, and a unique "Untitled N" title. Returns the new canvas.
     */
    suspend fun createCanvas(spaceId: String, folderId: String?): CanvasEntity {
        val now = clock()
        val title = nextUntitledTitle(canvasDao.allForSpace(spaceId).map { it.title })
        val canvas = CanvasEntity(
            id = idGen(),
            spaceId = spaceId,
            title = title,
            widthCu = CanvasRepository.DEFAULT_WIDTH_CU,
            heightCu = CanvasRepository.DEFAULT_HEIGHT_CU,
            createdAt = now,
            updatedAt = now,
            origin = "user",
            folderId = folderId,
            deletedAt = null,
        )
        canvasDao.upsert(canvas)
        layerDao.upsert(
            LayerEntity(
                id = idGen(),
                canvasId = canvas.id,
                z = 0,
                owner = "user",
                type = "ink",
                visible = true,
                opacity = 1.0f,
                jobId = null,
                createdAt = now,
            ),
        )
        return canvas
    }

    /** Create a folder under [parentId] (null = root). */
    suspend fun createFolder(spaceId: String, parentId: String?, name: String): FolderEntity {
        val now = clock()
        val folder = FolderEntity(
            id = idGen(),
            spaceId = spaceId,
            parentId = parentId,
            name = name,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        folderDao.upsert(folder)
        return folder
    }

    /** Rename a folder (bumps `updated_at`). */
    suspend fun renameFolder(id: String, name: String) = folderDao.rename(id, name, clock())

    /** Reparent a folder (bumps `updated_at`). */
    suspend fun moveFolder(id: String, newParentId: String?) =
        folderDao.move(id, newParentId, clock())

    /**
     * Delete a folder to Trash: soft-delete the folder and its whole subtree (subfolders
     * and their canvases), so nothing dangles at a now-hidden parent. Ink is untouched —
     * a restore brings the canvas back exactly as it was.
     */
    suspend fun deleteFolder(spaceId: String, folderId: String) {
        val now = clock()
        val childrenByParent = folderDao.allForSpace(spaceId).groupBy { it.parentId }
        val subtree = mutableListOf<String>()
        val stack = ArrayDeque<String>()
        stack.addLast(folderId)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            subtree.add(id)
            childrenByParent[id]?.forEach { stack.addLast(it.id) }
        }
        for (fid in subtree) {
            canvasDao.trashCanvasesInFolder(spaceId, fid, now)
            folderDao.setDeletedAt(fid, now)
        }
    }

    /** Move a canvas into [folderId] (null = root); bumps `updated_at`. Strokes untouched. */
    suspend fun moveCanvas(id: String, folderId: String?) = canvasDao.move(id, folderId, clock())

    // --- Stage 14 (ADR-0010): move a canvas / folder subtree to ANOTHER space's root ---

    /**
     * Move a canvas to [targetSpaceId]'s root: set `space_id` and clear `folder_id` (a folder
     * id from the old space is meaningless in the new one). Cards and layers are untouched —
     * ink rides along with the canvas. Agent-origin canvases may now differ from the server's
     * `canvases.space_id`; accepted per ADR-0010 (Phase 4 reconciles).
     */
    suspend fun moveCanvasToSpace(id: String, targetSpaceId: String) =
        canvasDao.moveToSpace(id, targetSpaceId, clock())

    /**
     * Move a whole folder subtree to [targetSpaceId]'s root. The moved folder is reparented to
     * the target root (`parent_id = null`); every descendant folder and canvas — INCLUDING
     * trashed ones — has its `space_id` rewritten while keeping the subtree's internal shape.
     * Reuses the [deleteFolder] subtree walk and runs in one transaction so a partial move can
     * never strand ink under a space that no longer owns its parent. No-op onto the same space.
     */
    suspend fun moveFolderToSpace(spaceId: String, folderId: String, targetSpaceId: String) {
        if (spaceId == targetSpaceId) return
        val now = clock()
        val childrenByParent = folderDao.allForSpace(spaceId).groupBy { it.parentId }
        val subtree = mutableListOf<String>()
        val stack = ArrayDeque<String>()
        stack.addLast(folderId)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            subtree.add(id)
            childrenByParent[id]?.forEach { stack.addLast(it.id) }
        }
        val subtreeSet = subtree.toSet()
        val canvasesToMove = canvasDao.allForSpace(spaceId).filter { it.folderId in subtreeSet }
        runInTransaction {
            // The moved root: reparent to the target space root, keeping the tree below it.
            folderDao.moveToSpace(folderId, targetSpaceId, null, now)
            subtree.forEach { fid -> if (fid != folderId) folderDao.setSpace(fid, targetSpaceId, now) }
            canvasesToMove.forEach { c -> canvasDao.setSpace(c.id, targetSpaceId, now) }
        }
    }

    /** Rename a canvas (bumps `updated_at`). */
    suspend fun renameCanvas(id: String, title: String) = canvasDao.rename(id, title, clock())

    /** Soft-delete a canvas to Trash. */
    suspend fun deleteCanvas(id: String) = canvasDao.setDeletedAt(id, clock())

    /** Restore a soft-deleted canvas from Trash. */
    suspend fun restoreCanvas(id: String) = canvasDao.setDeletedAt(id, null)

    /** Restore a soft-deleted folder from Trash. */
    suspend fun restoreFolder(id: String) = folderDao.setDeletedAt(id, null)

    /** Hard-delete a canvas from Trash ("Delete forever"). */
    suspend fun purgeCanvas(id: String) = canvasDao.hardDelete(id)

    /** Hard-delete a folder from Trash ("Delete forever"). */
    suspend fun purgeFolder(id: String) = folderDao.hardDelete(id)

    /**
     * Purge Trash entries whose `deleted_at` is older than 30 days (call on app start).
     * Uses the injected [clock] so a JVM test can advance time.
     */
    suspend fun purgeExpiredTrash() {
        val cutoff = clock() - TRASH_TTL_MS
        canvasDao.purgeTrashedBefore(cutoff)
        folderDao.purgeTrashedBefore(cutoff)
    }

    companion object {
        /** Trash time-to-live: 30 days in milliseconds. */
        const val TRASH_TTL_MS = 30L * 24 * 60 * 60 * 1000

        private val UNTITLED = Regex("^Untitled (\\d+)$")

        /**
         * The next non-colliding "Untitled N" title given [existing] titles in the space.
         * N is one past the highest existing "Untitled N" (1 when there is none), so a
         * freshly created canvas never shares a title with a live or trashed one.
         */
        fun nextUntitledTitle(existing: List<String>): String {
            val max = existing.mapNotNull { UNTITLED.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                .maxOrNull() ?: 0
            return "Untitled ${max + 1}"
        }
    }
}
