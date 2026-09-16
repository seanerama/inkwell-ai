package com.inkwell.render

import com.inkwell.contracts.Highlight
import com.inkwell.contracts.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure [AnchorHitTest] used by the Stage-10 two-way anchor
 * interaction (SPEC §4.7): resolving a card's anchors to CU rects (card → canvas) and
 * mapping a CU tap to the card it belongs to (canvas → card).
 */
class AnchorHitTestTest {

    private val w = CoordinateMapping.DEFAULT_WIDTH_CU
    private val h = CoordinateMapping.DEFAULT_HEIGHT_CU

    private val highlight = Highlight(
        id = "a1",
        points = listOf(listOf(0.10, 0.20), listOf(0.40, 0.30)),
    )
    private val byId = mapOf<String, com.inkwell.contracts.Annotation>("a1" to highlight)

    @Test
    fun annotation_anchor_resolves_to_that_annotation_bounds() {
        val rect = AnchorHitTest.rectForAnchor(
            AnchorHitTest.AnchorRegion(annotationId = "a1"), byId, w, h,
        )!!
        assertEquals(0.10 * w, rect[0], 1e-6)
        assertEquals(0.20 * h, rect[1], 1e-6)
        assertEquals(0.30 * w, rect[2], 1e-6)
        assertEquals(0.10 * h, rect[3], 1e-6)
    }

    @Test
    fun region_anchor_resolves_to_a_scaled_rect() {
        val rect = AnchorHitTest.rectForAnchor(
            AnchorHitTest.AnchorRegion(region = listOf(0.5, 0.5, 0.1, 0.1)), byId, w, h,
        )!!
        assertEquals(0.5 * w, rect[0], 1e-6)
        assertEquals(0.5 * h, rect[1], 1e-6)
    }

    @Test
    fun unknown_annotation_id_does_not_resolve() {
        assertNull(
            AnchorHitTest.rectForAnchor(AnchorHitTest.AnchorRegion(annotationId = "nope"), byId, w, h),
        )
    }

    @Test
    fun tap_inside_an_annotation_anchor_selects_its_card() {
        val cards = listOf(
            listOf(AnchorHitTest.AnchorRegion(region = listOf(0.5, 0.5, 0.1, 0.1))), // card 0
            listOf(AnchorHitTest.AnchorRegion(annotationId = "a1")), // card 1
        )
        // A point inside the highlight bounds (card 1): CU (300, 750).
        val index = AnchorHitTest.cardIndexForTap(300.0, 750.0, cards, byId, w, h)
        assertEquals(1, index)
    }

    @Test
    fun tap_outside_all_anchors_selects_nothing() {
        val cards = listOf(listOf(AnchorHitTest.AnchorRegion(annotationId = "a1")))
        assertNull(AnchorHitTest.cardIndexForTap(0.0, 0.0, cards, byId, w, h))
    }

    @Test
    fun first_matching_card_wins() {
        val region = AnchorHitTest.AnchorRegion(region = listOf(0.0, 0.0, 1.0, 1.0))
        val cards = listOf(listOf(region), listOf(region))
        assertEquals(0, AnchorHitTest.cardIndexForTap(100.0, 100.0, cards, byId, w, h))
    }

    @Test
    fun point_anchor_is_padded_so_it_stays_tappable() {
        // A `text` anchor is a zero-area box at its `at` point; a near tap still hits it.
        val text = Text(id = "t1", at = listOf(0.5, 0.5), text = "10", size = 0.02)
        val map = mapOf<String, com.inkwell.contracts.Annotation>("t1" to text)
        val cards = listOf(listOf(AnchorHitTest.AnchorRegion(annotationId = "t1")))
        val cx = 0.5 * w
        val cy = 0.5 * h
        assertTrue(
            "a tap within the point padding hits the text anchor",
            AnchorHitTest.cardIndexForTap(cx + 5.0, cy + 5.0, cards, map, w, h) == 0,
        )
    }
}
