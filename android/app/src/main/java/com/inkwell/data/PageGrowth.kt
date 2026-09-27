package com.inkwell.data

import com.inkwell.data.PageExtent.Companion.MAX_PAGES_PER_AXIS

/**
 * Stage 34 (ADR-0014 §2, contract `ink-storage` "ADR-0014 additions — page grid"): the
 * growth rule of the expandable canvas. Pure Kotlin, JVM unit-tested (`PageGrowthTest`),
 * shared by the capture view (start test, live clip), the view model and the repository
 * (the grid persisted with the stroke), so all three agree exactly.
 *
 * - **Ghost ring.** One page deep around the grid in all eight directions. It is absent on
 *   an axis whose grid already spans [MAX_PAGES_PER_AXIS] pages (growth there would exceed
 *   the cap), so a corner ring page exists only when both of its axes can grow.
 * - **Start test.** A pen or marker stroke starts only on a grid or ring page (grid only
 *   when the `EXPANDABLE_CANVAS` switch is off).
 * - **Growth.** The grid grows by whole pages to cover the stroke's points — the same
 *   point bbox the stored `bbox_*` columns hold, and the same rule as the v5 migration
 *   repair ([PageExtent.covering]); stroke width is not counted. It never shrinks.
 * - **Cap.** Each axis stops at 8 pages. The points are walked in drawing order
 *   ([extend]), so the grid first grows toward where the pen went first, and a region
 *   that has been shown live is never taken back later in the stroke: the live clip
 *   ([extend] per sample) and the committed grid ([grow]) are the same rectangle, so what
 *   is seen while writing is what stays. Without the cap this is exactly the smallest
 *   grid covering old grid ∪ stroke bbox.
 */
object PageGrowth {

    /** The pages a stroke may start on: the grid plus, when [expandable], the ring. */
    data class Writable(val cols: IntRange, val rows: IntRange)

    /** True when the grid can still grow along its columns (it spans fewer than 8). */
    fun canGrowCols(grid: PageExtent): Boolean = grid.cols < MAX_PAGES_PER_AXIS

    /** True when the grid can still grow along its rows (it spans fewer than 8). */
    fun canGrowRows(grid: PageExtent): Boolean = grid.rows < MAX_PAGES_PER_AXIS

    /** The writable page ranges: grid ∪ ghost ring, or the grid alone when not [expandable]. */
    fun writable(grid: PageExtent, expandable: Boolean): Writable {
        val dc = if (expandable && canGrowCols(grid)) 1 else 0
        val dr = if (expandable && canGrowRows(grid)) 1 else 0
        return Writable(grid.minCol - dc..grid.maxCol + dc, grid.minRow - dr..grid.maxRow + dr)
    }

    /**
     * The ghost-ring pages `(col, row)` around [grid] (never a grid page), row-major. Empty
     * when neither axis can grow.
     */
    fun ringPages(grid: PageExtent): List<Pair<Int, Int>> {
        val w = writable(grid, expandable = true)
        val out = ArrayList<Pair<Int, Int>>()
        for (r in w.rows) for (c in w.cols) if (!grid.containsPage(c, r)) out.add(c to r)
        return out
    }

    /**
     * The start test (stylus / pen or marker `ACTION_DOWN`): true when canvas point
     * ([x], [y]) lies on a writable page of [grid]. False for a non-finite point or a
     * degenerate page size.
     */
    fun canStartAt(grid: PageExtent, x: Float, y: Float, pageW: Int, pageH: Int, expandable: Boolean): Boolean {
        if (pageW <= 0 || pageH <= 0 || !x.isFinite() || !y.isFinite()) return false
        val w = writable(grid, expandable)
        return PageExtent.pageIndex(x.toDouble(), pageW) in w.cols &&
            PageExtent.pageIndex(y.toDouble(), pageH) in w.rows
    }

    /**
     * Grow [region] (a valid grid, or the live region of a stroke) to include the page of
     * point ([x], [y]), as far as the cap allows. Never shrinks; non-finite points are
     * ignored.
     */
    fun extend(region: PageExtent, x: Float, y: Float, pageW: Int, pageH: Int): PageExtent {
        if (pageW <= 0 || pageH <= 0 || !x.isFinite() || !y.isFinite()) return region
        val (c0, c1) = extendAxis(region.minCol, region.maxCol, PageExtent.pageIndex(x.toDouble(), pageW))
        val (r0, r1) = extendAxis(region.minRow, region.maxRow, PageExtent.pageIndex(y.toDouble(), pageH))
        if (c0 == region.minCol && c1 == region.maxCol && r0 == region.minRow && r1 == region.maxRow) return region
        return PageExtent(c0, c1, r0, r1)
    }

    /**
     * The grid after a committed stroke: [grid] extended by the first [count] stride-5
     * [points] in order (see [extend]). Equals [grid] when the stroke lies inside it.
     */
    fun grow(grid: PageExtent, points: FloatArray, count: Int, pageW: Int, pageH: Int): PageExtent {
        val stride = PackedPoints.STRIDE
        val n = minOf(count, points.size / stride)
        var region = grid
        for (i in 0 until n) region = extend(region, points[i * stride], points[i * stride + 1], pageW, pageH)
        return region
    }

    /**
     * True when [extent] covers every page the point bbox `[x, x+w] × [y, y+h]` touches —
     * false means the stroke reached past the 8-page cap (its points stay stored and render
     * clipped at the grid edge).
     */
    fun covers(extent: PageExtent, x: Float, y: Float, w: Float, h: Float, pageW: Int, pageH: Int): Boolean {
        if (pageW <= 0 || pageH <= 0) return true
        if (!x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite()) return true
        return PageExtent.pageIndex(x.toDouble(), pageW) >= extent.minCol &&
            PageExtent.pageIndex((x + w).toDouble(), pageW) <= extent.maxCol &&
            PageExtent.pageIndex(y.toDouble(), pageH) >= extent.minRow &&
            PageExtent.pageIndex((y + h).toDouble(), pageH) <= extent.maxRow
    }

    /** The grid union of [a] and [b] (the smallest rectangle containing both). */
    fun union(a: PageExtent, b: PageExtent): PageExtent = PageExtent(
        minOf(a.minCol, b.minCol), maxOf(a.maxCol, b.maxCol),
        minOf(a.minRow, b.minRow), maxOf(a.maxRow, b.maxRow),
    )

    /** True when [outer] contains every page of [inner]. */
    fun contains(outer: PageExtent, inner: PageExtent): Boolean =
        outer.minCol <= inner.minCol && outer.maxCol >= inner.maxCol &&
            outer.minRow <= inner.minRow && outer.maxRow >= inner.maxRow

    /**
     * One axis: include page [p] in `[lo, hi]` without exceeding 8 pages and without
     * giving up any page already in the range.
     */
    private fun extendAxis(lo: Int, hi: Int, p: Int): Pair<Int, Int> = when {
        p < lo -> minOf(lo, maxOf(p, hi - (MAX_PAGES_PER_AXIS - 1))) to hi
        p > hi -> lo to maxOf(hi, minOf(p, lo + (MAX_PAGES_PER_AXIS - 1)))
        else -> lo to hi
    }
}
