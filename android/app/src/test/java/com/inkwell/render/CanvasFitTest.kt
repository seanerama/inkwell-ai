package com.inkwell.render

import com.inkwell.data.PageExtent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 36: the fit-to-screen math ([CanvasTransform.fitting]) and how it meets stage 35's export
 * rule. A fitted view shows the whole page grid, centred, with a uniform margin; its viewport ∩
 * grid (contract `coordinate-mapping` "ADR-0014 additions — region export", unchanged) is then
 * exactly the whole grid, and for grids up to 2 pages on the longest axis the legibility floor
 * never blocks sending.
 *
 * View sizes are tablet canvas areas (the toolbar taken off) at density 2, so the 16 dp margin
 * is 32 px, plus a phone for good measure. The viewport is derived from the fit exactly as
 * `InkView` reports it (float transform, double arithmetic).
 */
class CanvasFitTest {

    private val a4w = CoordinateMapping.DEFAULT_WIDTH_CU // 2480
    private val a4h = CoordinateMapping.DEFAULT_HEIGHT_CU // 3508
    private val m = 32f // 16 dp at density 2

    // Idea Tab Pro-sized canvas areas (2944 × 1840 px screen).
    private val landscape = 2944 to 1560
    private val portrait = 1840 to 2600

    // A phone (density 2.625: 16 dp = 42 px).
    private val phone = 1080 to 2000
    private val phoneMargin = 42f

    private fun fit(grid: PageExtent, pageW: Int, pageH: Int, view: Pair<Int, Int>, margin: Float = m) =
        CanvasTransform.fittingGrid(grid, pageW, pageH, view.first, view.second, margin)

    /** The canvas-unit viewport `[l, t, r, b]` exactly as `InkView.reportViewport` computes it. */
    private fun viewport(f: CanvasTransform.Fit, view: Pair<Int, Int>): DoubleArray {
        val s = f.scale.toDouble()
        return doubleArrayOf(
            (0.0 - f.tx) / s,
            (0.0 - f.ty) / s,
            (view.first - f.tx.toDouble()) / s,
            (view.second - f.ty.toDouble()) / s,
        )
    }

    /** Stage 35's export region for the fitted view: viewport ∩ grid, snapped (as the view model). */
    private fun region(f: CanvasTransform.Fit, view: Pair<Int, Int>, grid: PageExtent, pageW: Int, pageH: Int): CoordinateMapping.Region? {
        val v = viewport(f, view)
        return CoordinateMapping.visibleRegion(
            v[0], v[1], v[2], v[3],
            grid.minCol.toLong() * pageW, grid.minRow.toLong() * pageH,
            (grid.maxCol + 1).toLong() * pageW, (grid.maxRow + 1).toLong() * pageH,
        )?.takeIf { CoordinateMapping.isExportable(it) }
    }

    private fun wholeGrid(grid: PageExtent, pageW: Int, pageH: Int) = CoordinateMapping.Region(
        grid.minCol * pageW, grid.minRow * pageH, grid.cols * pageW, grid.rows * pageH,
    )

    /** The grid's rect in view px `[l, t, r, b]` through the fit (the transform's own math). */
    private fun gridInView(f: CanvasTransform.Fit, grid: PageExtent, pageW: Int, pageH: Int): FloatArray {
        val t = CanvasTransform(f.scale, f.tx, f.ty)
        return floatArrayOf(
            t.canvasToViewX(grid.leftCu(pageW).toFloat()),
            t.canvasToViewY(grid.topCu(pageH).toFloat()),
            t.canvasToViewX(grid.rightCu(pageW).toFloat()),
            t.canvasToViewY(grid.bottomCu(pageH).toFloat()),
        )
    }

    /**
     * The fit is centred, keeps at least [margin] clear on every side, touches the margin on
     * the binding axis ([heightBound] or width-bound), and uses the min-fit scale.
     */
    private fun assertFitted(
        grid: PageExtent,
        pageW: Int,
        pageH: Int,
        view: Pair<Int, Int>,
        heightBound: Boolean,
        margin: Float = m,
    ): CanvasTransform.Fit {
        val f = fit(grid, pageW, pageH, view, margin)
        val (w, h) = view
        val gw = grid.widthCu(pageW)
        val gh = grid.heightCu(pageH)
        val expected = if (heightBound) (h - 2 * margin) / gh else (w - 2 * margin) / gw
        assertEquals("min-fit scale", expected.toFloat(), f.scale, 1e-6f)
        assertEquals("bound axis", heightBound, (h - 2 * margin) / gh < (w - 2 * margin) / gw)

        val r = gridInView(f, grid, pageW, pageH)
        val eps = 0.01f
        assertTrue("left margin ${r[0]}", r[0] >= margin - eps)
        assertTrue("top margin ${r[1]}", r[1] >= margin - eps)
        assertTrue("right margin ${w - r[2]}", w - r[2] >= margin - eps)
        assertTrue("bottom margin ${h - r[3]}", h - r[3] >= margin - eps)
        if (heightBound) {
            assertEquals("top gap is the margin", margin, r[1], eps)
            assertEquals("bottom gap is the margin", margin, h - r[3], eps)
        } else {
            assertEquals("left gap is the margin", margin, r[0], eps)
            assertEquals("right gap is the margin", margin, w - r[2], eps)
        }
        // Centred: equal gaps on each axis.
        assertEquals("centred horizontally", r[0], w - r[2], eps)
        assertEquals("centred vertically", r[1], h - r[3], eps)
        return f
    }

    /** After the fit, stage 35's region is the whole grid and Send is not blocked by the floor. */
    private fun assertWholeGridSendable(grid: PageExtent, pageW: Int, pageH: Int, view: Pair<Int, Int>, margin: Float = m) {
        val f = fit(grid, pageW, pageH, view, margin)
        val r = region(f, view, grid, pageW, pageH)
        assertEquals("region == whole grid", wholeGrid(grid, pageW, pageH), r)
        assertFalse("the legibility floor does not block", CoordinateMapping.belowLegibilityFloor(r!!, pageW, pageH))
    }

    // --- The fit math ---

    @Test
    fun a4_portrait_in_a_landscape_view_is_height_bound() {
        assertFitted(PageExtent.SINGLE, a4w, a4h, landscape, heightBound = true)
    }

    @Test
    fun a4_portrait_in_a_portrait_view_is_width_bound() {
        assertFitted(PageExtent.SINGLE, a4w, a4h, portrait, heightBound = false)
        assertFitted(PageExtent.SINGLE, a4w, a4h, phone, heightBound = false, margin = phoneMargin)
    }

    @Test
    fun a4_landscape_page() {
        // A landscape page (3508 × 2480 CU): height-bound in the landscape view, width-bound in portrait.
        assertFitted(PageExtent.SINGLE, a4h, a4w, landscape, heightBound = true)
        assertFitted(PageExtent.SINGLE, a4h, a4w, portrait, heightBound = false)
    }

    @Test
    fun two_by_one_grid() {
        val grid = PageExtent(0, 1, 0, 0)
        assertFitted(grid, a4w, a4h, landscape, heightBound = true)
        assertFitted(grid, a4w, a4h, portrait, heightBound = false)
    }

    @Test
    fun one_by_three_grid() {
        val grid = PageExtent(0, 0, 0, 2)
        assertFitted(grid, a4w, a4h, landscape, heightBound = true)
        assertFitted(grid, a4w, a4h, portrait, heightBound = true)
    }

    @Test
    fun a_grid_with_negative_columns_is_centred_on_its_own_bounds() {
        val grid = PageExtent(-2, 0, 0, 0) // x −4960 .. 2480
        val f = assertFitted(grid, a4w, a4h, landscape, heightBound = false)
        // The grid's centre (x −1240, y 1754) is the view's centre.
        val t = CanvasTransform(f.scale, f.tx, f.ty)
        assertEquals(landscape.first / 2f, t.canvasToViewX(-1240f), 0.01f)
        assertEquals(landscape.second / 2f, t.canvasToViewY(1754f), 0.01f)
        // Page (0,0) is to the right of centre: its left edge is past the middle.
        assertTrue(t.canvasToViewX(0f) > landscape.first / 2f)
    }

    @Test
    fun an_eight_by_eight_grid_clamps_at_min_scale_and_stays_centred() {
        val grid = PageExtent(-3, 4, -2, 5)
        for (view in listOf(landscape, portrait, phone)) {
            val f = fit(grid, a4w, a4h, view)
            assertEquals("clamped at minScale", CanvasTransform.DEFAULT_MIN_SCALE, f.scale, 0f)
            val t = CanvasTransform(f.scale, f.tx, f.ty)
            val cx = (grid.leftCu(a4w) + grid.widthCu(a4w) / 2).toFloat()
            val cy = (grid.topCu(a4h) + grid.heightCu(a4h) / 2).toFloat()
            assertEquals(view.first / 2f, t.canvasToViewX(cx), 0.01f)
            assertEquals(view.second / 2f, t.canvasToViewY(cy), 0.01f)
        }
    }

    @Test
    fun a_tiny_grid_clamps_at_max_scale_and_stays_centred() {
        val f = CanvasTransform.fitting(10.0, 20.0, 30.0, 40.0, 2000, 1000, m)
        assertEquals(CanvasTransform.DEFAULT_MAX_SCALE, f.scale, 0f)
        val t = CanvasTransform(f.scale, f.tx, f.ty)
        assertEquals(1000f, t.canvasToViewX(20f), 0.001f)
        assertEquals(500f, t.canvasToViewY(30f), 0.001f)
    }

    @Test
    fun margins_are_honoured_at_any_margin() {
        for (margin in listOf(0f, 16f, 32f, 100f)) {
            assertFitted(PageExtent.SINGLE, a4w, a4h, landscape, heightBound = true, margin = margin)
            assertFitted(PageExtent(0, 1, 0, 0), a4w, a4h, portrait, heightBound = false, margin = margin)
        }
    }

    @Test
    fun a_zero_or_too_small_view_never_crashes_and_gives_min_scale() {
        for ((w, h) in listOf(0 to 0, 0 to 1000, 40 to 40)) {
            val f = CanvasTransform.fittingGrid(PageExtent.SINGLE, a4w, a4h, w, h, m)
            assertEquals(CanvasTransform.DEFAULT_MIN_SCALE, f.scale, 0f)
            assertTrue(f.tx.isFinite() && f.ty.isFinite())
        }
    }

    @Test
    fun honours_a_custom_scale_range() {
        val f = CanvasTransform.fittingGrid(PageExtent.SINGLE, a4w, a4h, landscape.first, landscape.second, m, minScale = 0.5f, maxScale = 2f)
        assertEquals(0.5f, f.scale, 0f)
    }

    // --- Interplay with stage 35: region == whole grid, the floor never blocks ≤ 2 pages ---

    @Test
    fun after_a_fit_the_region_is_the_whole_grid() {
        val grids = listOf(
            PageExtent.SINGLE,
            PageExtent(0, 1, 0, 0),
            PageExtent(0, 0, 0, 2),
            PageExtent(-2, 0, 0, 0),
            PageExtent(0, 0, 0, 1),
            PageExtent(-1, 0, -1, 0),
        )
        for (grid in grids) {
            for ((view, margin) in listOf(landscape to m, portrait to m, phone to phoneMargin)) {
                val f = fit(grid, a4w, a4h, view, margin)
                assertEquals("$grid in $view", wholeGrid(grid, a4w, a4h), region(f, view, grid, a4w, a4h))
            }
        }
        // A landscape page too.
        val f = fit(PageExtent.SINGLE, a4h, a4w, landscape)
        assertEquals(CoordinateMapping.Region(0, 0, a4h, a4w), region(f, landscape, PageExtent.SINGLE, a4h, a4w))
    }

    @Test
    fun a_single_page_fitted_is_exactly_the_v1_export() {
        for (view in listOf(landscape, portrait)) {
            val f = fit(PageExtent.SINGLE, a4w, a4h, view)
            val r = region(f, view, PageExtent.SINGLE, a4w, a4h)
            assertEquals(CoordinateMapping.Region.page(a4w, a4h), r)
            // The export metadata is the v1 whole-page export (1109 × 1568, origin 0).
            assertEquals(CoordinateMapping.export(a4w, a4h), CoordinateMapping.export(r!!))
        }
    }

    @Test
    fun the_floor_never_blocks_a_fitted_grid_up_to_two_pages_on_its_longest_axis() {
        // A4 portrait on a landscape and a portrait tablet view.
        assertWholeGridSendable(PageExtent.SINGLE, a4w, a4h, landscape)
        assertWholeGridSendable(PageExtent.SINGLE, a4w, a4h, portrait)
        assertWholeGridSendable(PageExtent.SINGLE, a4w, a4h, phone, phoneMargin)
        // A4 landscape.
        assertWholeGridSendable(PageExtent.SINGLE, a4h, a4w, landscape)
        assertWholeGridSendable(PageExtent.SINGLE, a4h, a4w, portrait)
        // 2 × 1, and the other two-page-longest-axis shapes (1 × 2, 2 × 2), grown either way.
        for (grid in listOf(PageExtent(0, 1, 0, 0), PageExtent(-1, 0, 0, 0), PageExtent(0, 0, 0, 1), PageExtent(-1, 0, -1, 0))) {
            for (view in listOf(landscape, portrait)) {
                assertWholeGridSendable(grid, a4w, a4h, view)
                assertWholeGridSendable(grid, a4h, a4w, view)
            }
        }
    }

    @Test
    fun a_fitted_single_page_is_never_a_sliver() {
        // The stage 35 16 px minimum and "Scroll to a page to send" never trigger for one page.
        for ((view, margin) in listOf(landscape to m, portrait to m, phone to phoneMargin)) {
            for ((pw, ph) in listOf(a4w to a4h, a4h to a4w)) {
                val f = fit(PageExtent.SINGLE, pw, ph, view, margin)
                val r = requireNotNull(region(f, view, PageExtent.SINGLE, pw, ph)) { "no region in $view" }
                assertTrue(CoordinateMapping.isExportable(r))
            }
        }
    }

    @Test
    fun a_fitted_three_page_axis_may_legitimately_hit_the_floor() {
        // 1 × 3: the whole grid is the region, and its 10524 CU edge is past 2 × 3508 — the
        // contract's "Zoom in to send" (left as is).
        val grid = PageExtent(0, 0, 0, 2)
        val f = fit(grid, a4w, a4h, portrait)
        val r = requireNotNull(region(f, portrait, grid, a4w, a4h))
        assertEquals(wholeGrid(grid, a4w, a4h), r)
        assertTrue(CoordinateMapping.belowLegibilityFloor(r, a4w, a4h))
    }
}
