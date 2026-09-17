package com.inkwell.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwell.data.SpaceEntity
import com.inkwell.data.SpaceSync
import com.inkwell.net.ApiException
import com.inkwell.net.DeviceRepository
import com.inkwell.net.Space
import com.inkwell.net.SpaceCreateRequest
import com.inkwell.net.SpacePatchRequest
import kotlinx.coroutines.launch

/**
 * Pure, JVM-unit-testable core of Stage 15's on-device space editing. Everything here is a
 * plain function over plain values (hex colour strings, field sets) — deliberately free of
 * `android.graphics` / `Color.parseColor` so a JVM test can exercise it without the "not
 * mocked" Android stubs (see [com.inkwell.render.AnnotationRenderer.accentFrom], which owns
 * the only colour parsing and already falls back safely; the swatches here stay hex strings
 * and only the composable renders them).
 */
object SpaceEdits {

    /** OFF-network / unpaired copy: the sheet/dialog is kept open, nothing was saved. */
    const val OFFLINE = "Offline — changes not saved"

    /** The editable fields as the user has them right now (colour is a `#RRGGBB` string). */
    data class Draft(
        val name: String,
        val color: String,
        val model: String,
        val systemPrompt: String,
    )

    fun draftOf(space: SpaceEntity): Draft =
        Draft(name = space.name, color = space.color, model = space.model, systemPrompt = space.systemPrompt)

    /**
     * A partial PATCH body carrying ONLY the fields that differ from [original]; unchanged
     * fields stay null so `explicitNulls=false` (ApiClient's Json) omits them on the wire — a
     * true partial PATCH. `name` is trimmed before comparison. `position` is never set here:
     * ordering is owned by [swap] (Move left/right).
     */
    fun diff(original: SpaceEntity, draft: Draft): SpacePatchRequest = SpacePatchRequest(
        name = draft.name.trim().takeIf { it != original.name },
        color = draft.color.takeIf { it != original.color },
        model = draft.model.takeIf { it != original.model },
        systemPrompt = draft.systemPrompt.takeIf { it != original.systemPrompt },
    )

    /** True when [draft] differs from [original] in any editable field (drives Save-enabled). */
    fun hasChanges(original: SpaceEntity, draft: Draft): Boolean = diff(original, draft) != SpacePatchRequest()

    /**
     * The two PATCH calls that swap [space] with its [neighbour] in `position`: this space
     * takes the neighbour's position and vice-versa. Returned as `(id → patch)` pairs so the
     * caller issues exactly two `PATCH /spaces/{id}`.
     */
    fun swap(space: SpaceEntity, neighbour: SpaceEntity): List<Pair<String, SpacePatchRequest>> = listOf(
        space.id to SpacePatchRequest(position = neighbour.position),
        neighbour.id to SpacePatchRequest(position = space.position),
    )

    /**
     * Map a save/create failure to the user-facing message (contract device-api error codes,
     * §Stage 13 additions): `403`/`disabled` → editing is off; `409` → duplicate name (create);
     * `422` → the server's own message inline; anything else (network, unpaired) → [OFFLINE].
     */
    fun errorMessage(t: Throwable): String = when {
        t is ApiException && (t.statusCode == 403 || t.body?.code == "disabled") ->
            "Editing spaces is turned off on the server"
        t is ApiException && t.statusCode == 409 ->
            "A space with that name already exists"
        t is ApiException && t.statusCode == 422 ->
            t.body?.message?.takeIf { it.isNotBlank() } ?: "That change was rejected"
        else -> OFFLINE
    }
}

/**
 * Stage 15 orchestration for the space-settings sheet and the "new space" dialog. Holds the
 * open/closed UI state, performs the network save/create/reorder, mirrors the returned row
 * back into Room ([upsertSpace] + [SpaceSync.refresh]), and asks the host to re-load the tabs
 * (selecting the created space) via [onSpacesChanged]. The diff / swap / error-mapping logic
 * lives in the pure [SpaceEdits] object; this class only wires it to IO and Compose state.
 *
 * Injected as lambdas so the whole thing stays constructible off an emulator; the pure core is
 * tested directly (SpaceEditsTest) and the IO path through the instrumented lane.
 */
class SpaceSettingsViewModel(
    private val deviceRepositoryProvider: () -> DeviceRepository?,
    private val spaceSync: SpaceSync?,
    private val upsertSpace: suspend (SpaceEntity) -> Unit,
    private val loadSpaces: suspend () -> List<SpaceEntity>,
    /** Host hook: re-load the tab mirror and (for a create) select the given space id. */
    private val onSpacesChanged: (String?) -> Unit = {},
) : ViewModel() {

    /** The space whose settings sheet is open, or null when closed. */
    var editing by mutableStateOf<SpaceEntity?>(null)
        private set

    /** True while a save/reorder network call is in flight (disables the buttons). */
    var saving by mutableStateOf(false)
        private set

    /** Inline error shown in the sheet; non-null keeps the sheet open. */
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** True when the "new space" dialog is open. */
    var creating by mutableStateOf(false)
        private set

    var createSaving by mutableStateOf(false)
        private set

    var createError by mutableStateOf<String?>(null)
        private set

    // The tabs' current order, captured when the sheet opens; drives Move left/right bounds.
    private var ordered: List<SpaceEntity> = emptyList()

    /** Open the settings sheet for [space], remembering [allSpaces] for Move left/right. */
    fun openSettings(space: SpaceEntity, allSpaces: List<SpaceEntity>) {
        ordered = allSpaces.sortedBy { it.position }
        editing = space
        errorMessage = null
        saving = false
    }

    fun closeSettings() {
        editing = null
        errorMessage = null
        saving = false
    }

    fun openCreate() {
        creating = true
        createError = null
        createSaving = false
    }

    fun closeCreate() {
        creating = false
        createError = null
        createSaving = false
    }

    /** Can [space] move in [direction] (-1 left, +1 right) within the current tab order? */
    fun canMove(space: SpaceEntity, direction: Int): Boolean {
        val index = ordered.indexOfFirst { it.id == space.id }
        if (index < 0) return false
        val target = index + direction
        return target in ordered.indices
    }

    private fun neighbourOf(space: SpaceEntity, direction: Int): SpaceEntity? {
        val index = ordered.indexOfFirst { it.id == space.id }
        if (index < 0) return null
        return ordered.getOrNull(index + direction)
    }

    /**
     * Save [draft] against the loaded [original]: PATCH only the changed fields, mirror the
     * returned row into Room + refresh, then close and ask the host to re-load the tabs. A
     * no-op when nothing changed. On failure the sheet stays open with a mapped message.
     */
    fun save(original: SpaceEntity, draft: SpaceEdits.Draft) {
        val patch = SpaceEdits.diff(original, draft)
        if (patch == SpacePatchRequest()) return
        val dev = deviceRepositoryProvider()
        if (dev == null) {
            errorMessage = SpaceEdits.OFFLINE
            return
        }
        saving = true
        errorMessage = null
        viewModelScope.launch {
            try {
                val updated = dev.patchSpace(original.id, patch)
                upsertSpace(updated.toEntity())
                spaceSync?.refresh()
                saving = false
                editing = null
                onSpacesChanged(original.id)
            } catch (t: Throwable) {
                saving = false
                errorMessage = SpaceEdits.errorMessage(t)
            }
        }
    }

    /** Move [space] one step in [direction] (-1 left, +1 right) by swapping positions (two PATCHes). */
    fun move(space: SpaceEntity, direction: Int) {
        val neighbour = neighbourOf(space, direction) ?: return
        val patches = SpaceEdits.swap(space, neighbour)
        val dev = deviceRepositoryProvider()
        if (dev == null) {
            errorMessage = SpaceEdits.OFFLINE
            return
        }
        saving = true
        errorMessage = null
        viewModelScope.launch {
            try {
                for ((id, patch) in patches) {
                    val updated = dev.patchSpace(id, patch)
                    upsertSpace(updated.toEntity())
                }
                spaceSync?.refresh()
                // Re-capture the order and the edited row so the sheet's buttons stay correct.
                ordered = loadSpaces().sortedBy { it.position }
                editing = ordered.firstOrNull { it.id == space.id } ?: editing
                saving = false
                onSpacesChanged(space.id)
            } catch (t: Throwable) {
                saving = false
                errorMessage = SpaceEdits.errorMessage(t)
            }
        }
    }

    /** Create a new space (a new agent) with [name] + [color]; on success select its tab. */
    fun createSpace(name: String, color: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val dev = deviceRepositoryProvider()
        if (dev == null) {
            createError = SpaceEdits.OFFLINE
            return
        }
        createSaving = true
        createError = null
        viewModelScope.launch {
            try {
                val created = dev.createSpace(SpaceCreateRequest(name = trimmed, color = color))
                upsertSpace(created.toEntity())
                spaceSync?.refresh()
                createSaving = false
                creating = false
                onSpacesChanged(created.id)
            } catch (t: Throwable) {
                createSaving = false
                createError = SpaceEdits.errorMessage(t)
            }
        }
    }

    private fun Space.toEntity(): SpaceEntity = SpaceEntity(
        id = id,
        name = name,
        slug = slug,
        systemPrompt = systemPrompt,
        tools = tools,
        model = model,
        color = color,
        position = position,
        createdAt = SpaceSync.parseEpochMs(createdAt),
    )
}
