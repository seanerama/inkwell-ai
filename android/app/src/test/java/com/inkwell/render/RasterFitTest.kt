package com.inkwell.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure raster placement/scaling maths (Stage 22, ADR-0012). No Android.
 */
class RasterFitTest {

    @Test
    fun fitToWidth_fills_width_and_preserves_aspect_top_left() {
        // A portrait page 850×1100 fitted to a 2480-wide canvas.
        val p = RasterFit.fitToWidth(srcW = 850, srcH = 1100, canvasWidthCu = 2480)
        assertEquals(0f, p.xCu, 0f)
        assertEquals(0f, p.yCu, 0f)
        assertEquals(2480f, p.wCu, 0f)
        // height = 2480 * 1100/850 = 3209.4…
        assertEquals(2480f * 1100f / 850f, p.hCu, 0.01f)
    }

    @Test
    fun canvasSizeFor_picks_portrait_or_landscape() {
        assertEquals(2480 to 3508, RasterFit.canvasSizeFor(850, 1100)) // portrait
        assertEquals(2480 to 3508, RasterFit.canvasSizeFor(1000, 1000)) // square → portrait
        assertEquals(3508 to 2480, RasterFit.canvasSizeFor(1100, 850)) // landscape → same-area swap
    }

    @Test
    fun sampleSize_bounds_large_images_to_power_of_two() {
        assertEquals(1, RasterFit.sampleSize(2000, 2000, 2480, 3508)) // already fits
        assertEquals(4, RasterFit.sampleSize(10000, 10000, 2480, 2480)) // 10000/4=2500 >= 2480
        assertTrue("never zero", RasterFit.sampleSize(1, 1, 2480, 3508) >= 1)
    }

    @Test
    fun destRectPx_applies_scale_and_translation() {
        val placement = RasterFit.Placement(xCu = 0f, yCu = 0f, wCu = 2480f, hCu = 3508f)
        val rect = RasterFit.destRectPx(placement, scale = 0.5f, tx = 10f, ty = 20f)
        assertEquals(10f, rect.left, 0f)
        assertEquals(20f, rect.top, 0f)
        assertEquals(10f + 2480f * 0.5f, rect.right, 0f)
        assertEquals(20f + 3508f * 0.5f, rect.bottom, 0f)
        assertEquals(1240f, rect.width, 0f)
        assertEquals(1754f, rect.height, 0f)
    }
}
