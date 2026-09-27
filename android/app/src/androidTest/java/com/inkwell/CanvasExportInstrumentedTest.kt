package com.inkwell

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkwell.render.CanvasExporter
import com.inkwell.render.CoordinateMapping
import com.inkwell.render.ExportLayer
import com.inkwell.render.ExportRaster
import com.inkwell.render.RasterFit
import com.inkwell.render.RenderStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * Instrumented export test (emulator lane in release.yml, alongside the Stage 3 class):
 * export a canvas with three drawn rectangles and assert the resulting PNG has a
 * non-white pixel at the centre of the middle rectangle and white corners. Also checks
 * the default canvas exports 1109×1568 (the round() formula; the "1108" prose is issue #9).
 *
 * Each "rectangle" is a filled bar: a 2-point horizontal stroke at full pressure whose
 * [RenderStroke.widthCu] is the bar height, so its centre pixel is solidly painted.
 */
@RunWith(AndroidJUnit4::class)
class CanvasExportInstrumentedTest {

    private val widthCu = CoordinateMapping.DEFAULT_WIDTH_CU // 2480
    private val heightCu = CoordinateMapping.DEFAULT_HEIGHT_CU // 3508
    private val barHeightCu = 200f

    /** A filled horizontal bar centred at (cx, cy) in canvas units. */
    private fun rect(id: String, cx: Float, cy: Float, halfWidthCu: Float): RenderStroke {
        val points = floatArrayOf(
            cx - halfWidthCu, cy, 1f, 0f, 0f,
            cx + halfWidthCu, cy, 1f, 0f, 1f,
        )
        return RenderStroke(
            id = id,
            points = points,
            tool = "pen",
            color = Color.BLACK,
            widthCu = barHeightCu,
            bboxX = cx - halfWidthCu, bboxY = cy - barHeightCu / 2,
            bboxW = halfWidthCu * 2, bboxH = barHeightCu,
        )
    }

    @Test
    fun three_rectangles_export_center_nonwhite_corners_white() {
        val midCx = 1240f
        val midCy = 1754f
        val strokes = listOf(
            rect("left", 500f, midCy, 120f),
            rect("mid", midCx, midCy, 120f),
            rect("right", 1980f, midCy, 120f),
        )
        val result = CanvasExporter.export(
            widthCu, heightCu,
            listOf(ExportLayer(z = 0, visible = true, strokes = strokes)),
        )
        assertTrue("export should succeed", result is CanvasExporter.Result.Success)
        result as CanvasExporter.Result.Success

        // Default canvas → 1109×1568 (formula; issue-#9 prose says 1108).
        assertEquals(1109, result.export.w)
        assertEquals(1568, result.export.h)

        val bmp = BitmapFactory.decodeByteArray(result.png, 0, result.png.size)
        assertEquals(1109, bmp.width)
        assertEquals(1568, bmp.height)

        val scale = CoordinateMapping.exportScale(widthCu, heightCu)
        val exX = (midCx * scale).roundToInt().coerceIn(0, bmp.width - 1)
        val exY = (midCy * scale).roundToInt().coerceIn(0, bmp.height - 1)
        val center = bmp.getPixel(exX, exY)
        assertTrue(
            "centre of middle rect should be non-white, was ${Integer.toHexString(center)}",
            center != Color.WHITE,
        )

        // All four corners are the white background.
        assertEquals(Color.WHITE, bmp.getPixel(0, 0))
        assertEquals(Color.WHITE, bmp.getPixel(bmp.width - 1, 0))
        assertEquals(Color.WHITE, bmp.getPixel(0, bmp.height - 1))
        assertEquals(Color.WHITE, bmp.getPixel(bmp.width - 1, bmp.height - 1))
        bmp.recycle()
    }

    /**
     * Stage 22 (acceptance): a pushed canvas's export composites the raster BENEATH the ink.
     * A solid-red raster fills the canvas (z=-1); a black ink bar crosses the middle (z=0).
     * A corner (raster only) reads red; the bar centre (ink over raster) reads black — proving
     * the raster is below the ink, so the agent sees the document with the marks on top.
     */
    @Test
    fun raster_composites_beneath_ink() {
        val red = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val raster = ExportRaster(
            z = -1,
            visible = true,
            bitmap = red,
            placement = RasterFit.Placement(0f, 0f, widthCu.toFloat(), heightCu.toFloat()),
        )
        val midCy = 1754f
        val inkBar = rect("bar", 1240f, midCy, 900f)

        val result = CanvasExporter.export(
            widthCu, heightCu,
            layers = listOf(ExportLayer(z = 0, visible = true, strokes = listOf(inkBar))),
            rasters = listOf(raster),
        )
        assertTrue("export should succeed", result is CanvasExporter.Result.Success)
        result as CanvasExporter.Result.Success

        val bmp = BitmapFactory.decodeByteArray(result.png, 0, result.png.size)
        // Corner: raster fills the canvas, so the white background is covered → red.
        assertEquals(Color.RED, bmp.getPixel(2, 2))
        // Bar centre: ink drawn over the raster → black (ink wins, raster is beneath).
        val scale = CoordinateMapping.exportScale(widthCu, heightCu)
        val exX = (1240f * scale).roundToInt().coerceIn(0, bmp.width - 1)
        val exY = (midCy * scale).roundToInt().coerceIn(0, bmp.height - 1)
        assertEquals(Color.BLACK, bmp.getPixel(exX, exY))
        bmp.recycle()
        red.recycle()
    }

    /**
     * Stage 35: a region export on a multi-page grid (the per-page clipped draw passes). On a
     * 2 × 1 grid, a bar crossing the page edge at x = 2480 exports solid on both sides of the
     * seam and at it (no gap, no double-drawn edge), and a region of page (1,0) alone is
     * translated by its origin: its bar lands at `(x − 2480) × scale` and page (0,0)'s ink is
     * not in it.
     */
    @Test
    fun region_export_on_a_two_page_grid() {
        val grid = com.inkwell.data.PageExtent(0, 1, 0, 0)
        val cross = rect("cross", 2480f, 1000f, 600f) // x 1880..3080, across the page edge
        val left = rect("left", 800f, 2500f, 300f) // page (0,0) only
        val right = rect("right", 3900f, 2500f, 300f) // page (1,0) only
        val layers = listOf(ExportLayer(z = 0, visible = true, strokes = listOf(cross, left, right)))

        // Both pages: 4960 × 3508 CU → 1568 × 1109 px.
        val both = CoordinateMapping.Region(0, 0, 2 * widthCu, heightCu)
        val r1 = CanvasExporter.export(both, widthCu, heightCu, grid, layers)
        assertTrue(r1 is CanvasExporter.Result.Success)
        r1 as CanvasExporter.Result.Success
        assertEquals(1568, r1.export.w)
        assertEquals(1109, r1.export.h)
        val b1 = BitmapFactory.decodeByteArray(r1.png, 0, r1.png.size)
        val k1 = CoordinateMapping.exportScale(both.widthCu, both.heightCu)
        val y1 = (1000f * k1).roundToInt()
        for (x in listOf(2300f, 2479f, 2480f, 2481f, 2700f)) {
            assertEquals("bar at x=$x", Color.BLACK, b1.getPixel((x * k1).roundToInt(), y1))
        }
        assertTrue(b1.getPixel((800f * k1).roundToInt(), (2500f * k1).roundToInt()) != Color.WHITE)
        assertTrue(b1.getPixel((3900f * k1).roundToInt(), (2500f * k1).roundToInt()) != Color.WHITE)
        b1.recycle()

        // Page (1,0) alone: origin (2480, 0), one page in size → 1109 × 1568 px.
        val page1 = CoordinateMapping.Region(widthCu, 0, widthCu, heightCu)
        val r2 = CanvasExporter.export(page1, widthCu, heightCu, grid, layers) as CanvasExporter.Result.Success
        assertEquals(CoordinateMapping.export(page1), r2.export)
        assertEquals(1109, r2.export.w)
        val b2 = BitmapFactory.decodeByteArray(r2.png, 0, r2.png.size)
        val k2 = CoordinateMapping.exportScale(widthCu, heightCu)
        assertTrue(b2.getPixel(((3900f - widthCu) * k2).roundToInt(), (2500f * k2).roundToInt()) != Color.WHITE)
        assertEquals("page (0,0)'s bar is outside the region", Color.WHITE, b2.getPixel((800f * k2).roundToInt(), (2500f * k2).roundToInt()))
        // The crossing bar's page-(1,0) half starts at the region's left edge.
        assertEquals(Color.BLACK, b2.getPixel(2, (1000f * k2).roundToInt()))
        b2.recycle()
    }
}
