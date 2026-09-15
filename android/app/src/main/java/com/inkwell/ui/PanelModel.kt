package com.inkwell.ui

/**
 * The content of the [SidePanel] after a job finishes (SPEC §9.4 step 6 / failed path).
 *
 *  - on `done`: [summary] plus [cardTitles] (plain text this stage); [isError] false.
 *  - on `failed`: [errorTitle] + [errorBody] (the error card body); [isError] true and
 *    no layer was rendered.
 */
data class PanelModel(
    val summary: String,
    val cardTitles: List<String>,
    val isError: Boolean,
    val errorTitle: String? = null,
    val errorBody: String? = null,
)

/** A row in the minimal layer tray: enough to list and toggle a layer's visibility. */
data class LayerRow(
    val id: String,
    val label: String,
    val owner: String,
    val visible: Boolean,
)
