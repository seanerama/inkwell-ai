package com.inkwell.render

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
 */
object CoordinateMapping {

    const val DEFAULT_WIDTH_CU = 2480
    const val DEFAULT_HEIGHT_CU = 3508
    const val EXPORT_LONGEST_EDGE = 1568

    // --- NM → CU (mapping the agent's coordinates back onto the canvas) ---

    fun nmToCuX(nmX: Double, widthCu: Int = DEFAULT_WIDTH_CU): Double = nmX * widthCu

    fun nmToCuY(nmY: Double, heightCu: Int = DEFAULT_HEIGHT_CU): Double = nmY * heightCu

    fun nmToCu(
        nm: Pair<Double, Double>,
        widthCu: Int = DEFAULT_WIDTH_CU,
        heightCu: Int = DEFAULT_HEIGHT_CU,
    ): Pair<Double, Double> = nmToCuX(nm.first, widthCu) to nmToCuY(nm.second, heightCu)

    // --- CU → NM (the exact inverse; `cuToNm(nmToCu(p)) == p`) ---

    fun cuToNmX(cuX: Double, widthCu: Int = DEFAULT_WIDTH_CU): Double = cuX / widthCu

    fun cuToNmY(cuY: Double, heightCu: Int = DEFAULT_HEIGHT_CU): Double = cuY / heightCu

    fun cuToNm(
        cu: Pair<Double, Double>,
        widthCu: Int = DEFAULT_WIDTH_CU,
        heightCu: Int = DEFAULT_HEIGHT_CU,
    ): Pair<Double, Double> = cuToNmX(cu.first, widthCu) to cuToNmY(cu.second, heightCu)

    /** `text.size` (fraction of canvas height) → CU height (contract §Mapping back). */
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
    ): DoubleArray {
        require(sel.size == 4) { "selection must be [x,y,w,h], was $sel" }
        return doubleArrayOf(
            sel[0] * widthCu,
            sel[1] * heightCu,
            sel[2] * widthCu,
            sel[3] * heightCu,
        )
    }

    /** Inverse of [selectionNmToCu]: a canvas-unit `[x,y,w,h]` rect back to normalized. */
    fun selectionCuToNm(
        sel: DoubleArray,
        widthCu: Int = DEFAULT_WIDTH_CU,
        heightCu: Int = DEFAULT_HEIGHT_CU,
    ): List<Double> {
        require(sel.size == 4) { "selection must be [x,y,w,h], was ${sel.toList()}" }
        return listOf(
            sel[0] / widthCu,
            sel[1] / heightCu,
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
    ): DoubleArray {
        require(points.isNotEmpty()) { "points must be non-empty" }
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (p in points) {
            val x = nmToCuX(p[0], widthCu)
            val y = nmToCuY(p[1], heightCu)
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        return doubleArrayOf(minX, minY, maxX - minX, maxY - minY)
    }

    // --- Export dimensions (EX) ---

    /**
     * Export metadata `{ w, h, width_cu, height_cu }` for the `POST /jobs` body
     * (contract `device-api`), computed by the frozen `coordinate-mapping` formula.
     */
    data class Export(
        val w: Int,
        val h: Int,
        val widthCu: Int,
        val heightCu: Int,
    )

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
    fun export(widthCu: Int = DEFAULT_WIDTH_CU, heightCu: Int = DEFAULT_HEIGHT_CU): Export {
        val (w, h) = exportDimensions(widthCu, heightCu)
        return Export(w = w, h = h, widthCu = widthCu, heightCu = heightCu)
    }
}
