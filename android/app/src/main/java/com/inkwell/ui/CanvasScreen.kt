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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
 * the Stage 6 canvas.annotate loop — a **Send** button (gated by
 * [BuildConfig.SEND_ENABLED]), a non-blocking in-progress indicator (the user keeps
 * drawing, SPEC §9.4 step 4), a **SidePanel** for the agent `summary` + card titles
 * (right column in landscape, bottom panel in portrait), and a minimal **layer tray**
 * with a per-layer visibility toggle so the agent highlight can be hidden to judge
 * placement. The agent layer renders through [com.inkwell.render.AnnotationRenderer].
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

    // Instruction entry (SPEC §9.4 step 1): typed, with the feel-test preset.
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

/** The agent `summary` + card titles, or (on failure) the error card body. */
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
                if (panel.cardTitles.isNotEmpty()) {
                    Spacer(Modifier.size(4.dp))
                    Text("Cards", fontWeight = FontWeight.SemiBold)
                    panel.cardTitles.forEach { title ->
                        Text(text = "• $title")
                    }
                }
            }
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

/** Typed instruction with the feel-test preset (SPEC §9.4 step 1). */
@Composable
private fun InstructionDialog(viewModel: CanvasViewModel) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = viewModel::dismissInstruction,
        title = { Text("Send to agent") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = viewModel.instruction,
                    onValueChange = viewModel::onInstructionChange,
                    label = { Text("Instruction") },
                    modifier = Modifier.fillMaxWidth().testTag(CanvasTags.INSTRUCTION_FIELD),
                )
                TextButton(
                    onClick = viewModel::usePreset,
                    modifier = Modifier.testTag(CanvasTags.INSTRUCTION_PRESET),
                ) { Text("Preset: \"${CanvasViewModel.PRESET_INSTRUCTION}\"") }
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

        // Send (Stage 6 feature) — present only when the kill-switch is ON; disabled
        // (with an inline offline state on the instruction sheet) when offline.
        if (viewModel.sendEnabled) {
            OutlinedButton(
                onClick = viewModel::toggleLayerTray,
                modifier = Modifier.testTag(CanvasTags.LAYERS),
            ) { Text("Layers") }
            Button(
                onClick = viewModel::openInstruction,
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

    fun color(index: Int) = "canvas_color_$index"
}
