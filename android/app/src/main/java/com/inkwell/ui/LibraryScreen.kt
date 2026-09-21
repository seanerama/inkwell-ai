package com.inkwell.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.inkwell.BuildConfig
import com.inkwell.data.CanvasEntity
import com.inkwell.data.FolderEntity
import com.inkwell.data.SpaceEntity

/** A folder/canvas the context menu, rename, or move dialog is currently acting on. */
private data class TileRef(val isFolder: Boolean, val id: String, val name: String)

/**
 * The Library launch surface (Stage 11, SPEC §9.3 CanvasGrid): a breadcrumb bar, a grid
 * of folder and canvas tiles (thumbnail + title + relative date) for the current folder,
 * a "+" FAB offering **New canvas** / **New folder**, long-press → Rename / Move… /
 * Delete, and a **Trash** entry at the root with Restore / Delete forever. Works in
 * portrait and landscape (the grid column count follows orientation).
 *
 * Opening a canvas tile is delegated to [onOpenCanvas] (the host swaps in the canvas
 * screen). [loadThumbnail] returns a cached 256-px thumbnail for a canvas id, or null
 * (placeholder) — the bytes are produced by [com.inkwell.data.ThumbnailRenderer].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenCanvas: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Stage 28: opens the settings/pairing screen (server URL + token). Plumbed from
     * MainActivity.LibraryRoute so the gear in the breadcrumb bar is one tap from the
     * Library. Defaults to a no-op so existing call sites/tests that omit it still compile.
     */
    onOpenSettings: () -> Unit = {},
    /**
     * Stage 27: opens the per-space Brain view for a given space id. Called by the brain icon in
     * the breadcrumb bar (active space) and the tab's ⋯ menu (that tab's space). Gated by
     * `BuildConfig.BRAIN`; a no-op default keeps existing call sites/tests compiling.
     */
    onOpenBrain: (String) -> Unit = {},
    loadThumbnail: (CanvasEntity) -> ImageBitmap? = { null },
    /**
     * Stage 15: hosts the space-settings sheet + "new space" dialog. Null (default) keeps the
     * Stage-14 read-only tab bar — existing call sites/tests that omit it still compile.
     */
    settingsViewModel: SpaceSettingsViewModel? = null,
) {
    val config = LocalConfiguration.current
    val columns = if (config.screenWidthDp >= config.screenHeightDp) 4 else 2

    var fabMenu by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var renameFor by remember { mutableStateOf<TileRef?>(null) }
    var moveFor by remember { mutableStateOf<TileRef?>(null) }

    Box(modifier = modifier.fillMaxSize().testTag(LibraryTags.SCREEN)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Stage 14: the server-mirrored space tab bar sits above the breadcrumb (SPEC §9.3).
            if (BuildConfig.SPACES && viewModel.spaces.isNotEmpty()) {
                SpaceTabBar(
                    spaces = viewModel.spaces,
                    activeSpaceId = viewModel.activeSpaceId,
                    notSynced = viewModel.spacesNotSynced,
                    onSelect = { viewModel.selectSpace(it) },
                    onRefresh = { viewModel.refreshSpaces() },
                    // Stage 15: long-press → Space settings; trailing "+" → new space.
                    onEditSpace = { id ->
                        viewModel.spaces.firstOrNull { it.id == id }?.let { space ->
                            settingsViewModel?.openSettings(space, viewModel.spaces)
                        }
                    },
                    onAddSpace = { settingsViewModel?.openCreate() },
                    // Stage 22: unread pushed-canvas badge per tab.
                    unseenBySpace = viewModel.unseenBySpace,
                    // Stage 27: the tab's ⋯ menu → Brain (gated by BuildConfig.BRAIN).
                    onOpenBrain = onOpenBrain,
                )
            }
            BreadcrumbBar(viewModel = viewModel, onOpenSettings = onOpenSettings, onOpenBrain = onOpenBrain)

            if (viewModel.showTrash) {
                TrashList(viewModel = viewModel)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (viewModel.folders.isEmpty() && viewModel.canvases.isEmpty()) {
                        Text("This folder is empty. Use + to add a canvas or folder.")
                    }
                    GridRows(items = viewModel.folders, columns = columns) { folder ->
                        FolderTile(
                            folder = folder,
                            onOpen = { viewModel.openFolder(folder) },
                            onRename = { renameFor = TileRef(true, folder.id, folder.name) },
                            onMove = { moveFor = TileRef(true, folder.id, folder.name) },
                            onDelete = { viewModel.deleteFolder(folder.id) },
                        )
                    }
                    GridRows(items = viewModel.canvases, columns = columns) { canvas ->
                        val thumb = remember(canvas.id, canvas.updatedAt) { loadThumbnail(canvas) }
                        CanvasTile(
                            canvas = canvas,
                            thumbnail = thumb,
                            // Stage 22: a "New" dot on an unread pushed canvas; opening it marks
                            // it seen (clears the dot and the tab badge).
                            isNew = canvas.id in viewModel.unseenCanvasIds,
                            onOpen = { viewModel.markSeen(canvas.id); onOpenCanvas(canvas.id) },
                            onRename = { renameFor = TileRef(false, canvas.id, canvas.title) },
                            onMove = { moveFor = TileRef(false, canvas.id, canvas.title) },
                            onDelete = { viewModel.deleteCanvas(canvas.id) },
                        )
                    }
                }
            }
        }

        // "+" FAB with a New canvas / New folder menu (hidden while viewing Trash).
        if (!viewModel.showTrash) {
            Box(modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
                FloatingActionButton(
                    onClick = { fabMenu = true },
                    modifier = Modifier.testTag(LibraryTags.FAB),
                ) { Icon(Icons.Filled.Add, contentDescription = "Add") }
                DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("New canvas") },
                        onClick = {
                            fabMenu = false
                            viewModel.createCanvas(onCreated = onOpenCanvas)
                        },
                        modifier = Modifier.testTag(LibraryTags.NEW_CANVAS),
                    )
                    DropdownMenuItem(
                        text = { Text("New folder") },
                        onClick = { fabMenu = false; showNewFolder = true },
                        modifier = Modifier.testTag(LibraryTags.NEW_FOLDER),
                    )
                }
            }
        }
    }

    if (showNewFolder) {
        TextPromptDialog(
            title = "New folder",
            initial = "",
            fieldTag = LibraryTags.NEW_FOLDER_FIELD,
            confirmTag = LibraryTags.NEW_FOLDER_CONFIRM,
            onConfirm = { viewModel.createFolder(it); showNewFolder = false },
            onDismiss = { showNewFolder = false },
        )
    }

    renameFor?.let { target ->
        TextPromptDialog(
            title = "Rename",
            initial = target.name,
            fieldTag = LibraryTags.RENAME_FIELD,
            confirmTag = LibraryTags.RENAME_CONFIRM,
            onConfirm = {
                if (target.isFolder) viewModel.renameFolder(target.id, it)
                else viewModel.renameCanvas(target.id, it)
                renameFor = null
            },
            onDismiss = { renameFor = null },
        )
    }

    moveFor?.let { target ->
        MoveDialog(
            target = target,
            folders = viewModel.allFolders,
            // Stage 14: the OTHER space tabs, offered as move targets ("Other spaces").
            otherSpaces = if (BuildConfig.SPACES) {
                viewModel.spaces.filter { it.id != viewModel.activeSpaceId }
            } else {
                emptyList()
            },
            onPick = { destId ->
                if (target.isFolder) viewModel.moveFolder(target.id, destId)
                else viewModel.moveCanvas(target.id, destId)
                moveFor = null
            },
            onPickSpace = { spaceId ->
                if (target.isFolder) viewModel.moveFolderToSpace(target.id, spaceId)
                else viewModel.moveCanvasToSpace(target.id, spaceId)
                moveFor = null
            },
            onDismiss = { moveFor = null },
        )
    }

    // Stage 15: the space-settings sheet and the "new space" dialog (flag-gated; the host
    // supplies the ViewModel only when SPACE_SETTINGS is on).
    if (BuildConfig.SPACE_SETTINGS && settingsViewModel != null) {
        settingsViewModel.editing?.let { space ->
            SpaceSettingsSheet(space = space, viewModel = settingsViewModel)
        }
        if (settingsViewModel.creating) {
            NewSpaceDialog(viewModel = settingsViewModel)
        }
    }
}

@Composable
private fun BreadcrumbBar(
    viewModel: LibraryViewModel,
    onOpenSettings: () -> Unit,
    onOpenBrain: (String) -> Unit = {},
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .testTag(LibraryTags.BREADCRUMB),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { viewModel.navigateTo(-1) }) { Text("Space") }
                viewModel.breadcrumb.forEachIndexed { index, folder ->
                    Text(" › ")
                    TextButton(onClick = { viewModel.navigateTo(index) }) { Text(folder.name) }
                }
            }
            // Stage 27: the per-space Brain, one tap from the Library (beside Settings/Trash),
            // gated by BuildConfig.BRAIN. Opens the Brain view for the ACTIVE space.
            if (BuildConfig.BRAIN) {
                IconButton(
                    onClick = { viewModel.activeSpaceId?.let(onOpenBrain) },
                    modifier = Modifier.testTag(LibraryTags.BRAIN),
                ) { Icon(Icons.Filled.Star, contentDescription = "Brain") }
            }
            // Stage 28: Settings is one tap from the Library — a gear beside the Trash entry.
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.testTag(LibraryTags.SETTINGS),
            ) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
            if (viewModel.showTrash) {
                TextButton(
                    onClick = { viewModel.closeTrash() },
                    modifier = Modifier.testTag(LibraryTags.TRASH_BACK),
                ) { Text("Back") }
            } else if (viewModel.breadcrumb.isEmpty()) {
                // Trash lives at the root.
                TextButton(
                    onClick = { viewModel.openTrash() },
                    modifier = Modifier.testTag(LibraryTags.TRASH),
                ) { Text("Trash") }
            }
        }
    }
}

/** Lay [items] out in rows of [columns], each cell equal width; pads the last row. */
@Composable
private fun <T> GridRows(items: List<T>, columns: Int, tile: @Composable (T) -> Unit) {
    items.chunked(columns).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            row.forEach { item ->
                Box(modifier = Modifier.weight(1f)) { tile(item) }
            }
            // Pad a short final row so tiles keep their column width.
            repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(
    folder: FolderEntity,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
            .testTag(LibraryTags.folderTile(folder.id)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("📁", fontWeight = FontWeight.Bold)
            Text(folder.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(relativeDate(folder.updatedAt), color = Color(0xFF757575))
        }
        ItemMenu(
            expanded = menu,
            onDismiss = { menu = false },
            onRename = { menu = false; onRename() },
            onMove = { menu = false; onMove() },
            onDelete = { menu = false; onDelete() },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CanvasTile(
    canvas: CanvasEntity,
    thumbnail: ImageBitmap?,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    isNew: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
            .testTag(LibraryTags.canvasTile(canvas.id)),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.707f) // A4 portrait
                    .background(Color.White)
                    .border(1.dp, Color(0xFFDDDDDD)),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbnail != null) {
                    androidx.compose.foundation.Image(
                        bitmap = thumbnail,
                        contentDescription = "Canvas thumbnail",
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text("✎", color = Color(0xFFBBBBBB))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                // Stage 22: a "New" dot marks an unread pushed canvas until it is opened.
                if (isNew) {
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(8.dp)
                            .background(Color(0xFF1B6EF3), CircleShape)
                            .testTag(LibraryTags.newDot(canvas.id)),
                    )
                }
                Text(
                    canvas.title.ifBlank { "Untitled" },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            Text(relativeDate(canvas.updatedAt), color = Color(0xFF757575))
        }
        ItemMenu(
            expanded = menu,
            onDismiss = { menu = false },
            onRename = { menu = false; onRename() },
            onMove = { menu = false; onMove() },
            onDelete = { menu = false; onDelete() },
        )
    }
}

@Composable
private fun ItemMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Rename") },
            onClick = onRename,
            modifier = Modifier.testTag(LibraryTags.MENU_RENAME),
        )
        DropdownMenuItem(
            text = { Text("Move…") },
            onClick = onMove,
            modifier = Modifier.testTag(LibraryTags.MENU_MOVE),
        )
        DropdownMenuItem(
            text = { Text("Delete") },
            onClick = onDelete,
            modifier = Modifier.testTag(LibraryTags.MENU_DELETE),
        )
    }
}

@Composable
private fun TrashList(viewModel: LibraryViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .testTag(LibraryTags.TRASH_LIST),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Trash", fontWeight = FontWeight.Bold)
        if (viewModel.trashFolders.isEmpty() && viewModel.trashCanvases.isEmpty()) {
            Text("Trash is empty.")
        }
        viewModel.trashFolders.forEach { folder ->
            TrashRow(
                label = "📁 ${folder.name}",
                onRestore = { viewModel.restoreFolder(folder.id) },
                onDeleteForever = { viewModel.purgeFolderForever(folder.id) },
            )
        }
        viewModel.trashCanvases.forEach { canvas ->
            TrashRow(
                label = canvas.title.ifBlank { "Untitled" },
                onRestore = { viewModel.restoreCanvas(canvas.id) },
                onDeleteForever = { viewModel.purgeCanvasForever(canvas.id) },
            )
        }
    }
}

@Composable
private fun TrashRow(label: String, onRestore: () -> Unit, onDeleteForever: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        TextButton(onClick = onRestore, modifier = Modifier.testTag(LibraryTags.RESTORE)) {
            Text("Restore")
        }
        IconButton(
            onClick = onDeleteForever,
            modifier = Modifier.testTag(LibraryTags.DELETE_FOREVER),
        ) { Icon(Icons.Filled.Delete, contentDescription = "Delete forever") }
    }
}

@Composable
private fun TextPromptDialog(
    title: String,
    initial: String,
    fieldTag: String,
    confirmTag: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(fieldTag),
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }, modifier = Modifier.testTag(confirmTag)) {
                Text("OK")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MoveDialog(
    target: TileRef,
    folders: List<FolderEntity>,
    otherSpaces: List<SpaceEntity>,
    onPick: (String?) -> Unit,
    onPickSpace: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move \"${target.name}\" to…") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedButton(
                    onClick = { onPick(null) },
                    modifier = Modifier.fillMaxWidth().testTag(LibraryTags.MOVE_ROOT),
                ) { Text("Space root") }
                folders
                    // A folder can't be moved into itself.
                    .filter { !(target.isFolder && it.id == target.id) }
                    .forEach { folder ->
                        OutlinedButton(
                            onClick = { onPick(folder.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(LibraryTags.moveTarget(folder.id)),
                        ) { Text(folder.name) }
                    }
                // Stage 14: move to another space's root ("Other spaces").
                if (otherSpaces.isNotEmpty()) {
                    Text(
                        "Other spaces",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    otherSpaces.forEach { space ->
                        OutlinedButton(
                            onClick = { onPickSpace(space.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(LibraryTags.moveSpaceTarget(space.id)),
                        ) { Text(space.name) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A coarse relative-date label ("just now", "3h ago", "2d ago", or a day count). */
private fun relativeDate(thenMs: Long): String {
    val delta = System.currentTimeMillis() - thenMs
    val minutes = delta / 60_000
    val hours = delta / 3_600_000
    val days = delta / 86_400_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 30 -> "${days}d ago"
        else -> "${days}d ago"
    }
}

/** Stable tags for Compose/instrumented tests. */
object LibraryTags {
    const val SCREEN = "library_screen"
    const val BREADCRUMB = "library_breadcrumb"
    const val SETTINGS = "library_settings"

    /** Stage 27: the Brain icon in the breadcrumb bar (gated by BuildConfig.BRAIN). */
    const val BRAIN = "library_brain"
    const val FAB = "library_fab"
    const val NEW_CANVAS = "library_new_canvas"
    const val NEW_FOLDER = "library_new_folder"
    const val NEW_FOLDER_FIELD = "library_new_folder_field"
    const val NEW_FOLDER_CONFIRM = "library_new_folder_confirm"
    const val RENAME_FIELD = "library_rename_field"
    const val RENAME_CONFIRM = "library_rename_confirm"
    const val MENU_RENAME = "library_menu_rename"
    const val MENU_MOVE = "library_menu_move"
    const val MENU_DELETE = "library_menu_delete"
    const val MOVE_ROOT = "library_move_root"
    const val TRASH = "library_trash"
    const val TRASH_BACK = "library_trash_back"
    const val TRASH_LIST = "library_trash_list"
    const val RESTORE = "library_restore"
    const val DELETE_FOREVER = "library_delete_forever"

    fun folderTile(id: String) = "library_folder_$id"
    fun canvasTile(id: String) = "library_canvas_$id"

    /** Stage 22: the "New" (unread pushed canvas) dot on a tile. */
    fun newDot(id: String) = "library_new_$id"
    fun moveTarget(id: String) = "library_move_target_$id"

    /** Stage 14: a "move to another space" target in the Move… dialog. */
    fun moveSpaceTarget(id: String) = "library_move_space_$id"
}
