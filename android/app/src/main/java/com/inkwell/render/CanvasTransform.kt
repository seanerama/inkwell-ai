package com.inkwell.render

/**
 * The pan/zoom transform between view pixels and canvas units, applied identically to
 * the committed-stroke bitmap cache and the live overlay (stage requirement). A canvas
 * unit maps to `scale` view pixels; the canvas origin sits at (`tx`, `ty`) in view
 * pixels.
 *
 *   view_x = cu_x * scale + tx
 *   cu_x   = (view_x - tx) / scale
 *
 * Pure Kotlin so the mapping is unit-testable; [LayerRenderer] mirrors it into an
 * `android.graphics.Matrix` for drawing.
 *
 * Stage 36: [fitting] computes the transform that shows a whole page grid, centred, with a
 * uniform margin. A canvas opens at that view, and the Fit control returns to it. The fitted
 * view makes the stage 35 export region (viewport ∩ grid) exactly the whole grid.
 */
class CanvasTransform(
    var scale: Float = 1f,
    var tx: Float = 0f,
    var ty: Float = 0f,
) {
    var minScale: Float = DEFAULT_MIN_SCALE
    var maxScale: Float = DEFAULT_MAX_SCALE

    fun viewToCanvasX(viewX: Float): Float = (viewX - tx) / scale
    fun viewToCanvasY(viewY: Float): Float = (viewY - ty) / scale
    fun canvasToViewX(cuX: Float): Float = cuX * scale + tx
    fun canvasToViewY(cuY: Float): Float = cuY * scale + ty

    /** Pan by a view-pixel delta. */
    fun panBy(dxView: Float, dyView: Float) {
        tx += dxView
        ty += dyView
    }

    /**
     * Zoom by [factor] about a view-space focus point, keeping the canvas point under
     * the focus stationary. Scale is clamped to [[minScale], [maxScale]].
     */
    fun zoomBy(factor: Float, focusViewX: Float, focusViewY: Float) {
        val newScale = (scale * factor).coerceIn(minScale, maxScale)
        val applied = newScale / scale
        // Keep the canvas point under the focus fixed.
        tx = focusViewX - (focusViewX - tx) * applied
        ty = focusViewY - (focusViewY - ty) * applied
        scale = newScale
    }

    /** Stage 36: a fitted `scale / tx / ty` (see [fitting]). */
    data class Fit(val scale: Float, val tx: Float, val ty: Float)

    companion object {
        const val DEFAULT_MIN_SCALE = 0.1f
        const val DEFAULT_MAX_SCALE = 8f

        /** Stage 36: the uniform margin around a fitted grid, in dp (converted by the view). */
        const val FIT_MARGIN_DP = 16f

        /**
         * Stage 36: the transform that shows the whole rect `[left, right) × [top, bottom)`
         * (canvas CU; the page grid's bounds) in a [viewW] × [viewH] px view, centred, with
         * [marginPx] of clear space on every side:
         *
         *   scale = min((viewW − 2m) / gridW, (viewH − 2m) / gridH), clamped to [[minScale], [maxScale]]
         *
         * The grid's centre always lands on the view's centre, including when the clamp bites
         * (for example an 8 × 8 grid below [minScale] is centred at [minScale]). A degenerate
         * grid or a view too small for its margins gives [minScale], centred. Pure: no view.
         */
        fun fitting(
            left: Double,
            top: Double,
            right: Double,
            bottom: Double,
            viewW: Int,
            viewH: Int,
            marginPx: Float,
            minScale: Float = DEFAULT_MIN_SCALE,
            maxScale: Float = DEFAULT_MAX_SCALE,
        ): Fit {
            val gw = right - left
            val gh = bottom - top
            val m = marginPx.toDouble().coerceAtLeast(0.0)
            val raw = minOf((viewW - 2 * m) / gw, (viewH - 2 * m) / gh)
            val scale = if (raw.isFinite() && raw > 0.0) {
                raw.toFloat().coerceIn(minScale, maxScale)
            } else {
                minScale
            }
            val cx = if (gw.isFinite()) left + gw / 2 else 0.0
            val cy = if (gh.isFinite()) top + gh / 2 else 0.0
            return Fit(
                scale = scale,
                tx = (viewW / 2.0 - cx * scale).toFloat(),
                ty = (viewH / 2.0 - cy * scale).toFloat(),
            )
        }

        /** Stage 36: [fitting] over the bounds of page grid [extent] with [pageW] × [pageH] CU pages. */
        fun fittingGrid(
            extent: com.inkwell.data.PageExtent,
            pageW: Int,
            pageH: Int,
            viewW: Int,
            viewH: Int,
            marginPx: Float,
            minScale: Float = DEFAULT_MIN_SCALE,
            maxScale: Float = DEFAULT_MAX_SCALE,
        ): Fit = fitting(
            extent.leftCu(pageW), extent.topCu(pageH), extent.rightCu(pageW), extent.bottomCu(pageH),
            viewW, viewH, marginPx, minScale, maxScale,
        )
    }
}
