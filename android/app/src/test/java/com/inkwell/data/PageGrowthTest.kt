package com.inkwell.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 34 (ADR-0014 §2): the growth rule — ghost ring, start test, commit-time growth in
 * any direction, the 8-page cap per axis, and the switch-off behaviour. Pages are 100 × 200
 * canvas units, so page (c, r) covers `[100c, 100c+100) × [200r, 200r+200)`.
 */
class PageGrowthTest {

    private val w = 100
    private val h = 200

    /** Stride-5 points (x, y, p=0.5, tilt 0, t = index) in the given order. */
    private fun pts(vararg xy: Pair<Float, Float>): FloatArray {
        val out = FloatArray(xy.size * PackedPoints.STRIDE)
        xy.forEachIndexed { i, (x, y) ->
            val o = i * PackedPoints.STRIDE
            out[o] = x; out[o + 1] = y; out[o + 2] = 0.5f; out[o + 4] = i.toFloat()
        }
        return out
    }

    private fun grow(grid: PageExtent, vararg xy: Pair<Float, Float>): PageExtent {
        val p = pts(*xy)
        return PageGrowth.grow(grid, p, xy.size, w, h)
    }

    private val single = PageExtent.SINGLE

    // --- Growth in all eight directions (one ring page) ---

    @Test
    fun grows_left_into_a_negative_column() {
        assertEquals(PageExtent(-1, 0, 0, 0), grow(single, 50f to 50f, -30f to 60f))
    }

    @Test
    fun grows_right() {
        assertEquals(PageExtent(0, 1, 0, 0), grow(single, 90f to 50f, 130f to 60f))
    }

    @Test
    fun grows_up_into_a_negative_row() {
        assertEquals(PageExtent(0, 0, -1, 0), grow(single, 50f to 10f, 50f to -150f))
    }

    @Test
    fun grows_down() {
        assertEquals(PageExtent(0, 0, 0, 1), grow(single, 50f to 190f, 50f to 350f))
    }

    @Test
    fun grows_into_each_corner() {
        assertEquals("up-left", PageExtent(-1, 0, -1, 0), grow(single, -10f to -10f))
        assertEquals("up-right", PageExtent(0, 1, -1, 0), grow(single, 150f to -10f))
        assertEquals("down-left", PageExtent(-1, 0, 0, 1), grow(single, -10f to 250f))
        assertEquals("down-right", PageExtent(0, 1, 0, 1), grow(single, 150f to 250f))
    }

    @Test
    fun a_stroke_inside_the_grid_does_not_change_it() {
        val grid = PageExtent(-1, 2, 0, 1)
        assertSame(grid, grow(grid, -50f to 10f, 250f to 390f))
    }

    @Test
    fun a_page_edge_belongs_to_the_next_page() {
        // x = 100 is the first unit of page 1 ([100, 200)); x = 99.9 is still page 0.
        assertEquals(PageExtent(0, 0, 0, 0), grow(single, 99.9f to 10f))
        assertEquals(PageExtent(0, 1, 0, 0), grow(single, 100f to 10f))
    }

    @Test
    fun growth_matches_the_v5_repair_rule_when_under_the_cap() {
        // Same point-bbox semantics as the migration repair (PageExtent.covering): growth and
        // repair agree on the grid a stroke needs.
        val grown = grow(single, 50f to 50f, -250f to 700f, 330f to -390f)
        assertEquals(PageExtent.covering(-250.0, -390.0, 330.0, 700.0, w, h), grown)
        assertEquals(PageExtent(-3, 3, -2, 3), grown)
    }

    // --- Multi-page jumps ---

    @Test
    fun a_long_stroke_crossing_two_ring_pages_grows_by_whole_rows_and_columns() {
        // One stroke leaves the ring and keeps going: two more columns, one more row.
        assertEquals(PageExtent(0, 2, 0, 1), grow(single, 50f to 50f, 150f to 150f, 280f to 260f))
        // A single fast segment that skips a page still yields a rectangle (the page between
        // is included).
        assertEquals(PageExtent(-3, 0, 0, 0), grow(single, 50f to 50f, -280f to 50f))
    }

    @Test
    fun the_grid_stays_rectangular() {
        // Growing left and down gives the whole 2 × 2 block, not an L.
        val g = grow(single, -50f to 50f, 50f to 250f)
        assertEquals(PageExtent(-1, 0, 0, 1), g)
        assertEquals(4, g.pageCount)
    }

    // --- The 8-page cap, per axis ---

    @Test
    fun columns_are_capped_at_eight_and_rows_still_grow() {
        // From (0,0) far right and one page down: columns stop at 0..7, rows grow freely.
        val g = grow(single, 50f to 50f, 1250f to 250f)
        assertEquals(PageExtent(0, 7, 0, 1), g)
        assertEquals(8, g.cols)
        assertFalse(PageGrowth.covers(g, 50f, 50f, 1200f, 200f, w, h))
    }

    @Test
    fun rows_are_capped_at_eight_and_columns_still_grow() {
        val g = grow(single, 50f to 50f, -50f to -2000f)
        assertEquals(PageExtent(-1, 0, -7, 0), g)
        assertEquals(8, g.rows)
    }

    @Test
    fun a_grid_at_the_cap_on_an_axis_never_grows_on_it_again() {
        val full = PageExtent(-7, 0, 0, 0)
        assertEquals(full, grow(full, 50f to 50f, 150f to 60f))
        assertEquals(full, grow(full, -650f to 50f, -900f to 60f))
        // ...but the other axis still grows.
        assertEquals(PageExtent(-7, 0, 0, 1), grow(full, 50f to 50f, 50f to 250f))
    }

    @Test
    fun the_cap_keeps_the_side_the_pen_reached_first_and_never_takes_back_shown_pages() {
        // Left first (3 pages), then far right: the three left pages stay, the right gets
        // only what is left of the cap (4 pages) — never a shift that hides the left ink.
        assertEquals(PageExtent(-3, 4, 0, 0), grow(single, -250f to 50f, 1500f to 50f))
        // Right first: the right side is kept.
        assertEquals(PageExtent(0, 7, 0, 0), grow(single, 1500f to 50f, -250f to 50f))
    }

    @Test
    fun growth_never_shrinks_the_grid() {
        val grid = PageExtent(-2, 3, -1, 4)
        val g = grow(grid, 5000f to -9000f, -9000f to 9000f)
        assertTrue(PageGrowth.contains(g, grid))
        assertTrue(g.isValid)
    }

    @Test
    fun non_finite_points_are_ignored() {
        assertEquals(single, grow(single, Float.NaN to 50f, 50f to Float.POSITIVE_INFINITY))
    }

    // --- Ghost ring and the start test ---

    @Test
    fun the_ring_is_one_page_deep_in_all_eight_directions() {
        val ring = PageGrowth.ringPages(single).toSet()
        val expected = setOf(
            -1 to -1, 0 to -1, 1 to -1,
            -1 to 0, 1 to 0,
            -1 to 1, 0 to 1, 1 to 1,
        )
        assertEquals(expected, ring)
    }

    @Test
    fun the_ring_is_not_drawn_on_an_axis_at_the_cap() {
        val fullCols = PageExtent(-3, 4, 0, 0)
        val ring = PageGrowth.ringPages(fullCols)
        assertTrue("no left/right ring pages", ring.all { (c, _) -> c in -3..4 })
        assertEquals("only above and below", 16, ring.size)
        assertTrue(PageGrowth.ringPages(PageExtent(-3, 4, -7, 0)).isEmpty())
    }

    @Test
    fun start_test_grid_ring_and_beyond() {
        fun start(x: Float, y: Float, grid: PageExtent = single) = PageGrowth.canStartAt(grid, x, y, w, h, expandable = true)
        assertTrue("grid", start(50f, 50f))
        assertTrue("left ring", start(-50f, 50f))
        assertTrue("right ring", start(150f, 50f))
        assertTrue("top ring", start(50f, -150f))
        assertTrue("bottom ring", start(50f, 350f))
        assertTrue("corner ring", start(-50f, -150f))
        assertFalse("beyond the left ring", start(-101f, 50f))
        assertFalse("beyond the right ring", start(200f, 50f))
        assertFalse("beyond the bottom ring", start(50f, 400f))
        assertFalse("beyond the corner", start(-150f, -250f))
        assertFalse("non-finite", start(Float.NaN, 0f))
        // A grid at the column cap has no left/right ring: only the grid and above/below.
        val fullCols = PageExtent(-7, 0, 0, 0)
        assertFalse(start(150f, 50f, fullCols))
        assertTrue(start(-650f, 50f, fullCols))
        assertTrue(start(-650f, 250f, fullCols))
    }

    // --- The switch off ---

    @Test
    fun switch_off_the_start_test_uses_the_grid_only() {
        val grid = PageExtent(-1, 0, 0, 0)
        fun start(x: Float, y: Float) = PageGrowth.canStartAt(grid, x, y, w, h, expandable = false)
        assertTrue(start(-50f, 50f))
        assertTrue(start(50f, 150f))
        assertFalse("no ring with the switch off", start(150f, 50f))
        assertFalse(start(-150f, 50f))
        assertFalse(start(50f, 250f))
        assertEquals(PageGrowth.Writable(-1..0, 0..0), PageGrowth.writable(grid, expandable = false))
    }

    // --- Helpers ---

    @Test
    fun covers_union_and_contains() {
        val g = PageExtent(-1, 1, 0, 0)
        assertTrue(PageGrowth.covers(g, -100f, 0f, 299f, 199f, w, h))
        assertFalse(PageGrowth.covers(g, -100f, 0f, 300f, 10f, w, h))
        assertFalse(PageGrowth.covers(g, 0f, -1f, 10f, 10f, w, h))
        assertEquals(PageExtent(-2, 1, -1, 3), PageGrowth.union(PageExtent(-2, 0, 0, 3), PageExtent(0, 1, -1, 0)))
        assertTrue(PageGrowth.contains(PageExtent(-2, 1, -1, 3), single))
        assertFalse(PageGrowth.contains(single, PageExtent(-1, 0, 0, 0)))
    }

    // --- Stage 35 (stage-34 review follow-up): the cap check uses the true maxima ---

    @Test
    fun covers_points_does_not_falsely_report_the_cap_one_float_step_inside_a_page_edge() {
        val a4w = 2480
        val a4h = 3508
        val grid = PageExtent(-1, 0, 0, 0)
        val minX = -1234.567f
        val maxX = Math.nextDown(2480f) // the last float inside page 0
        val pts = FloatArray(2 * PackedPoints.STRIDE).also {
            it[0] = minX; it[1] = 100f
            it[PackedPoints.STRIDE] = maxX; it[PackedPoints.STRIDE + 1] = 200f
        }
        // The old bbox arithmetic rounds x + w up onto the page edge (column 1)...
        assertTrue("precondition: x + w rounds to the edge", minX + (maxX - minX) >= 2480f)
        assertFalse(PageGrowth.covers(grid, minX, 100f, maxX - minX, 100f, a4w, a4h))
        // ...the true extremes stay inside the grid.
        assertTrue(PageGrowth.coversPoints(grid, pts, 2, a4w, a4h))
        assertTrue(PageGrowth.coversBounds(grid, minX, 100f, maxX, 200f, a4w, a4h))
        // A point really past the edge is still reported.
        pts[PackedPoints.STRIDE] = 2480f
        assertFalse(PageGrowth.coversPoints(grid, pts, 2, a4w, a4h))
        assertTrue("empty stroke", PageGrowth.coversPoints(grid, FloatArray(0), 0, a4w, a4h))
    }
}
