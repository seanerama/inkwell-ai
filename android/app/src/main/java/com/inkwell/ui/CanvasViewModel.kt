package com.inkwell.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwell.data.CanvasRepository
import com.inkwell.data.StrokeCommitData
import com.inkwell.ink.StrokeCommit
import com.inkwell.render.RenderStroke
import com.inkwell.render.StrokeMapper
import kotlinx.coroutines.launch

/**
 * State + actions for [CanvasScreen]. Loads the default canvas and its strokes on
 * open (ink survives restart), persists a stroke on pen-up, and supports eraser and
 * undo-last-stroke. Committed strokes are held as [RenderStroke]s in insertion order
 * so undo removes exactly the most recent one.
 */
class CanvasViewModel(
    private val repository: CanvasRepository,
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

    init {
        viewModelScope.launch {
            val state = repository.openDefaultCanvas()
            inkLayerId = state.inkLayerId
            canvasWidth = state.widthCu
            canvasHeight = state.heightCu
            strokes.clear()
            strokes.addAll(state.strokes.map(StrokeMapper::toRenderStroke))
            ready = true
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

    companion object {
        const val DEFAULT_WIDTH_CU = 3f
        val PALETTE = listOf("#111111", "#1B6EF3", "#E5484D")
    }
}
