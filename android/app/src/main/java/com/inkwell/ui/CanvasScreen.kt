package com.inkwell.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.inkwell.BuildConfig
import com.inkwell.ink.InkView

/**
 * The launch surface (SPEC §9.3): a full-bleed ink canvas with a minimal toolbar, plus
 * the send/poll/render loop — a one-tap **Send** button (gated by
 * [BuildConfig.SEND_ENABLED]; with [BuildConfig.ONE_TAP_ASK] it posts `canvas.ask`
 * with no instruction), an **"Add a note…"** icon button that opens the optional note
 * sheet (which also offers "Mark it up instead" = the Stage-6 `canvas.annotate`), a
 * non-blocking in-progress indicator (the user keeps drawing, SPEC §9.4 step 4), a
 * **SidePanel** for the agent `summary` + cards (title + body as plain text; `answer`
 * cards expanded by default; right column in landscape, bottom panel in portrait), and
 * a minimal **layer tray** with a per-layer visibility toggle so the agent layer can be
 * hidden to judge placement. The agent layer renders through
 * [com.inkwell.render.AnnotationRenderer].
 */
@Composable
fun CanvasScreen(
    viewModel: CanvasViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    debugEnabled: Boolean = BuildConfig.DEBUG,
) {
    val config = LocalConfiguration.current
    val landscape = config.screenWidthDp >= config.screenHeightDp

    Column(modifier = modifier.fillMaxSize()) {
        Toolbar(viewModel = viewModel, onOpenSettings = onOpenSettings, debugEnabled = debugEnabled)

        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().testTag(CanvasTags.SURFACE),
                    factory = { ctx ->
                        InkView(ctx).apply {
                            onStrokeCommitted = { viewModel.onStrokeCommitted(it) }
                            onEraseStroke = { viewModel.onEraseStroke(it) }
                            this.debugEnabled = debugEnabled
                        }
                    },
                    update = { view ->
                        view.tool = viewModel.tool
                        view.colorHex = viewModel.colorHex
                        view.widthCu = viewModel.widthCu
                        view.debugEnabled = debugEnabled
                        view.setCanvasSize(viewModel.canvasWidth, viewModel.canvasHeight)
                        view.setCommittedStrokes(viewModel.strokes.toList())
                        // Agent layer (Stage 6) — rendered through AnnotationRenderer.
                        view.setAccentColor(viewModel.accentColor)
                        view.setAgentAnnotations(viewModel.agentAnnotations)
                        view.setAgentLayerVisible(viewModel.agentLayerVisible)
                        if (debugEnabled) {
                            view.setDebugHighlights(viewModel.fixtureAnnotations)
                        }
                    },
                )

                // Non-blocking in-progress indicator (SPEC §9.4 step 4). Does NOT cover
                // the canvas or intercept touches — the user keeps drawing.
                if (viewModel.jobInProgress) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .testTag(CanvasTags.SEND_PROGRESS),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text("Working…")
                    }
                }

                // Inline send status (offline / not paired / too large / failure).
                viewModel.sendStatus?.let { status ->
                    Text(
                        text = status,
                        color = Color(0xFFB00020),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp)
                            .testTag(CanvasTags.SEND_STATUS),
                    )
                }

                // Minimal layer tray overlay.
                if (viewModel.showLayerTray) {
                    LayerTray(
                        viewModel = viewModel,
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                    )
                }
            }

            // Landscape: the side panel is a right column beside the canvas.
            if (landscape) {
                viewModel.panel?.let { panel ->
                    SidePanel(
                        panel = panel,
                        onClose = viewModel::dismissPanel,
                        modifier = Modifier.width(320.dp).fillMaxHeight(),
                    )
                }
            }
        }

        // Portrait: the side panel is a bottom panel (bottom-sheet style).
        if (!landscape) {
            viewModel.panel?.let { panel ->
                SidePanel(
                    panel = panel,
                    onClose = viewModel::dismissPanel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    // Note / instruction entry (SPEC §9.4 step 1): optional note for canvas.ask, or the
    // Stage-6 annotate sheet when the one-tap kill-switch is OFF.
    if (viewModel.showInstruction) {
        InstructionDialog(viewModel = viewModel)
    }

    // Debug-only export preview (BuildConfig.DEBUG). No release-visible surface.
    if (debugEnabled && viewModel.showExportPreview) {
        ExportPreviewDialog(
            bitmap = viewModel.exportBitmap,
            info = viewModel.exportInfo,
            onDismiss = viewModel::dismissExportPreview,
        )
    }
}

/** The agent `summary` + cards (title, body as plain text), or (on failure) the error card body. */
@Composable
private fun SidePanel(
    panel: PanelModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = modifier.testTag(CanvasTags.SIDE_PANEL),
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (panel.isError) (panel.errorTitle ?: "Job failed") else "Summary",
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onClose, modifier = Modifier.testTag(CanvasTags.PANEL_CLOSE)) {
                    Text("Close")
                }
            }
            if (panel.isError) {
                Text(
                    text = panel.errorBody ?: "The job did not complete.",
                    modifier = Modifier.testTag(CanvasTags.PANEL_ERROR),
                )
            } else {
                Text(text = panel.summary, modifier = Modifier.testTag(CanvasTags.PANEL_SUMMARY))
                if (panel.cards.isNotEmpty()) {
                    Spacer(Modifier.size(4.dp))
                    Text("Cards", fontWeight = FontWeight.SemiBold)
                    panel.cards.forEachIndexed { index, card ->
                        PanelCardRow(panel = panel, index = index, card = card)
                    }
                }
            }
        }
    }
}

/**
 * One card in the panel: the title row toggles the body. An `answer` card starts
 * expanded; every other kind starts collapsed to its title. The body is the card's
 * Markdown shown as plain text (Markdown rendering is Phase 2).
 */
@Composable
private fun PanelCardRow(panel: PanelModel, index: Int, card: PanelCard) {
    var expanded by remember(panel, index) { mutableStateOf(card.expandedByDefault) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .testTag(CanvasTags.panelCard(index)),
    ) {
        Text(
            text = (if (expanded) "▾ " else "▸ ") + card.title,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag(CanvasTags.panelCardTitle(index)),
        )
        if (expanded && card.body.isNotBlank()) {
            Text(
                text = card.body,
                modifier = Modifier
                    .padding(start = 16.dp, top = 2.dp)
                    .testTag(CanvasTags.panelCardBody(index)),
            )
        }
    }
}

/** Minimal layer tray: list layers with a per-layer visibility toggle. */
@Composable
private fun LayerTray(viewModel: CanvasViewModel, modifier: Modifier = Modifier) {
    Surface(tonalElevation = 4.dp, modifier = modifier.testTag(CanvasTags.LAYER_TRAY)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Layers", fontWeight = FontWeight.Bold)
            viewModel.layerRows.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = row.label, modifier = Modifier.weight(1f))
                    if (row.owner == "agent") {
                        Switch(
                            checked = viewModel.agentLayerVisible,
                            onCheckedChange = { viewModel.toggleAgentLayer() },
                            modifier = Modifier.testTag(CanvasTags.LAYER_TOGGLE_AGENT),
                        )
                    } else {
                        // The ink layer is always visible in Phase 1.
                        Switch(checked = true, onCheckedChange = null, enabled = false)
                    }
                }
            }
            if (viewModel.layerRows.none { it.owner == "agent" }) {
                Text("No agent layer yet.")
            }
        }
    }
}

/**
 * The note sheet (SPEC §9.4 step 1). Stage 7 (one-tap ask ON): an optional note sent
 * with `canvas.ask`, plus **"Mark it up instead"** which sends `canvas.annotate` with the
 * typed text (or the Stage-6 preset when blank). Kill-switch OFF: the Stage-6 sheet —
 * typed instruction with the feel-test preset, sent as `canvas.annotate`.
 */
@Composable
private fun InstructionDialog(viewModel: CanvasViewModel) {
    val oneTap = viewModel.oneTapAsk
    androidx.compose.material3.AlertDialog(
        onDismissRequest = viewModel::dismissInstruction,
        title = { Text(if (oneTap) "Add a note" else "Send to agent") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = viewModel.instruction,
                    onValueChange = viewModel::onInstructionChange,
                    label = { Text(if (oneTap) "Note for the agent (optional)" else "Instruction") },
                    modifier = Modifier.fillMaxWidth().testTag(CanvasTags.INSTRUCTION_FIELD),
                )
                if (oneTap) {
                    TextButton(
                        onClick = viewModel::sendAnnotate,
                        enabled = viewModel.online,
                        modifier = Modifier.testTag(CanvasTags.INSTRUCTION_ANNOTATE),
                    ) { Text("Mark it up instead") }
                } else {
                    TextButton(
                        onClick = viewModel::usePreset,
                        modifier = Modifier.testTag(CanvasTags.INSTRUCTION_PRESET),
                    ) { Text("Preset: \"${CanvasViewModel.PRESET_INSTRUCTION}\"") }
                }
                if (!viewModel.online) {
                    Text("Offline — Send is disabled until you reconnect.", color = Color(0xFFB00020))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = viewModel::send,
                enabled = viewModel.online,
                modifier = Modifier.testTag(CanvasTags.INSTRUCTION_SEND),
            ) { Text("Send") }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissInstruction) { Text("Cancel") }
        },
    )
}

/** Debug-only dialog showing the exported PNG and its dimensions (or an error). */
@Composable
private fun ExportPreviewDialog(
    bitmap: android.graphics.Bitmap?,
    info: String?,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(CanvasTags.EXPORT_PREVIEW_CLOSE)) {
                Text("Close")
            }
        },
        title = { Text("Export preview") },
        text = {
            Column {
                Text(info ?: "No export.", modifier = Modifier.testTag(CanvasTags.EXPORT_PREVIEW_INFO))
                Spacer(Modifier.size(12.dp))
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Exported canvas PNG",
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.Gray),
                    )
                }
            }
        },
    )
}

@Composable
private fun Toolbar(
    viewModel: CanvasViewModel,
    onOpenSettings: () -> Unit,
    debugEnabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolButton("Pen", selected = viewModel.tool == "pen", tag = CanvasTags.PEN) {
            viewModel.selectTool("pen")
        }
        ToolButton("Marker", selected = viewModel.tool == "marker", tag = CanvasTags.MARKER) {
            viewModel.selectTool("marker")
        }
        ToolButton("Eraser", selected = viewModel.tool == "eraser", tag = CanvasTags.ERASER) {
            viewModel.selectTool("eraser")
        }

        Spacer(Modifier.size(8.dp))

        CanvasViewModel.PALETTE.forEachIndexed { index, hex ->
            ColorSwatch(
                hex = hex,
                selected = viewModel.colorHex == hex && viewModel.tool != "eraser",
                tag = CanvasTags.color(index),
            ) { viewModel.selectColor(hex) }
        }

        Spacer(Modifier.weight(1f))

        // Debug-only tools (BuildConfig.DEBUG); absent from release builds.
        if (debugEnabled) {
            OutlinedButton(
                onClick = viewModel::exportPreview,
                modifier = Modifier.testTag(CanvasTags.EXPORT_PREVIEW),
            ) { Text("Export preview") }
            OutlinedButton(
                onClick = viewModel::toggleFixture,
                modifier = Modifier.testTag(CanvasTags.RENDER_FIXTURE),
            ) { Text(if (viewModel.fixtureVisible) "Hide fixture" else "Render fixture") }
        }

        // Send — present only when the kill-switch is ON; disabled when offline. Stage 7:
        // one tap posts canvas.ask; the optional note lives behind "Add a note…".
        if (viewModel.sendEnabled) {
            OutlinedButton(
                onClick = viewModel::toggleLayerTray,
                modifier = Modifier.testTag(CanvasTags.LAYERS),
            ) { Text("Layers") }
            if (viewModel.oneTapAsk) {
                IconButton(
                    onClick = viewModel::openInstruction,
                    enabled = viewModel.online,
                    modifier = Modifier.testTag(CanvasTags.ADD_NOTE),
                ) { Icon(Icons.Filled.Edit, contentDescription = "Add a note…") }
            }
            Button(
                onClick = viewModel::onSendTapped,
                enabled = viewModel.online,
                modifier = Modifier.testTag(CanvasTags.SEND),
            ) { Text(if (viewModel.online) "Send" else "Offline") }
        }

        OutlinedButton(
            onClick = viewModel::undoLast,
            modifier = Modifier.testTag(CanvasTags.UNDO),
        ) { Text("Undo") }

        TextButton(
            onClick = onOpenSettings,
            modifier = Modifier.testTag(CanvasTags.SETTINGS),
        ) { Text("Settings") }
    }
}

@Composable
private fun ToolButton(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.testTag(tag)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.testTag(tag)) { Text(label) }
    }
}

@Composable
private fun ColorSwatch(hex: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val color = Color(android.graphics.Color.parseColor(hex))
    val borderColor = if (selected) Color.Black else Color.Gray
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(color)
            .border(if (selected) 3.dp else 1.dp, borderColor, CircleShape)
            .testTag(tag)
            .clickable(onClick = onClick),
    )
}

/** Stable tags for Compose/instrumented tests. */
object CanvasTags {
    const val SURFACE = "canvas_surface"
    const val PEN = "canvas_pen"
    const val MARKER = "canvas_marker"
    const val ERASER = "canvas_eraser"
    const val UNDO = "canvas_undo"
    const val SETTINGS = "canvas_settings"
    const val EXPORT_PREVIEW = "canvas_export_preview"
    const val RENDER_FIXTURE = "canvas_render_fixture"
    const val EXPORT_PREVIEW_INFO = "canvas_export_preview_info"
    const val EXPORT_PREVIEW_CLOSE = "canvas_export_preview_close"

    // Stage 6 loop surfaces.
    const val SEND = "canvas_send"
    const val LAYERS = "canvas_layers"
    const val SEND_PROGRESS = "canvas_send_progress"
    const val SEND_STATUS = "canvas_send_status"
    const val INSTRUCTION_FIELD = "canvas_instruction_field"
    const val INSTRUCTION_PRESET = "canvas_instruction_preset"
    const val INSTRUCTION_SEND = "canvas_instruction_send"
    const val SIDE_PANEL = "canvas_side_panel"
    const val PANEL_SUMMARY = "canvas_panel_summary"
    const val PANEL_ERROR = "canvas_panel_error"
    const val PANEL_CLOSE = "canvas_panel_close"
    const val LAYER_TRAY = "canvas_layer_tray"
    const val LAYER_TOGGLE_AGENT = "canvas_layer_toggle_agent"

    // Stage 7 one-tap ask surfaces.
    const val ADD_NOTE = "canvas_add_note"
    const val INSTRUCTION_ANNOTATE = "canvas_instruction_annotate"

    fun color(index: Int) = "canvas_color_$index"
    fun panelCard(index: Int) = "canvas_panel_card_$index"
    fun panelCardTitle(index: Int) = "canvas_panel_card_title_$index"
    fun panelCardBody(index: Int) = "canvas_panel_card_body_$index"
}
