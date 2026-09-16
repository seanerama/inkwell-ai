package com.inkwell.ui

import com.inkwell.contracts.Card
import com.inkwell.contracts.CardActionKind
import com.inkwell.contracts.CardKind
import com.inkwell.net.CardResponse

/** A card anchor for the panel: an annotation id or a normalized `[x,y,w,h]` region. */
data class PanelAnchor(
    val annotationId: String? = null,
    val region: List<Double>? = null,
)

/**
 * A card action row (Stage 10). [kind] is the wire string so additive vocabulary is
 * tolerated; [supported] is true only for the kinds this stage acts on (`confirm`,
 * `reject`) — every other kind renders disabled with "coming later".
 */
data class PanelAction(
    val id: String,
    val label: String,
    val kind: String,
) {
    val supported: Boolean get() = kind == "confirm" || kind == "reject"
}

/**
 * One agent card as the side panel shows it. Stage 7 carried `(kind, title, body)`;
 * Stage 10 adds the card's server [id], its device-changeable [state]
 * (`open`/`done`/`dismissed`), its [anchors] (for the canvas-highlight interaction) and
 * its [actions]. The body is Markdown (rendered by [markdownToAnnotatedString] when
 * `BuildConfig.CARD_ACTIONS` is on; shown as plain text otherwise). An `answer` card is
 * expanded by default; every other kind is collapsed to its title.
 *
 * The Stage-7 constructor shape `(kind, title, body)` is preserved (new fields are
 * defaulted) so existing call sites and tests still compile.
 */
data class PanelCard(
    val kind: CardKind,
    val title: String,
    val body: String,
    val id: String = "",
    val state: String = "open",
    val anchors: List<PanelAnchor> = emptyList(),
    val actions: List<PanelAction> = emptyList(),
) {
    val expandedByDefault: Boolean get() = kind == CardKind.ANSWER

    companion object {
        /** From the parsed `agent-output` card (no server identity/state — Stage-7 path). */
        fun from(card: Card): PanelCard = PanelCard(
            kind = card.kind,
            title = card.title,
            body = card.body,
            anchors = card.anchors.map { PanelAnchor(it.annotationId, it.region) },
            actions = card.actions.map { PanelAction(it.id, it.label, it.kind.wireName()) },
        )

        /** From the server card (Stage 10 — carries id, state, anchors, actions). */
        fun from(card: CardResponse): PanelCard = PanelCard(
            kind = cardKindFromWire(card.kind),
            title = card.title,
            body = card.body,
            id = card.id,
            state = card.state,
            anchors = card.anchors.map { PanelAnchor(it.annotationId, it.region) },
            actions = card.actions.map { PanelAction(it.id, it.label, it.kind) },
        )
    }
}

/** Map a wire `kind` string to [CardKind]; an unknown (additive) kind degrades to FACT. */
fun cardKindFromWire(kind: String): CardKind = when (kind) {
    "answer" -> CardKind.ANSWER
    "task" -> CardKind.TASK
    "fact" -> CardKind.FACT
    "question" -> CardKind.QUESTION
    "action" -> CardKind.ACTION
    "error" -> CardKind.ERROR
    else -> CardKind.FACT
}

/** The wire string for a contract [CardActionKind]. */
fun CardActionKind.wireName(): String = when (this) {
    CardActionKind.CONFIRM -> "confirm"
    CardActionKind.REJECT -> "reject"
    CardActionKind.RUN_TOOL -> "run_tool"
    CardActionKind.OPEN_CANVAS -> "open_canvas"
    CardActionKind.SAVE_TO_BRAIN -> "save_to_brain"
}

/**
 * The content of the [SidePanel] after a job finishes (SPEC §9.4 step 6 / failed path).
 *
 *  - on `done`: [summary] plus [cards]; [isError] false.
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

/**
 * Pure (Android-free, JVM-unit-testable) reducer for the optimistic card-state update
 * of Stage 10: on an action tap the row is updated immediately ([optimisticState] +
 * [withState]); when the server's authoritative card comes back the row is reconciled
 * from it ([reconcile]). No coroutines, no Compose — the ViewModel wires these to the
 * repository call and its result.
 */
object CardStateReducer {

    /** The state a `confirm`/`reject` action moves a card to; other kinds leave it as-is. */
    fun optimisticState(actionKind: String, current: String): String = when (actionKind) {
        "confirm" -> "done"
        "reject" -> "dismissed"
        else -> current
    }

    /** Return [cards] with the card whose [PanelCard.id] equals [cardId] set to [state]. */
    fun withState(cards: List<PanelCard>, cardId: String, state: String): List<PanelCard> =
        cards.map { if (it.id == cardId && it.id.isNotEmpty()) it.copy(state = state) else it }

    /**
     * Reconcile [cards] with the server's authoritative [serverCard] (the response of the
     * PATCH/action call): the matching row takes the server's [PanelCard.state]. Other
     * rows are untouched. If no row matches (unknown id) the list is returned unchanged.
     */
    fun reconcile(cards: List<PanelCard>, serverCard: PanelCard): List<PanelCard> =
        withState(cards, serverCard.id, serverCard.state)
}
