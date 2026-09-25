package com.inkwell.ink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.SystemClock
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import androidx.graphics.surface.SurfaceControlCompat
import com.inkwell.data.PackedPoints
import com.inkwell.render.LayerRenderer
import kotlin.math.max
import kotlin.math.min

/** Stage 31: a finished pen stroke waiting on the wet layer for its dry copy (canvas units). */
class WetStroke(val points: FloatArray, val color: Int, val widthCu: Float)

/**
 * Stage 31: one front-buffer frame of the in-progress pen stroke, snapshotted on the UI
 * thread and consumed on the renderer's thread. Nothing here is mutated after
 * construction ([LivePoints] is append-only; see [StrokeBuilder.liveBuffer]).
 *
 * @param live the in-progress stroke's real (filtered) points.
 * @param predicted the display-only predicted tail (stride 5, canvas units).
 * @param damage `[l, t, r, b]` canvas-unit rect the front buffer clears and redraws.
 */
class WetFrame(
    val live: LivePoints,
    val liveColor: Int,
    val liveWidthCu: Float,
    val predicted: FloatArray,
    val damage: FloatArray,
    val inflateCu: Float,
    val scale: Float,
    val tx: Float,
    val ty: Float,
    val newestEventTimeMs: Long,
)

/**
 * Stage 31: what the wet layer's multi-buffered layer shows: finished strokes still
 * waiting for their dry copy ([pending]) plus, while a wet stroke is in progress, its real
 * points so far ([live]; never the predicted tail). Immutable; replaced wholesale.
 */
class WetScene(
    val pending: List<WetStroke>,
    val live: LivePoints?,
    val liveColor: Int,
    val liveWidthCu: Float,
    val scale: Float,
    val tx: Float,
    val ty: Float,
) {
    companion object {
        val EMPTY = WetScene(emptyList(), null, 0, 0f, 1f, 0f, 0f)
    }
}

/**
 * Stage 31: the **front-buffered wet layer** for the pen (SPEC §9.2(5), "render the
 * in-progress stroke to a separate overlay"). A transparent [SurfaceView] stacked above
 * [InkView] and driven by [CanvasFrontBufferedRenderer]:
 *
 *  - **Front-buffered layer** ([drawLive]): the in-progress pen stroke, drawn
 *    incrementally into a buffer that is on screen while it is drawn, so ink lands about
 *    a frame sooner than through the View pipeline. Each frame clears and redraws only
 *    the [WetDamage] rect (new segments plus the old and new predicted tail).
 *  - **Multi-buffered layer** ([showScene]): the current [WetScene] — finished strokes
 *    waiting for [InkView] to draw their dry copy. Every update goes through
 *    [CanvasFrontBufferedRenderer.commit], which is double-buffered (no tearing), hides
 *    the front buffer in the same transaction, and queues any front frames of a next
 *    stroke until it lands. That is what makes the pen-up and wet→dry hand-offs
 *    flicker-free. The scene is read at render time, so overlapping commits always
 *    converge on the newest one.
 *
 * Geometry is drawn with [LayerRenderer.drawSegment] (the same width function as the dry
 * cache) through the same pan/zoom transform, so wet and dry strokes coincide. Only the
 * pen uses this layer: marker ink is translucent and incremental segments would darken
 * where they overlap, and the eraser draws nothing.
 *
 * Lifecycle: the renderer is created on attach and released on detach. When the surface
 * is destroyed or re-configured (rotation, backgrounding) [onSurfaceLost] fires and
 * [InkView] falls back to its View overlay; [isAvailable] is false until the surface is
 * back. Never touches strokes or storage: it only draws what [InkView] hands it.
 */
class WetInkLayer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    /** Fired on the UI thread when wet content is lost (surface destroyed/re-configured). */
    var onSurfaceLost: (() -> Unit)? = null

    /** Debug readout (debug builds): front-buffer submit time − newest sample's eventTime. */
    val latency = LatencyMeter()

    private var renderer: CanvasFrontBufferedRenderer<WetFrame>? = null
    private var surfaceReady = false

    /** The multi-buffered layer's content; written on the UI thread, read on render. */
    @Volatile
    private var scene: WetScene = WetScene.EMPTY

    init {
        // Above InkView's window content, transparent everywhere except wet ink.
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
        // Purely visual: touches fall through to the InkView underneath.
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** True when a stroke can be drawn on the front buffer right now. */
    val isAvailable: Boolean
        get() = surfaceReady && renderer?.isValid() == true

    /**
     * Draw one front-buffer frame of the in-progress stroke. [scene] (pending strokes plus
     * this stroke's real points) is what a commit would show if one lands mid-stroke,
     * e.g. a surface redraw request.
     */
    fun drawLive(frame: WetFrame, scene: WetScene) {
        if (!isAvailable) return
        this.scene = scene
        renderer?.renderFrontBufferedLayer(frame)
    }

    /**
     * Show [scene] on the multi-buffered layer and hide the front buffer (and any
     * predicted tail on it), atomically. [WetScene.EMPTY] drops all wet ink.
     */
    fun showScene(scene: WetScene) {
        this.scene = scene
        if (isAvailable) renderer?.commit()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (renderer == null) renderer = CanvasFrontBufferedRenderer(this, Callbacks(latency) { scene })
    }

    override fun onDetachedFromWindow() {
        surfaceReady = false
        renderer?.release(true)
        renderer = null
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // The renderer rebuilds its layers on any size/transform change, dropping wet ink.
        val wasReady = surfaceReady
        surfaceReady = width > 0 && height > 0
        if (wasReady) {
            scene = WetScene.EMPTY
            onSurfaceLost?.invoke()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        scene = WetScene.EMPTY
        onSurfaceLost?.invoke()
    }

    /** Rendering callbacks, run on the renderer's own thread. */
    private class Callbacks(
        private val latency: LatencyMeter,
        private val currentScene: () -> WetScene,
    ) : CanvasFrontBufferedRenderer.Callback<WetFrame> {
        private val paint = LayerRenderer.newStrokePaint()
        private var lastFrontEventTimeMs = 0L
        private val tailScratch = FloatArray(PackedPoints.STRIDE * (MAX_TAIL_POINTS + 1))

        override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: WetFrame) {
            val live = param.live
            val damage = param.damage
            lastFrontEventTimeMs = param.newestEventTimeMs
            canvas.save()
            canvas.clipRect(
                damage[0] * param.scale + param.tx,
                damage[1] * param.scale + param.ty,
                damage[2] * param.scale + param.tx,
                damage[3] * param.scale + param.ty,
            )
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.translate(param.tx, param.ty)
            canvas.scale(param.scale, param.scale)
            paint.color = param.liveColor
            drawStroke(canvas, live.points, live.pointCount, param.liveWidthCu, damage, param.inflateCu)
            drawTail(canvas, live, param)
            canvas.restore()
        }

        override fun onFrontBufferedLayerRenderComplete(
            frontBufferedLayerSurfaceControl: SurfaceControlCompat,
            transaction: SurfaceControlCompat.Transaction,
        ) {
            if (lastFrontEventTimeMs > 0L) {
                latency.record(SystemClock.uptimeMillis() - lastFrontEventTimeMs)
            }
        }

        override fun onDrawMultiBufferedLayer(
            canvas: Canvas,
            bufferWidth: Int,
            bufferHeight: Int,
            params: Collection<WetFrame>,
        ) {
            // The front-buffer params are ignored: the scene is the single source of truth
            // for this layer, read now so overlapping commits converge on the newest.
            val scene = currentScene()
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            if (scene.pending.isEmpty() && scene.live == null) return
            canvas.save()
            canvas.translate(scene.tx, scene.ty)
            canvas.scale(scene.scale, scene.scale)
            for (s in scene.pending) {
                paint.color = s.color
                drawStroke(canvas, s.points, s.points.size / PackedPoints.STRIDE, s.widthCu, null, 0f)
            }
            scene.live?.let { live ->
                paint.color = scene.liveColor
                drawStroke(canvas, live.points, live.pointCount, scene.liveWidthCu, null, 0f)
            }
            canvas.restore()
        }

        /** Real segments of a stroke; with [clip], only those touching it. Pen: opaque. */
        private fun drawStroke(
            c: Canvas,
            points: FloatArray,
            count: Int,
            widthCu: Float,
            clip: FloatArray?,
            inflateCu: Float,
        ) {
            if (count <= 0) return
            val stride = PackedPoints.STRIDE
            if (count == 1) {
                paint.strokeWidth = LayerRenderer.modulatedWidthCu(widthCu, PEN, points[2])
                c.drawPoint(points[0], points[1], paint)
                return
            }
            for (i in 0 until count - 1) {
                if (clip != null) {
                    val a = i * stride
                    val b = a + stride
                    val l = min(points[a], points[b]) - inflateCu
                    val t = min(points[a + 1], points[b + 1]) - inflateCu
                    val r = max(points[a], points[b]) + inflateCu
                    val bt = max(points[a + 1], points[b + 1]) + inflateCu
                    if (r < clip[0] || l > clip[2] || bt < clip[1] || t > clip[3]) continue
                }
                LayerRenderer.drawSegment(c, points, i, PEN, widthCu, paint)
            }
        }

        /** The predicted tail, joined to the newest real point. Display only. */
        private fun drawTail(c: Canvas, live: LivePoints, param: WetFrame) {
            val tail = param.predicted
            val stride = PackedPoints.STRIDE
            val tailCount = min(tail.size / stride, MAX_TAIL_POINTS)
            if (tailCount == 0 || live.pointCount == 0) return
            val last = (live.pointCount - 1) * stride
            System.arraycopy(live.points, last, tailScratch, 0, stride)
            System.arraycopy(tail, 0, tailScratch, stride, tailCount * stride)
            for (i in 0 until tailCount) {
                LayerRenderer.drawSegment(c, tailScratch, i, PEN, param.liveWidthCu, paint)
            }
        }
    }

    private companion object {
        const val PEN = "pen"
        const val MAX_TAIL_POINTS = 16
    }
}

/**
 * Stage 31: the canvas host when the low-latency pen is on — [inkView] with the
 * [wetLayer] stacked above it in one `AndroidView`. With the switch off, `CanvasScreen`
 * hosts a bare [InkView] exactly as before.
 */
class InkSurfaceHost(context: Context) : FrameLayout(context) {
    val inkView = InkView(context)
    val wetLayer = WetInkLayer(context)

    init {
        addView(inkView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(wetLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        inkView.attachWetLayer(wetLayer)
    }
}
