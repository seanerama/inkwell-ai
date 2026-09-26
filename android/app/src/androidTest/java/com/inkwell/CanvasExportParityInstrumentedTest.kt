package com.inkwell

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inkwell.render.CanvasExporter
import com.inkwell.render.ExportLayer
import com.inkwell.render.ExportRaster
import com.inkwell.render.InkFixtures
import com.inkwell.render.LegacyLayerRenderer
import com.inkwell.render.RasterFit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 32: export stays byte-identical. The exporter now shares one LOD-0 page tile
 * across ink layers instead of allocating a page bitmap per layer; its PNG must equal,
 * byte for byte, the pre-stage-32 pipeline ([LegacyLayerRenderer.legacyExportPng]) on a
 * fixture with two visible ink layers, a hidden one and a raster beneath them.
 */
@RunWith(AndroidJUnit4::class)
class CanvasExportParityInstrumentedTest {

    private val w = InkFixtures.PAGE_W
    private val h = InkFixtures.PAGE_H

    private fun gradient(): Bitmap {
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        for (y in 0 until 48) for (x in 0 until 64) {
            bmp.setPixel(x, y, Color.argb(255, x * 4, y * 5, 160))
        }
        return bmp
    }

    private fun assertSameExport(layers: List<ExportLayer>, rasters: List<ExportRaster>) {
        val expected = LegacyLayerRenderer.legacyExportPng(w, h, layers, rasters)
        assertTrue("fixture must stay under the 2 MB cap", expected.size <= CanvasExporter.MAX_PNG_BYTES)
        val result = CanvasExporter.export(w, h, layers, rasters)
        assertTrue("export should succeed", result is CanvasExporter.Result.Success)
        val png = (result as CanvasExporter.Result.Success).png

        val a = BitmapFactory.decodeByteArray(expected, 0, expected.size)
        val b = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        val pa = IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
        val pb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        assertEquals("differing pixels", 0, pa.indices.count { pa[it] != pb[it] })
        assertArrayEquals("PNG bytes", expected, png)
        a.recycle()
        b.recycle()
    }

    @Test
    fun one_ink_layer_export_is_byte_identical() {
        assertSameExport(listOf(ExportLayer(0, true, InkFixtures.handwriting())), emptyList())
    }

    @Test
    fun several_layers_and_a_raster_export_byte_identical() {
        val raster = gradient()
        try {
            assertSameExport(
                layers = listOf(
                    ExportLayer(z = 0, visible = true, strokes = InkFixtures.handwriting(seed = 1L)),
                    ExportLayer(z = 2, visible = false, strokes = InkFixtures.handwriting(seed = 3L, idPrefix = "hidden")),
                    ExportLayer(z = 1, visible = true, strokes = InkFixtures.handwriting(seed = 2L, idPrefix = "t")),
                    ExportLayer(z = 3, visible = true, strokes = emptyList()),
                ),
                rasters = listOf(
                    ExportRaster(-1, true, raster, RasterFit.Placement(120f, 200f, 1800f, 1350f)),
                ),
            )
        } finally {
            raster.recycle()
        }
    }
}
