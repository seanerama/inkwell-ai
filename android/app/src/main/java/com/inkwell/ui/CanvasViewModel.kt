package com.inkwell.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwell.BuildConfig
import com.inkwell.contracts.Annotation
import com.inkwell.data.CanvasRepository
import com.inkwell.data.CardStatePersistence
import com.inkwell.data.LayerRepository
import com.inkwell.data.StrokeCommitData
import com.inkwell.ink.StrokeCommit
import com.inkwell.net.Connectivity
import com.inkwell.net.DeviceRepository
import com.inkwell.net.JobResultHandler
import com.inkwell.net.JobRequestBuilder
import com.inkwell.net.LoopController
import com.inkwell.net.OfflineJobQueue
import com.inkwell.render.AnchorHitTest
import com.inkwell.render.AnnotationRenderer
import com.inkwell.render.CanvasExporter
import com.inkwell.render.ExportLayer
import com.inkwell.render.RenderStroke
import com.inkwell.render.StrokeMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State + actions for [CanvasScreen]. Loads the default canvas and its strokes on
 * open (ink survives restart), persists a stroke on pen-up, and supports eraser and
 * undo-last-stroke. Committed strokes are held as [RenderStroke]s in insertion order
 * so undo removes exactly the most recent one.
 */
class CanvasViewModel(
    private val repository: CanvasRepository,
    // --- Stage 6 loop collaborators (all optional so existing construction/tests hold) ---
    private val layerRepository: LayerRepository? = null,
    /** Builds a paired [DeviceRepository], or null when the device is not paired. */
    private val deviceRepositoryProvider: () -> DeviceRepository? = { null },
    private val connectivity: Connectivity = Connectivity.AlwaysOnline,
    /** Shared offline queue (process singleton in production). */
    private val offlineQueue: OfflineJobQueue = OfflineJobQueue(),
    /** The kill-switch: Send is only present when this is true (release default OFF). */
    val sendEnabled: Boolean = BuildConfig.SEND_ENABLED,
    /**
     * Stage 7 kill-switch: ON → Send is one tap and posts `canvas.ask` with no
     * instruction (the note is optional, behind "Add a note…"); OFF → the Stage-6
     * sheet-first `canvas.annotate` flow.
     */
    val oneTapAsk: Boolean = BuildConfig.ONE_TAP_ASK,
    /**
     * Stage 10 kill-switch: ON → cards are real objects (action buttons, device-driven
     * state, tappable anchors, Markdown bodies, and the Ask/Mark-up job-type picker);
     * OFF → the Stage-7 read-only cards. Default from [BuildConfig.CARD_ACTIONS].
     */
    val cardActionsEnabled: Boolean = BuildConfig.CARD_ACTIONS,
    /** Loads the last chosen job type ("ask"/"annotate"); persisted per the picker. */
    private val loadJobType: () -> String = { "ask" },
    /** Persists the chosen job type (Stage 10 picker remembers the last choice). */
    private val saveJobType: (String) -> Unit = {},
    /** Per-card state persistence (Room-backed in the app; null in lightweight tests). */
    private val cardStatePersistence: CardStatePersistence? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** The canvas exporter (contract coordinate-mapping); injectable so `send()` is JVM-testable. */
    private val exporter: (Int, Int, List<ExportLayer>) -> CanvasExporter.Result = CanvasExporter::export,
    /**
     * Stage 11: when true (the flag-OFF / legacy path) the ViewModel opens the default
     * canvas on creation. With the Library ([BuildConfig.LIBRARY]) on, MainActivity drives
     * [openCanvas] for a chosen tile instead, so this is set false to avoid a wasted load.
     */
    private val autoOpenDefault: Boolean = true,
) : ViewModel() {

    var tool by mutableStateOf("pen")
        private set
    var colorHex by mutableStateOf(PALETTE[0])
        private set
    var widthCu by mutableStateOf(DEFAULT_WIDTH_CU)
        private set
    var canvasWidth by mutableStateOf(CanvasRepository.DEFAULT_WIDTH_CU)
        private set
    var canvasHeight by mutableStateOf(CanvasRepository.DEFAULT_HEIGHT_CU)
        private set
    var ready by mutableStateOf(false)
        private set

    /** Stage 11: the current canvas title, shown/edited in the canvas top bar. */
    var title by mutableStateOf("")
        private set

    /** Committed strokes in insertion order; the last is what undo removes. */
    val strokes: SnapshotStateList<RenderStroke> = mutableStateListOf()

    private var inkLayerId: String? = null
    private var canvasId: String? = null

    /** Stage 11: the folder the open canvas lives in (null = root), for Back navigation. */
    var currentFolderId: String? = null
        private set

    /** Stage 11: the id of the open canvas (null until loaded), for thumbnail rendering. */
    val currentCanvasId: String? get() = canvasId

    // --- Stage 6: send/poll/render loop state (SPEC §9.4) ---

    /** True while the device has a validated internet connection (SPEC §9.5). */
    var online by mutableStateOf(connectivity.isOnline())
        private set

    /** Whether the note/instruction sheet is showing. */
    var showInstruction by mutableStateOf(false)
        private set

    /** The typed note/instruction (SPEC §9.4 step 1). Optional for `canvas.ask`. */
    var instruction by mutableStateOf("")
        private set

    /** Non-blocking in-progress indicator — the user keeps drawing (SPEC §9.4 step 4). */
    var jobInProgress by mutableStateOf(false)
        private set

    /** Inline send status/error text (offline, too-large, not-paired, failures). */
    var sendStatus by mutableStateOf<String?>(null)
        private set

    /** The side-panel content after a terminal job (null until one arrives). */
    var panel by mutableStateOf<PanelModel?>(null)
        private set

    /** Annotations currently rendered on the agent layer (every type: native or fallback). */
    var agentAnnotations by mutableStateOf<List<Annotation>>(emptyList())
        private set

    /** Agent-layer visibility toggled from the layer tray. */
    var agentLayerVisible by mutableStateOf(true)
        private set

    /** Whether the minimal layer tray is expanded. */
    var showLayerTray by mutableStateOf(false)
        private set

    /** Rows for the layer tray (ink + any agent layers). */
    val layerRows: SnapshotStateList<LayerRow> = mutableStateListOf()

    // --- Stage 10: job-type picker, card actions/state, and anchor interaction ---

    /** The chosen job type for the primary Send: "ask" (canvas.ask) or "annotate". */
    var jobType by mutableStateOf(if (cardActionsEnabled) loadJobType() else "ask")
        private set

    /** The primary Send button label follows the picker: "Send" for ask, "Mark up" for annotate. */
    val sendLabel: String
        get() = when {
            !online -> "Offline"
            cardActionsEnabled && jobType == "annotate" -> "Mark up"
            else -> "Send"
        }

    /** The terminal job whose cards the panel is showing (needed to change card state). */
    private var currentJobId: String? = null

    /** Agent annotations indexed by id, for anchor hit-testing (both directions, §4.7). */
    private var annotationsById: Map<String, Annotation> = emptyMap()

    /** CU rects `[x,y,w,h]` currently pulsing on the canvas after a card tap (~1.5 s). */
    var anchorPulses by mutableStateOf<List<DoubleArray>>(emptyList())
        private set

    /** The card index the panel should expand + scroll to after a canvas mark tap. */
    var selectedCardIndex by mutableStateOf<Int?>(null)
        private set

    /** Monotonic token so a newer card tap cancels an older pulse's auto-clear. */
    private var pulseToken = 0

    /** The resolved server `work` space id (looked up by slug, cached per session). */
    private var workSpaceId: String? = null

    // --- Debug-only (BuildConfig.DEBUG) export-preview + fixture-render state ---
    /** Space accent color for agent annotations (SPEC §6.3). */
    val accentColor: Int = AnnotationRenderer.DEFAULT_ACCENT

    var showExportPreview by mutableStateOf(false)
        private set
    var exportBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var exportInfo by mutableStateOf<String?>(null)
        private set

    var fixtureVisible by mutableStateOf(false)
        private set

    /** Fixture annotations to overlay when the "Render fixture" toggle is on. */
    val fixtureAnnotations: List<Annotation>
        get() = if (fixtureVisible) DebugFixtures.annotations else emptyList()

    init {
        if (autoOpenDefault) {
            viewModelScope.launch {
                applyState(repository.openDefaultCanvas())
            }
        }
        // Observe connectivity so Send disables/enables and a reconnect flushes the queue.
        viewModelScope.launch {
            connectivity.online.collect { isOnline ->
                val was = online
                online = isOnline
                if (isOnline && !was) flushOfflineQueue()
            }
        }
    }

    /** Apply a loaded [com.inkwell.data.CanvasState] to the screen state. */
    private fun applyState(state: com.inkwell.data.CanvasState) {
        inkLayerId = state.inkLayerId
        canvasId = state.canvasId
        currentFolderId = state.folderId
        title = state.title
        canvasWidth = state.widthCu
        canvasHeight = state.heightCu
        strokes.clear()
        strokes.addAll(state.strokes.map(StrokeMapper::toRenderStroke))
        refreshLayerRows()
        ready = true
    }

    /**
     * Stage 11 (Library flow): open a specific canvas by id and load its strokes. Resets
     * any transient send/panel state so the newly opened canvas starts clean.
     */
    fun openCanvas(canvasId: String) {
        if (this.canvasId == canvasId && ready) return
        ready = false
        panel = null
        agentAnnotations = emptyList()
        viewModelScope.launch {
            repository.openCanvas(canvasId)?.let { applyState(it) }
        }
    }

    /** Stage 11: rename the open canvas (top-bar title edit); persists via the repository. */
    fun renameCanvas(newTitle: String) {
        val id = canvasId ?: return
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty() || trimmed == title) return
        title = trimmed
        viewModelScope.launch { repository.renameCanvas(id, trimmed) }
    }

    fun selectTool(value: String) { tool = value }

    fun selectColor(hex: String) {
        colorHex = hex
        if (tool == "eraser") tool = "pen" // choosing a color implies a drawing tool
    }

    /** Persist a stroke committed on pen-up and add it to the render list. */
    fun onStrokeCommitted(commit: StrokeCommit) {
        val layerId = inkLayerId ?: return
        viewModelScope.launch {
            val entity = repository.insertStroke(
                layerId = layerId,
                commit = StrokeCommitData(
                    stroke = commit.stroke,
                    tool = commit.tool,
                    colorHex = commit.colorHex,
                    widthCu = commit.widthCu,
                ),
            )
            strokes.add(StrokeMapper.toRenderStroke(entity))
        }
    }

    /** Eraser removed a stroke by id. */
    fun onEraseStroke(id: String) {
        val idx = strokes.indexOfFirst { it.id == id }
        if (idx < 0) return
        strokes.removeAt(idx)
        viewModelScope.launch { repository.deleteStroke(id) }
    }

    /** Undo removes only the last committed stroke. */
    fun undoLast() {
        if (strokes.isEmpty()) return
        val last = strokes.removeAt(strokes.lastIndex)
        viewModelScope.launch { repository.deleteStroke(last.id) }
    }

    /**
     * Debug-only: export the current canvas per contract `coordinate-mapping` and show
     * the resulting PNG and its dimensions. Gated at the call site by
     * `BuildConfig.DEBUG` ([CanvasScreen]).
     */
    fun exportPreview() {
        viewModelScope.launch {
            val layers = listOf(ExportLayer(z = 0, visible = true, strokes = strokes.toList()))
            val result = withContext(Dispatchers.Default) {
                CanvasExporter.export(canvasWidth, canvasHeight, layers)
            }
            when (result) {
                is CanvasExporter.Result.Success -> {
                    val bmp = withContext(Dispatchers.Default) {
                        BitmapFactory.decodeByteArray(result.png, 0, result.png.size)
                    }
                    exportBitmap = bmp
                    exportInfo = "${result.export.w} × ${result.export.h} px  " +
                        "(${result.png.size / 1024} KB)"
                }
                is CanvasExporter.Result.TooLarge -> {
                    exportBitmap = null
                    exportInfo = result.message
                }
            }
            showExportPreview = true
        }
    }

    fun dismissExportPreview() {
        showExportPreview = false
        exportBitmap = null
        exportInfo = null
    }

    /** Debug-only: toggle the fixture-highlight overlay on the current canvas. */
    fun toggleFixture() {
        fixtureVisible = !fixtureVisible
    }

    // --- Stage 6/7: send flow (SPEC §9.4) ---

    /**
     * The **Send** button. Stage 7 (`oneTapAsk`): one tap sends `canvas.ask` with no
     * instruction — no sheet. Kill-switch OFF: the Stage-6 behaviour, open the
     * instruction sheet first.
     */
    fun onSendTapped() {
        if (!sendEnabled) return
        if (cardActionsEnabled) {
            sendByJobType()
        } else if (oneTapAsk) {
            send()
        } else {
            openInstruction()
        }
    }

    /** Stage 10 picker: remember the choice; the Send label follows it. */
    fun selectJobType(type: String) {
        if (type != "ask" && type != "annotate") return // formalize/extract/action are Phase 3+
        jobType = type
        saveJobType(type)
    }

    /**
     * Stage 10 one-tap send honouring the job-type picker: "ask" posts `canvas.ask`
     * (typed note, or none when blank); "annotate" posts `canvas.annotate` (typed note,
     * or the feel-test preset when blank).
     */
    fun sendByJobType() {
        if (jobType == "annotate") {
            submit(type = "canvas.annotate", instr = instruction.trim().ifBlank { PRESET_INSTRUCTION })
        } else {
            submit(type = "canvas.ask", instr = instruction.trim().ifBlank { null })
        }
    }

    /** "Add a note…" (Stage 7) / the Stage-6 sheet: open the note/instruction sheet. */
    fun openInstruction() {
        if (!sendEnabled) return
        sendStatus = null
        showInstruction = true
    }

    fun dismissInstruction() { showInstruction = false }

    fun onInstructionChange(value: String) { instruction = value }

    /** The feel-test preset (SPEC Phase 1 acceptance); used by the Stage-6 sheet. */
    fun usePreset() { instruction = PRESET_INSTRUCTION }

    /** Layer-tray: toggle the agent layer's visibility so placement can be judged. */
    fun toggleAgentLayer() {
        agentLayerVisible = !agentLayerVisible
        refreshLayerRows()
    }

    fun toggleLayerTray() { showLayerTray = !showLayerTray }

    /**
     * The primary send (SPEC §9.4, Stage 7). With `oneTapAsk` it posts `canvas.ask`
     * with the typed note as `instruction`, or **no instruction at all** when the note
     * is blank — the agent reads the note and responds; there is no preset fallback
     * for ask. With the kill-switch OFF it is the Stage-6 send: `canvas.annotate` with
     * the typed text, or the feel-test preset when blank.
     */
    fun send() {
        if (oneTapAsk) {
            submit(type = "canvas.ask", instr = instruction.trim().ifBlank { null })
        } else {
            submit(type = "canvas.annotate", instr = instruction.ifBlank { PRESET_INSTRUCTION })
        }
    }

    /**
     * "Mark it up instead" (the Stage-6 flow, now inside the note sheet): posts
     * `canvas.annotate` with the typed text, or the feel-test preset when blank.
     */
    fun sendAnnotate() {
        submit(type = "canvas.annotate", instr = instruction.trim().ifBlank { PRESET_INSTRUCTION })
    }

    /**
     * Send flow (SPEC §9.4): export the canvas, build the job body for [type], then
     * either enqueue it (offline) or drive the loop to a terminal state and apply the
     * result. Never locks the UI — the caller keeps drawing while this runs.
     */
    private fun submit(type: String, instr: String?) {
        if (!sendEnabled) return
        showInstruction = false
        val cId = canvasId ?: run { sendStatus = "Canvas not ready yet."; return }
        val dev = deviceRepositoryProvider() ?: run {
            sendStatus = "Not paired — set the server URL and token in Settings."
            return
        }
        val layerRepo = layerRepository ?: run { sendStatus = "Layer store unavailable."; return }
        // The note is per-send: clear it so the next one-tap Send carries no stale note.
        instruction = ""

        viewModelScope.launch {
            // 1–2. Export the PNG (contract coordinate-mapping), off the main thread.
            val exportLayers = listOf(ExportLayer(z = 0, visible = true, strokes = strokes.toList()))
            val result = withContext(ioDispatcher) {
                exporter(canvasWidth, canvasHeight, exportLayers)
            }
            val (png, export) = when (result) {
                is CanvasExporter.Result.Success -> result.png to result.export
                is CanvasExporter.Result.TooLarge -> { sendStatus = result.message; return@launch }
            }

            // Resolve the seeded work space id (by slug), cached per session.
            val spaceId = workSpaceId ?: try {
                dev.workSpaceId()?.also { workSpaceId = it }
            } catch (e: Exception) {
                sendStatus = "Could not load spaces: ${e.message ?: e.javaClass.simpleName}"
                return@launch
            }
            if (spaceId == null) { sendStatus = "No 'work' space on the server."; return@launch }

            val request = JobRequestBuilder.build(
                type = type,
                spaceId = spaceId,
                canvasId = cId,
                pngBytes = png,
                export = export,
                instruction = instr, // null → omitted from the body (explicitNulls=false)
            )

            // 3. Offline: hold the job locally, flushed in order on reconnect (SPEC §9.5).
            if (!connectivity.isOnline()) {
                offlineQueue.enqueue(request)
                sendStatus = "Offline — will send when reconnected."
                return@launch
            }

            // 4. Non-blocking indicator on; the user can keep drawing.
            jobInProgress = true
            sendStatus = null
            try {
                val controller = LoopController(dev, JobResultHandler(layerRepo))
                val outcome = controller.run(request, cId) { queued -> currentJobId = queued.id }
                applyOutcome(outcome)
            } catch (e: Exception) {
                sendStatus = "Send failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                jobInProgress = false
            }
        }
    }

    /** Apply a terminal [com.inkwell.net.LoopOutcome] to the UI (SPEC §9.4 step 6). */
    private suspend fun applyOutcome(outcome: com.inkwell.net.LoopOutcome) {
        if (outcome.isError) {
            // failed: show the error card body, render NO layer.
            panel = PanelModel(
                summary = "",
                cards = emptyList(),
                isError = true,
                errorTitle = outcome.errorTitle,
                errorBody = outcome.errorBody,
            )
            return
        }
        // done: render every annotation and open the panel with the summary + cards.
        agentAnnotations = outcome.annotations
        annotationsById = outcome.annotations.associateBy { it.id }
        agentLayerVisible = true
        anchorPulses = emptyList()
        selectedCardIndex = null
        // Prefer the server's cards (identity + state + actions) when card-actions are on;
        // otherwise fall back to the parsed agent-output cards (Stage-7 read-only shape).
        val panelCards = if (cardActionsEnabled && outcome.serverCards.isNotEmpty()) {
            outcome.serverCards.map { PanelCard.from(it) }
        } else {
            outcome.cards.map { PanelCard.from(it) }
        }
        panel = PanelModel(
            summary = outcome.summary ?: "",
            cards = panelCards,
            isError = false,
        )
        // Persist the initial per-card state so a reopened canvas reflects it offline.
        if (cardActionsEnabled) persistAllCardStates(panelCards)
        refreshLayerRows()
    }

    fun dismissPanel() {
        panel = null
        anchorPulses = emptyList()
        selectedCardIndex = null
    }

    // --- Stage 10: card actions + state (optimistic then reconcile from the server) ---

    /**
     * Invoke a card [action] (Stage 10). Supported kinds (`confirm`/`reject`) update the
     * row **optimistically**, then reconcile from the server's returned card; on failure
     * the optimistic change is reverted. Unsupported kinds and the OFF kill-switch no-op.
     */
    fun onCardAction(card: PanelCard, action: PanelAction) {
        if (!cardActionsEnabled || !action.supported || card.id.isEmpty()) return
        val dev = deviceRepositoryProvider() ?: run {
            sendStatus = "Not paired — set the server URL and token in Settings."
            return
        }
        val previous = card.state
        val optimistic = CardStateReducer.optimisticState(action.kind, previous)
        updatePanelCardState(card.id, optimistic)
        persistCardState(card.id, optimistic)
        viewModelScope.launch {
            try {
                val updated = dev.runCardAction(card.id, action.id)
                updatePanelCardState(updated.id, updated.state)
                persistCardState(updated.id, updated.state)
            } catch (e: Exception) {
                // Reconcile back to the pre-tap state (the server rejected or is unreachable).
                updatePanelCardState(card.id, previous)
                persistCardState(card.id, previous)
                sendStatus = "Action failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Replace the panel's copy of a card's state in place (by id). */
    private fun updatePanelCardState(cardId: String, state: String) {
        panel = panel?.let { it.copy(cards = CardStateReducer.withState(it.cards, cardId, state)) }
    }

    private fun persistCardState(cardId: String, state: String) {
        val store = cardStatePersistence ?: return
        val jobId = currentJobId ?: return
        viewModelScope.launch {
            try {
                store.save(cardId, jobId, state, System.currentTimeMillis())
            } catch (_: Exception) {
                // Persistence is a convenience; a write failure never blocks the UI.
            }
        }
    }

    private fun persistAllCardStates(cards: List<PanelCard>) {
        val store = cardStatePersistence ?: return
        val jobId = currentJobId ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            cards.filter { it.id.isNotEmpty() }.forEach { card ->
                try {
                    store.save(card.id, jobId, card.state, now)
                } catch (_: Exception) {
                    // Ignore — best-effort offline cache.
                }
            }
        }
    }

    // --- Stage 10: anchors (both directions, SPEC §4.7) ---

    /**
     * Card → canvas: tapping a card pulses a highlight over each of its anchored
     * annotation bounds (or `region` rect) for ~1.5 s. No-op when the card has no
     * resolvable anchors or the kill-switch is OFF.
     */
    fun onCardTapped(card: PanelCard) {
        if (!cardActionsEnabled) return
        val regions = card.anchors.map { AnchorHitTest.AnchorRegion(it.annotationId, it.region) }
        val rects = AnchorHitTest.rectsForAnchors(regions, annotationsById, canvasWidth, canvasHeight)
        if (rects.isEmpty()) return
        anchorPulses = rects
        val token = ++pulseToken
        viewModelScope.launch {
            delay(ANCHOR_PULSE_MS)
            if (pulseToken == token) anchorPulses = emptyList()
        }
    }

    /**
     * Canvas → card: a tap at canvas-unit `(xCu, yCu)` selects the first card whose
     * anchor bounds contain it, so the panel expands + scrolls to it. No-op when the tap
     * hits no anchored mark.
     */
    fun onCanvasTapCu(xCu: Float, yCu: Float) {
        if (!cardActionsEnabled) return
        val cards = panel?.cards ?: return
        val cardAnchors = cards.map { card ->
            card.anchors.map { AnchorHitTest.AnchorRegion(it.annotationId, it.region) }
        }
        val index = AnchorHitTest.cardIndexForTap(
            xCu.toDouble(), yCu.toDouble(), cardAnchors, annotationsById, canvasWidth, canvasHeight,
        )
        if (index != null) selectedCardIndex = index
    }

    /** The UI calls this once it has expanded/scrolled to [selectedCardIndex]. */
    fun onCardSelectionConsumed() { selectedCardIndex = null }

    /** Flush offline-created jobs in insertion order when connectivity returns. */
    private fun flushOfflineQueue() {
        val dev = deviceRepositoryProvider() ?: return
        val cId = canvasId ?: return
        val layerRepo = layerRepository ?: return
        viewModelScope.launch {
            try {
                val handler = JobResultHandler(layerRepo)
                offlineQueue.flush { pending ->
                    val queued = dev.submitAgentJob(pending.request)
                    val terminal = dev.pollUntilTerminal(queued.id, dev.sync(null).cursor)
                    applyOutcome(handler.handle(terminal, cId))
                }
            } catch (e: Exception) {
                sendStatus = "Reconnect flush failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Rebuild the layer-tray rows from the persisted layers (agent layers appended). */
    private fun refreshLayerRows() {
        val layerRepo = layerRepository
        val cId = canvasId
        viewModelScope.launch {
            val rows = mutableListOf<LayerRow>()
            rows += LayerRow(id = inkLayerId ?: "ink", label = "Ink", owner = "user", visible = true)
            if (layerRepo != null && cId != null) {
                layerRepo.layersFor(cId)
                    .filter { it.owner == "agent" }
                    .forEach { l ->
                        rows += LayerRow(
                            id = l.id,
                            label = "Agent annotations",
                            owner = "agent",
                            visible = agentLayerVisible,
                        )
                    }
            }
            layerRows.clear()
            layerRows.addAll(rows)
        }
    }

    companion object {
        const val DEFAULT_WIDTH_CU = 3f

        /** How long a card-tap anchor pulse stays lit on the canvas (SPEC §4.7 ~1.5 s). */
        const val ANCHOR_PULSE_MS = 1_500L

        val PALETTE = listOf("#111111", "#1B6EF3", "#E5484D")

        /** The feel-test preset instruction (SPEC Phase 1 acceptance / stage). */
        const val PRESET_INSTRUCTION = "Highlight the most important box"
    }
}
