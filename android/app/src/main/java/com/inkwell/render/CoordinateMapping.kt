package com.inkwell.render

import kotlin.math.roundToInt

/**
 * Coordinate conversions for contract `coordinate-mapping` v1 (SPEC §5). The renderer
 * proper lands in a later stage; this stage freezes and tests the NM→CU mapping the
 * whole seam depends on.
 *
 * Spaces: canvas units (CU, absolute), export pixels (EX), normalized (NM, `[0,1]`).
 * Mapping back from the agent's normalized coordinates:
 *   `cu_x = nm_x * width_cu`, `cu_y = nm_y * height_cu`.
 */
object CoordinateMapping {

    const val DEFAULT_WIDTH_CU = 2480
    const val DEFAULT_HEIGHT_CU = 3508
    const val EXPORT_LONGEST_EDGE = 1568

    fun nmToCuX(nmX: Double, widthCu: Int = DEFAULT_WIDTH_CU): Double = nmX * widthCu

    fun nmToCuY(nmY: Double, heightCu: Int = DEFAULT_HEIGHT_CU): Double = nmY * heightCu

    fun nmToCu(
        nm: Pair<Double, Double>,
        widthCu: Int = DEFAULT_WIDTH_CU,
        heightCu: Int = DEFAULT_HEIGHT_CU,
    ): Pair<Double, Double> = nmToCuX(nm.first, widthCu) to nmToCuY(nm.second, heightCu)

    /** `text.size` (fraction of canvas height) → CU height. */
    fun sizeToCu(size: Double, heightCu: Int = DEFAULT_HEIGHT_CU): Double = size * heightCu

    /**
     * Export dimensions: the longest edge is exactly [EXPORT_LONGEST_EDGE] px, aspect
     * preserved (contract `coordinate-mapping` §Export step 1).
     */
    fun exportDimensions(
        widthCu: Int = DEFAULT_WIDTH_CU,
        heightCu: Int = DEFAULT_HEIGHT_CU,
    ): Pair<Int, Int> {
        val scale = EXPORT_LONGEST_EDGE.toDouble() / maxOf(widthCu, heightCu)
        return (widthCu * scale).roundToInt() to (heightCu * scale).roundToInt()
    }
}
