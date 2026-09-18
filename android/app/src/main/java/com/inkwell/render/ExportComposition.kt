package com.inkwell.render

/**
 * The pure, JVM-testable stacking rule the canvas composites through (Stage 22, SPEC §5.2
 * export ordering / §4.3 "raster layers render beneath ink by default, `z < 0`").
 *
 * Both the on-screen render ([com.inkwell.ink.InkView] draws the raster before ink) and the
 * export ([CanvasExporter]) must draw raster layers FIRST, then ink, so the agent sees the
 * document with the marks on top. Rather than bake that order into Android drawing code that
 * only an emulator can exercise, the order is a single ascending-`z` sort here: rasters carry
 * `z < 0` and ink `z >= 0`, so a stable sort puts every raster below every ink layer. The
 * exporter calls [ordered], and a JVM unit test asserts the raster-below-ink invariant on it.
 */
object ExportComposition {

    /** Anything with a stacking order. Rasters use `z < 0`; ink uses `z >= 0`. */
    interface Ordered {
        val z: Int
    }

    /**
     * The draw order: ascending `z`, stable for ties (so equal-`z` items keep input order).
     * Rasters (`z < 0`) therefore precede ink (`z >= 0`) — raster beneath ink.
     */
    fun <T : Ordered> ordered(items: List<T>): List<T> = items.sortedBy { it.z }
}
