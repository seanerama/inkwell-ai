package com.inkwell.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwell.BuildConfig
import com.inkwell.data.CanvasEntity
import com.inkwell.data.CanvasRepository
import com.inkwell.data.FolderEntity
import com.inkwell.data.LibraryRepository
import com.inkwell.data.SpaceEntity
import com.inkwell.data.SpaceSync
import kotlinx.coroutines.launch

/**
 * State + actions for [LibraryScreen] (Stage 11). Drives the per-space folder tree over
 * [LibraryRepository]: list a folder's contents (subfolders first, then canvases newest
 * first — the ordering is the DAO's), navigate via breadcrumbs, create / rename / move /
 * delete-to-Trash / restore, and open a canvas (delegated to the host via a callback).
 *
 * The single seeded space is resolved once from [CanvasRepository.ensureSeededSpaceId];
 * the model is per-space from the start (the space tab bar stays Phase 3). Trash is
 * purged on creation (app start), using the repository's injected clock.
 */
class LibraryViewModel(
    private val library: LibraryRepository,
    private val canvasRepository: CanvasRepository,
    /**
     * Stage 14: mirrors the server's spaces + reconciles the local placeholder (ADR-0010).
     * Null in the flag-OFF path / lightweight tests — then the tab bar shows the single
     * seeded space and no refresh happens.
     */
    private val spaceSync: SpaceSync? = null,
    /** Stage 14: the active-space id persisted in prefs (see MainActivity's `inkwell_prefs`). */
    private val loadActiveSpaceId: () -> String? = { null },
    private val saveActiveSpaceId: (String) -> Unit = {},
    /** Stage 14 kill-switch; OFF restores the single-seeded-space Library. [BuildConfig.SPACES]. */
    private val spacesEnabled: Boolean = BuildConfig.SPACES,
) : ViewModel() {

    var ready by mutableStateOf(false)
        private set
    var spaceId by mutableStateOf<String?>(null)
        private set

    // --- Stage 14: the server-mirrored space tab bar (ADR-0010) ---

    /** Spaces for the tab bar, ordered by position (empty until loaded / under the flag-OFF path). */
    var spaces by mutableStateOf<List<SpaceEntity>>(emptyList())
        private set

    /** The selected tab's space id (drives content, "+", Trash, and move targets). */
    var activeSpaceId by mutableStateOf<String?>(null)
        private set

    /** Subtle hint: true only when spaces could not be synced AND none is cached (SPEC §, Stage 14). */
    var spacesNotSynced by mutableStateOf(false)
        private set

    /** The folder currently shown (null = space root). */
    var folderId by mutableStateOf<String?>(null)
        private set

    /** Path from the root to the current folder (empty at root); drives the breadcrumb. */
    val breadcrumb: SnapshotStateList<FolderEntity> = mutableStateListOf()

    var folders by mutableStateOf<List<FolderEntity>>(emptyList())
        private set
    var canvases by mutableStateOf<List<CanvasEntity>>(emptyList())
        private set

    /** All live folders in the space — the candidate targets for a Move… action. */
    var allFolders by mutableStateOf<List<FolderEntity>>(emptyList())
        private set

    var showTrash by mutableStateOf(false)
        private set
    var trashFolders by mutableStateOf<List<FolderEntity>>(emptyList())
        private set
    var trashCanvases by mutableStateOf<List<CanvasEntity>>(emptyList())
        private set

    init {
        viewModelScope.launch {
            if (spacesEnabled) {
                // Always have at least the unpaired placeholder so a tab shows offline.
                canvasRepository.ensureSeededSpaceId()
                // App-start trigger: mirror + reconcile (non-fatal — cached mirror otherwise).
                val ok = tryRefreshSpaces()
                loadSpaces()
                spacesNotSynced = !ok && spaces.size <= 1
                val saved = loadActiveSpaceId()
                val sid = spaces.firstOrNull { it.id == saved }?.id
                    ?: spaces.firstOrNull()?.id
                    ?: canvasRepository.ensureSeededSpaceId()
                activeSpaceId = sid
                spaceId = sid
            } else {
                val sid = canvasRepository.ensureSeededSpaceId()
                spaceId = sid
            }
            library.purgeExpiredTrash() // purge Trash > 30 days on app start
            refresh()
            ready = true
        }
    }

    /** Refresh the mirror, swallowing failures; true when the server responded. */
    private suspend fun tryRefreshSpaces(): Boolean {
        val sync = spaceSync ?: return false
        return try {
            sync.refresh()
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun loadSpaces() {
        spaces = canvasRepository.allSpaces()
    }

    private suspend fun refresh() {
        val sid = spaceId ?: return
        val contents = library.contents(sid, folderId)
        folders = contents.folders
        canvases = contents.canvases
        allFolders = library.liveFolders(sid)
    }

    private fun reload() {
        viewModelScope.launch { refresh() }
    }

    /** Enter a subfolder. */
    fun openFolder(folder: FolderEntity) {
        breadcrumb.add(folder)
        folderId = folder.id
        showTrash = false
        reload()
    }

    /**
     * Jump to a breadcrumb level: [index] -1 is the root; 0..n-1 select that crumb and
     * drop everything deeper.
     */
    fun navigateTo(index: Int) {
        while (breadcrumb.size > index + 1) breadcrumb.removeAt(breadcrumb.lastIndex)
        folderId = breadcrumb.lastOrNull()?.id
        showTrash = false
        reload()
    }

    fun openTrash() {
        val sid = spaceId ?: return
        showTrash = true
        viewModelScope.launch {
            val t = library.trash(sid)
            trashFolders = t.folders
            trashCanvases = t.canvases
        }
    }

    fun closeTrash() {
        showTrash = false
        reload()
    }

    private fun reloadTrash() {
        if (showTrash) openTrash()
    }

    /** Create a canvas in the current folder and hand its id back to open it. */
    fun createCanvas(onCreated: (String) -> Unit) {
        val sid = spaceId ?: return
        viewModelScope.launch {
            val canvas = library.createCanvas(sid, folderId)
            refresh()
            onCreated(canvas.id)
        }
    }

    fun createFolder(name: String) {
        val sid = spaceId ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            library.createFolder(sid, folderId, trimmed)
            refresh()
        }
    }

    fun renameFolder(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { library.renameFolder(id, trimmed); refresh() }
    }

    fun renameCanvas(id: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { library.renameCanvas(id, trimmed); refresh() }
    }

    fun moveCanvas(id: String, targetFolderId: String?) {
        viewModelScope.launch { library.moveCanvas(id, targetFolderId); refresh() }
    }

    fun moveFolder(id: String, targetParentId: String?) {
        // Guard against moving a folder into itself (its own subtree is left to the UI's
        // target list, which excludes the folder itself).
        if (id == targetParentId) return
        viewModelScope.launch { library.moveFolder(id, targetParentId); refresh() }
    }

    // --- Stage 14: tab bar + move between spaces (ADR-0010) ---

    /** Switch to a space tab: reset to its root, re-scope content/"+"/Trash, persist the choice. */
    fun selectSpace(id: String) {
        if (id == activeSpaceId) return
        activeSpaceId = id
        spaceId = id
        saveActiveSpaceId(id)
        breadcrumb.clear()
        folderId = null
        showTrash = false
        reload()
    }

    /** Pull-to-refresh trigger: re-mirror the server's spaces and reconcile, then reload. */
    fun refreshSpaces() {
        if (!spacesEnabled) return
        viewModelScope.launch {
            val ok = tryRefreshSpaces()
            loadSpaces()
            spacesNotSynced = !ok && spaces.size <= 1
            // Reconciliation may have repointed the active space onto a server id; remap.
            val ids = spaces.map { it.id }.toSet()
            if (activeSpaceId !in ids) {
                val sid = spaces.firstOrNull()?.id
                activeSpaceId = sid
                spaceId = sid
                sid?.let { saveActiveSpaceId(it) }
                breadcrumb.clear()
                folderId = null
                showTrash = false
            }
            refresh()
        }
    }

    /** Move a canvas to another space's root (Move… dialog "Other spaces"). */
    fun moveCanvasToSpace(id: String, targetSpaceId: String) {
        viewModelScope.launch { library.moveCanvasToSpace(id, targetSpaceId); refresh() }
    }

    /** Move a folder subtree to another space's root (Move… dialog "Other spaces"). */
    fun moveFolderToSpace(id: String, targetSpaceId: String) {
        val sid = spaceId ?: return
        if (sid == targetSpaceId) return
        viewModelScope.launch { library.moveFolderToSpace(sid, id, targetSpaceId); refresh() }
    }

    fun deleteCanvas(id: String) {
        viewModelScope.launch { library.deleteCanvas(id); refresh() }
    }

    fun deleteFolder(id: String) {
        val sid = spaceId ?: return
        viewModelScope.launch { library.deleteFolder(sid, id); refresh() }
    }

    fun restoreCanvas(id: String) {
        viewModelScope.launch { library.restoreCanvas(id); reloadTrash(); refresh() }
    }

    fun restoreFolder(id: String) {
        viewModelScope.launch { library.restoreFolder(id); reloadTrash(); refresh() }
    }

    fun purgeCanvasForever(id: String) {
        viewModelScope.launch { library.purgeCanvas(id); reloadTrash() }
    }

    fun purgeFolderForever(id: String) {
        viewModelScope.launch { library.purgeFolder(id); reloadTrash() }
    }
}
