package com.inkwell.data

import kotlin.math.floor

/**
 * Stage 32 (ADR-0014 §1, contract `ink-storage` "ADR-0014 additions — page grid"): a
 * canvas is a rectangular grid of equal pages. `width_cu × height_cu` is the **page** size;
 * this is the grid's extent in whole pages. Page `(c, r)` covers
 * `[c·width_cu, (c+1)·width_cu) × [r·height_cu, (r+1)·height_cu)`, so coordinates may be
 * negative. Invariants: `minCol ≤ 0 ≤ maxCol`, `minRow ≤ 0 ≤ maxRow`, and at most
 * [MAX_PAGES_PER_AXIS] pages per axis. Every pre-v5 canvas is [SINGLE] (page `(0,0)` only).
 *
 * Pure Kotlin so the migration repair's covering rule is JVM unit-testable.
 */
data class PageExtent(
    val minCol: Int = 0,
    val maxCol: Int = 0,
    val minRow: Int = 0,
    val maxRow: Int = 0,
) {
    val cols: Int get() = maxCol - minCol + 1
    val rows: Int get() = maxRow - minRow + 1
    val pageCount: Int get() = cols * rows

    /** True when the extent honours the contract invariants (page (0,0) inside, ≤ 8 per axis). */
    val isValid: Boolean
        get() = minCol <= 0 && maxCol >= 0 && minRow <= 0 && maxRow >= 0 &&
            cols <= MAX_PAGES_PER_AXIS && rows <= MAX_PAGES_PER_AXIS

    fun containsPage(col: Int, row: Int): Boolean = col in minCol..maxCol && row in minRow..maxRow

    /** Grid bounds in canvas units, `[left, top, right, bottom)` for a [pageW]×[pageH] page. */
    fun leftCu(pageW: Int): Double = minCol.toDouble() * pageW
    fun topCu(pageH: Int): Double = minRow.toDouble() * pageH
    fun rightCu(pageW: Int): Double = (maxCol + 1).toDouble() * pageW
    fun bottomCu(pageH: Int): Double = (maxRow + 1).toDouble() * pageH
    fun widthCu(pageW: Int): Double = cols.toDouble() * pageW
    fun heightCu(pageH: Int): Double = rows.toDouble() * pageH

    companion object {
        /** The single page `(0,0)`: every canvas created before v5, and every new canvas. */
        val SINGLE = PageExtent(0, 0, 0, 0)

        /** ADR-0014 §2: the grid is capped at 8 pages per axis. */
        const val MAX_PAGES_PER_AXIS = 8

        /**
         * The page index holding coordinate [cu] on an axis with page size [pageSize]:
         * `floor(cu / pageSize)` (page `c` covers `[c·size, (c+1)·size)`), saturated to Int.
         */
        fun pageIndex(cu: Double, pageSize: Int): Int {
            val idx = floor(cu / pageSize)
            return when {
                idx.isNaN() -> 0
                idx >= Int.MAX_VALUE -> Int.MAX_VALUE
                idx <= Int.MIN_VALUE -> Int.MIN_VALUE
                else -> idx.toInt()
            }
        }

        /**
         * The v5 repair rule (contract `ink-storage` ADR-0014 section): the smallest
         * rectangle of whole pages that contains page `(0,0)` and every point of the stroke
         * bbox union `[minX, maxX] × [minY, maxY]` (canvas units; bbox columns only, so
         * stroke width is not counted), clamped per axis by [clampAxis]. Returns [SINGLE]
         * for a degenerate page size or a non-finite bbox.
         */
        fun covering(
            minX: Double,
            minY: Double,
            maxX: Double,
            maxY: Double,
            pageW: Int,
            pageH: Int,
        ): PageExtent {
            if (pageW <= 0 || pageH <= 0) return SINGLE
            if (!minX.isFinite() || !minY.isFinite() || !maxX.isFinite() || !maxY.isFinite()) return SINGLE
            val (c0, c1) = clampAxis(
                minOf(0, pageIndex(minOf(minX, maxX), pageW)),
                maxOf(0, pageIndex(maxOf(minX, maxX), pageW)),
            )
            val (r0, r1) = clampAxis(
                minOf(0, pageIndex(minOf(minY, maxY), pageH)),
                maxOf(0, pageIndex(maxOf(minY, maxY), pageH)),
            )
            return PageExtent(c0, c1, r0, r1)
        }

        /**
         * Clamp one axis `[min, max]` (with `min ≤ 0 ≤ max`) to at most
         * [MAX_PAGES_PER_AXIS] pages, keeping page 0 inside. When the ink spans more than the
         * cap, the positive direction (right / down, where handwriting overflows a page) is
         * kept first: `max' = min(max, 7)`, then `min' = max(min, max' − 7)`. Ink beyond the
         * clamped grid stays stored, untouched, and simply is not shown.
         */
        fun clampAxis(min: Int, max: Int): Pair<Int, Int> {
            val lo = minOf(min, 0)
            val hi = maxOf(max, 0)
            val newMax = minOf(hi, MAX_PAGES_PER_AXIS - 1)
            val newMin = maxOf(lo, newMax - (MAX_PAGES_PER_AXIS - 1))
            return newMin to newMax
        }
    }
}

/** The page grid stored on a canvas row (Room v5). */
val CanvasEntity.pageExtent: PageExtent
    get() = PageExtent(pageMinCol, pageMaxCol, pageMinRow, pageMaxRow)
