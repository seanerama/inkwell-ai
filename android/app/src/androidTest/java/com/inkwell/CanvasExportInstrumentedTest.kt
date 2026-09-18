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
}
