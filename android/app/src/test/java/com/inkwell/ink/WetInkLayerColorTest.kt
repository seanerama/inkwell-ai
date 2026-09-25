package com.inkwell.ink

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stage 33: the wet layer paints the pen fully opaque, as `LayerRenderer` paints the dry
 * pen stroke (alpha forced to 255), so a translucent pen colour cannot make the wet and
 * dry copies differ at the hand-off. The RGB channels are kept exactly.
 */
class WetInkLayerColorTest {

    @Test
    fun alpha_is_forced_to_255_and_rgb_is_kept() {
        assertEquals(0xFF112233.toInt(), opaquePenColor(0x80112233.toInt()))
        assertEquals(0xFF112233.toInt(), opaquePenColor(0x00112233))
        assertEquals(0xFF112233.toInt(), opaquePenColor(0xFF112233.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), opaquePenColor(0x01FFFFFF))
    }
}
