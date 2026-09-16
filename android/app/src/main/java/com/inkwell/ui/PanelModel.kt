package com.inkwell.ui

import com.inkwell.contracts.Card
import com.inkwell.contracts.CardKind

/**
 * One agent card as the side panel shows it (Stage 7): `(kind, title, body)`. The body
 * is the card's Markdown shown as plain text this stage (Markdown rendering is Phase 2).
 * An `answer` card is expanded by default; every other kind is collapsed to its title.
 */
data class PanelCard(
    val kind: CardKind,
    val title: String,
    val body: String,
) {
    val expandedByDefault: Boolean get() = kind == CardKind.ANSWER

    companion object {
        fun from(card: Card): PanelCard = PanelCard(kind = card.kind, title = card.title, body = card.body)
    }
}

/**
 * The content of the [SidePanel] after a job finishes (SPEC §9.4 step 6 / failed path).
 *
 *  - on `done`: [summary] plus [cards] (`(kind, title, body)`, bodies as plain text);
 *    [isError] false.
 *  - on `failed`: [errorTitle] + [errorBody] (the error card body); [isError] true and
 *    no layer was rendered.
 */
data class PanelModel(
    val summary: String,
    val cards: List<PanelCard>,
    val isError: Boolean,
    val errorTitle: String? = null,
    val errorBody: String? = null,
) {
    /** Card titles only (the Stage-6 surface), derived from [cards]. */
    val cardTitles: List<String> get() = cards.map { it.title }
}

/** A row in the minimal layer tray: enough to list and toggle a layer's visibility. */
data class LayerRow(
    val id: String,
    val label: String,
    val owner: String,
    val visible: Boolean,
)
