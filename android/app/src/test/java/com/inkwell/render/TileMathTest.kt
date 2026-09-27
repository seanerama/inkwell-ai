package com.inkwell.render

import com.inkwell.data.PageExtent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 32: tile keying, LOD choice and tile coverage for the tiled committed-ink cache. */
class TileMathTest {

    private val w = 2480
    private val h = 3508
    private val grid3x3 = PageExtent(-1, 1, -1, 1)

    @Test
    fun lod_is_full_resolution_at_and_above_half_scale() {
        assertEquals(0, TileMath.lodFor(8f))
        assertEquals(0, TileMath.lodFor(1f))
        assertEquals(0, TileMath.lodFor(0.75f))
        // Exactly 0.5 keeps LOD 0: the single-page tile is the old page bitmap.
        assertEquals(0, TileMath.lodFor(0.5f))
    }

    @Test
    fun lod_steps_in_powers_of_two_below_half_scale_and_is_capped() {
        assertEquals(1, TileMath.lodFor(0.49f))
        assertEquals(1, TileMath.lodFor(0.26f))
        assertEquals(1, TileMath.lodFor(0.25f))
        assertEquals(2, TileMath.lodFor(0.2f))
        assertEquals(3, TileMath.lodFor(0.1f))
        assertEquals(TileMath.MAX_LOD, TileMath.lodFor(0.001f))
        assertEquals(TileMath.MAX_LOD, TileMath.lodFor(0f))
    }

    @Test
    fun a_tile_is_never_magnified_below_full_resolution() {
        var s = 0.02f
        while (s <= 1f) {
            val lod = TileMath.lodFor(s)
            val res = TileMath.lodResolution(lod)
            if (lod < TileMath.MAX_LOD) {
                assertTrue("scale $s lod $lod", res > s || (lod == 0 && res >= s))
                assertTrue("scale $s lod $lod downsample ≤ 2", res <= 2 * s || lod == 0)
            }
            s += 0.01f
        }
    }

    @Test
    fun tile_keys_include_layer_page_lod_and_subtile() {
        val a = TileKey("ink", 0, 0, 0)
        assertEquals(a, TileKey("ink", 0, 0, 0, 0, 0, 1))
        assertNotEquals(a, TileKey("agent", 0, 0, 0))
        assertNotEquals(a, TileKey("ink", 1, 0, 0))
        assertNotEquals(a, TileKey("ink", 0, -1, 0))
        assertNotEquals(a, TileKey("ink", 0, 0, 1))
        assertNotEquals(TileKey("ink", 0, 0, 0, 1, 0, 4), TileKey("ink", 0, 0, 0, 0, 1, 4))
    }

    @Test
    fun single_page_lod0_tile_is_the_whole_page_at_one_px_per_cu() {
        val s = TileMath.spec(TileKey("ink", 0, 0, 0), w, h)
        assertEquals(0, s.pxLeft)
        assertEquals(0, s.pxTop)
        assertEquals(w, s.pxWidth)
        assertEquals(h, s.pxHeight)
        assertEquals(1f, s.scaleX)
        assertEquals(1f, s.scaleY)
        assertEquals(0f, s.leftCu)
        assertEquals(w.toFloat(), s.rightCu)
        assertEquals(34_799_360L, s.bytes) // the old page cache, ~34.8 MB
    }

    @Test
    fun coarser_lods_cover_exactly_the_page() {
        for (lod in 1..TileMath.MAX_LOD) {
            val s = TileMath.spec(TileKey("ink", 2, -1, lod), w, h)
            assertEquals(TileMath.pagePixels(w, lod), s.pxWidth)
            assertEquals(2f * w, s.leftCu, 0.001f)
            assertEquals(3f * w, s.rightCu, 0.01f)
            assertEquals(-1f * h, s.topCu, 0.001f)
            assertEquals(0f, s.bottomCu, 0.01f)
        }
        assertEquals(1240, TileMath.pagePixels(w, 1))
        assertEquals(1754, TileMath.pagePixels(h, 1))
        assertEquals(439, TileMath.pagePixels(h, 3)) // ceil(3508 / 8)
    }

    @Test
    fun a_single_page_is_never_subdivided_a_multi_page_grid_is_at_fine_lods() {
        assertEquals(1, TileMath.subdivisions(PageExtent.SINGLE, 0))
        assertEquals(4, TileMath.subdivisions(grid3x3, 0))
        assertEquals(2, TileMath.subdivisions(grid3x3, 1))
        assertEquals(1, TileMath.subdivisions(grid3x3, 2))
        assertEquals(1, TileMath.subdivisions(grid3x3, 5))
    }

    @Test
    fun subtiles_partition_the_page_without_gaps() {
        val div = 4
        var x = 0
        for (sx in 0 until div) {
            val s = TileMath.spec(TileKey("ink", 0, 0, 0, sx, 0, div), w, h)
            assertEquals(x, s.pxLeft)
            x += s.pxWidth
        }
        assertEquals(w, x)
        assertEquals(620, TileMath.spec(TileKey("ink", 0, 0, 0, 1, 1, div), w, h).pxWidth)
        assertEquals(877, TileMath.spec(TileKey("ink", 0, 0, 0, 1, 1, div), w, h).pxHeight)
    }

    @Test
    fun a_bbox_inside_page_zero_touches_only_that_tile() {
        val tiles = TileMath.tilesTouching("ink", RectCu(100f, 100f, 200f, 200f), w, h, PageExtent.SINGLE, 0)
        assertEquals(listOf(TileKey("ink", 0, 0, 0)), tiles.map { it.key })
    }

    @Test
    fun a_bbox_with_negative_coordinates_touches_the_pages_left_and_above() {
        val tiles = TileMath.tilesTouching("ink", RectCu(-10f, -10f, 10f, 10f), w, h, grid3x3, 2)
        assertEquals(
            setOf(TileKey("ink", -1, -1, 2), TileKey("ink", 0, -1, 2), TileKey("ink", -1, 0, 2), TileKey("ink", 0, 0, 2)),
            tiles.map { it.key }.toSet(),
        )
    }

    @Test
    fun page_edges_are_half_open() {
        // x = 2480 belongs to page 1; x just below belongs to page 0 only.
        val onEdge = TileMath.tilesTouching("ink", RectCu(2480f, 10f, 2480f, 10f), w, h, grid3x3, 2)
        assertEquals(listOf(TileKey("ink", 1, 0, 2)), onEdge.map { it.key })
        val below = TileMath.tilesTouching("ink", RectCu(2479.5f, 10f, 2479.5f, 10f), w, h, grid3x3, 2)
        assertEquals(listOf(TileKey("ink", 0, 0, 2)), below.map { it.key })
        val across = TileMath.tilesTouching("ink", RectCu(2470f, 3500f, 2490f, 3510f), w, h, grid3x3, 2)
        assertEquals(4, across.size)
    }

    @Test
    fun tiles_are_clamped_to_the_grid() {
        val far = TileMath.tilesTouching("ink", RectCu(-1e6f, -1e6f, 1e6f, 1e6f), w, h, PageExtent.SINGLE, 3)
        assertEquals(listOf(TileKey("ink", 0, 0, 3)), far.map { it.key })
        val outside = TileMath.tilesTouching("ink", RectCu(3000f, 0f, 4000f, 10f), w, h, PageExtent.SINGLE, 0)
        assertTrue(outside.isEmpty())
    }

    @Test
    fun lod0_subtiles_for_a_corner_bbox_on_a_multi_page_grid() {
        // A bbox around the corner shared by pages (0,0),(1,0),(0,1),(1,1): one sub-tile each.
        val tiles = TileMath.tilesTouching("ink", RectCu(2470f, 3500f, 2490f, 3510f), w, h, grid3x3, 0)
        assertEquals(
            setOf(
                TileKey("ink", 0, 0, 0, 3, 3, 4), TileKey("ink", 1, 0, 0, 0, 3, 4),
                TileKey("ink", 0, 1, 0, 3, 0, 4), TileKey("ink", 1, 1, 0, 0, 0, 4),
            ),
            tiles.map { it.key }.toSet(),
        )
    }

    @Test
    fun viewport_maps_view_pixels_to_canvas_units_with_a_pixel_of_padding() {
        val vp = TileMath.viewportCu(0f, 0f, 1000f, 500f, scale = 2f, tx = 100f, ty = -50f)
        assertEquals((-1f - 100f) / 2f, vp.left, 1e-4f)
        assertEquals((501f + 50f) / 2f, vp.bottom, 1e-4f)
    }

    @Test
    fun choose_lod_coarsens_until_the_visible_tiles_fit_the_budget() {
        // Whole 3×3 grid visible at scale 0.6 → LOD 0 by scale, but 9 A4 pages at LOD 0
        // are ~313 MB: coarsened until they fit 96 MB.
        val vp = RectCu(-w.toFloat(), -h.toFloat(), 2f * w, 2f * h)
        val budget = 96L * 1024 * 1024
        val (lod, tiles) = TileMath.chooseLod("ink", vp, w, h, grid3x3, 0.6f, budget)
        assertTrue(tiles.sumOf { it.bytes } <= budget)
        assertEquals(1, lod)
        assertEquals(9 * 4, tiles.size) // 2×2 sub-tiles per page at LOD 1
        // A small zoomed-in viewport keeps full resolution.
        val (lod0, few) = TileMath.chooseLod("ink", RectCu(100f, 100f, 1500f, 1000f), w, h, grid3x3, 2f, budget)
        assertEquals(0, lod0)
        assertEquals(6, few.size) // 3 × 2 sub-tiles of 620 × 877
    }

    @Test
    fun ink_reach_covers_half_width_and_coarsest_lod_pixel() {
        assertEquals(1.5f + 32f + 2f, TileMath.inkReachCu(3f), 0f)
    }
}
