package com.inkwell.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.input.motionprediction.MotionEventPredictor
import com.inkwell.contracts.Annotation
import com.inkwell.data.PackedPoints
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
    // Stage 31: rolling average of (ink handed to the compositor − MotionEvent.eventTime)
    // for the live stroke, and which path drew it ("wet" front buffer or "view").
    val inkLatencyMs: Double? = null,
    val inkLatencyPath: String = "",
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
 *
 * Stage 31 — **low-latency pen** ([lowLatency]; set from `InkPrefs` when a canvas opens,
 * where it defaults on since stage 33). When on and a [WetInkLayer] is attached and available, a pen stroke is
 * drawn on the front-buffered wet layer instead of through `invalidate()`:
 *  - input is unbuffered (`requestUnbufferedDispatch` on the stylus ACTION_DOWN) and every
 *    `historySize` sample is still iterated (§9.2(2));
 *  - a `MotionEventPredictor` tail is drawn ahead of the nib on the wet layer only.
 *    Samples enter through [LiveStrokeSink], which keeps predicted points out of the
 *    [StrokeBuilder], so the stored stroke is byte-identical to the one captured with the
 *    switch off (contract `ink-storage`);
 *  - on pen-up the stroke commits through the same [commitStroke] path. The finished
 *    stroke stays on the wet layer's multi-buffered layer until [setCommittedStrokes]
 *    delivers its dry copy and this view has drawn a frame with it; only then is the wet
 *    copy released (wet→dry hand-off, no gap and no flicker);
 *  - pan/zoom or a lost surface drops wet content and this view draws those strokes
 *    itself until they are dry. Marker and eraser always use the existing path.
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

    /**
     * Stage 31: the "Low-latency pen" switch. Off on a bare view; `CanvasScreen` sets it
     * from `InkPrefs` (default on since stage 33). Applies to pen strokes that start after
     * it is set; the wet layer is used only when attached.
     */
    var lowLatency: Boolean = false

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
    // Stage 22: a pushed raster (PDF page / image) rendered BENEATH ink; cached, blitted
    // through the same [transform] as ink so it tracks pan/zoom without re-rasterising.
    private val rasterRenderer = com.inkwell.render.RasterRenderer()
    private var documentVisible = true
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

    // Stage 31: every live sample enters through the sink (real → builder; predicted →
    // display-only tail), for both the wet and the View path.
    private var sink: LiveStrokeSink? = null
    private var drawing = false
    private var erasing = false

    // --- Stage 31: low-latency pen state ---
    private var wetLayer: WetInkLayer? = null
    private var predictor: MotionEventPredictor? = null
    private var predictorUnavailable = false
    /** True while the current stroke is drawn on the wet layer (not through invalidate). */
    private var wetStroke = false
    private val wetDamage = WetDamage()
    private var lastRealEventTimeMs = 0L
    /** Finished pen strokes whose dry copy has not been drawn yet (see [PendingDry]). */
    private val pendingDry = ArrayList<PendingDry>()
    private val viewLatency = LatencyMeter()
    private var lastLatencyPath = ""

    /** Stage 31 (tests): how many strokes started on the wet layer. */
    @get:VisibleForTesting
    var wetStrokesStarted: Int = 0
        private set

    /**
     * Stage 33 (tests): predicted samples the last finished (or aborted) stroke's
     * [LiveStrokeSink] received from the predictor ([LiveStrokeSink.predictedSamplesReceived]).
     * Greater than zero proves `MotionEventPredictor` produced output on the wet path.
     */
    @get:VisibleForTesting
    var lastStrokePredictedSamples: Int = 0
        private set

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
        // Stage 31: a pending wet stroke is "dry" once its stored copy is in the set. The
        // view model appends new strokes, so only the tail of the list is searched.
        if (pendingDry.isNotEmpty()) {
            val recent = strokes.takeLast(pendingDry.size + DRY_SEARCH_SLACK)
            for (p in pendingDry) {
                if (!p.dryArrived && recent.any { it.points.contentEquals(p.stroke.points) }) {
                    p.dryArrived = true
                }
            }
        }
        invalidate()
    }

    /** Stage 22: set (or clear, with null) the pushed raster rendered beneath ink. */
    fun setRaster(spec: com.inkwell.render.RasterRenderer.RasterSpec?) {
        rasterRenderer.setRaster(spec)
        invalidate()
    }

    /** Stage 22: layer-tray toggle for the "Document" (raster) layer's visibility. */
    fun setDocumentVisible(visible: Boolean) {
        if (documentVisible == visible) return
        documentVisible = visible
        invalidate()
    }

    fun setTransform(scale: Float, tx: Float, ty: Float) {
        transform.scale = scale
        transform.tx = tx
        transform.ty = ty
        onTransformChanged()
        invalidate()
    }

    /**
     * Stage 31: attach the front-buffered wet layer stacked above this view (see
     * [InkSurfaceHost]). Used only while [lowLatency] is on, and only for the pen.
     */
    fun attachWetLayer(layer: WetInkLayer) {
        wetLayer = layer
        layer.onSurfaceLost = { onWetSurfaceLost() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Stage 22: the pushed raster renders FIRST, beneath ink (SPEC §4.3 / §5.2).
        if (documentVisible && rasterRenderer.hasRaster()) {
            rasterRenderer.draw(canvas, transform)
        }
        // Stage 31: a stroke on the wet layer is not drawn here (it would double up).
        val live = if (wetStroke) null else sink?.takeIf { !it.isEmpty }?.snapshotPoints()
        renderer.draw(
            canvas,
            transform,
            liveStroke = live,
            liveTool = tool,
            liveColor = parseColor(colorHex),
            liveWidthCu = widthCu,
        )
        drawPendingDry(canvas)
        if (live != null && debugEnabled && lowLatency) sampleViewLatency()
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
        // Stage 31: the predictor sees every event of a low-latency pen gesture.
        if (lowLatency && tool == "pen") recordForPrediction(event)

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
                        sink?.let { addHistoricalAndCurrent(event, it) }
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

        // Stage 31: deliver this stylus gesture's samples as they arrive, not batched to
        // vsync. History is still iterated below (§9.2(2)).
        if (lowLatency && tool == "pen" && toolType == MotionEvent.TOOL_TYPE_STYLUS) {
            requestUnbufferedDispatch(event)
        }
        beginStroke(event)
        return true
    }

    // --- Drawing ---

    private fun beginStroke(event: MotionEvent) {
        val s = LiveStrokeSink(minCutoff, beta)
        s.start(event.eventTime)
        sink = s
        drawing = true
        // Stage 31: the pen goes to the wet layer when the switch is on and the surface is
        // up; otherwise (marker, surface not ready) this stroke takes the existing path.
        wetStroke = lowLatency && tool == "pen" && wetLayer?.isAvailable == true
        if (wetStroke) wetStrokesStarted++
        wetDamage.reset()
        addHistoricalAndCurrent(event, s)
        if (wetStroke) renderWetFrame(s) else invalidate()
    }

    private fun onDrawMove(event: MotionEvent) {
        val s = sink ?: return
        addHistoricalAndCurrent(event, s)
        if (wetStroke) {
            updatePrediction(s)
            renderWetFrame(s)
        } else {
            invalidate()
        }
    }

    /** Iterate ALL historical samples then the current one (§9.2(2)); none dropped. */
    private fun addHistoricalAndCurrent(event: MotionEvent, b: LiveStrokeSink) {
        val t0 = System.nanoTime()
        val tiltAxis = MotionEvent.AXIS_TILT
        for (h in 0 until event.historySize) {
            b.addReal(
                transform.viewToCanvasX(event.getHistoricalX(h)),
                transform.viewToCanvasY(event.getHistoricalY(h)),
                event.getHistoricalPressure(h),
                event.getHistoricalAxisValue(tiltAxis, h),
                event.getHistoricalEventTime(h),
            )
            countSample()
        }
        b.addReal(
            transform.viewToCanvasX(event.x),
            transform.viewToCanvasY(event.y),
            event.pressure,
            event.getAxisValue(tiltAxis),
            event.eventTime,
        )
        countSample()
        lastRealEventTimeMs = event.eventTime
        lastFilterLatencyUs = (System.nanoTime() - t0) / 1000
        emitDebugStats()
    }

    private fun commitStroke() {
        val s = sink
        drawing = false
        sink = null
        lastStrokePredictedSamples = s?.predictedSamplesReceived ?: 0
        // finish() drops any predicted tail: only real samples are built and stored.
        val built = s?.finish()
        if (wetStroke) {
            wetStroke = false
            // Stage 31: keep the finished stroke on the wet layer (multi-buffered, which
            // also hides the front buffer and its predicted tail) until its dry copy is
            // drawn. Registered before the commit callback so a synchronous dry copy is
            // matched too.
            if (built != null) addPendingDry(built)
            showWetScene()
        }
        if (built != null) {
            onStrokeCommitted?.invoke(StrokeCommit(built, tool, colorHex, widthCu))
        }
        invalidate()
    }

    private fun abortLiveStroke() {
        drawing = false
        sink?.let { lastStrokePredictedSamples = it.predictedSamplesReceived }
        sink = null
        if (wetStroke) {
            wetStroke = false
            showWetScene()
        }
        invalidate()
    }

    // --- Stage 31: low-latency pen (wet layer, prediction, wet→dry hand-off) ---

    /**
     * A finished pen stroke still shown by the wet layer (or, once the wet copy is
     * dropped, by this view) until its dry copy is drawn. Matched to the stored copy by
     * its exact points (the Room round-trip is lossless, contract `ink-storage`).
     */
    private class PendingDry(val stroke: BuiltStroke, val wet: WetStroke) {
        var dryArrived = false
        var releaseScheduled = false
        /** Wet copy dropped (pan/zoom or surface lost): drawn by this view instead. */
        var viewFallback = false
    }

    private fun addPendingDry(built: BuiltStroke) {
        val p = PendingDry(built, WetStroke(built.points, parseColor(colorHex), widthCu))
        pendingDry.add(p)
        // Never leave a wet stroke behind if its dry copy never arrives (e.g. no ink layer
        // to persist into): release it after a timeout, as the old path would have.
        postDelayed({ if (pendingDry.contains(p)) releasePending(listOf(p)) }, PENDING_DRY_TIMEOUT_MS)
    }

    /**
     * The wet multi-buffered scene: pending strokes whose wet copy is still shown, plus
     * the live stroke's real points while it is wet (never its predicted tail).
     */
    private fun wetScene(live: LivePoints?): WetScene = WetScene(
        pending = pendingDry.filter { !it.viewFallback }.map { it.wet },
        live = live,
        liveColor = parseColor(colorHex),
        liveWidthCu = widthCu,
        scale = transform.scale,
        tx = transform.tx,
        ty = transform.ty,
    )

    /** Half the widest pen segment plus a 2-px anti-aliasing margin, in canvas units. */
    private fun wetInflateCu(): Float =
        LayerRenderer.modulatedWidthCu(widthCu, "pen", MAX_PRESSURE_FOR_DAMAGE) / 2f + 2f / transform.scale

    private fun renderWetFrame(s: LiveStrokeSink) {
        val layer = wetLayer
        if (layer == null || !layer.isAvailable) {
            fallBackToViewPath()
            return
        }
        val live = s.liveBuffer()
        val tail = s.predictedTail
        val inflate = wetInflateCu()
        val damage = wetDamage.next(live.points, live.pointCount, tail, inflate) ?: return
        val frame = WetFrame(
            live = live,
            liveColor = parseColor(colorHex),
            liveWidthCu = widthCu,
            predicted = tail,
            damage = damage,
            inflateCu = inflate,
            scale = transform.scale,
            tx = transform.tx,
            ty = transform.ty,
            newestEventTimeMs = lastRealEventTimeMs,
        )
        layer.drawLive(frame, wetScene(live))
        if (debugEnabled) lastLatencyPath = "wet"
    }

    /** Re-render the wet multi-buffered layer from [pendingDry] (hides the front buffer). */
    private fun showWetScene() {
        wetLayer?.showScene(wetScene(live = null))
    }

    private fun recordForPrediction(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) ensurePredictor()
        // A synthetic or inconsistent stream must never break capture.
        try {
            predictor?.record(event)
        } catch (_: RuntimeException) {
            // Ignored: prediction is display-only.
        }
    }

    private fun ensurePredictor() {
        if (predictor != null || predictorUnavailable) return
        predictor = try {
            MotionEventPredictor.newInstance(this)
        } catch (_: RuntimeException) {
            // e.g. a Context without a display (a detached test view): no prediction.
            predictorUnavailable = true
            null
        }
    }

    /**
     * Replace the sink's predicted tail from the predictor. Predicted samples go to
     * [LiveStrokeSink.setPredicted] only — never to the builder.
     */
    private fun updatePrediction(s: LiveStrokeSink) {
        val predicted = try {
            predictor?.predict()
        } catch (_: RuntimeException) {
            null
        }
        if (predicted == null) {
            s.clearPredicted()
            return
        }
        try {
            val stride = PackedPoints.STRIDE
            val n = predicted.historySize + 1
            val tail = FloatArray(n * stride)
            for (h in 0 until predicted.historySize) {
                tail[h * stride] = transform.viewToCanvasX(predicted.getHistoricalX(h))
                tail[h * stride + 1] = transform.viewToCanvasY(predicted.getHistoricalY(h))
                tail[h * stride + 2] = predicted.getHistoricalPressure(h)
            }
            val c = (n - 1) * stride
            tail[c] = transform.viewToCanvasX(predicted.x)
            tail[c + 1] = transform.viewToCanvasY(predicted.y)
            tail[c + 2] = predicted.pressure
            s.setPredicted(tail)
        } finally {
            predicted.recycle()
        }
    }

    /** The wet layer went away mid-stroke: finish this stroke through the View overlay. */
    private fun fallBackToViewPath() {
        if (!wetStroke) return
        wetStroke = false
        sink?.clearPredicted()
        invalidate()
    }

    /** Surface destroyed/re-configured: its wet ink is gone, so this view takes over. */
    private fun onWetSurfaceLost() {
        for (p in pendingDry) p.viewFallback = true
        fallBackToViewPath()
        invalidate()
    }

    /**
     * Pan/zoom moved the canvas under wet ink drawn at the old transform: drop the wet
     * content and draw the not-yet-dry strokes here (they track the transform) instead.
     */
    private fun onTransformChanged() {
        val hasWetContent = wetStroke || pendingDry.any { !it.viewFallback }
        if (!hasWetContent) return
        for (p in pendingDry) p.viewFallback = true
        fallBackToViewPath()
        // Through a commit (not clear()) so it is ordered after any commit in flight.
        wetLayer?.showScene(WetScene.EMPTY)
    }

    /**
     * Draw not-yet-dry strokes whose wet copy was dropped, and start the wet→dry hand-off
     * for strokes whose dry copy this frame has just drawn from the cache.
     */
    private fun drawPendingDry(canvas: Canvas) {
        if (pendingDry.isEmpty()) return
        val handOff = ArrayList<PendingDry>()
        val done = ArrayList<PendingDry>()
        for (p in pendingDry) {
            when {
                p.dryArrived && p.viewFallback -> done.add(p) // the cache drew it this frame
                p.dryArrived && !p.releaseScheduled -> handOff.add(p)
                p.viewFallback -> renderer.drawOverlayStroke(
                    canvas, transform, p.stroke.points, "pen", p.wet.color, p.wet.widthCu,
                )
            }
        }
        pendingDry.removeAll(done.toSet())
        if (handOff.isEmpty()) return
        for (p in handOff) p.releaseScheduled = true
        // Release the wet copy only after the frame that draws the dry copy has been
        // handed to the compositor, plus one more vsync, so wet and dry briefly overlap
        // (identical opaque pen pixels) instead of leaving a gap.
        viewTreeObserver.registerFrameCommitCallback {
            postOnAnimation { releasePending(handOff) }
        }
    }

    private fun releasePending(done: List<PendingDry>) {
        for (p in done) p.releaseScheduled = true
        if (!pendingDry.removeAll(done.toSet())) return
        // Mid-stroke, the wet scene is re-rendered at this stroke's pen-up instead; a stale
        // wet copy until then sits exactly on its dry copy.
        if (!wetStroke) showWetScene()
        invalidate() // a view-drawn fallback copy must go too
    }

    private fun sampleViewLatency() {
        val eventTime = lastRealEventTimeMs
        lastLatencyPath = "view"
        viewTreeObserver.registerFrameCommitCallback {
            viewLatency.record(SystemClock.uptimeMillis() - eventTime)
        }
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
        onTransformChanged()
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
        onDebugStats?.invoke(
            InkDebugStats(
                lastSampleRateHz, lastFilterLatencyUs, droppedSamples,
                inkLatencyMs = currentInkLatencyMs(), inkLatencyPath = lastLatencyPath,
            ),
        )
        invalidate()
    }

    /** Stage 31: the rolling ink latency for whichever path drew the last live stroke. */
    private fun currentInkLatencyMs(): Double? = when (lastLatencyPath) {
        "wet" -> wetLayer?.latency?.averageMs()
        "view" -> viewLatency.averageMs()
        else -> null
    }

    private fun drawDebugOverlay(canvas: Canvas) {
        val lines = buildList {
            add("sample rate: ${lastSampleRateHz} Hz")
            add("filter latency: ${lastFilterLatencyUs} us")
            add("dropped samples: $droppedSamples")
            // Stage 31: shown once the low-latency switch is on (compare wet vs view).
            if (lowLatency) {
                val ms = currentInkLatencyMs()?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "–"
                add("ink latency: $ms ms (${lastLatencyPath.ifEmpty { "–" }})")
            }
        }
        val pad = 12f
        val lineH = debugPaint.textSize + 8f
        val boxH = lineH * lines.size + pad
        canvas.drawRect(0f, 0f, 420f, boxH, debugBgPaint)
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

        /** Stage 31: release a wet stroke whose dry copy never arrives after this long. */
        const val PENDING_DRY_TIMEOUT_MS = 2_000L

        /** Stage 31: how many extra trailing committed strokes to search for a dry copy. */
        private const val DRY_SEARCH_SLACK = 8

        /** Stage 31: pressure bound for sizing the wet damage rect (digitizers may exceed 1). */
        private const val MAX_PRESSURE_FOR_DAMAGE = 1.5f
    }
}
