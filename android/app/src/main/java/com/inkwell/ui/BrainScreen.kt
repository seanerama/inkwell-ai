package com.inkwell.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.inkwell.net.BrainEntry

/**
 * The per-space Brain view (Stage 27, SPEC §12 Phase 5). The brain is server-truth and is
 * fetched LIVE — never mirrored (ADR-0013 §6). Lists entries newest-first (kind chip, text,
 * tags, date), a debounced search field (`?q=`), long-press → Delete (with a 5 s undo), a "+"
 * FAB → manual-entry dialog, and (online-only) a "Brain needs a connection" banner that greys
 * the last-fetched list on a network failure.
 *
 * Tapping an entry whose `source_canvas_id` exists locally opens that canvas via [onOpenCanvas].
 * All of this is gated at the entry points by `BuildConfig.BRAIN` (this screen is only reached
 * when the flag is on).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrainScreen(
    viewModel: BrainViewModel,
    slug: String,
    spaceName: String,
    onBack: () -> Unit,
    onOpenCanvas: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(slug) { viewModel.open(slug) }

    var showAdd by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize().testTag(BrainTags.SCREEN)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: back + title.
            Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onBack, modifier = Modifier.testTag(BrainTags.BACK)) {
                        Text("< Back")
                    }
                    Text(
                        text = "Brain — ${spaceName.ifBlank { "Space" }}",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                }
            }

            OutlinedTextField(
                value = viewModel.query,
                onValueChange = viewModel::onQueryChange,
                singleLine = true,
                label = { Text("Search the brain") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag(BrainTags.SEARCH),
            )

            // Online-only: a connection error greys the last-fetched list (ADR-0013 §6).
            if (viewModel.needsConnection) {
                Surface(color = Color(0xFFFDECEA)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag(BrainTags.OFFLINE),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Brain needs a connection",
                            color = Color(0xFFB00020),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.refresh() }) { Text("Retry") }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
                    .alpha(if (viewModel.needsConnection) 0.4f else 1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (viewModel.entries.isEmpty() && !viewModel.loading) {
                    Text(
                        if (viewModel.query.isBlank()) {
                            "Nothing in the brain yet. Use + to add, or Remember on a canvas."
                        } else {
                            "No matches."
                        },
                    )
                }
                viewModel.entries.forEach { entry ->
                    BrainRow(
                        entry = entry,
                        onOpen = { viewModel.onEntryTapped(entry, onOpenCanvas) },
                        onDelete = { viewModel.deleteEntry(entry) },
                    )
                }
            }
        }

        // "+" FAB → manual-entry dialog.
        FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .testTag(BrainTags.ADD),
        ) { Icon(Icons.Filled.Add, contentDescription = "Add to brain") }

        // Delete undo "snackbar" (5 s window; the ViewModel drops it after the window).
        viewModel.pendingUndo?.let {
            Surface(
                tonalElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .testTag(BrainTags.UNDO),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Deleted")
                    TextButton(
                        onClick = { viewModel.undoDelete() },
                        modifier = Modifier.testTag(BrainTags.UNDO_ACTION),
                    ) { Text("Undo") }
                }
            }
        }
    }

    if (showAdd) {
        AddEntryDialog(
            onConfirm = { kind, text, tags ->
                viewModel.addEntry(kind, text, tags)
                showAdd = false
            },
            onDismiss = { showAdd = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrainRow(entry: BrainEntry, onOpen: () -> Unit, onDelete: () -> Unit) {
    Surface(
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onDelete)
            .testTag(BrainTags.entry(entry.id)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindChip(entry.kind)
                Text(
                    text = relativeBrainDate(entry.createdAt),
                    color = Color(0xFF757575),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                text = entry.text,
                modifier = Modifier.padding(top = 6.dp).testTag(BrainTags.entryText(entry.id)),
            )
            if (entry.tags.isNotEmpty()) {
                Text(
                    text = entry.tags.joinToString(" ") { "#$it" },
                    color = Color(0xFF3B6EA5),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun KindChip(kind: String) {
    val bg = when (kind) {
        "task" -> Color(0xFF8E5B00)
        "reference" -> Color(0xFF3B6EA5)
        "decision" -> Color(0xFF2E7D32)
        else -> Color(0xFF5A5A5A) // fact / unknown
    }
    Text(
        text = kind,
        color = Color.White,
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun AddEntryDialog(
    onConfirm: (kind: String, text: String, tags: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf(BrainViewModel.KINDS.first()) }
    var text by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to brain") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Kind", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BrainViewModel.KINDS.forEach { k ->
                        if (k == kind) {
                            Button(
                                onClick = { kind = k },
                                modifier = Modifier.testTag(BrainTags.addKind(k)),
                            ) { Text(k) }
                        } else {
                            OutlinedButton(
                                onClick = { kind = k },
                                modifier = Modifier.testTag(BrainTags.addKind(k)),
                            ) { Text(k) }
                        }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Text") },
                    modifier = Modifier.fillMaxWidth().testTag(BrainTags.ADD_TEXT),
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    singleLine = true,
                    label = { Text("Tags (space or comma separated)") },
                    modifier = Modifier.fillMaxWidth().testTag(BrainTags.ADD_TAGS),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(kind, text, tags) },
                modifier = Modifier.testTag(BrainTags.ADD_CONFIRM),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A coarse relative-date label from an RFC 3339 `created_at`. Best-effort — an unparseable
 * value falls back to the raw date portion (the brain view is a browse surface, not a clock).
 */
private fun relativeBrainDate(createdAt: String): String =
    createdAt.take(10).ifBlank { "" }

/** Stable tags for Compose/instrumented tests. */
object BrainTags {
    const val SCREEN = "brain_screen"
    const val BACK = "brain_back"
    const val SEARCH = "brain_search"
    const val ADD = "brain_add"
    const val ADD_TEXT = "brain_add_text"
    const val ADD_TAGS = "brain_add_tags"
    const val ADD_CONFIRM = "brain_add_confirm"
    const val OFFLINE = "brain_offline"
    const val UNDO = "brain_undo"
    const val UNDO_ACTION = "brain_undo_action"

    fun entry(id: String) = "brain_entry_$id"
    fun entryText(id: String) = "brain_entry_text_$id"
    fun addKind(kind: String) = "brain_add_kind_$kind"
}
