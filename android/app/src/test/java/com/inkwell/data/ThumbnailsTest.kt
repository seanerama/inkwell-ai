package com.inkwell.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit test for the pure thumbnail path derivation (Stage 11). The actual bitmap
 * render is Android-dependent ([ThumbnailRenderer], emulator lane); the `canvasId →
 * relative path` mapping is pure and lives in [Thumbnails].
 */
class ThumbnailsTest {

    @Test
    fun relativePath_is_under_the_thumbs_dir_named_by_canvas_id() {
        assertEquals("thumbs/canvas-1.png", Thumbnails.relativePath("canvas-1"))
        assertEquals("thumbs/abc123.png", Thumbnails.relativePath("abc123"))
    }

    @Test
    fun relativePath_uses_the_shared_dir_constant() {
        val id = "xyz"
        assertTrue(Thumbnails.relativePath(id).startsWith("${Thumbnails.DIR}/"))
        assertTrue(Thumbnails.relativePath(id).endsWith(".png"))
    }

    @Test
    fun fit_of_a_single_a4_page_matches_the_old_thumbnail_size() {
        val fit = Thumbnails.fit(2480.0, 3508.0)
        assertEquals(181, fit.widthPx)
        assertEquals(256, fit.heightPx)
        assertEquals(256.0 / 3508.0, fit.scale, 1e-12)
    }

    @Test
    fun fit_of_a_multi_page_grid_scales_the_whole_grid_into_the_box() {
        // 3 × 1 grid of A4 pages: 7440 × 3508 CU → landscape thumbnail.
        val wide = Thumbnails.fit(3 * 2480.0, 3508.0)
        assertEquals(256, wide.widthPx)
        assertEquals(121, wide.heightPx)
        // 3 × 3 grid keeps the page aspect ratio.
        val square = Thumbnails.fit(3 * 2480.0, 3 * 3508.0)
        assertEquals(181, square.widthPx)
        assertEquals(256, square.heightPx)
        // 8 × 1: very wide, but at least one pixel tall.
        val strip = Thumbnails.fit(8 * 2480.0, 3508.0)
        assertEquals(256, strip.widthPx)
        assertEquals(45, strip.heightPx)
        assertTrue(Thumbnails.fit(1e9, 1.0).heightPx >= 1)
    }
}
