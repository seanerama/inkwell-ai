package com.inkwell.data

/**
 * Pure, JVM-testable thumbnail path derivation (Stage 11). A canvas thumbnail is a cached
 * 256-px PNG, never the source of truth (contract `ink-storage` invariant 1: ink is
 * vectors). The bytes are rendered by the Android-only [ThumbnailRenderer]; this object
 * only names the file so the mapping `canvasId → relative path` can be unit-tested with
 * no Android dependency.
 */
object Thumbnails {

    /** Cache directory (relative to `filesDir`) that holds all canvas thumbnails. */
    const val DIR = "thumbs"

    /** Thumbnail long-edge size in pixels. */
    const val SIZE_PX = 256

    /** Relative path under `filesDir` for a canvas's thumbnail, e.g. `thumbs/<id>.png`. */
    fun relativePath(canvasId: String): String = "$DIR/$canvasId.png"

    /** A thumbnail's pixel size and its canvas-unit → pixel [scale]. */
    data class Fit(val widthPx: Int, val heightPx: Int, val scale: Double)

    /**
     * Stage 32 (ADR-0014): fit the whole page grid ([gridWidthCu] × [gridHeightCu], canvas
     * units) into a [longEdge]-px box, keeping the aspect ratio: `scale = longEdge / max`,
     * each side `round(side · scale)` and at least 1 px. A single A4 page is 181 × 256; a
     * 3 × 1 grid of A4 pages is 256 × 121.
     */
    fun fit(gridWidthCu: Double, gridHeightCu: Double, longEdge: Int = SIZE_PX): Fit {
        val maxSide = maxOf(gridWidthCu, gridHeightCu)
        if (!(maxSide > 0.0)) return Fit(1, 1, 0.0)
        val scale = longEdge / maxSide
        val w = Math.round(gridWidthCu * scale).toInt().coerceIn(1, longEdge)
        val h = Math.round(gridHeightCu * scale).toInt().coerceIn(1, longEdge)
        return Fit(w, h, scale)
    }
}
