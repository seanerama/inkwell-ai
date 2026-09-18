package com.inkwell.render

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit test for the export/render stacking rule (Stage 22, SPEC §5.2 / §4.3): raster
 * layers (`z < 0`) composite BENEATH ink (`z >= 0`). This is the exact ordering
 * [CanvasExporter] and [com.inkwell.ink.InkView] draw through, extracted so the "raster
 * below ink" invariant is provable without a device.
 */
class ExportCompositionTest {

    private data class Item(override val z: Int, val tag: String) : ExportComposition.Ordered

    @Test
    fun raster_below_ink() {
        val raster = Item(z = -1, tag = "raster")
        val ink = Item(z = 0, tag = "ink")
        // Deliberately supply ink first — order must come from z, not input order.
        val ordered = ExportComposition.ordered(listOf(ink, raster))
        assertEquals(listOf("raster", "ink"), ordered.map { it.tag })
    }

    @Test
    fun ascending_z_across_many_layers() {
        val items = listOf(
            Item(2, "ink-top"),
            Item(-1, "raster"),
            Item(0, "ink"),
            Item(1, "agent"),
        )
        assertEquals(
            listOf("raster", "ink", "agent", "ink-top"),
            ExportComposition.ordered(items).map { it.tag },
        )
    }

    @Test
    fun stable_for_equal_z() {
        val items = listOf(Item(-1, "r1"), Item(-1, "r2"), Item(0, "ink"))
        assertEquals(listOf("r1", "r2", "ink"), ExportComposition.ordered(items).map { it.tag })
    }
}
