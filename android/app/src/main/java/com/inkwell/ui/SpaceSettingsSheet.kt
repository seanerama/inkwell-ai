package com.inkwell.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.inkwell.data.SpaceEntity
import com.inkwell.render.AnnotationRenderer

/** The model presets offered by the dropdown, plus a free-text "Custom…" escape hatch. */
private val MODEL_PRESETS = listOf("claude-sonnet-5", "claude-opus-5", "claude-haiku-4-5-20251001")

/** The prompt's hard length limit (contract device-api §Stage 13: `system_prompt ≤ 8000`). */
private const val PROMPT_MAX = 8000

private const val PROMPT_HELPER =
    "How this space's agent should think and answer. It runs on the server before every job in this space."

/**
 * The swatch palette: the canvas pen palette ([CanvasViewModel.PALETTE]) followed by the four
 * Phase-3 seed colours. These are just `#RRGGBB` strings in the model; the composable renders
 * them (parsing via [AnnotationRenderer.accentFrom], the one safe colour parser).
 */
val SPACE_SWATCHES: List<String> =
    CanvasViewModel.PALETTE + listOf("#2F6FED", "#2FA84F", "#8A4FED", "#ED8A2F")

/**
 * Stage 15 space-settings sheet: edit a space's Name, Colour, Model and system Prompt, reorder
 * it with Move left/right, and Save the changed fields (`PATCH /spaces/{id}`). Save is disabled
 * until a field changes (and while an over-length prompt or an in-flight save blocks it). The
 * draft lives in local `remember` state; change-detection and the patch diff are the pure
 * [SpaceEdits] functions. Errors are shown inline and keep the sheet open.
 */
@Composable
fun SpaceSettingsSheet(
    space: SpaceEntity,
    viewModel: SpaceSettingsViewModel,
) {
    var name by remember(space.id) { mutableStateOf(space.name) }
    var color by remember(space.id) { mutableStateOf(space.color) }
    var prompt by remember(space.id) { mutableStateOf(space.systemPrompt) }
    var custom by remember(space.id) { mutableStateOf(space.model !in MODEL_PRESETS) }
    var model by remember(space.id) { mutableStateOf(space.model) }
    var modelMenu by remember { mutableStateOf(false) }

    val draft = SpaceEdits.Draft(name = name, color = color, model = model, systemPrompt = prompt)
    val changed = SpaceEdits.hasChanges(space, draft)
    val promptTooLong = prompt.length > PROMPT_MAX
    val canSave = changed && !promptTooLong && !viewModel.saving

    AlertDialog(
        onDismissRequest = { viewModel.closeSettings() },
        title = { Text("Space settings") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .testTag(SpaceSettingsTags.SHEET),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(SpaceSettingsTags.NAME_FIELD),
                )

                // Colour swatches
                Text("Colour", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SPACE_SWATCHES.forEach { hex ->
                        val selected = hex.equals(color, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(AnnotationRenderer.accentFrom(hex)), CircleShape)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) Color(0xFF111111) else Color(0xFFCCCCCC),
                                    shape = CircleShape,
                                )
                                .clickable { color = hex }
                                .testTag(SpaceSettingsTags.swatch(hex)),
                        )
                    }
                }

                // Model dropdown (presets + Custom…)
                Text("Model", fontWeight = FontWeight.SemiBold)
                Box {
                    OutlinedButton(
                        onClick = { modelMenu = true },
                        modifier = Modifier.fillMaxWidth().testTag(SpaceSettingsTags.MODEL),
                    ) { Text(if (custom) "Custom…" else model) }
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        MODEL_PRESETS.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset) },
                                onClick = {
                                    modelMenu = false
                                    custom = false
                                    model = preset
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Custom…") },
                            onClick = {
                                modelMenu = false
                                custom = true
                            },
                        )
                    }
                }
                if (custom) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Custom model") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag(SpaceSettingsTags.CUSTOM_MODEL_FIELD),
                    )
                }

                // System prompt
                Text("System prompt", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("System prompt") },
                    minLines = 4,
                    isError = promptTooLong,
                    supportingText = { Text("${prompt.length} / $PROMPT_MAX") },
                    modifier = Modifier.fillMaxWidth().testTag(SpaceSettingsTags.PROMPT_FIELD),
                )
                Text(PROMPT_HELPER, color = Color(0xFF757575))

                // Move left / right (reorder by swapping positions)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.move(space, -1) },
                        enabled = !viewModel.saving && viewModel.canMove(space, -1),
                        modifier = Modifier.testTag(SpaceSettingsTags.MOVE_LEFT),
                    ) { Text("Move left") }
                    OutlinedButton(
                        onClick = { viewModel.move(space, +1) },
                        enabled = !viewModel.saving && viewModel.canMove(space, +1),
                        modifier = Modifier.testTag(SpaceSettingsTags.MOVE_RIGHT),
                    ) { Text("Move right") }
                }

                viewModel.errorMessage?.let { msg ->
                    Text(
                        msg,
                        color = Color(0xFFB00020),
                        modifier = Modifier.testTag(SpaceSettingsTags.ERROR),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { viewModel.save(space, draft) },
                enabled = canSave,
                modifier = Modifier.testTag(SpaceSettingsTags.SAVE),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.closeSettings() }) { Text("Cancel") }
        },
    )
}

/**
 * Stage 15 "new space" dialog (the trailing "+" tab): Name (required) + Colour → `POST /spaces`
 * → the new tab is selected. Slug is derived server-side. A duplicate name surfaces the
 * mapped 409 message inline and keeps the dialog open.
 */
@Composable
fun NewSpaceDialog(viewModel: SpaceSettingsViewModel) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(SPACE_SWATCHES.first()) }

    AlertDialog(
        onDismissRequest = { viewModel.closeCreate() },
        title = { Text("New space") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .testTag(SpaceSettingsTags.NEW_DIALOG),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(SpaceSettingsTags.NEW_NAME_FIELD),
                )
                Text("Colour", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SPACE_SWATCHES.forEach { hex ->
                        val selected = hex.equals(color, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(AnnotationRenderer.accentFrom(hex)), CircleShape)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) Color(0xFF111111) else Color(0xFFCCCCCC),
                                    shape = CircleShape,
                                )
                                .clickable { color = hex }
                                .testTag(SpaceSettingsTags.newSwatch(hex)),
                        )
                    }
                }
                viewModel.createError?.let { msg ->
                    Text(
                        msg,
                        color = Color(0xFFB00020),
                        modifier = Modifier.testTag(SpaceSettingsTags.NEW_ERROR),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { viewModel.createSpace(name, color) },
                enabled = name.isNotBlank() && !viewModel.createSaving,
                modifier = Modifier.testTag(SpaceSettingsTags.NEW_CONFIRM),
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.closeCreate() }) { Text("Cancel") }
        },
    )
}

/** Stable tags for Compose/instrumented tests. */
object SpaceSettingsTags {
    const val SHEET = "space_settings_sheet"
    const val NAME_FIELD = "space_settings_name"
    const val MODEL = "space_settings_model"
    const val CUSTOM_MODEL_FIELD = "space_settings_custom_model"
    const val PROMPT_FIELD = "space_settings_prompt"
    const val SAVE = "space_settings_save"
    const val MOVE_LEFT = "space_settings_move_left"
    const val MOVE_RIGHT = "space_settings_move_right"
    const val ERROR = "space_settings_error"

    const val NEW_DIALOG = "space_new_dialog"
    const val NEW_NAME_FIELD = "space_new_name"
    const val NEW_CONFIRM = "space_new_confirm"
    const val NEW_ERROR = "space_new_error"

    fun swatch(hex: String) = "space_settings_swatch_$hex"
    fun newSwatch(hex: String) = "space_new_swatch_$hex"
}
