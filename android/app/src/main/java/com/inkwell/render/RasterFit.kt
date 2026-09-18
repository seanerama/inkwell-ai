package com.inkwell.render

/**
 * Pure, JVM-testable placement/scaling maths for a pushed raster (Stage 22, ADR-0012).
 * A raster (a PDF page or image) is fitted to the canvas WIDTH and placed at the top-left;
 * a portrait page uses the default A4 canvas (2480×3508 CU) and a landscape page the
 * same-area swap (3508×2480). The bitmap render itself is Android-only ([RasterRenderer]);
 * these functions decide the sizes and rects it uses, so they can be unit-tested with no
 * device.
 */
object RasterFit {

    /** A raster's placement on the canvas, in canvas units (top-left origin). */
    data class Placement(val xCu: Float, val yCu: Float, val wCu: Float, val hCu: Float)

    /** A destination rectangle in view pixels (after the pan/zoom transform). */
    data class RectPx(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /**
     * Fit a source page of [srcW]×[srcH] pixels to the canvas width [canvasWidthCu],
     * preserving aspect and anchored at the top-left. The width fills the canvas; the height
     * is the aspect-scaled value (which may exceed the canvas height for a very tall page —
     * that is intended; the page scrolls).
     */
    fun fitToWidth(srcW: Int, srcH: Int, canvasWidthCu: Int): Placement {
        require(srcW > 0 && srcH > 0) { "source page must be non-empty ($srcW×$srcH)" }
        val w = canvasWidthCu.toFloat()
        val h = w * srcH.toFloat() / srcW.toFloat()
        return Placement(xCu = 0f, yCu = 0f, wCu = w, hCu = h)
    }

    /**
     * The canvas size (widthCu to heightCu) for a page of the given source pixel size:
     * portrait (or square) → A4 portrait; landscape → the same-area A4 swap (ADR-0012).
     */
    fun canvasSizeFor(
        srcW: Int,
        srcH: Int,
        portraitW: Int = 2480,
        portraitH: Int = 3508,
    ): Pair<Int, Int> =
        if (srcH >= srcW) portraitW to portraitH else portraitH to portraitW

    /**
     * A power-of-two `inSampleSize` for [android.graphics.BitmapFactory.Options] that bounds
     * a decode of a [srcW]×[srcH] image down toward [targetW]×[targetH] (memory guard for
     * large images). Returns 1 when the source already fits. Never returns 0.
     */
    fun sampleSize(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Int {
        if (targetW <= 0 || targetH <= 0) return 1
        var inSample = 1
        if (srcH > targetH || srcW > targetW) {
            val halfH = srcH / 2
            val halfW = srcW / 2
            while (halfH / inSample >= targetH && halfW / inSample >= targetW) {
                inSample *= 2
            }
        }
        return inSample
    }

    /**
     * The destination pixel rectangle a [Placement] maps to under a pan/zoom transform of
     * [scale] view-px per canvas-unit and canvas origin at ([tx], [ty]) view pixels.
     */
    fun destRectPx(
        placement: Placement,
        scale: Float,
        tx: Float,
        ty: Float,
    ): RectPx = RectPx(
        left = placement.xCu * scale + tx,
        top = placement.yCu * scale + ty,
        right = (placement.xCu + placement.wCu) * scale + tx,
        bottom = (placement.yCu + placement.hCu) * scale + ty,
    )
}
