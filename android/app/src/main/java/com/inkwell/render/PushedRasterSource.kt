package com.inkwell.render

import com.inkwell.data.dao.LayerDao
import com.inkwell.data.dao.RasterDao
import kotlin.math.roundToInt

/**
 * Loads a pushed canvas's raster layer from Room and turns it into the render/export types
 * (Stage 22). One place reads the `raster` layers ([LayerDao]) and their [RasterDao] rows,
 * so the on-screen [RasterRenderer] and the pre-send [CanvasExporter] agree on placement and
 * bytes. Android-touching (it rasterises via [RasterBitmaps]); the app wires it from the two
 * DAOs, gated by `BuildConfig.PUSH_INBOX`.
 */
class PushedRasterSource(
    private val layerDao: LayerDao,
    private val rasterDao: RasterDao,
) {

    /**
     * The on-screen raster spec for [canvasId] — the first raster of its (lowest-z) raster
     * layer — or null when the canvas has no raster. The bitmap is rasterised lazily by
     * [RasterRenderer], so this only carries the file path + placement.
     */
    suspend fun specFor(canvasId: String): RasterRenderer.RasterSpec? {
        val layer = layerDao.forCanvas(canvasId).firstOrNull { it.type == "raster" } ?: return null
        val raster = rasterDao.forLayer(layer.id).firstOrNull() ?: return null
        return RasterRenderer.RasterSpec(
            blobPath = raster.blobUri,
            mime = raster.mime,
            page = raster.page,
            placement = RasterFit.Placement(raster.xCu, raster.yCu, raster.wCu, raster.hCu),
        )
    }

    /**
     * Every raster of [canvasId] rasterised for the export, carrying the layer's `z` (`< 0`,
     * so [CanvasExporter] draws it BENEATH ink). A raster that fails to rasterise is skipped
     * (the ink still exports). Empty when the canvas has no raster.
     */
    suspend fun exportRastersFor(canvasId: String): List<ExportRaster> {
        val out = mutableListOf<ExportRaster>()
        for (layer in layerDao.forCanvas(canvasId).filter { it.type == "raster" }) {
            for (raster in rasterDao.forLayer(layer.id)) {
                val placement = RasterFit.Placement(raster.xCu, raster.yCu, raster.wCu, raster.hCu)
                val bmp = RasterBitmaps.render(
                    blobPath = raster.blobUri,
                    mime = raster.mime,
                    page = raster.page,
                    targetWpx = placement.wCu.roundToInt().coerceAtLeast(1),
                    targetHpx = placement.hCu.roundToInt().coerceAtLeast(1),
                ) ?: continue
                out += ExportRaster(z = layer.z, visible = true, bitmap = bmp, placement = placement)
            }
        }
        return out
    }
}
