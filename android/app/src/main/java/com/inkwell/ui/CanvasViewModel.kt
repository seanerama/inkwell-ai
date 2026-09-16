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
import com.inkwell.data.LayerRepository
import com.inkwell.data.StrokeCommitData
import com.inkwell.ink.StrokeCommit
import com.inkwell.net.Connectivity
import com.inkwell.net.DeviceRepository
import com.inkwell.net.JobResultHandler
import com.inkwell.net.JobRequestBuilder
import com.inkwell.net.LoopController
import com.inkwell.net.OfflineJobQueue
import com.inkwell.render.AnnotationRenderer
import com.inkwell.render.CanvasExporter
import com.inkwell.render.ExportLayer
import com.inkwell.render.RenderStroke
import com.inkwell.render.StrokeMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** The canvas exporter (contract coordinate-mapping); injectable so `send()` is JVM-testable. */
    private val exporter: (Int, Int, List<ExportLayer>) -> CanvasExporter.Result = CanvasExporter::export,
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

    /** Committed strokes in insertion order; the last is what undo removes. */
    val strokes: SnapshotStateList<RenderStroke> = mutableStateListOf()

    private var inkLayerId: String? = null
    private var canvasId: String? = null

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
        viewModelScope.launch {
            val state = repository.openDefaultCanvas()
            inkLayerId = state.inkLayerId
            canvasId = state.canvasId
            canvasWidth = state.widthCu
            canvasHeight = state.heightCu
            strokes.clear()
            strokes.addAll(state.strokes.map(StrokeMapper::toRenderStroke))
            refreshLayerRows()
            ready = true
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
        if (oneTapAsk) send() else openInstruction()
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
                val outcome = controller.run(request, cId) { /* queued: indicator already on */ }
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
        agentLayerVisible = true
        panel = PanelModel(
            summary = outcome.summary ?: "",
            cards = outcome.cards.map(PanelCard::from),
            isError = false,
        )
        refreshLayerRows()
    }

    fun dismissPanel() { panel = null }

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
        val PALETTE = listOf("#111111", "#1B6EF3", "#E5484D")

        /** The feel-test preset instruction (SPEC Phase 1 acceptance / stage). */
        const val PRESET_INSTRUCTION = "Highlight the most important box"
    }
}
