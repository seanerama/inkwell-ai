package com.inkwell.ui

import com.inkwell.contracts.CardKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for the pure [CardStateReducer] — the Stage-10 optimistic-then-reconcile
 * card-state logic, independent of coroutines/Compose.
 */
class CardStateReducerTest {

    private fun card(id: String, state: String) =
        PanelCard(kind = CardKind.TASK, title = "t", body = "b", id = id, state = state)

    @Test
    fun confirm_moves_open_to_done_optimistically() {
        assertEquals("done", CardStateReducer.optimisticState("confirm", "open"))
    }

    @Test
    fun reject_moves_open_to_dismissed_optimistically() {
        assertEquals("dismissed", CardStateReducer.optimisticState("reject", "open"))
    }

    @Test
    fun unsupported_action_leaves_state_unchanged() {
        assertEquals("open", CardStateReducer.optimisticState("save_to_brain", "open"))
    }

    @Test
    fun with_state_updates_only_the_matching_id() {
        val cards = listOf(card("c1", "open"), card("c2", "open"))
        val next = CardStateReducer.withState(cards, "c2", "done")
        assertEquals("open", next[0].state)
        assertEquals("done", next[1].state)
    }

    @Test
    fun with_state_ignores_empty_ids() {
        val cards = listOf(card("", "open"))
        assertEquals("open", CardStateReducer.withState(cards, "", "done").single().state)
    }

    @Test
    fun reconcile_takes_the_servers_state() {
        val cards = listOf(card("c1", "done")) // optimistic guess
        val server = card("c1", "dismissed") // server disagreed
        assertEquals("dismissed", CardStateReducer.reconcile(cards, server).single().state)
    }

    @Test
    fun reconcile_unknown_id_is_a_no_op() {
        val cards = listOf(card("c1", "open"))
        val server = card("cX", "done")
        assertEquals("open", CardStateReducer.reconcile(cards, server).single().state)
    }
}
