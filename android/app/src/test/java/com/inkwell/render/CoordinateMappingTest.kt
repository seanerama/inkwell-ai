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
        // Width from the FORMULA (round), which is authoritative: round(2480 * 1568/3508)
        // = round(1108.506) = 1109. The device-api POST /jobs example and the stage/
        // acceptance prose print 1108 — that is the known off-by-one in issue #9. We follow
        // round(), so the default canvas exports 1109×1568, and we assert 1109 explicitly.
        val expectedW = Math.round(2480.0 * 1568.0 / 3508.0).toInt()
        assertEquals(1109, expectedW)
        assertEquals(1109, w)
    }
}
