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
}
