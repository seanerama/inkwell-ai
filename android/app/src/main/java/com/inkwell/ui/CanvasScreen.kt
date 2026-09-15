package com.inkwell.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.inkwell.BuildConfig
import com.inkwell.ink.InkView

/**
 * The launch surface (SPEC §9.3, this stage): a full-bleed ink canvas with a minimal
 * toolbar — pen, marker, eraser, three colors, and undo-last-stroke. The canvas is a
 * custom [InkView] (raw `MotionEvent` capture, §9.2) hosted via [AndroidView];
 * committed strokes and the selected tool/color flow in through the `update` block so
 * recomposition never rebuilds the view. Pairing moves to a settings entry
 * ([onOpenSettings]).
 */
@Composable
fun CanvasScreen(
    viewModel: CanvasViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    debugEnabled: Boolean = BuildConfig.DEBUG,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Toolbar(viewModel = viewModel, onOpenSettings = onOpenSettings)
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
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
                },
            )
        }
    }
}

@Composable
private fun Toolbar(
    viewModel: CanvasViewModel,
    onOpenSettings: () -> Unit,
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
    fun color(index: Int) = "canvas_color_$index"
}
