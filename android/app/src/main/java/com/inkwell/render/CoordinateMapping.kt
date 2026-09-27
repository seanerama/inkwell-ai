package com.inkwell.render

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Coordinate conversions for contract `coordinate-mapping` v1 (SPEC §5) — the pure,
 * Android-free home for the highest-risk seam in the system. All functions here are
 * plain Kotlin so they run in JVM unit tests without an emulator.
 *
 * Spaces (contract `coordinate-mapping` §Exposes):
 *   - Canvas units (CU): absolute, `0..width_cu` × `0..height_cu`.
 *   - Export pixels (EX): `0..export_w` × `0..export_h`.
 *   - Normalized (NM): `0.0..1.0` on both axes; everything the agent returns.
 *
 * Stage 4 consolidates the `Coordinates` surface named in the stage spec into this
 * single object (rather than duplicating it) — `nmToCu`, `cuToNm`, `sizeToCu`, and
 * selection-rect mapping all live here.
 *
 * Mapping back from the agent's normalized coordinates (contract §Mapping back):
 *   `cu_x = nm_x * width_cu`, `cu_y = nm_y * height_cu`.
 *
 * Stage 35 (contract `coordinate-mapping` "ADR-0014 additions — region export"): an agent
 * job exports a [Region] of the canvas — the viewport intersected with the page grid,
 * snapped to whole CU — and mapping back is origin-aware:
 *   `cu_x = origin_x_cu + nm_x * width_cu`, `cu_y = origin_y_cu + nm_y * height_cu`,
 * with `width_cu`/`height_cu` the **region** size recorded with the job. Every origin
 * parameter defaults to 0, which is exactly the v1 whole-single-page mapping.
 */
object CoordinateMapping {

    const val DEFAULT_WIDTH_CU = 2480
    const val DEFAULT_HEIGHT_CU = 3508
    const val EXPORT_LONGEST_EDGE = 1568

    // --- NM → CU (mapping the agent's coordinates back onto the canvas) ---

    // Origin 0 adds exactly 0.0, so the v1 mapping is unchanged bit for bit.
    fun nmToCuX(nmX: Double, widthCu: Int = DEFAULT_WIDTH_CU, originX: Int = 0): Double =
        originX + nmX * widthCu

    fun nmToCuY(nmY: Double, heightCu: Int = DEFAULT_HEIGHT_CU, originY: Int = 0): Double =
        originY + nmY * heightCu

    fun nmToCu(
        nm: Pair<Double, Double>,
        widthCu: Int = DEFAULT_WIDTH_CU,
        heightCu: Int = DEFAULT_HEIGHT_CU,
        originX: Int = 0,
        originY: Int = 0,
    ): Pair<Double, Double> = nmToCuX(nm.first, widthCu, originX) to nmToCuY(nm.second, heightCu, originY)

    // --- CU → NM (the exact inverse; `cuToNm(nmToCu(p)) == p`) ---

    fun cuToNmX(cuX: Double, widthCu: Int = DEFAULT_WIDTH_CU, originX: Int = 0): Double =
        (cuX - originX) / widthCu

    fun cuToNmY(cuY: Double, heightCu: Int = DEFAULT_HEIGHT_CU, originY: Int = 0): Double =
        (cuY - originY) / heightCu

    fun cuToNm(
        cu: Pair<Double, Double>,
        widthCu: Int = DEFAULT_WIDTH_CU,
        heightCu: Int = DEFAULT_HEIGHT_CU,
        originX: Int = 0,
        originY: Int = 0,
    ): Pair<Double, Double> = cuToNmX(cu.first, widthCu, originX) to cuToNmY(cu.second, heightCu, originY)

    /**
     * `text.size` (fraction of canvas height) → CU height (contract §Mapping back). With a
     * region export, [heightCu] is the **region** height; a size has no origin.
     */
    fun sizeToCu(size: Double, heightCu: Int = DEFAULT_HEIGHT_CU): Double = size * heightCu

    // --- Selection / region rects ---

    /**
     * Map a normalized `[x, y, w, h]` selection rect to canvas units. Selection rects
     * map exactly the same way as any other coordinate (contract §Mapping back:
     * "Selection rects map the same way").
     */
    fun selectionNmToCu(
        sel: List<Double>,
        widthCu: Int = DEFAULT_WIDTH_CU,
        heightCu: Int = DEFAULT_HEIGHT_CU,
        originX: Int = 0,
        originY: Int = 0,
    ): DoubleArray {
        require(sel.size == 4) { "selection must be [x,y,w,h], was $sel" }
        return doubleArrayOf(
            nmToCuX(sel[0], widthCu, originX),
            nmToCuY(sel[1], heightCu, originY),
            sel[2] * widthCu,
            sel[3] * heightCu,
        )
    }

    /** Inverse of [selectionNmToCu]: a canvas-unit `[x,y,w,h]` rect back to normalized. */
    fun selectionCuToNm(
        sel: DoubleArray,
        widthCu: Int = DEFAULT_WIDTH_CU,
        heightCu: Int = DEFAULT_HEIGHT_CU,
        originX: Int = 0,
        originY: Int = 0,
    ): List<Double> {
        require(sel.size == 4) { "selection must be [x,y,w,h], was ${sel.toList()}" }
        return listOf(
            cuToNmX(sel[0], widthCu, originX),
            cuToNmY(sel[1], heightCu, originY),
            sel[2] / widthCu,
            sel[3] / heightCu,
        )
    }

    /**
     * Axis-aligned bounding box, in canvas units, of a list of normalized `[x,y]`
     * points (e.g. a `highlight`'s polygon). Returned as `[x, y, w, h]`. Used to check
     * that a rendered annotation lands at `points × canvas size`.
     */
    fun nmPointsBoundsCu(
        points: List<List<Double>>,
        widthCu: Int = DEFAULT_WIDTH_CU,
        heightCu: Int = DEFAULT_HEIGHT_CU,
        originX: Int = 0,
        originY: Int = 0,
    ): DoubleArray {
        require(points.isNotEmpty()) { "points must be non-empty" }
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (p in points) {
            val x = nmToCuX(p[0], widthCu, originX)
            val y = nmToCuY(p[1], heightCu, originY)
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        return doubleArrayOf(minX, minY, maxX - minX, maxY - minY)
    }

    // --- Export dimensions (EX) ---

    /**
     * Export metadata `{ w, h, width_cu, height_cu, origin_x_cu, origin_y_cu }` for the
     * `POST /jobs` body (contract `device-api`), computed by the frozen
     * `coordinate-mapping` formula. Stage 35: [widthCu]/[heightCu] are the exported
     * **region**'s size and [originX]/[originY] its top-left corner in canvas CU (0 for a
     * v1 whole-single-page export).
     */
    data class Export(
        val w: Int,
        val h: Int,
        val widthCu: Int,
        val heightCu: Int,
        val originX: Int = 0,
        val originY: Int = 0,
    ) {
        /** The region this export covers — what the job's agent geometry maps through. */
        val region: Region get() = Region(originX, originY, widthCu, heightCu)
    }

    /**
     * Stage 35: an agent job's export region in canvas CU — top-left ([originX], [originY])
     * (may be negative) and size [widthCu] × [heightCu] (both > 0). It is recorded with the
     * job's result, and that job's annotations, anchors and margin notes always map through
     * it, never through the canvas's current bounds, so later growth never moves them.
     */
    data class Region(
        val originX: Int,
        val originY: Int,
        val widthCu: Int,
        val heightCu: Int,
    ) {
        val rightCu: Long get() = originX.toLong() + widthCu
        val bottomCu: Long get() = originY.toLong() + heightCu
        val longestEdgeCu: Int get() = maxOf(widthCu, heightCu)

        companion object {
            /** The v1 export: a whole single page at origin (0,0). */
            fun page(widthCu: Int, heightCu: Int): Region = Region(0, 0, widthCu, heightCu)
        }
    }

    /**
     * Stage 35 (contract `coordinate-mapping` ADR-0014 additions, "Export region"): the
     * viewport `[left, right) × [top, bottom)` (canvas CU, as the view shows it) intersected
     * with the page-grid bounds `[gridLeft, gridRight) × [gridTop, gridBottom)` and
     * snapped to whole CU — outward (floor the top-left, ceil the bottom-right), then
     * clamped back inside the grid, whose edges are whole CU already. Null when the
     * viewport does not overlap the grid (nothing of the canvas is on screen) or is not
     * finite.
     *
     * A viewport that covers the whole grid gives exactly the grid, so a single-page
     * canvas seen whole is `(0, 0, width_cu, height_cu)`: the v1 export.
     */
    fun visibleRegion(
        viewLeft: Double,
        viewTop: Double,
        viewRight: Double,
        viewBottom: Double,
        gridLeft: Long,
        gridTop: Long,
        gridRight: Long,
        gridBottom: Long,
    ): Region? {
        if (!viewLeft.isFinite() || !viewTop.isFinite() || !viewRight.isFinite() || !viewBottom.isFinite()) {
            return null
        }
        val l = maxOf(floor(minOf(viewLeft, viewRight)), gridLeft.toDouble())
        val t = maxOf(floor(minOf(viewTop, viewBottom)), gridTop.toDouble())
        val r = minOf(ceil(maxOf(viewLeft, viewRight)), gridRight.toDouble())
        val b = minOf(ceil(maxOf(viewTop, viewBottom)), gridBottom.toDouble())
        if (!(r > l && b > t)) return null
        val w = r - l
        val h = b - t
        if (w > Int.MAX_VALUE || h > Int.MAX_VALUE || l < Int.MIN_VALUE || t < Int.MIN_VALUE) return null
        return Region(l.toInt(), t.toInt(), w.toInt(), h.toInt())
    }

    /**
     * Stage 35 legibility floor (contract `coordinate-mapping` ADR-0014 additions): true
     * when the [region]'s longest edge exceeds `2 ×` the page's longest edge. Such a region
     * is not exported — handwriting would fall below about 0.22 export px per CU — and Send
     * is disabled with a "Zoom in to send" hint instead. Exactly `2 ×` is still allowed.
     */
    fun belowLegibilityFloor(region: Region, pageWidthCu: Int, pageHeightCu: Int): Boolean =
        region.longestEdgeCu.toLong() > 2L * maxOf(pageWidthCu, pageHeightCu)

    /** Uniform CU→EX scale: `1568 / max(width_cu, height_cu)` (contract §Export step 1). */
    fun exportScale(widthCu: Int = DEFAULT_WIDTH_CU, heightCu: Int = DEFAULT_HEIGHT_CU): Double =
        EXPORT_LONGEST_EDGE.toDouble() / maxOf(widthCu, heightCu)

    /**
     * Export dimensions: the longest edge is exactly [EXPORT_LONGEST_EDGE] px, aspect
     * preserved (contract `coordinate-mapping` §Export step 1). The formula is
     * authoritative and uses `round()`: for the default 2480×3508 canvas this is
     * **1109 × 1568** (`round(2480 * 1568/3508) = round(1108.506) = 1109`). The prose
     * "1108×1568" in the stage/acceptance/device-api example is the known off-by-one
     * tracked in issue #9; the round() formula wins.
     */
    fun exportDimensions(
        widthCu: Int = DEFAULT_WIDTH_CU,
        heightCu: Int = DEFAULT_HEIGHT_CU,
    ): Pair<Int, Int> {
        val scale = exportScale(widthCu, heightCu)
        return (widthCu * scale).roundToInt() to (heightCu * scale).roundToInt()
    }

    /** [Export] metadata for the job body (contract `device-api` `export`). */
    fun export(
        widthCu: Int = DEFAULT_WIDTH_CU,
        heightCu: Int = DEFAULT_HEIGHT_CU,
        originX: Int = 0,
        originY: Int = 0,
    ): Export {
        val (w, h) = exportDimensions(widthCu, heightCu)
        return Export(w = w, h = h, widthCu = widthCu, heightCu = heightCu, originX = originX, originY = originY)
    }

    /** Stage 35: [Export] metadata for a region export (the formula applied to the region). */
    fun export(region: Region): Export = export(region.widthCu, region.heightCu, region.originX, region.originY)
}
