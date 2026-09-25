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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.inkwell.BuildConfig
import com.inkwell.ink.InkPrefs
import com.inkwell.ink.InkSettings
import com.inkwell.ink.InkSurfaceHost
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
 *
 * Stage 31: the Settings "Ink" switches ([InkPrefs], behind [BuildConfig.LOW_LATENCY_INK])
 * are read once when the canvas opens. With "Low-latency pen" on, the canvas hosts an
 * [InkSurfaceHost] (InkView + the front-buffered wet layer); otherwise a bare [InkView]
 * exactly as before. The smoothing preset sets the one-euro knobs either way.
 */
@Composable
fun CanvasScreen(
    viewModel: CanvasViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    debugEnabled: Boolean = BuildConfig.DEBUG,
    // Stage 11: when the Library launched this canvas, Back returns to its folder. Null
    // in the flag-OFF path (there is nothing to go back to).
    onBack: (() -> Unit)? = null,
    // Stage 27: opens the per-space Brain view (the "View in Brain" snackbar action after a
    // save_to_brain). Null → the snackbar shows without the action (e.g. flag-OFF path).
    onOpenBrain: (() -> Unit)? = null,
    // Stage 31: the ink switches to apply; null → read InkPrefs when the canvas opens
    // (stage 33: InkSettings.GATE_OFF — pen path unchanged, Standard smoothing — when the
    // LOW_LATENCY_INK kill switch is off; the runtime defaults are now on / Responsive).
    inkSettings: InkSettings? = null,
) {
    val config = LocalConfiguration.current
    val landscape = config.screenWidthDp >= config.screenHeightDp
    val context = LocalContext.current
    val ink = remember(inkSettings) {
        inkSettings ?: if (BuildConfig.LOW_LATENCY_INK) {
            InkSettings.from(InkPrefs.from(context))
        } else {
            InkSettings.GATE_OFF
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (onBack != null) {
            CanvasTopBar(viewModel = viewModel, onBack = onBack)
        }
        Toolbar(viewModel = viewModel, onOpenSettings = onOpenSettings, debugEnabled = debugEnabled)

        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().testTag(CanvasTags.SURFACE),
                    factory = { ctx ->
                        // Stage 31: the wet layer is only created when the switch is on.
                        val host = if (ink.lowLatencyPen) InkSurfaceHost(ctx) else null
                        val inkView = host?.inkView ?: InkView(ctx)
                        inkView.apply {
                            lowLatency = ink.lowLatencyPen
                            minCutoff = ink.smoothing.minCutoff
                            beta = ink.smoothing.beta
                            onStrokeCommitted = { viewModel.onStrokeCommitted(it) }
                            onEraseStroke = { viewModel.onEraseStroke(it) }
                            // Stage 10: a finger tap on an agent mark scrolls the panel to
                            // its card (SPEC §4.7, canvas → card). Gated in the ViewModel.
                            onAnchorTap = { xCu, yCu -> viewModel.onCanvasTapCu(xCu, yCu) }
                            this.debugEnabled = debugEnabled
                        }
                        host ?: inkView
                    },
                    update = { root ->
                        val view = (root as? InkSurfaceHost)?.inkView ?: root as InkView
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
                        // Stage 12: an agent-origin canvas (a Formalize redraw) draws opaque.
                        view.setAgentOriginCanvas(viewModel.agentOriginCanvas)
                        // Stage 22: the pushed raster (PDF page / image) renders beneath ink.
                        view.setRaster(viewModel.canvasRaster)
                        view.setDocumentVisible(viewModel.documentVisible)
                        // Stage 10: card → canvas anchor pulse (~1.5 s after a card tap).
                        view.setAnchorPulses(viewModel.anchorPulses)
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

                // Stage 27: "Saved to brain" snackbar with a "View in Brain" action, shown after
                // a save_to_brain card action succeeds. Auto-dismisses after a few seconds.
                if (viewModel.brainEnabled && viewModel.brainSaved) {
                    LaunchedEffect(Unit) {
                        kotlinx.coroutines.delay(5_000)
                        viewModel.consumeBrainSaved()
                    }
                    Surface(
                        tonalElevation = 6.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                            .testTag(CanvasTags.BRAIN_SNACKBAR),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Saved to brain")
                            if (onOpenBrain != null) {
                                TextButton(
                                    onClick = { viewModel.consumeBrainSaved(); onOpenBrain() },
                                    modifier = Modifier.testTag(CanvasTags.BRAIN_VIEW_ACTION),
                                ) { Text("View in Brain") }
                            }
                        }
                    }
                }
            }

            // Landscape: the side panel is a right column beside the canvas.
            if (landscape) {
                viewModel.panel?.let { panel ->
                    SidePanel(
                        panel = panel,
                        viewModel = viewModel,
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
                    viewModel = viewModel,
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

/** The agent `summary` + cards, or (on failure) the error card body. */
@Composable
private fun SidePanel(
    panel: PanelModel,
    viewModel: CanvasViewModel,
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
                        PanelCardRow(
                            viewModel = viewModel,
                            panel = panel,
                            index = index,
                            card = card,
                            selected = viewModel.selectedCardIndex == index,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One card in the panel. The title row toggles the body and (Stage 10) pulses the card's
 * anchored canvas region (card → canvas, SPEC §4.7). An `answer` card starts expanded;
 * every other kind starts collapsed. With [CanvasViewModel.cardActionsEnabled] the row
 * shows the card's state and its actions — `confirm`/`reject` as live buttons, every
 * other kind disabled with "coming later" — and the body renders as Markdown; otherwise
 * the Stage-7 read-only body (plain text) is shown. A [selected] card (from a canvas mark
 * tap) is force-expanded.
 */
@Composable
private fun PanelCardRow(
    viewModel: CanvasViewModel,
    panel: PanelModel,
    index: Int,
    card: PanelCard,
    selected: Boolean,
) {
    var expanded by remember(panel, index) { mutableStateOf(card.expandedByDefault) }
    // Canvas → card: a tap on the mark expands and scrolls the panel to this card.
    LaunchedEffect(selected) {
        if (selected) {
            expanded = true
            viewModel.onCardSelectionConsumed()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                expanded = !expanded
                viewModel.onCardTapped(card) // card → canvas anchor pulse
            }
            .testTag(CanvasTags.panelCard(index)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = (if (expanded) "▾ " else "▸ ") + card.title,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).testTag(CanvasTags.panelCardTitle(index)),
            )
            if (viewModel.cardActionsEnabled) {
                CardStateChip(state = card.state, tag = CanvasTags.panelCardState(index))
            }
        }
        if (expanded && card.body.isNotBlank()) {
            val bodyModifier = Modifier
                .padding(start = 16.dp, top = 2.dp)
                .testTag(CanvasTags.panelCardBody(index))
            if (viewModel.cardActionsEnabled) {
                Text(text = markdownToAnnotatedString(card.body), modifier = bodyModifier)
            } else {
                Text(text = card.body, modifier = bodyModifier)
            }
        }
        if (expanded && viewModel.cardActionsEnabled && card.actions.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                card.actions.forEachIndexed { ai, action ->
                    // Stage 27: hide save_to_brain actions entirely when BuildConfig.BRAIN is off;
                    // when on, they are live (the server returns 200 since stages 25/26).
                    val hidden = action.kind == "save_to_brain" && !viewModel.brainEnabled
                    if (hidden) return@forEachIndexed
                    val tag = CanvasTags.panelCardAction(index, ai)
                    val actionable = action.supported ||
                        (action.kind == "save_to_brain" && viewModel.brainEnabled)
                    if (actionable) {
                        Button(
                            onClick = { viewModel.onCardAction(card, action) },
                            modifier = Modifier.testTag(tag),
                        ) { Text(action.label) }
                    } else {
                        OutlinedButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.testTag(tag),
                        ) { Text("${action.label} — coming later") }
                    }
                }
            }
        }
    }
}

/** A small state pill for a card: open / done / dismissed. */
@Composable
private fun CardStateChip(state: String, tag: String) {
    val bg = when (state) {
        "done" -> Color(0xFF2E7D32)
        "dismissed" -> Color(0xFF9E9E9E)
        else -> Color(0xFF1B6EF3)
    }
    Text(
        text = state,
        color = Color.White,
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag(tag),
    )
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
                    if (row.owner == "document") {
                        // Stage 22: the pushed "Document" (raster) layer's visibility toggle.
                        Switch(
                            checked = viewModel.documentVisible,
                            onCheckedChange = { viewModel.toggleDocumentLayer() },
                            modifier = Modifier.testTag(CanvasTags.LAYER_TOGGLE_DOCUMENT),
                        )
                    } else if (row.owner == "agent") {
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
    val cardActions = viewModel.cardActionsEnabled
    val oneTap = viewModel.oneTapAsk
    androidx.compose.material3.AlertDialog(
        onDismissRequest = viewModel::dismissInstruction,
        title = { Text(if (cardActions) "Send to agent" else if (oneTap) "Add a note" else "Send to agent") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (cardActions) {
                    // Job-type picker: Ask / Mark up (Stage 10), plus Formalize (Stage 12,
                    // behind BuildConfig.FORMALIZE). Extract / Action remain Phase 3+.
                    val formalize = viewModel.formalizeEnabled
                    val brain = viewModel.brainEnabled
                    Text("Job type", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SegmentedChoice(
                            label = "Ask",
                            selected = viewModel.jobType == "ask",
                            tag = CanvasTags.INSTRUCTION_ASK,
                        ) { viewModel.selectJobType("ask") }
                        SegmentedChoice(
                            label = "Mark up",
                            selected = viewModel.jobType == "annotate",
                            tag = CanvasTags.INSTRUCTION_MARKUP,
                        ) { viewModel.selectJobType("annotate") }
                        if (formalize) {
                            SegmentedChoice(
                                label = "Formalize",
                                selected = viewModel.jobType == "formalize",
                                tag = CanvasTags.INSTRUCTION_FORMALIZE,
                            ) { viewModel.selectJobType("formalize") }
                        }
                        // Stage 27: the fourth option — Remember (posts canvas.extract).
                        if (brain) {
                            SegmentedChoice(
                                label = "Remember",
                                selected = viewModel.jobType == "extract",
                                tag = CanvasTags.INSTRUCTION_REMEMBER,
                            ) { viewModel.selectJobType("extract") }
                        }
                    }
                    // The still-unimplemented Phase 3+ types stay greyed (Formalize drops out
                    // once its flag is on; Extract drops out once BuildConfig.BRAIN is on).
                    val greyed = buildList {
                        if (!formalize) add("Formalize")
                        if (!brain) add("Extract")
                        add("Action")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        greyed.forEach { label ->
                            OutlinedButton(onClick = {}, enabled = false) { Text(label) }
                        }
                    }
                    Text("${greyed.joinToString(" / ")} — Phase 3+", color = Color(0xFF9E9E9E))
                }
                OutlinedTextField(
                    value = viewModel.instruction,
                    onValueChange = viewModel::onInstructionChange,
                    label = {
                        Text(
                            if (cardActions) {
                                if (viewModel.jobType == "annotate") "Instruction (optional)" else "Note for the agent (optional)"
                            } else if (oneTap) {
                                "Note for the agent (optional)"
                            } else {
                                "Instruction"
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag(CanvasTags.INSTRUCTION_FIELD),
                )
                if (!cardActions && oneTap) {
                    TextButton(
                        onClick = viewModel::sendAnnotate,
                        enabled = viewModel.online,
                        modifier = Modifier.testTag(CanvasTags.INSTRUCTION_ANNOTATE),
                    ) { Text("Mark it up instead") }
                } else if (!cardActions) {
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
                onClick = { if (cardActions) viewModel.onSendTapped() else viewModel.send() },
                enabled = viewModel.online,
                modifier = Modifier.testTag(CanvasTags.INSTRUCTION_SEND),
            ) { Text(if (cardActions) viewModel.sendLabel else "Send") }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissInstruction) { Text("Cancel") }
        },
    )
}

/** A segmented-control choice (selected → filled Button, else OutlinedButton). */
@Composable
private fun SegmentedChoice(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.testTag(tag)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.testTag(tag)) { Text(label) }
    }
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

/**
 * Stage 11 canvas top bar: a **back** button that returns to the folder the canvas lives
 * in, and an **editable title** — tap it to edit, the change is persisted via
 * [CanvasViewModel.renameCanvas]. Only shown when the Library launched this canvas.
 */
@Composable
private fun CanvasTopBar(viewModel: CanvasViewModel, onBack: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(viewModel.title) { mutableStateOf(viewModel.title) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack, modifier = Modifier.testTag(CanvasTags.BACK)) {
            Text("< Library")
        }
        if (editing) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag(CanvasTags.TITLE_FIELD),
            )
            TextButton(
                onClick = { viewModel.renameCanvas(draft); editing = false },
                modifier = Modifier.testTag(CanvasTags.TITLE_SAVE),
            ) { Text("Save") }
        } else {
            Text(
                text = viewModel.title.ifBlank { "Untitled" },
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .clickable { draft = viewModel.title; editing = true }
                    .testTag(CanvasTags.TITLE),
            )
        }
    }
}

@Composable
private fun Toolbar(
    viewModel: CanvasViewModel,
    onOpenSettings: () -> Unit,
    debugEnabled: Boolean,
) {
    // Stage 28: the actions live in a horizontally-scrollable strip so a long toolbar
    // never clips them off-screen, while the overflow (⋮) — which holds Undo and the
    // always-reachable Settings — stays pinned to the trailing edge at any width.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
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
                // The note sheet is reachable whenever one-tap ask OR the Stage-10 picker is on
                // (the picker lives inside the sheet).
                if (viewModel.oneTapAsk || viewModel.cardActionsEnabled) {
                    // Stage 11: a visible "Note" label sits beside the pencil — the owner
                    // could not find the bare icon (2026-09-16). The whole affordance opens
                    // the note sheet.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable(enabled = viewModel.online, onClick = viewModel::openInstruction)
                            .testTag(CanvasTags.ADD_NOTE),
                    ) {
                        IconButton(
                            onClick = viewModel::openInstruction,
                            enabled = viewModel.online,
                        ) { Icon(Icons.Filled.Edit, contentDescription = "Add a note…") }
                        Text("Note", modifier = Modifier.testTag(CanvasTags.NOTE_LABEL))
                    }
                }
                Button(
                    onClick = viewModel::onSendTapped,
                    enabled = viewModel.online,
                    modifier = Modifier.testTag(CanvasTags.SEND),
                ) { Text(viewModel.sendLabel) }
            }
        }

        // Trailing overflow: pinned (never scrolls, never clips). Holds Undo + Settings so
        // Settings is always one tap away regardless of the toolbar's width.
        var overflow by remember { mutableStateOf(false) }
        Box {
            IconButton(
                onClick = { overflow = true },
                modifier = Modifier.testTag(CanvasTags.OVERFLOW),
            ) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                DropdownMenuItem(
                    text = { Text("Undo") },
                    onClick = { overflow = false; viewModel.undoLast() },
                    modifier = Modifier.testTag(CanvasTags.UNDO),
                )
                DropdownMenuItem(
                    text = { Text("Settings") },
                    onClick = { overflow = false; onOpenSettings() },
                    modifier = Modifier.testTag(CanvasTags.SETTINGS),
                )
            }
        }
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

    // Stage 28: the trailing overflow (⋮) that holds Undo + Settings so Settings never clips.
    const val OVERFLOW = "canvas_overflow"
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
    const val LAYER_TOGGLE_DOCUMENT = "canvas_layer_toggle_document"

    // Stage 7 one-tap ask surfaces.
    const val ADD_NOTE = "canvas_add_note"
    const val NOTE_LABEL = "canvas_note_label"
    const val INSTRUCTION_ANNOTATE = "canvas_instruction_annotate"

    // Stage 11 canvas top bar (Library flow).
    const val BACK = "canvas_back"
    const val TITLE = "canvas_title"
    const val TITLE_FIELD = "canvas_title_field"
    const val TITLE_SAVE = "canvas_title_save"

    // Stage 10 job-type picker.
    const val INSTRUCTION_ASK = "canvas_instruction_ask"
    const val INSTRUCTION_MARKUP = "canvas_instruction_markup"

    // Stage 12 job-type picker: Formalize.
    const val INSTRUCTION_FORMALIZE = "canvas_instruction_formalize"

    // Stage 27 job-type picker: Remember (canvas.extract) + the "Saved to brain" snackbar.
    const val INSTRUCTION_REMEMBER = "canvas_instruction_remember"
    const val BRAIN_SNACKBAR = "canvas_brain_snackbar"
    const val BRAIN_VIEW_ACTION = "canvas_brain_view_action"

    fun color(index: Int) = "canvas_color_$index"
    fun panelCard(index: Int) = "canvas_panel_card_$index"
    fun panelCardTitle(index: Int) = "canvas_panel_card_title_$index"
    fun panelCardBody(index: Int) = "canvas_panel_card_body_$index"

    // Stage 10 card state + actions.
    fun panelCardState(index: Int) = "canvas_panel_card_state_$index"
    fun panelCardAction(cardIndex: Int, actionIndex: Int) =
        "canvas_panel_card_action_${cardIndex}_$actionIndex"
}
