package com.inkwell.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 32: the v5 migration repair's page-grid covering rule, including the 8-page clamp. */
class PageExtentTest {

    private val w = 2480
    private val h = 3508

    private fun cover(minX: Double, minY: Double, maxX: Double, maxY: Double) =
        PageExtent.covering(minX, minY, maxX, maxY, w, h)

    @Test
    fun ink_inside_page_zero_keeps_the_single_page() {
        assertEquals(PageExtent.SINGLE, cover(0.0, 0.0, 2479.9, 3507.9))
        assertEquals(PageExtent.SINGLE, cover(100.0, 200.0, 300.0, 400.0))
    }

    @Test
    fun ink_to_the_right_and_below_grows_whole_pages() {
        assertEquals(PageExtent(0, 1, 0, 0), cover(100.0, 100.0, 2600.0, 200.0))
        assertEquals(PageExtent(0, 0, 0, 2), cover(100.0, 100.0, 200.0, 3508.0 * 2 + 1))
        // The right edge is exclusive: a point exactly at x = width is on page 1.
        assertEquals(PageExtent(0, 1, 0, 0), cover(0.0, 0.0, 2480.0, 10.0))
    }

    @Test
    fun ink_to_the_left_and_above_grows_negative_pages() {
        assertEquals(PageExtent(-1, 0, 0, 0), cover(-0.5, 10.0, 100.0, 20.0))
        assertEquals(PageExtent(-2, 0, -1, 0), cover(-2480.0 - 1, -1.0, 10.0, 10.0))
        assertEquals(PageExtent(-1, 1, -1, 1), cover(-5.0, -5.0, 2485.0, 3510.0))
    }

    @Test
    fun ink_far_away_keeps_page_zero_inside_the_grid() {
        // Ink only on page (3,2): the grid still contains page (0,0).
        assertEquals(PageExtent(0, 3, 0, 2), cover(3.5 * w, 2.5 * h, 3.6 * w, 2.6 * h))
        assertEquals(PageExtent(-3, 0, 0, 0), cover(-2.5 * w, 10.0, -2.4 * w, 20.0))
    }

    @Test
    fun the_grid_is_clamped_to_eight_pages_per_axis_keeping_page_zero() {
        // 20 pages to the right → 0..7.
        assertEquals(PageExtent(0, 7, 0, 0), cover(10.0, 10.0, 20.0 * w, 10.0))
        // 20 pages to the left → -7..0.
        assertEquals(PageExtent(-7, 0, 0, 0), cover(-20.0 * w, 10.0, 10.0, 10.0))
        // Both directions overflow: the positive side is kept first.
        assertEquals(PageExtent(0, 7, -7, 0), cover(-20.0 * w, -20.0 * h, 20.0 * w, 10.0))
        // Both directions, 5 each way (11 pages): max 5, then min = 5 - 7 = -2.
        assertEquals(PageExtent(-2, 5, 0, 0), cover(-5.0 * w + 1, 10.0, 5.5 * w, 10.0))
        val clamped = cover(-1e12, -1e12, 1e12, 1e12)
        assertTrue(clamped.isValid)
        assertEquals(8, clamped.cols)
        assertEquals(8, clamped.rows)
    }

    @Test
    fun clamp_axis_rule() {
        assertEquals(0 to 0, PageExtent.clampAxis(0, 0))
        assertEquals(-3 to 4, PageExtent.clampAxis(-3, 4))
        assertEquals(0 to 7, PageExtent.clampAxis(0, 9))
        assertEquals(-7 to 0, PageExtent.clampAxis(-9, 0))
        assertEquals(-1 to 6, PageExtent.clampAxis(-1, 6))
        assertEquals(-1 to 6, PageExtent.clampAxis(-4, 6))
    }

    @Test
    fun degenerate_inputs_fall_back_to_the_single_page() {
        assertEquals(PageExtent.SINGLE, PageExtent.covering(-10.0, -10.0, 5000.0, 5000.0, 0, h))
        assertEquals(PageExtent.SINGLE, cover(Double.NaN, 0.0, 10.0, 10.0))
        assertEquals(PageExtent.SINGLE, cover(0.0, 0.0, Double.POSITIVE_INFINITY, 10.0))
    }

    @Test
    fun validity_and_grid_bounds() {
        assertTrue(PageExtent.SINGLE.isValid)
        assertFalse(PageExtent(1, 2, 0, 0).isValid) // page 0 outside
        assertFalse(PageExtent(0, 8, 0, 0).isValid) // 9 pages
        val g = PageExtent(-1, 1, 0, 2)
        assertEquals(9, g.pageCount)
        assertEquals(-2480.0, g.leftCu(w), 0.0)
        assertEquals(3 * 2480.0, g.widthCu(w), 0.0)
        assertEquals(3 * 3508.0, g.bottomCu(h), 0.0)
        assertTrue(g.containsPage(-1, 2))
        assertFalse(g.containsPage(2, 0))
    }
}
