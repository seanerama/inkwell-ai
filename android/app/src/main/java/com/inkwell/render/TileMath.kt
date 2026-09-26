package com.inkwell.render

import com.inkwell.data.PageExtent

/**
 * Stage 32 (ADR-0014 §3): the key of one cached committed-ink tile — page ([col], [row])
 * of [layer] at level of detail [lod], sub-tile ([sx], [sy]) of [div]×[div] within that
 * page. A single-page canvas uses one tile per page per LOD (`div = 1`); a multi-page grid
 * splits the finest LODs into sub-tiles ([TileMath.subdivisions]) so a zoomed-in viewport
 * over a page corner costs a few small tiles rather than four whole pages.
 */
data class TileKey(
    val layer: String,
    val col: Int,
    val row: Int,
    val lod: Int,
    val sx: Int = 0,
    val sy: Int = 0,
    val div: Int = 1,
)

/**
 * Pure geometry of one tile. The page at [lod] is rasterised at
 * `pagePxW × pagePxH = ceil(pageW / 2^lod) × ceil(pageH / 2^lod)` pixels, so a tile
 * maps canvas units to its pixels by `px = (cu − pageOrigin) · scale − pxOrigin` with
 * `scaleX = pagePxW / pageW` (exactly 1 at LOD 0, exactly the page at every LOD so
 * neighbouring pages never overlap). The tile covers `[leftCu, rightCu) × [topCu, bottomCu)`.
 */
data class TileSpec(
    val key: TileKey,
    val pageW: Int,
    val pageH: Int,
    val pxLeft: Int,
    val pxTop: Int,
    val pxWidth: Int,
    val pxHeight: Int,
    val scaleX: Float,
    val scaleY: Float,
) {
    val pageLeftCu: Float get() = key.col.toFloat() * pageW
    val pageTopCu: Float get() = key.row.toFloat() * pageH
    val leftCu: Float get() = pageLeftCu + pxLeft / scaleX
    val topCu: Float get() = pageTopCu + pxTop / scaleY
    val rightCu: Float get() = pageLeftCu + (pxLeft + pxWidth) / scaleX
    val bottomCu: Float get() = pageTopCu + (pxTop + pxHeight) / scaleY

    /** ARGB_8888 bytes of this tile's bitmap. */
    val bytes: Long get() = pxWidth.toLong() * pxHeight * TileMath.BYTES_PER_PIXEL

    /** True when the closed rect `[l, r] × [t, b]` (canvas units) touches this tile. */
    fun intersects(l: Float, t: Float, r: Float, b: Float): Boolean =
        l < rightCu && r >= leftCu && t < bottomCu && b >= topCu
}

/** A rectangle in canvas units (closed bounds, as a stroke's inflated bbox). */
data class RectCu(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Stage 32: pure tile arithmetic for the tiled level-of-detail committed-ink cache
 * ([LayerRenderer]). JVM unit-tested.
 */
object TileMath {

    /** Coarsest LOD: 1/32 px per CU (an A4 page is 78 × 110 px). */
    const val MAX_LOD = 5

    const val BYTES_PER_PIXEL = 4

    /** Sub-tiles per page axis at LOD 0 on a multi-page grid (620 × 877 px for A4). */
    const val MULTI_PAGE_SUBDIVISIONS = 4

    /** Pixels per canvas unit at [lod]: `2^-lod` (LOD 0 is today's full 1 px/CU). */
    fun lodResolution(lod: Int): Float = 1f / (1 shl lod)

    /**
     * The LOD for an on-screen [scale] (view px per CU): the coarsest level whose
     * resolution is still **strictly greater** than [scale], capped at LOD 0 (1 px/CU) and
     * [MAX_LOD]. A tile is therefore only ever downsampled on screen, by a factor in
     * (1, 2] — or drawn 1:1 / magnified at scale ≥ 1, exactly as the old page bitmap was.
     *
     * At scale 0.5 this is LOD 0 (resolution 1 > 0.5), so a single page renders from the
     * very bitmap the pre-stage-32 renderer used; LOD 1 starts just below 0.5.
     */
    fun lodFor(scale: Float): Int {
        if (!(scale > 0f)) return MAX_LOD
        var lod = 0
        while (lod < MAX_LOD && lodResolution(lod + 1) > scale) lod++
        return lod
    }

    /** Page pixel size at [lod]: `ceil(sizeCu / 2^lod)`. */
    fun pagePixels(sizeCu: Int, lod: Int): Int {
        val d = 1 shl lod
        return (sizeCu + d - 1) / d
    }

    /** Sub-tiles per page axis at [lod] for [extent] (1 for a single-page canvas). */
    fun subdivisions(extent: PageExtent, lod: Int): Int =
        if (extent.pageCount <= 1) 1 else maxOf(1, MULTI_PAGE_SUBDIVISIONS shr lod)

    /** Geometry of tile [key] for a [pageW]×[pageH] page. */
    fun spec(key: TileKey, pageW: Int, pageH: Int): TileSpec {
        val ppw = pagePixels(pageW, key.lod)
        val pph = pagePixels(pageH, key.lod)
        val x0 = (key.sx.toLong() * ppw / key.div).toInt()
        val x1 = ((key.sx + 1).toLong() * ppw / key.div).toInt()
        val y0 = (key.sy.toLong() * pph / key.div).toInt()
        val y1 = ((key.sy + 1).toLong() * pph / key.div).toInt()
        return TileSpec(
            key = key,
            pageW = pageW,
            pageH = pageH,
            pxLeft = x0,
            pxTop = y0,
            pxWidth = x1 - x0,
            pxHeight = y1 - y0,
            scaleX = if (key.lod == 0) 1f else ppw.toFloat() / pageW,
            scaleY = if (key.lod == 0) 1f else pph.toFloat() / pageH,
        )
    }

    /**
     * The grid pages a closed canvas-unit rect touches, clamped to [extent]:
     * `cols × rows` as two ranges (empty when the rect misses the grid).
     */
    fun pagesTouching(rect: RectCu, pageW: Int, pageH: Int, extent: PageExtent): Pair<IntRange, IntRange> {
        val c0 = maxOf(extent.minCol, PageExtent.pageIndex(rect.left.toDouble(), pageW))
        val c1 = minOf(extent.maxCol, PageExtent.pageIndex(rect.right.toDouble(), pageW))
        val r0 = maxOf(extent.minRow, PageExtent.pageIndex(rect.top.toDouble(), pageH))
        val r1 = minOf(extent.maxRow, PageExtent.pageIndex(rect.bottom.toDouble(), pageH))
        return (c0..c1) to (r0..r1)
    }

    /**
     * Every tile at [lod] of [layer] that the closed rect touches, within [extent] — used
     * both for the viewport (what to draw or build) and for a stroke's inflated bbox (what
     * to invalidate). Order: row-major by page, then sub-tile.
     */
    fun tilesTouching(
        layer: String,
        rect: RectCu,
        pageW: Int,
        pageH: Int,
        extent: PageExtent,
        lod: Int,
    ): List<TileSpec> {
        if (pageW <= 0 || pageH <= 0) return emptyList()
        if (!(rect.left <= rect.right && rect.top <= rect.bottom)) return emptyList()
        val (cols, rows) = pagesTouching(rect, pageW, pageH, extent)
        if (cols.isEmpty() || rows.isEmpty()) return emptyList()
        val div = subdivisions(extent, lod)
        val out = ArrayList<TileSpec>()
        for (row in rows) for (col in cols) {
            for (sy in 0 until div) for (sx in 0 until div) {
                val s = spec(TileKey(layer, col, row, lod, sx, sy, div), pageW, pageH)
                if (s.pxWidth > 0 && s.pxHeight > 0 && s.intersects(rect.left, rect.top, rect.right, rect.bottom)) {
                    out.add(s)
                }
            }
        }
        return out
    }

    /**
     * The canvas-unit rect a `viewW × viewH` view shows under ([scale], [tx], [ty]),
     * padded by one view pixel so a tile with a single visible pixel is never culled.
     */
    fun viewportCu(viewLeft: Float, viewTop: Float, viewRight: Float, viewBottom: Float, scale: Float, tx: Float, ty: Float): RectCu {
        val pad = 1f
        return RectCu(
            left = (viewLeft - pad - tx) / scale,
            top = (viewTop - pad - ty) / scale,
            right = (viewRight + pad - tx) / scale,
            bottom = (viewBottom + pad - ty) / scale,
        )
    }

    /**
     * The LOD to draw at: [lodFor] of [scale], coarsened one level at a time while the
     * visible tiles would not fit in [budgetBytes] (so the frame's own tiles always fit
     * the LRU budget). Returns the LOD and its visible tiles.
     */
    fun chooseLod(
        layer: String,
        viewport: RectCu,
        pageW: Int,
        pageH: Int,
        extent: PageExtent,
        scale: Float,
        budgetBytes: Long,
    ): Pair<Int, List<TileSpec>> {
        var lod = lodFor(scale)
        var tiles = tilesTouching(layer, viewport, pageW, pageH, extent, lod)
        while (lod < MAX_LOD && tiles.sumOf { it.bytes } > budgetBytes) {
            lod++
            tiles = tilesTouching(layer, viewport, pageW, pageH, extent, lod)
        }
        return lod to tiles
    }

    /**
     * How far a stroke's ink can reach beyond its point bbox, in canvas units: half its
     * widest modulated width (render-time width rule, contract `ink-storage`), plus one
     * pixel at the coarsest LOD for anti-aliasing / hairline spill, plus a 2-CU margin.
     */
    fun inkReachCu(maxAbsModulatedWidthCu: Float): Float =
        maxAbsModulatedWidthCu / 2f + (1 shl MAX_LOD).toFloat() + 2f
}
