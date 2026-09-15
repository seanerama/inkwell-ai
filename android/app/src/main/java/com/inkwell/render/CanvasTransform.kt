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
 */
class CanvasTransform(
    var scale: Float = 1f,
    var tx: Float = 0f,
    var ty: Float = 0f,
) {
    var minScale: Float = 0.1f
    var maxScale: Float = 8f

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
}
