package com.inkwell.render

import com.inkwell.contracts.Annotation
import com.inkwell.contracts.Arrow
import com.inkwell.contracts.Highlight
import com.inkwell.contracts.MarginNote
import com.inkwell.contracts.RectAnnotation
import com.inkwell.contracts.Text
import com.inkwell.data.PageExtent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 35 (ADR-0014 §4–§5, contract `coordinate-mapping` "ADR-0014 additions — region
 * export"): the pure pieces of region export — the region (viewport ∩ grid, snapped to whole
 * CU), the export formula applied to it, the legibility floor, origin-aware mapping back of
 * every geometry kind, and the export's per-page draw partition.
 */
class RegionExportTest {

    private val pageW = CoordinateMapping.DEFAULT_WIDTH_CU // 2480
    private val pageH = CoordinateMapping.DEFAULT_HEIGHT_CU // 3508

    /** [CoordinateMapping.visibleRegion] over the bounds of [grid] (A4 pages). */
    private fun region(l: Double, t: Double, r: Double, b: Double, grid: PageExtent): CoordinateMapping.Region? =
        CoordinateMapping.visibleRegion(
            l, t, r, b,
            grid.minCol.toLong() * pageW, grid.minRow.toLong() * pageH,
            (grid.maxCol + 1).toLong() * pageW, (grid.maxRow + 1).toLong() * pageH,
        )

    // --- region = viewport ∩ grid ---

    @Test
    fun a_single_page_seen_whole_is_the_v1_export() {
        val r = region(-512.3, -80.0, 3000.9, 4100.2, PageExtent.SINGLE)
        assertEquals(CoordinateMapping.Region(0, 0, pageW, pageH), r)
        // ...and exactly the grid when the viewport is the page itself.
        assertEquals(CoordinateMapping.Region(0, 0, pageW, pageH), region(0.0, 0.0, 2480.0, 3508.0, PageExtent.SINGLE))
    }

    @Test
    fun a_viewport_inside_a_page_is_snapped_outward_to_whole_cu() {
        val r = region(100.4, 200.6, 1100.2, 1900.01, PageExtent.SINGLE)
        assertEquals(CoordinateMapping.Region(100, 200, 1001, 1701), r)
    }

    @Test
    fun negative_origins_on_a_grid_grown_left_and_up() {
        val grid = PageExtent(-2, 0, -1, 0)
        val r = region(-3000.5, -100.2, -1000.1, 500.9, grid)
        assertEquals(CoordinateMapping.Region(-3001, -101, 2001, 602), r)
        // A viewport past the grid's top-left corner is clamped to it.
        assertEquals(
            CoordinateMapping.Region(-2 * pageW, -pageH, 2 * pageW + 100, pageH + 50),
            region(-99_999.0, -99_999.0, 100.0, 50.0, grid),
        )
    }

    @Test
    fun partial_overlap_with_the_grid_edge_is_clipped_to_the_grid() {
        val grid = PageExtent(-1, 0, 0, 0)
        // The viewport hangs off the grid's left and top edges.
        val r = region(-9000.0, -9000.0, 1000.0, 1000.0, grid)
        assertEquals(CoordinateMapping.Region(-pageW, 0, pageW + 1000, 1000), r)
        // And off the right / bottom edges of a 2 x 1 grid.
        val r2 = region(4000.2, 3000.0, 9000.0, 9000.0, PageExtent(0, 1, 0, 0))
        assertEquals(CoordinateMapping.Region(4000, 3000, 2 * pageW - 4000, pageH - 3000), r2)
    }

    @Test
    fun page_1_0_of_a_two_page_grid() {
        val r = region(2480.0, 0.0, 4960.0, 3508.0, PageExtent(0, 1, 0, 0))
        assertEquals(CoordinateMapping.Region(2480, 0, 2480, 3508), r)
    }

    @Test
    fun a_viewport_that_misses_the_grid_has_no_region() {
        assertNull(region(3000.0, 0.0, 4000.0, 100.0, PageExtent.SINGLE))
        assertNull(region(-10.0, -10.0, 0.0, 0.0, PageExtent.SINGLE)) // touches the corner only
        assertNull(region(Double.NaN, 0.0, 100.0, 100.0, PageExtent.SINGLE))
        assertNull(region(0.0, 0.0, Double.POSITIVE_INFINITY, 100.0, PageExtent.SINGLE))
    }

    // --- the export formula on the region ---

    @Test
    fun landscape_region_exports_1568_by_1109() {
        val export = CoordinateMapping.export(CoordinateMapping.Region(2480, 0, 3508, 2480))
        assertEquals(1568, export.w)
        assertEquals(1109, export.h)
        assertEquals(3508, export.widthCu)
        assertEquals(2480, export.heightCu)
        assertEquals(2480, export.originX)
        assertEquals(0, export.originY)
        assertEquals(CoordinateMapping.Region(2480, 0, 3508, 2480), export.region)
    }

    @Test
    fun the_v1_export_is_unchanged_with_origin_zero() {
        val v1 = CoordinateMapping.export()
        assertEquals(CoordinateMapping.Export(1109, 1568, pageW, pageH, 0, 0), v1)
        assertEquals(v1, CoordinateMapping.export(CoordinateMapping.Region.page(pageW, pageH)))
    }

    // --- legibility floor ---

    @Test
    fun legibility_floor_is_twice_the_page_longest_edge() {
        fun blocked(w: Int, h: Int) = CoordinateMapping.belowLegibilityFloor(CoordinateMapping.Region(0, 0, w, h), pageW, pageH)
        assertFalse(blocked(2 * 2480, 3508)) // two pages side by side: 4960
        assertFalse(blocked(7016, 100)) // exactly 2 x 3508 is allowed
        assertTrue(blocked(7017, 100))
        assertTrue(blocked(3 * 2480, 3508)) // three pages wide: 7440
        assertTrue(blocked(2480, 7017))
        // A landscape page: its longest edge is its width.
        assertFalse(CoordinateMapping.belowLegibilityFloor(CoordinateMapping.Region(0, 0, 7016, 2480), 3508, 2480))
        assertTrue(CoordinateMapping.belowLegibilityFloor(CoordinateMapping.Region(0, 0, 7017, 2480), 3508, 2480))
    }

    // --- nm → cu through the job's region ---

    private val job = CoordinateMapping.Region(originX = 2480, originY = -3508, widthCu = 3000, heightCu = 2000)

    @Test
    fun points_map_origin_plus_nm_times_region_size_and_back() {
        val (x, y) = CoordinateMapping.nmToCu(0.25 to 0.5, job.widthCu, job.heightCu, job.originX, job.originY)
        assertEquals(2480 + 750.0, x, 0.0)
        assertEquals(-3508 + 1000.0, y, 0.0)
        val (nx, ny) = CoordinateMapping.cuToNm(x to y, job.widthCu, job.heightCu, job.originX, job.originY)
        assertEquals(0.25, nx, 1e-12)
        assertEquals(0.5, ny, 1e-12)

        val poly = AnnotationGeometry.polylineCu(listOf(listOf(0.0, 0.0), listOf(1.0, 1.0)), job.widthCu, job.heightCu, job.originX, job.originY)
        assertEquals(listOf(2480.0 to -3508.0, 5480.0 to -1508.0), poly)

        val bounds = AnnotationGeometry.boundsCu(
            Highlight("h", listOf(listOf(0.1, 0.2), listOf(0.4, 0.3))), job.widthCu, job.heightCu, job.originX, job.originY,
        )
        assertArrayEquals(doubleArrayOf(2480 + 300.0, -3508 + 400.0, 900.0, 200.0), bounds, 1e-9)
    }

    @Test
    fun rect_maps_its_corner_through_the_origin_and_its_size_by_the_region() {
        val rect = RectAnnotation("r", x = 0.1, y = 0.2, w = 0.3, h = 0.4)
        val b = AnnotationGeometry.boundsCu(rect, job.widthCu, job.heightCu, job.originX, job.originY)
        assertArrayEquals(doubleArrayOf(2480 + 300.0, -3508 + 400.0, 900.0, 800.0), b, 1e-9)
        assertEquals(2480 + 300.0 to -3508 + 400.0, AnnotationGeometry.rectLabelAnchorCu(rect, job.widthCu, job.heightCu, job.originX, job.originY))
    }

    @Test
    fun text_size_is_a_fraction_of_the_region_height_and_its_anchor_is_origin_aware() {
        val text = Text("t", at = listOf(0.42, 0.18), text = "10", size = 0.02)
        val p = AnnotationGeometry.textPlacement(text, job.widthCu, job.heightCu, job.originX, job.originY)
        assertEquals(2480 + 0.42 * 3000, p.xCu, 1e-9)
        assertEquals(-3508 + 0.18 * 2000, p.yCu, 1e-9)
        assertEquals(0.02 * 2000, p.sizeCu, 1e-9) // region height, never the page or grid
        assertEquals(40.0, CoordinateMapping.sizeToCu(0.02, job.heightCu), 1e-9)
    }

    @Test
    fun margin_note_sits_at_the_region_right_edge_at_origin_plus_y() {
        val note = MarginNote("m", y = 0.25, text = "check")
        val b = AnnotationGeometry.boundsCu(note, job.widthCu, job.heightCu, job.originX, job.originY)
        assertEquals(2480.0 + 3000, b[0], 0.0) // the region's right edge: the gutter's left
        assertEquals(-3508 + 0.25 * 2000, b[1], 1e-9)
        assertEquals(5480.0, AnnotationGeometry.marginGutterLeftCu(job.widthCu, job.originX), 0.0)
        // Stacking starts from origin + y × region height.
        val tops = AnnotationGeometry.stackMarginNotesCu(listOf(0.25, 0.26), 50.0, job.heightCu, job.originY)
        assertEquals(-3508 + 500.0, tops[0], 1e-9)
        assertEquals(-3508 + 550.0, tops[1], 1e-9)
    }

    @Test
    fun arrow_head_and_label_follow_the_origin() {
        val a = Arrow("a", from = listOf(0.0, 0.5), to = listOf(0.5, 0.5), label = "x")
        val head = AnnotationGeometry.arrowHeadCu(a.from, a.to, 18.0, job.widthCu, job.heightCu, job.originX, job.originY)
        assertEquals(2480 + 1500.0 to -3508 + 1000.0, head.tip)
        val local = AnnotationGeometry.arrowLabelAnchorCu(a.from, a.to, 24.0, job.widthCu, job.heightCu)
        val shifted = AnnotationGeometry.arrowLabelAnchorCu(a.from, a.to, 24.0, job.widthCu, job.heightCu, job.originX, job.originY)
        assertEquals(local.first + 2480, shifted.first, 1e-9)
        assertEquals(local.second - 3508, shifted.second, 1e-9)
    }

    @Test
    fun anchors_of_both_kinds_map_through_the_job_region() {
        val text = Text("t1", at = listOf(0.5, 0.5), text = "10", size = 0.02)
        val byId = mapOf<String, Annotation>("t1" to text)
        val byAnnotation = AnchorHitTest.rectForAnchor(
            AnchorHitTest.AnchorRegion(annotationId = "t1"), byId, job.widthCu, job.heightCu, job.originX, job.originY,
        )!!
        assertArrayEquals(doubleArrayOf(2480 + 1500.0, -3508 + 1000.0, 0.0, 40.0), byAnnotation, 1e-9)
        val byRegion = AnchorHitTest.rectForAnchor(
            AnchorHitTest.AnchorRegion(region = listOf(0.1, 0.1, 0.2, 0.2)), byId, job.widthCu, job.heightCu, job.originX, job.originY,
        )!!
        assertArrayEquals(doubleArrayOf(2480 + 300.0, -3508 + 200.0, 600.0, 400.0), byRegion, 1e-9)

        // A tap on the mark (in canvas CU) finds the card; the same spot without the origin does not.
        val cards = listOf(listOf(AnchorHitTest.AnchorRegion(annotationId = "t1")))
        assertEquals(0, AnchorHitTest.cardIndexForTap(3980.0, -2500.0, cards, byId, job.widthCu, job.heightCu, originX = job.originX, originY = job.originY))
        assertNull(AnchorHitTest.cardIndexForTap(1500.0, 1000.0, cards, byId, job.widthCu, job.heightCu, originX = job.originX, originY = job.originY))
    }

    @Test
    fun selection_rects_map_the_same_way_and_invert() {
        val cu = CoordinateMapping.selectionNmToCu(listOf(0.1, 0.2, 0.3, 0.4), job.widthCu, job.heightCu, job.originX, job.originY)
        assertArrayEquals(doubleArrayOf(2780.0, -3108.0, 900.0, 800.0), cu, 1e-9)
        val nm = CoordinateMapping.selectionCuToNm(cu, job.widthCu, job.heightCu, job.originX, job.originY)
        assertEquals(listOf(0.1, 0.2, 0.3, 0.4).map { Math.round(it * 1e9) }, nm.map { Math.round(it * 1e9) })
    }

    @Test
    fun origin_zero_is_bit_identical_to_the_v1_mapping() {
        for (nm in listOf(0.0, 0.123456789, 0.5, 0.987654321, 1.0, -0.05, 1.05)) {
            assertEquals(nm * pageW, CoordinateMapping.nmToCuX(nm, pageW), 0.0)
            assertEquals(nm * pageH, CoordinateMapping.nmToCuY(nm, pageH), 0.0)
            assertEquals((nm * pageW) / pageW, CoordinateMapping.cuToNmX(nm * pageW, pageW), 0.0)
        }
        val rect = RectAnnotation("r", x = 0.1234, y = 0.5678, w = 0.3, h = 0.2)
        assertArrayEquals(
            doubleArrayOf(0.1234 * pageW, 0.5678 * pageH, 0.3 * pageW, 0.2 * pageH),
            AnnotationGeometry.boundsCu(rect, pageW, pageH), 0.0,
        )
    }

    @Test
    fun a_later_grid_change_does_not_move_a_jobs_geometry() {
        // The geometry is a function of the JOB's region only: growing the grid (which changes
        // the canvas bounds and so any future region) leaves it where it was.
        val jobRegion = CoordinateMapping.Region(2480, 0, 2480, 3508)
        val h = Highlight("h", listOf(listOf(0.4, 0.45), listOf(0.6, 0.55)))
        fun place() = AnnotationGeometry.boundsCu(h, jobRegion.widthCu, jobRegion.heightCu, jobRegion.originX, jobRegion.originY)
        val before = place()
        // The canvas grows left by a page: the grid bounds become [-2480, 4960).
        val grownRegionNow = region(-2480.0, 0.0, 4960.0, 3508.0, PageExtent(-1, 1, 0, 0))
        assertEquals(CoordinateMapping.Region(-2480, 0, 3 * 2480, 3508), grownRegionNow)
        assertArrayEquals(before, place(), 0.0)
        assertEquals(2480 + 0.4 * 2480, before[0], 1e-9) // still on page (1,0)
    }

    // --- export draw partition (multi-page grid) ---

    @Test
    fun page_chunks_partition_the_export_image_exactly() {
        val grid = PageExtent(-1, 1, 0, 1)
        val r = CoordinateMapping.Region(-1000, 1000, 5000, 4000) // spans columns -1..1, rows 0..1
        val export = CoordinateMapping.export(r)
        val scale = CoordinateMapping.exportScale(r.widthCu, r.heightCu).toFloat()
        val chunks = CanvasExporter.pageChunks(r, pageW, pageH, grid, scale, export.w, export.h)
        val hits = IntArray(export.w * export.h)
        for (c in chunks) for (y in c[1] until c[3]) for (x in c[0] until c[2]) hits[y * export.w + x]++
        assertTrue("every export pixel is drawn exactly once", hits.all { it == 1 })
        // Six grid pages touch the region (3 columns x 2 rows).
        assertEquals(6, chunks.size)
        // The column boundary at x = 0 CU lands at round(1000 x scale) px.
        assertTrue(chunks.any { it[0] == Math.round(1000 * scale.toDouble()).toInt() })
    }

    // --- review fix: sliver regions are not exportable (MIN_EXPORT_SIDE_PX = 16) ---

    @Test
    fun a_one_cu_sliver_is_not_exportable_and_never_exports_a_zero_px_side() {
        // The page's right edge 0.8 CU inside the screen's left: region (2479, 0, 1, 3508).
        val sliver = region(2479.2, -10.0, 4000.0, 3600.0, PageExtent.SINGLE)
        assertEquals(CoordinateMapping.Region(2479, 0, 1, 3508), sliver)
        assertFalse(CoordinateMapping.isExportable(sliver!!))
        // The formula alone would round the width to 0 px; the defensive floor keeps it 1.
        assertEquals(1 to 1568, CoordinateMapping.exportDimensions(1, 3508))
    }

    @Test
    fun the_minimum_export_side_boundary_is_16_px() {
        // 35 CU of a page-high region → round(15.645) = 16 px: exportable.
        assertEquals(16, CoordinateMapping.exportDimensions(35, 3508).first)
        assertTrue(CoordinateMapping.isExportable(CoordinateMapping.Region(2445, 0, 35, 3508)))
        // 34 CU → round(15.197) = 15 px: not exportable.
        assertEquals(15, CoordinateMapping.exportDimensions(34, 3508).first)
        assertFalse(CoordinateMapping.isExportable(CoordinateMapping.Region(2446, 0, 34, 3508)))
        // The same on the other axis (a page-wide strip exports at 1568/2480 px/CU: 24 CU →
        // 15 px, 25 CU → 16 px), and a whole page is of course fine.
        assertFalse(CoordinateMapping.isExportable(CoordinateMapping.Region(0, 3484, 2480, 24)))
        assertTrue(CoordinateMapping.isExportable(CoordinateMapping.Region(0, 3483, 2480, 25)))
        assertTrue(CoordinateMapping.isExportable(CoordinateMapping.Region(0, 0, pageW, pageH)))
    }

    // --- review fix: oversized pages export at a coarser LOD instead of dropping ink ---

    private val budget = LayerRenderer.DEFAULT_BUDGET_BYTES

    @Test
    fun a4_pages_always_export_at_lod_0_so_byte_identity_holds() {
        fun lod(w: Int, h: Int, grid: PageExtent) =
            CanvasExporter.exportLod(w, h, grid, CoordinateMapping.exportScale(w, h), budget)
        assertEquals(0, lod(2480, 3508, PageExtent.SINGLE))
        assertEquals(0, lod(3508, 2480, PageExtent.SINGLE))
        assertEquals(0, lod(1600, 1200, PageExtent.SINGLE))
        // Multi-page A4 grids (one page pass + neighbouring sub-tiles) stay at LOD 0 too.
        assertEquals(0, CanvasExporter.exportLod(2480, 3508, PageExtent(-1, 1, 0, 1), 0.3, budget))
    }

    @Test
    fun an_oversized_page_exports_at_a_coarser_lod_still_finer_than_the_export() {
        // A formalized canvas the size of the largest region (7016 × 7016 CU): LOD 0 would
        // be 196.9 MB, over the 96 MB budget.
        val scale = CoordinateMapping.exportScale(7016, 7016) // 0.2235 px/CU
        val lod = CanvasExporter.exportLod(7016, 7016, PageExtent.SINGLE, scale, budget)
        assertEquals(1, lod)
        assertTrue(TileMath.lodResolution(lod) >= scale)
        val bytes = TileMath.pagePixels(7016, lod).toLong() * TileMath.pagePixels(7016, lod) * 4
        assertTrue("fits the budget: $bytes", bytes <= budget)
        // 7016 × 3508 (98.4 MB) still fits the 96 MiB (100.7 MB) budget: LOD 0; just past
        // it, 7016 × 3600 (101.0 MB), steps down one LOD.
        assertEquals(0, CanvasExporter.exportLod(7016, 3508, PageExtent.SINGLE, CoordinateMapping.exportScale(7016, 3508), budget))
        assertEquals(1, CanvasExporter.exportLod(7016, 3600, PageExtent.SINGLE, CoordinateMapping.exportScale(7016, 3600), budget))
        // Never coarser than the export itself, even when nothing fits.
        assertEquals(0, CanvasExporter.exportLod(7016, 7016, PageExtent.SINGLE, 0.9, budget))
    }
}
