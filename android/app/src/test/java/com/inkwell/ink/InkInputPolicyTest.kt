package com.inkwell.ink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Palm/finger rejection (SPEC §9.2(3)): a finger draws only when no stylus is in
 * range; while a stylus is present a finger is a gesture (pan/zoom) or a resting palm
 * and must never produce ink. A stylus always draws.
 */
class InkInputPolicyTest {

    private val finger = InkInputPolicy.TOOL_TYPE_FINGER
    private val stylus = InkInputPolicy.TOOL_TYPE_STYLUS

    @Test
    fun finger_is_dropped_while_a_stylus_is_in_range() {
        val policy = InkInputPolicy()
        policy.setStylusInRange(true)
        assertFalse("finger must not draw while stylus in range", policy.acceptsDrawFrom(finger))
    }

    @Test
    fun finger_draws_when_no_stylus_present() {
        val policy = InkInputPolicy()
        policy.setStylusInRange(false)
        assertTrue(policy.acceptsDrawFrom(finger))
    }

    @Test
    fun stylus_always_draws() {
        val policy = InkInputPolicy()
        policy.setStylusInRange(true)
        assertTrue(policy.acceptsDrawFrom(stylus))
    }

    @Test
    fun presence_is_derived_from_pointer_tool_types() {
        val policy = InkInputPolicy()
        policy.onPointersChanged(intArrayOf(finger, stylus))
        assertTrue(policy.stylusInRange)
        assertFalse(policy.acceptsDrawFrom(finger))

        policy.onPointersChanged(intArrayOf(finger, finger))
        assertFalse(policy.stylusInRange)
        assertTrue(policy.acceptsDrawFrom(finger))
    }
}
