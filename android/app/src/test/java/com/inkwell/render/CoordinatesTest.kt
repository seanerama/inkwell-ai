package com.inkwell.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * JVM unit tests for the pure coordinate seam (contract `coordinate-mapping` v1,
 * SPEC §5). These run without an emulator — the whole point of keeping the mapping
 * Android-free. Export dimensions are derived FROM the formula, never hardcoded to the
 * "1108" prose (issue #9); the default canvas is 1109×1568.
 */
class CoordinatesTest {

    private val w = CoordinateMapping.DEFAULT_WIDTH_CU // 2480
    private val h = CoordinateMapping.DEFAULT_HEIGHT_CU // 3508

    /** round(width_cu * 1568 / max(width_cu, height_cu)) — the authoritative formula. */
    private fun expectedExport(widthCu: Int, heightCu: Int): Pair<Int, Int> {
        val scale = 1568.0 / maxOf(widthCu, heightCu)
        return Math.round(widthCu * scale).toInt() to Math.round(heightCu * scale).toInt()
    }

    @Test
    fun export_longest_edge_is_1568_and_aspect_preserved_for_three_canvases() {
        val canvases = listOf(2480 to 3508, 3508 to 2480, 1000 to 1000)
        for ((cw, ch) in canvases) {
            val (ew, eh) = CoordinateMapping.exportDimensions(cw, ch)
            val (expW, expH) = expectedExport(cw, ch)
            assertEquals("width for ${cw}x$ch", expW, ew)
            assertEquals("height for ${cw}x$ch", expH, eh)
            // Longest edge is exactly 1568.
            assertEquals("longest edge for ${cw}x$ch", 1568, maxOf(ew, eh))
            // Aspect preserved within one pixel of rounding.
            val srcAspect = cw.toDouble() / ch
            val exAspect = ew.toDouble() / eh
            assertTrue(
                "aspect drift for ${cw}x$ch: src=$srcAspect ex=$exAspect",
                abs(srcAspect - exAspect) < (1.0 / minOf(ew, eh)) + 1e-9,
            )
        }
    }

    @Test
    fun default_canvas_exports_1109_by_1568() {
        assertEquals(1109 to 1568, CoordinateMapping.exportDimensions())
        val export = CoordinateMapping.export()
        assertEquals(1109, export.w)
        assertEquals(1568, export.h)
        assertEquals(2480, export.widthCu)
        assertEquals(3508, export.heightCu)
    }

    @Test
    fun nmToCu_exact_for_default_canvas() {
        assertEquals(0.0, CoordinateMapping.nmToCuX(0.0, w), 0.0)
        assertEquals(0.0, CoordinateMapping.nmToCuY(0.0, h), 0.0)
        assertEquals(2480.0, CoordinateMapping.nmToCuX(1.0, w), 0.0)
        assertEquals(3508.0, CoordinateMapping.nmToCuY(1.0, h), 0.0)
        assertEquals(1240.0, CoordinateMapping.nmToCuX(0.5, w), 0.0)
        assertEquals(1754.0, CoordinateMapping.nmToCuY(0.5, h), 0.0)
    }

    @Test
    fun cuToNm_is_exact_inverse_of_nmToCu_within_1e6() {
        val samples = listOf(0.0 to 0.0, 1.0 to 1.0, 0.5 to 0.5, 0.137 to 0.913, 0.42 to 0.08)
        for ((nx, ny) in samples) {
            val cu = CoordinateMapping.nmToCu(nx to ny, w, h)
            val back = CoordinateMapping.cuToNm(cu, w, h)
            assertEquals(nx, back.first, 1e-6)
            assertEquals(ny, back.second, 1e-6)
        }
    }

    @Test
    fun sizeToCu_maps_size_times_height_cu() {
        assertEquals(0.02 * h, CoordinateMapping.sizeToCu(0.02, h), 1e-9)
        assertEquals(0.02 * 3508, CoordinateMapping.sizeToCu(0.02), 1e-9) // default height
    }

    @Test
    fun selection_rect_maps_normalized_to_canvas_units() {
        val sel = listOf(0.1, 0.2, 0.5, 0.3)
        val cu = CoordinateMapping.selectionNmToCu(sel, w, h)
        assertEquals(0.1 * w, cu[0], 1e-9)
        assertEquals(0.2 * h, cu[1], 1e-9)
        assertEquals(0.5 * w, cu[2], 1e-9)
        assertEquals(0.3 * h, cu[3], 1e-9)
        // Round-trips.
        val back = CoordinateMapping.selectionCuToNm(cu, w, h)
        for (i in sel.indices) assertEquals(sel[i], back[i], 1e-9)
    }

    @Test
    fun nm_points_bounds_is_points_times_canvas_size() {
        // The valid-full-vocabulary highlight a1.
        val points = listOf(
            listOf(0.10, 0.20), listOf(0.40, 0.20), listOf(0.40, 0.30), listOf(0.10, 0.30),
        )
        val bbox = CoordinateMapping.nmPointsBoundsCu(points, w, h)
        assertEquals(0.10 * w, bbox[0], 1e-9)
        assertEquals(0.20 * h, bbox[1], 1e-9)
        assertEquals(0.30 * w, bbox[2], 1e-9) // width  = (0.40-0.10) * w
        assertEquals(0.10 * h, bbox[3], 1e-9) // height = (0.30-0.20) * h
    }
}
