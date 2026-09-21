package com.inkwell.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwell.net.BrainCreate
import com.inkwell.net.BrainEntry
import com.inkwell.net.DeviceRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * State + actions for [BrainScreen] (Stage 27, SPEC §12 Phase 5). The brain is **server-truth**
 * and is NEVER mirrored on the device (ADR-0013 §6): this ViewModel fetches live from
 * `GET /brain/{slug}` every time — there is no Room entity/DAO/migration for it.
 *
 * Scoped to one space [slug]. Newest-first list (the server's order), a debounced search
 * (`?q=`), long-press → delete (`DELETE`) with a 5 s undo that re-`POST`s the same entry, and
 * "+" → a manual-entry dialog (`POST`). Tapping an entry whose `source_canvas_id` exists in the
 * local canvas store opens that canvas.
 *
 * Online only: a network failure sets [needsConnection] ("Brain needs a connection") and keeps
 * the last-fetched list (greyed by the screen). Collaborators are injected so the model is
 * JVM-unit-testable with fakes (mirrors [LibraryViewModel]/[SpaceSettingsViewModel]).
 */
class BrainViewModel(
    /** Builds a paired [DeviceRepository], or null when the device is not paired. */
    private val deviceRepositoryProvider: () -> DeviceRepository? = { null },
    /** True when a canvas id exists in the local store (drives tap-to-open, ADR-0013 §6). */
    private val canvasExistsLocally: suspend (String) -> Boolean = { false },
    /** Search debounce; injectable so tests can drive it with 0 ms. */
    private val searchDebounceMs: Long = 300L,
    /** How long the delete undo stays offered (SPEC §12 Phase 5: 5 s). */
    private val undoWindowMs: Long = 5_000L,
) : ViewModel() {

    /** The active space slug the view is scoped to (set via [open]). */
    var slug by mutableStateOf("")
        private set

    /** The entries shown, newest first (the server's order). */
    var entries by mutableStateOf<List<BrainEntry>>(emptyList())
        private set

    /** The current search text (empty = newest). */
    var query by mutableStateOf("")
        private set

    /** True while a fetch is in flight (first load / search). */
    var loading by mutableStateOf(false)
        private set

    /**
     * True when the last network call failed: the screen shows "Brain needs a connection" and
     * greys the last-fetched [entries] (server-truth, online-only — ADR-0013 §6). Cleared on the
     * next success.
     */
    var needsConnection by mutableStateOf(false)
        private set

    /** An entry pending undo after a delete (drives the 5 s "Undo" snackbar); null otherwise. */
    var pendingUndo by mutableStateOf<BrainEntry?>(null)
        private set

    private var searchJob: Job? = null
    private var undoJob: Job? = null

    /** Open (or re-open) the view for [slug]: reset search and load newest-first. */
    fun open(slug: String) {
        if (this.slug == slug && entries.isNotEmpty()) return
        this.slug = slug
        query = ""
        fetch(null)
    }

    /** Manual refresh (retry after a connection error). */
    fun refresh() = fetch(query.trim().ifBlank { null })

    /** Debounced search: empty → newest; otherwise `?q=`. */
    fun onQueryChange(value: String) {
        query = value
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(searchDebounceMs)
            fetch(value.trim().ifBlank { null })
        }
    }

    private fun fetch(q: String?) {
        val dev = deviceRepositoryProvider() ?: run { needsConnection = true; return }
        val s = slug.ifBlank { return }
        loading = true
        viewModelScope.launch {
            try {
                entries = dev.getBrain(s, q)
                needsConnection = false
            } catch (_: Exception) {
                // Server-truth, online-only: keep the last list, flag the connection (ADR-0013 §6).
                needsConnection = true
            } finally {
                loading = false
            }
        }
    }

    /**
     * Add a manual entry (`POST /brain/{slug}`). [tags] is a comma/space-separated string. On
     * success the returned (created or existing) entry is inserted at the top; a failure flags
     * the connection and leaves the list unchanged.
     */
    fun addEntry(kind: String, text: String, tags: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val dev = deviceRepositoryProvider() ?: run { needsConnection = true; return }
        val s = slug.ifBlank { return }
        val create = BrainCreate(kind = kind, text = body, tags = parseTags(tags).ifEmpty { null })
        viewModelScope.launch {
            try {
                val added = dev.postBrain(s, create)
                // Idempotent server: an existing row comes back — de-dupe by id before prepending.
                entries = listOf(added) + entries.filterNot { it.id == added.id }
                needsConnection = false
            } catch (_: Exception) {
                needsConnection = true
            }
        }
    }

    /**
     * Delete an entry (`DELETE /brain/{slug}/{id}`) optimistically, offering a 5 s undo. The row
     * is removed at once; after [undoWindowMs] with no undo the deletion stands. A failed DELETE
     * flags the connection and restores the row.
     */
    fun deleteEntry(entry: BrainEntry) {
        val dev = deviceRepositoryProvider() ?: run { needsConnection = true; return }
        val s = slug.ifBlank { return }
        val previous = entries
        entries = entries.filterNot { it.id == entry.id }
        pendingUndo = entry
        undoJob?.cancel()
        viewModelScope.launch {
            try {
                dev.deleteBrain(s, entry.id)
                needsConnection = false
            } catch (_: Exception) {
                entries = previous // restore on failure
                pendingUndo = null
                needsConnection = true
                return@launch
            }
            // Keep the undo offered for the window, then let the deletion stand.
            undoJob = viewModelScope.launch {
                delay(undoWindowMs)
                if (pendingUndo?.id == entry.id) pendingUndo = null
            }
        }
    }

    /** Undo the last delete by re-`POST`ing the same entry (idempotent server-side). */
    fun undoDelete() {
        val entry = pendingUndo ?: return
        pendingUndo = null
        undoJob?.cancel()
        val dev = deviceRepositoryProvider() ?: run { needsConnection = true; return }
        val s = slug.ifBlank { return }
        viewModelScope.launch {
            try {
                val restored = dev.postBrain(
                    s,
                    BrainCreate(
                        kind = entry.kind,
                        text = entry.text,
                        tags = entry.tags.ifEmpty { null },
                        sourceCanvasId = entry.sourceCanvasId,
                    ),
                )
                entries = listOf(restored) + entries.filterNot { it.id == restored.id }
                needsConnection = false
            } catch (_: Exception) {
                needsConnection = true
            }
        }
    }

    /**
     * Tap an entry: if it carries a `source_canvas_id` that exists locally, open that canvas
     * (SPEC §12 Phase 5). Flashing `source_region` is a nice-to-have; a plain open is used here.
     */
    fun onEntryTapped(entry: BrainEntry, openCanvas: (String) -> Unit) {
        val cid = entry.sourceCanvasId ?: return
        viewModelScope.launch {
            if (canvasExistsLocally(cid)) openCanvas(cid)
        }
    }

    private fun parseTags(raw: String): List<String> =
        raw.split(',', ' ', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    companion object {
        /** The four brain kinds the add dialog and kind chip use (SPEC §4.7 / ADR-0013). */
        val KINDS = listOf("fact", "task", "reference", "decision")
    }
}
