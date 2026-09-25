package com.inkwell.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.inkwell.data.PackedPoints
import com.inkwell.data.PageExtent

/**
 * One committed stroke ready to draw: filtered stride-5 [points] in canvas units, its
 * [tool], parsed [color], and base [widthCu] (before pressure modulation).
 */
data class RenderStroke(
    val id: String,
    val points: FloatArray,
    val tool: String,
    val color: Int,
    val widthCu: Float,
    val bboxX: Float = 0f,
    val bboxY: Float = 0f,
    val bboxW: Float = 0f,
    val bboxH: Float = 0f,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RenderStroke) return false
        return id == other.id && points.contentEquals(other.points) &&
            tool == other.tool && color == other.color && widthCu == other.widthCu
    }

    override fun hashCode(): Int = id.hashCode()

    /**
     * Stage 32: `[minX, minY, maxX, maxY, reachCu]` of the points (the tiled cache's
     * culling and invalidation bounds), computed once per instance.
     */
    internal val inkBounds: FloatArray by lazy { LayerRenderer.computeInkBounds(points, tool, widthCu) }
}

/**
 * Renders one ink layer: committed strokes from a **tiled level-of-detail cache** blitted
 * through the pan/zoom transform, plus a **live overlay** for the in-progress stroke.
 *
 * Stage 32 (ADR-0014 §3) replaced the single page-sized bitmap with per-page tiles keyed
 * by `(layer, col, row, lod)` ([TileKey]; a multi-page grid also splits the finest LODs
 * into sub-tiles, [TileMath.subdivisions]):
 *  - a tile rasterises only the committed strokes whose ink can reach it;
 *  - the LOD follows [CanvasTransform.scale] in powers of two, capped at 1 px/CU
 *    ([TileMath.lodFor]), and is coarsened if the visible tiles would not fit the budget;
 *  - only tiles intersecting the viewport (the output canvas's clip) are built or drawn;
 *  - an LRU ([TileLru]) keeps the cached tiles under [budgetBytes];
 *  - invalidation is per tile: an appended stroke is drawn onto the cached tiles it
 *    touches; an erased / undone / changed stroke rebuilds only the tiles it touches.
 *
 * On a single-page canvas at scale ≥ 0.5 the one tile is exactly the pre-stage-32 page
 * bitmap (same size, same strokes in the same order, same blit matrix and paint), so the
 * output is pixel-identical (`TiledRendererParityInstrumentedTest`).
 *
 * Tiles are never re-rasterised per frame (SPEC §9.2(5)); pan/zoom only changes the
 * [Matrix] each tile is drawn with. The live stroke is drawn straight to the view each
 * frame in canvas units through the same transform, so capture and committed ink stay
 * pixel-aligned.
 *
 * Width is modulated at render time: `width_cu * (0.3 + 0.7 * p)` (SPEC §9.2(6));
 * stored pressure is never touched.
 */
class LayerRenderer(
    private val layerId: String = DEFAULT_LAYER_ID,
    /** Stage 32: the LRU byte budget for this layer's tiles (see [DEFAULT_BUDGET_BYTES]). */
    val budgetBytes: Long = DEFAULT_BUDGET_BYTES,
) {

    private var pageWidthCu = 0
    private var pageHeightCu = 0
    private var extent = PageExtent.SINGLE

    private var committed: List<RenderStroke> = emptyList()
    /** Bumped on every change to the committed set; a tile drawn at this generation is dry. */
    private var generation = 0L

    /**
     * Stage 32: force one LOD instead of choosing it from the scale (no budget coarsening).
     * [CanvasExporter] sets 0 so the export keeps rasterising at 1 px/CU and stays
     * byte-identical (contract `coordinate-mapping`).
     */
    var fixedLod: Int? = null

    private class Tile(val spec: TileSpec, val bitmap: Bitmap) {
        var dirty = true
        val pendingAppend = ArrayList<RenderStroke>()
    }

    private val tiles = TileLru<TileKey, Tile>(budgetBytes) { _, t -> t.bitmap.recycle() }

    // The last frame's visible tiles, and which of them were drawn up to date.
    private val lastFrameSpecs = ArrayList<TileSpec>()
    private val lastFrameDrawn = HashSet<TileKey>()
    private var lastFrameGeneration = -1L

    private val matrix = Matrix()
    private val tileMatrix = Matrix()
    private val clipRect = Rect()
    private val strokePaint = newStrokePaint()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Stage 32: the **page** size (a canvas is a grid of equal pages, ADR-0014 §1). */
    fun setCanvasSize(widthCu: Int, heightCu: Int) {
        if (widthCu == pageWidthCu && heightCu == pageHeightCu) return
        pageWidthCu = widthCu
        pageHeightCu = heightCu
        tiles.clear()
        generation++
    }

    /**
     * Stage 32: the canvas's page grid. Only pages inside it are drawn. Tiles of pages that
     * stay in the grid are kept unless the sub-tiling scheme changes (single ↔ multi-page).
     */
    fun setPageExtent(newExtent: PageExtent) {
        if (newExtent == extent) return
        val schemeChanged = (newExtent.pageCount <= 1) != (extent.pageCount <= 1)
        extent = newExtent
        if (schemeChanged) {
            tiles.clear()
        } else {
            for (key in tiles.keys()) if (!newExtent.containsPage(key.col, key.row)) tiles.remove(key)
        }
    }

    /**
     * Replace the committed strokes. Unchanged content invalidates nothing; strokes
     * appended at the end are drawn onto the cached tiles they touch at the next draw;
     * any other change (erase, undo, reorder, edit) rebuilds only the touched tiles.
     */
    fun setCommittedStrokes(strokes: List<RenderStroke>) {
        val old = committed
        // A private snapshot, so a caller mutating its list later cannot desync the diff.
        committed = ArrayList(strokes)
        var prefix = 0
        val common = minOf(old.size, strokes.size)
        while (prefix < common && sameStroke(old[prefix], strokes[prefix])) prefix++
        if (prefix == old.size && prefix == strokes.size) return // same content
        generation++
        if (prefix == old.size) {
            // Append-only (pen-up): draw the new strokes onto the tiles they touch.
            val added = committed.subList(prefix, committed.size)
            for (tile in tiles.values()) {
                if (tile.dirty) continue
                for (s in added) if (s.tool != "eraser" && reaches(s, tile.spec)) tile.pendingAppend.add(s)
            }
            return
        }
        // General change: every stroke present on only one side dirties the tiles it touches.
        val oldById = HashMap<String, RenderStroke>(old.size * 2)
        for (s in old) oldById[s.id] = s
        val newById = HashMap<String, RenderStroke>(strokes.size * 2)
        for (s in strokes) newById[s.id] = s
        val changed = ArrayList<RenderStroke>()
        for (s in old) if (newById[s.id]?.let { sameStroke(it, s) } != true) changed.add(s)
        for (s in strokes) if (oldById[s.id]?.let { sameStroke(it, s) } != true) changed.add(s)
        val keptOld = old.filter { newById[it.id]?.let { n -> sameStroke(n, it) } == true }.map { it.id }
        val keptNew = strokes.filter { oldById[it.id]?.let { o -> sameStroke(o, it) } == true }.map { it.id }
        if (keptOld != keptNew) {
            invalidateAll() // relative order changed: overlaps may draw differently
            return
        }
        for (tile in tiles.values()) {
            if (tile.dirty) continue
            if (changed.any { reaches(it, tile.spec) }) {
                tile.dirty = true
                tile.pendingAppend.clear()
            }
        }
    }

    /** Stage 32: mark every cached tile for a rebuild (bitmaps are kept and reused). */
    fun invalidateAll() {
        generation++
        for (tile in tiles.values()) {
            tile.dirty = true
            tile.pendingAppend.clear()
        }
    }

    fun release() {
        tiles.clear()
        lastFrameSpecs.clear()
        lastFrameDrawn.clear()
    }

    /**
     * Draw the layer into [outCanvas] under [transform].
     *
     * @param liveStroke stride-5 points of the in-progress stroke (canvas units) or
     *   null when nothing is being drawn.
     */
    fun draw(
        outCanvas: Canvas,
        transform: CanvasTransform,
        liveStroke: FloatArray? = null,
        liveTool: String = "pen",
        liveColor: Int = Color.BLACK,
        liveWidthCu: Float = DEFAULT_WIDTH_CU,
    ) {
        drawCommitted(outCanvas, transform)

        if (liveStroke != null && liveStroke.size >= PackedPoints.STRIDE) {
            matrix.reset()
            matrix.setScale(transform.scale, transform.scale)
            matrix.postTranslate(transform.tx, transform.ty)
            outCanvas.save()
            outCanvas.concat(matrix)
            drawStroke(outCanvas, liveStroke, liveTool, liveColor, liveWidthCu)
            outCanvas.restore()
        }
    }

    /**
     * Stage 31: draw one extra overlay stroke (canvas units) through [transform], exactly
     * as the live stroke is drawn. [com.inkwell.ink.InkView] uses it for finished pen
     * strokes whose wet copy was dropped (pan/zoom, surface lost) until their dry copy
     * reaches the tiles.
     */
    fun drawOverlayStroke(
        outCanvas: Canvas,
        transform: CanvasTransform,
        points: FloatArray,
        tool: String,
        color: Int,
        widthCu: Float,
    ) {
        if (points.size < PackedPoints.STRIDE) return
        matrix.reset()
        matrix.setScale(transform.scale, transform.scale)
        matrix.postTranslate(transform.tx, transform.ty)
        outCanvas.save()
        outCanvas.concat(matrix)
        drawStroke(outCanvas, points, tool, color, widthCu)
        outCanvas.restore()
    }

    /**
     * Stage 32 (wet→dry hand-off): true when the last [draw] showed the committed set as it
     * is now and drew, up to date, every visible tile the stroke's ink can reach. Parts of
     * the stroke outside the viewport or the page grid have nothing to wait for.
     */
    fun wasDrawnInLastFrame(points: FloatArray, tool: String, widthCu: Float): Boolean {
        if (lastFrameGeneration != generation) return false
        val reach = inkReach(points, tool, widthCu) ?: return true
        for (spec in lastFrameSpecs) {
            if (spec.intersects(reach.left, reach.top, reach.right, reach.bottom) && spec.key !in lastFrameDrawn) {
                return false
            }
        }
        return true
    }

    // --- Stage 32 test/debug hooks ---

    /** Bytes held by cached tiles (always ≤ [budgetBytes] after a draw, bar the note in [TileLru]). */
    val cachedBytes: Long get() = tiles.totalBytes

    val cachedTileCount: Int get() = tiles.size

    /** The LOD and the tiles the last [draw] showed. */
    var lastFrameLod: Int = 0
        private set

    val lastFrameTiles: List<TileKey> get() = lastFrameSpecs.map { it.key }

    // --- Tiles ---

    private fun drawCommitted(out: Canvas, transform: CanvasTransform) {
        lastFrameSpecs.clear()
        lastFrameDrawn.clear()
        lastFrameGeneration = generation
        if (pageWidthCu <= 0 || pageHeightCu <= 0 || !(transform.scale > 0f)) return
        if (!out.getClipBounds(clipRect)) return
        val viewport = TileMath.viewportCu(
            clipRect.left.toFloat(), clipRect.top.toFloat(),
            clipRect.right.toFloat(), clipRect.bottom.toFloat(),
            transform.scale, transform.tx, transform.ty,
        )
        val fixed = fixedLod
        val (lod, specs) = if (fixed != null) {
            fixed to TileMath.tilesTouching(layerId, viewport, pageWidthCu, pageHeightCu, extent, fixed)
        } else {
            TileMath.chooseLod(layerId, viewport, pageWidthCu, pageHeightCu, extent, transform.scale, budgetBytes)
        }
        lastFrameLod = lod
        lastFrameSpecs.addAll(specs)
        val pinned = specs.mapTo(HashSet(specs.size * 2)) { it.key }
        for (spec in specs) {
            val tile = ensureTile(spec, pinned) ?: continue
            drawTile(out, transform, tile)
            lastFrameDrawn.add(spec.key)
        }
        tiles.trim(pinned)
        if (tiles.totalBytes > budgetBytes) {
            Log.w(TAG, "tile cache ${tiles.totalBytes} B over budget $budgetBytes B at LOD $lod (${specs.size} visible)")
        }
    }

    /** The up-to-date tile for [spec], building or refreshing it; null if it cannot be allocated. */
    private fun ensureTile(spec: TileSpec, pinned: Set<TileKey>): Tile? {
        var tile = tiles[spec.key]
        if (tile == null) {
            tiles.makeRoomFor(spec.bytes, pinned)
            val bmp = allocate(spec, pinned) ?: return null
            tile = Tile(spec, bmp)
            tiles.put(spec.key, tile, spec.bytes)
        }
        when {
            tile.dirty -> rebuild(tile)
            tile.pendingAppend.isNotEmpty() -> {
                val c = tileCanvas(tile)
                for (s in tile.pendingAppend) drawStroke(c, s.points, s.tool, s.color, s.widthCu)
                tile.pendingAppend.clear()
            }
        }
        return tile
    }

    private fun allocate(spec: TileSpec, pinned: Set<TileKey>): Bitmap? = try {
        Bitmap.createBitmap(spec.pxWidth, spec.pxHeight, Bitmap.Config.ARGB_8888)
    } catch (_: OutOfMemoryError) {
        tiles.trimTo(0L, pinned)
        try {
            Bitmap.createBitmap(spec.pxWidth, spec.pxHeight, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "tile ${spec.key} (${spec.bytes} B) could not be allocated", e)
            null
        }
    }

    /**
     * Rasterise every committed stroke that reaches the tile, in committed order. For the
     * single-page LOD-0 tile the canvas matrix stays identity, so this is exactly the
     * pre-stage-32 page-cache rebuild.
     */
    private fun rebuild(tile: Tile) {
        val c = tileCanvas(tile)
        c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        for (stroke in committed) {
            if (stroke.tool == "eraser") continue // eraser removes strokes at capture
            if (!reaches(stroke, tile.spec)) continue
            drawStroke(c, stroke.points, stroke.tool, stroke.color, stroke.widthCu)
        }
        tile.dirty = false
        tile.pendingAppend.clear()
    }

    /** A canvas on the tile's bitmap mapping canvas units to tile pixels. */
    private fun tileCanvas(tile: Tile): Canvas {
        val spec = tile.spec
        val c = Canvas(tile.bitmap)
        if (spec.pxLeft != 0 || spec.pxTop != 0) c.translate(-spec.pxLeft.toFloat(), -spec.pxTop.toFloat())
        if (spec.scaleX != 1f || spec.scaleY != 1f) c.scale(spec.scaleX, spec.scaleY)
        if (spec.key.col != 0 || spec.key.row != 0) c.translate(-spec.pageLeftCu, -spec.pageTopCu)
        return c
    }

    /** Blit a tile through [transform]; for page (0,0) at LOD 0 this is the old page-cache matrix. */
    private fun drawTile(out: Canvas, transform: CanvasTransform, tile: Tile) {
        val spec = tile.spec
        tileMatrix.reset()
        tileMatrix.setScale(transform.scale / spec.scaleX, transform.scale / spec.scaleY)
        tileMatrix.postTranslate(
            spec.leftCu * transform.scale + transform.tx,
            spec.topCu * transform.scale + transform.ty,
        )
        out.drawBitmap(tile.bitmap, tileMatrix, bitmapPaint)
    }

    private fun sameStroke(a: RenderStroke, b: RenderStroke): Boolean = a === b || a == b

    private fun reaches(stroke: RenderStroke, spec: TileSpec): Boolean {
        val b = stroke.inkBounds
        if (b[0].isNaN()) return false
        val r = b[4]
        return spec.intersects(b[0] - r, b[1] - r, b[2] + r, b[3] + r)
    }

    /** Draw one stroke with per-segment pressure-modulated width, in canvas units. */
    private fun drawStroke(
        c: Canvas,
        points: FloatArray,
        tool: String,
        color: Int,
        widthCu: Float,
    ) = drawStrokeWith(c, points, tool, color, widthCu, strokePaint)

    companion object {
        private const val TAG = "LayerRenderer"

        const val DEFAULT_LAYER_ID = "ink"

        /**
         * Stage 32: the default tile budget, 96 MB (`feature-assessments/expandable-canvas-
         * assessment.md`, "Stage 32 measured budget"): one full-resolution A4 page tile
         * (34.8 MB, what a single-page canvas uses — the same as the old page cache) plus
         * room for the next LOD and panning neighbours, while the visible set at any zoom
         * stays within it ([TileMath.chooseLod]).
         */
        const val DEFAULT_BUDGET_BYTES: Long = 96L * 1024 * 1024

        /**
         * The rect (canvas units) the ink of a stroke with stride-5 [points] can touch:
         * its point bbox inflated by [TileMath.inkReachCu]. Null for an empty stroke.
         */
        fun inkReach(points: FloatArray, tool: String, widthCu: Float): RectCu? {
            val b = computeInkBounds(points, tool, widthCu)
            if (b[0].isNaN()) return null
            val r = b[4]
            return RectCu(b[0] - r, b[1] - r, b[2] + r, b[3] + r)
        }

        /** `[minX, minY, maxX, maxY, reachCu]` of stride-5 [points]; NaN bounds when empty. */
        internal fun computeInkBounds(points: FloatArray, tool: String, widthCu: Float): FloatArray {
            val stride = PackedPoints.STRIDE
            val count = points.size / stride
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            var maxW = 0f
            for (i in 0 until count) {
                val o = i * stride
                val x = points[o]
                val y = points[o + 1]
                if (x.isNaN() || y.isNaN()) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                val w = kotlin.math.abs(modulatedWidthCu(widthCu, tool, points[o + 2]))
                if (w > maxW) maxW = w
            }
            if (minX > maxX) return floatArrayOf(Float.NaN, Float.NaN, Float.NaN, Float.NaN, 0f)
            return floatArrayOf(minX, minY, maxX, maxY, TileMath.inkReachCu(maxW))
        }

        const val DEFAULT_WIDTH_CU = 3f
        const val MARKER_ALPHA = 110
        const val MARKER_WIDTH_SCALE = 3f

        /**
         * The render-time width function (SPEC §9.2(6)): `width_cu * (0.3 + 0.7 * p)`,
         * scaled for the marker. Stage 31: shared with the front-buffered wet layer so the
         * wet pen stroke matches the dry one exactly.
         */
        fun modulatedWidthCu(widthCu: Float, tool: String, pressure: Float): Float {
            val widthScale = if (tool == "marker") MARKER_WIDTH_SCALE else 1f
            return widthCu * widthScale * (0.3f + 0.7f * pressure)
        }

        /**
         * Draw segment [index] → [index] + 1 of stride-5 [points] (canvas units) with the
         * segment's mean pressure. [paint] must already carry the colour/alpha and a
         * round cap and join. Shared by the dry cache, the live overlay and (Stage 31) the
         * wet layer.
         */
        fun drawSegment(c: Canvas, points: FloatArray, index: Int, tool: String, widthCu: Float, paint: Paint) {
            val stride = PackedPoints.STRIDE
            val a = index * stride
            val b = a + stride
            val p = (points[a + 2] + points[b + 2]) * 0.5f
            paint.strokeWidth = modulatedWidthCu(widthCu, tool, p)
            c.drawLine(points[a], points[a + 1], points[b], points[b + 1], paint)
        }

        /**
         * Draw one stroke (stride-5 [points], canvas units) with per-segment
         * pressure-modulated width into [c] using [paint] (from [newStrokePaint]). The one
         * stroke-drawing routine shared by the tiles, the live overlay and (stage 32) the
         * thumbnail renderer.
         */
        fun drawStrokeWith(
            c: Canvas,
            points: FloatArray,
            tool: String,
            color: Int,
            widthCu: Float,
            paint: Paint,
        ) {
            val stride = PackedPoints.STRIDE
            val count = points.size / stride
            if (count == 0) return

            val alpha = if (tool == "marker") MARKER_ALPHA else 255
            paint.color = color
            paint.alpha = alpha

            if (count == 1) {
                paint.strokeWidth = modulatedWidthCu(widthCu, tool, points[2])
                c.drawPoint(points[0], points[1], paint)
                return
            }

            var i = 0
            while (i < count - 1) {
                drawSegment(c, points, i, tool, widthCu, paint)
                i++
            }
        }

        /** A paint configured exactly like the one the dry cache strokes with. */
        fun newStrokePaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }
}
