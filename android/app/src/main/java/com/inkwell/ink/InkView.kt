package com.inkwell.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.inkwell.contracts.Annotation
import com.inkwell.render.AnnotationRenderer
import com.inkwell.render.CanvasTransform
import com.inkwell.render.EraserHitTest
import com.inkwell.render.LayerRenderer
import com.inkwell.render.RenderStroke
import kotlin.math.hypot

/** A committed stroke handed back to the caller for persistence. */
data class StrokeCommit(
    val stroke: BuiltStroke,
    val tool: String,
    val colorHex: String,
    val widthCu: Float,
)

/** Live debug numbers for the on-screen overlay (debug builds). */
data class InkDebugStats(
    val sampleRateHz: Int,
    val filterLatencyUs: Long,
    val droppedSamples: Int,
)

/**
 * The ink capture surface (SPEC §9.2). A custom [View] handling raw [MotionEvent] —
 * Compose's `pointerInput` batches and drops samples, which produces visibly
 * polygonal strokes, so capture lives here.
 *
 * Contract of the touch pipeline:
 *  - iterate `event.historySize` samples and never drop them (§9.2(2));
 *  - a stylus draws; a finger pans/zooms and is rejected for drawing while a stylus
 *    is in range (§9.2(3), palm rejection) via [InkInputPolicy];
 *  - the one-euro filter runs on the live stream in [StrokeBuilder] before commit;
 *  - the in-progress stroke is a live overlay; committed strokes are cached
 *    ([LayerRenderer]) and never re-rasterised per frame (§9.2(5)).
 *
 * Rendering and the pan/zoom transform are delegated to [LayerRenderer] and
 * [CanvasTransform] so both the cache and the overlay move together.
 */
class InkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    // --- Tunable / injected state ---
    var tool: String = "pen"
    var colorHex: String = "#111111"
    var widthCu: Float = LayerRenderer.DEFAULT_WIDTH_CU
    var minCutoff: Double = OneEuroFilter.DEFAULT_MIN_CUTOFF
    var beta: Double = OneEuroFilter.DEFAULT_BETA
    var debugEnabled: Boolean = false

    var onStrokeCommitted: ((StrokeCommit) -> Unit)? = null
    var onEraseStroke: ((String) -> Unit)? = null
    var onDebugStats: ((InkDebugStats) -> Unit)? = null

    /**
     * Stage 10 (SPEC §4.7, canvas → card): a light tap on the canvas reports its
     * canvas-unit position so the panel can scroll to the card whose mark it hits. Fired
     * only for a short, near-stationary tap; drawing/pan is unaffected.
     */
    var onAnchorTap: ((Float, Float) -> Unit)? = null

    private val renderer = LayerRenderer()
    private val transform = CanvasTransform()
    private val policy = InkInputPolicy()
    private var hoverStylus = false

    private var committed: List<RenderStroke> = emptyList()

    // --- Debug-only agent-annotation overlay (Stage 4 "Render fixture") ---
    private var canvasWidthCu = 2480
    private var canvasHeightCu = 3508
    private var accentColor = AnnotationRenderer.DEFAULT_ACCENT
    private var debugHighlights: List<Annotation> = emptyList()

    // --- Agent-annotation layer (Stage 6, release-visible under BuildConfig.SEND_ENABLED).
    // Rendered through AnnotationRenderer (opacity-authoritative: it paints the 70%), so
    // the agent LayerEntity stays at 1.0 opacity and the transparency is never doubled.
    private var agentAnnotations: List<Annotation> = emptyList()
    private var agentLayerVisible: Boolean = true
    // Stage 12: when the open canvas is an agent redraw (origin=agent), its agent layer is
    // the content, so it renders opaque/ink-black rather than 70%/accent (SPEC §6.3).
    private var agentOriginCanvas: Boolean = false

    // --- Stage 10: card→canvas anchor pulse (CU rects) + canvas→card tap tracking ---
    private var anchorPulses: List<DoubleArray> = emptyList()
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var builder: StrokeBuilder? = null
    private var drawing = false
    private var erasing = false

    // Two-finger gesture state.
    private var gestureActive = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastSpan = 0f

    // Debug counters.
    private var droppedSamples = 0
    private var windowStartNs = 0L
    private var windowSamples = 0
    private var lastSampleRateHz = 0
    private var lastFilterLatencyUs = 0L

    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        textSize = 32f
        isFakeBoldText = true
    }
    private val debugBgPaint = Paint().apply { color = Color.argb(160, 255, 255, 255) }

    fun setCanvasSize(widthCu: Int, heightCu: Int) {
        canvasWidthCu = widthCu
        canvasHeightCu = heightCu
        renderer.setCanvasSize(widthCu, heightCu)
        invalidate()
    }

    /**
     * Debug-only ("Render fixture"): overlay agent annotations (highlights) drawn
     * through the same [transform] as ink, so their placement can be eyeballed before
     * the server exists. Only drawn when [debugEnabled] is true (BuildConfig.DEBUG).
     */
    fun setDebugHighlights(annotations: List<Annotation>) {
        debugHighlights = annotations
        invalidate()
    }

    fun setAccentColor(color: Int) {
        accentColor = color
        invalidate()
    }

    /**
     * Set the agent-layer annotations to render (Stage 6). Drawn through
     * [AnnotationRenderer] whenever the agent layer is visible — in both debug and
     * release (unlike [setDebugHighlights], which is the debug-only fixture preview).
     */
    fun setAgentAnnotations(annotations: List<Annotation>) {
        agentAnnotations = annotations
        invalidate()
    }

    /** Layer-tray visibility toggle for the agent layer (SPEC §6.3 / stage: judge placement). */
    fun setAgentLayerVisible(visible: Boolean) {
        agentLayerVisible = visible
        invalidate()
    }

    /** Stage 12: mark the open canvas as agent-origin so the agent layer renders opaque. */
    fun setAgentOriginCanvas(value: Boolean) {
        if (agentOriginCanvas == value) return
        agentOriginCanvas = value
        invalidate()
    }

    /**
     * Stage 10 (card → canvas, SPEC §4.7): the CU rects `[x,y,w,h]` to pulse as a
     * transient highlight after a card tap. Empty clears the pulse. Drawn through the
     * same [transform] as ink so it tracks pan/zoom.
     */
    fun setAnchorPulses(rects: List<DoubleArray>) {
        anchorPulses = rects
        invalidate()
    }

    fun setCommittedStrokes(strokes: List<RenderStroke>) {
        committed = strokes
        renderer.setCommittedStrokes(strokes)
        invalidate()
    }

    fun setTransform(scale: Float, tx: Float, ty: Float) {
        transform.scale = scale
        transform.tx = tx
        transform.ty = ty
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val live = builder?.takeIf { !it.isEmpty }?.snapshotPoints()
        renderer.draw(
            canvas,
            transform,
            liveStroke = live,
            liveTool = tool,
            liveColor = parseColor(colorHex),
            liveWidthCu = widthCu,
        )
        // Agent annotation layer (Stage 6): rendered in both debug and release when the
        // layer is visible, through AnnotationRenderer (which paints the 70% opacity).
        if (agentLayerVisible && agentAnnotations.isNotEmpty()) {
            AnnotationRenderer(
                accentColor, canvasWidthCu, canvasHeightCu,
                agentOriginCanvas = agentOriginCanvas,
            ).draw(canvas, transform, agentAnnotations)
        }
        // Stage 10: anchor pulse over a tapped card's region(s), through the ink transform.
        if (anchorPulses.isNotEmpty()) {
            pulsePaint.color = (accentColor and 0x00FFFFFF) or (0x55 shl 24) // ~33% accent
            for (rect in anchorPulses) {
                if (rect.size < 4) continue
                val left = transform.canvasToViewX(rect[0].toFloat())
                val top = transform.canvasToViewY(rect[1].toFloat())
                val right = transform.canvasToViewX((rect[0] + rect[2]).toFloat())
                val bottom = transform.canvasToViewY((rect[1] + rect[3]).toFloat())
                val pad = 8f * transform.scale
                canvas.drawRoundRect(
                    left - pad, top - pad, right + pad, bottom + pad, 12f, 12f, pulsePaint,
                )
            }
        }
        // Debug-only fixture preview overlay ("Render fixture", Stage 4).
        if (debugEnabled && debugHighlights.isNotEmpty()) {
            AnnotationRenderer(accentColor, canvasWidthCu, canvasHeightCu)
                .draw(canvas, transform, debugHighlights)
        }
        if (debugEnabled) drawDebugOverlay(canvas)
    }

    // Hover keeps palm rejection honest: while a stylus hovers, fingers are gestures.
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
            event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
        ) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE ->
                    hoverStylus = true
                MotionEvent.ACTION_HOVER_EXIT -> hoverStylus = false
            }
            policy.setStylusInRange(hoverStylus)
        }
        return super.onGenericMotionEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        refreshStylusPresence(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return onPrimaryDown(event)

            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second pointer arriving turns finger input into a pan/zoom gesture.
                if (event.pointerCount >= 2 && bothFingers(event)) {
                    abortLiveStroke()
                    beginGesture(event)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                when {
                    gestureActive -> updateGesture(event)
                    drawing -> onDrawMove(event)
                    erasing -> onEraseMove(event)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (gestureActive && event.pointerCount <= 2) endGesture()
                return true
            }

            MotionEvent.ACTION_UP -> {
                // Stage 10: recognise a short, near-stationary tap (before the state is
                // cleared) so a tap on an agent mark can scroll the panel to its card.
                val isTap = !gestureActive &&
                    (event.eventTime - downTime) <= TAP_TIMEOUT_MS &&
                    hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) <= TAP_SLOP_PX
                when {
                    gestureActive -> endGesture()
                    drawing -> {
                        // Capture the final pen-up sample (and any batched history) so
                        // the stroke ends exactly where the pen lifted, then commit.
                        builder?.let { addHistoricalAndCurrent(event, it) }
                        commitStroke()
                    }
                    erasing -> erasing = false
                }
                if (isTap && onAnchorTap != null) {
                    onAnchorTap?.invoke(
                        transform.viewToCanvasX(event.x),
                        transform.viewToCanvasY(event.y),
                    )
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                abortLiveStroke()
                erasing = false
                endGesture()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // --- Down handling ---

    private fun onPrimaryDown(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)
        // Stage 10: remember the down point so ACTION_UP can recognise a stationary tap.
        downX = event.x
        downY = event.y
        downTime = event.eventTime

        // Reject a finger/palm while a stylus is in range (§9.2(3)).
        if (toolType == MotionEvent.TOOL_TYPE_FINGER && policy.stylusInRange) {
            droppedSamples++
            emitDebugStats()
            return true
        }

        // A finger with no stylus present is a candidate for pan (single-finger scroll
        // is ignored here; pan/zoom needs two fingers). Drawing needs an accepting tool.
        val isEraserTool = tool == "eraser" || toolType == MotionEvent.TOOL_TYPE_ERASER
        if (isEraserTool) {
            erasing = true
            eraseAt(event.x, event.y)
            return true
        }

        if (!policy.acceptsDrawFrom(toolType)) {
            droppedSamples++
            emitDebugStats()
            return true
        }
        // Only draw with a stylus, or with a finger when no stylus is around.
        if (toolType == MotionEvent.TOOL_TYPE_FINGER && policy.stylusInRange) return true

        beginStroke(event)
        return true
    }

    // --- Drawing ---

    private fun beginStroke(event: MotionEvent) {
        val b = StrokeBuilder(minCutoff, beta)
        b.start(event.eventTime)
        builder = b
        drawing = true
        addHistoricalAndCurrent(event, b)
        invalidate()
    }

    private fun onDrawMove(event: MotionEvent) {
        val b = builder ?: return
        addHistoricalAndCurrent(event, b)
        invalidate()
    }

    /** Iterate ALL historical samples then the current one (§9.2(2)); none dropped. */
    private fun addHistoricalAndCurrent(event: MotionEvent, b: StrokeBuilder) {
        val t0 = System.nanoTime()
        val tiltAxis = MotionEvent.AXIS_TILT
        for (h in 0 until event.historySize) {
            b.add(
                transform.viewToCanvasX(event.getHistoricalX(h)),
                transform.viewToCanvasY(event.getHistoricalY(h)),
                event.getHistoricalPressure(h),
                event.getHistoricalAxisValue(tiltAxis, h),
                event.getHistoricalEventTime(h),
            )
            countSample()
        }
        b.add(
            transform.viewToCanvasX(event.x),
            transform.viewToCanvasY(event.y),
            event.pressure,
            event.getAxisValue(tiltAxis),
            event.eventTime,
        )
        countSample()
        lastFilterLatencyUs = (System.nanoTime() - t0) / 1000
        emitDebugStats()
    }

    private fun commitStroke() {
        val b = builder
        drawing = false
        builder = null
        val built = b?.build()
        if (built != null) {
            onStrokeCommitted?.invoke(StrokeCommit(built, tool, colorHex, widthCu))
        }
        invalidate()
    }

    private fun abortLiveStroke() {
        drawing = false
        builder = null
        invalidate()
    }

    // --- Erasing ---

    private fun onEraseMove(event: MotionEvent) {
        for (h in 0 until event.historySize) {
            eraseAt(event.getHistoricalX(h), event.getHistoricalY(h))
        }
        eraseAt(event.x, event.y)
    }

    private fun eraseAt(viewX: Float, viewY: Float) {
        val cx = transform.viewToCanvasX(viewX)
        val cy = transform.viewToCanvasY(viewY)
        val radius = ERASER_RADIUS_CU
        // Topmost stroke first (last committed) so erasing feels intuitive.
        for (i in committed.indices.reversed()) {
            val s = committed[i]
            if (EraserHitTest.hits(s.points, s.bboxX, s.bboxY, s.bboxW, s.bboxH, cx, cy, radius + s.widthCu)) {
                onEraseStroke?.invoke(s.id)
                return
            }
        }
    }

    // --- Two-finger pan/zoom ---

    private fun bothFingers(event: MotionEvent): Boolean {
        for (i in 0 until event.pointerCount) {
            if (event.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS) return false
        }
        return true
    }

    private fun beginGesture(event: MotionEvent) {
        gestureActive = true
        lastFocusX = (event.getX(0) + event.getX(1)) / 2f
        lastFocusY = (event.getY(0) + event.getY(1)) / 2f
        lastSpan = spanOf(event)
    }

    private fun updateGesture(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val fx = (event.getX(0) + event.getX(1)) / 2f
        val fy = (event.getY(0) + event.getY(1)) / 2f
        val span = spanOf(event)

        transform.panBy(fx - lastFocusX, fy - lastFocusY)
        if (lastSpan > 0f && span > 0f) {
            transform.zoomBy(span / lastSpan, fx, fy)
        }
        lastFocusX = fx
        lastFocusY = fy
        lastSpan = span
        invalidate()
    }

    private fun endGesture() {
        gestureActive = false
    }

    private fun spanOf(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    }

    // --- Stylus presence + debug ---

    private fun refreshStylusPresence(event: MotionEvent) {
        // Presence is authoritative from the current touch pointers combined with the
        // last hover state, so it never sticks "true" after the stylus has left.
        var touchStylus = false
        for (i in 0 until event.pointerCount) {
            val t = event.getToolType(i)
            if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) {
                touchStylus = true
            }
        }
        policy.setStylusInRange(hoverStylus || touchStylus)
    }

    private fun countSample() {
        val now = System.nanoTime()
        if (windowStartNs == 0L) windowStartNs = now
        windowSamples++
        val elapsed = now - windowStartNs
        if (elapsed >= 1_000_000_000L) {
            lastSampleRateHz = (windowSamples * 1_000_000_000.0 / elapsed).toInt()
            windowSamples = 0
            windowStartNs = now
        }
    }

    private fun emitDebugStats() {
        if (!debugEnabled) return
        onDebugStats?.invoke(InkDebugStats(lastSampleRateHz, lastFilterLatencyUs, droppedSamples))
        invalidate()
    }

    private fun drawDebugOverlay(canvas: Canvas) {
        val lines = listOf(
            "sample rate: ${lastSampleRateHz} Hz",
            "filter latency: ${lastFilterLatencyUs} us",
            "dropped samples: $droppedSamples",
        )
        val pad = 12f
        val lineH = debugPaint.textSize + 8f
        val boxH = lineH * lines.size + pad
        canvas.drawRect(0f, 0f, 360f, boxH, debugBgPaint)
        var y = pad + debugPaint.textSize
        for (l in lines) {
            canvas.drawText(l, pad, y, debugPaint)
            y += lineH
        }
    }

    private fun parseColor(hex: String): Int = try {
        Color.parseColor(hex)
    } catch (_: IllegalArgumentException) {
        Color.BLACK
    }

    companion object {
        const val ERASER_RADIUS_CU = 12f

        /** Max travel (view px) for an ACTION_UP to still count as a tap (Stage 10). */
        const val TAP_SLOP_PX = 24.0

        /** Max press duration (ms) for a tap (Stage 10). */
        const val TAP_TIMEOUT_MS = 250L
    }
}
