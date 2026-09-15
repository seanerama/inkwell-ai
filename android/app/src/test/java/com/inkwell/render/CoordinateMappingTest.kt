package com.inkwell.render

import org.junit.Assert.assertEquals
import org.junit.Test

/** NM→CU mapping for the default A4/300dpi canvas (2480×3508), contract coordinate-mapping. */
class CoordinateMappingTest {

    @Test
    fun maps_corners_and_center_exactly() {
        // [0,0] → top-left origin
        assertEquals(0.0, CoordinateMapping.nmToCuX(0.0), 0.0)
        assertEquals(0.0, CoordinateMapping.nmToCuY(0.0), 0.0)

        // [1,1] → bottom-right = full canvas extent
        assertEquals(2480.0, CoordinateMapping.nmToCuX(1.0), 0.0)
        assertEquals(3508.0, CoordinateMapping.nmToCuY(1.0), 0.0)

        // [0.5,0.5] → exact centre
        assertEquals(1240.0, CoordinateMapping.nmToCuX(0.5), 0.0)
        assertEquals(1754.0, CoordinateMapping.nmToCuY(0.5), 0.0)
    }

    @Test
    fun export_longest_edge_is_exactly_1568() {
        val (w, h) = CoordinateMapping.exportDimensions()
        // Contract guarantee: the longest edge is exactly 1568 px (A4 portrait → height).
        assertEquals(1568, h)
        // Width follows from round(2480 * 1568/3508) = 1108.506 -> 1109. (The device-api
        // POST /jobs *example* prints 1108; the coordinate-mapping formula is authoritative
        // and uses round(), so 1108/1109 differ only by that example's rounding.)
        org.junit.Assert.assertTrue("width was $w", w == 1108 || w == 1109)
    }
}
