package com.inkwell.ink

/**
 * Pointer arbitration for ink capture (SPEC §9.2(3)): the stylus draws, fingers
 * pan/zoom, and a finger touch is ignored for drawing while a stylus is in range
 * (a resting palm or a bracing hand must never leave ink).
 *
 * The tool-type constants mirror `android.view.MotionEvent.TOOL_TYPE_*` by value so
 * this policy stays a pure Kotlin object testable on the JVM without the Android
 * framework; [InkView] passes `event.getToolType(i)` straight through.
 */
class InkInputPolicy {

    /** True while at least one stylus pointer is touching or hovering the digitizer. */
    var stylusInRange: Boolean = false
        private set

    /** Update presence from the tool types currently seen on the digitizer. */
    fun onPointersChanged(toolTypes: IntArray) {
        stylusInRange = toolTypes.any { it == TOOL_TYPE_STYLUS }
    }

    /** Set stylus presence directly (e.g. from hover enter/exit). */
    fun setStylusInRange(inRange: Boolean) {
        stylusInRange = inRange
    }

    /**
     * Should a pointer of [toolType] be treated as drawing input?
     *
     * A stylus always draws. A finger draws only when no stylus is in range; while a
     * stylus is present a finger is a gesture (pan/zoom) or palm, never ink.
     */
    fun acceptsDrawFrom(toolType: Int): Boolean = when (toolType) {
        TOOL_TYPE_STYLUS, TOOL_TYPE_ERASER -> true
        TOOL_TYPE_FINGER -> !stylusInRange
        else -> !stylusInRange
    }

    companion object {
        // Mirror android.view.MotionEvent tool-type constants by value.
        const val TOOL_TYPE_FINGER = 1
        const val TOOL_TYPE_STYLUS = 2
        const val TOOL_TYPE_ERASER = 4
    }
}
