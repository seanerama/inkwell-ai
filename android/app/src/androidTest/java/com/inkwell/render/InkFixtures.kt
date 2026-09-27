package com.inkwell.render

import android.graphics.Color
import com.inkwell.data.PackedPoints
import java.util.Random

/**
 * Stage 32 (test-only): deterministic committed-ink fixtures for the renderer parity and
 * budget tests — pen and marker strokes with varying pressure and width, single-point dots,
 * strokes crossing page edges, strokes entirely off page (0,0) (negative too), and an eraser
 * stroke (never drawn).
 */
object InkFixtures {

    const val PAGE_W = 2480
    const val PAGE_H = 3508

    private val COLORS = intArrayOf(
        Color.parseColor("#111111"), Color.parseColor("#1E4FD8"), Color.parseColor("#C62828"),
        Color.parseColor("#2E7D32"), Color.parseColor("#F9A825"),
    )

    /** A polyline stroke through [xy] (canvas units) with pressure ramping over [p0]..[p1]. */
    fun stroke(id: String, tool: String, color: Int, widthCu: Float, xy: FloatArray, p0: Float, p1: Float): RenderStroke {
        val n = xy.size / 2
        val pts = FloatArray(n * PackedPoints.STRIDE)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until n) {
            val o = i * PackedPoints.STRIDE
            val x = xy[i * 2]
            val y = xy[i * 2 + 1]
            pts[o] = x
            pts[o + 1] = y
            pts[o + 2] = if (n == 1) p0 else p0 + (p1 - p0) * i / (n - 1)
            pts[o + 3] = 0.1f
            pts[o + 4] = i * 4f
            minX = minOf(minX, x); maxX = maxOf(maxX, x)
            minY = minOf(minY, y); maxY = maxOf(maxY, y)
        }
        return RenderStroke(id, pts, tool, color, widthCu, minX, minY, maxX - minX, maxY - minY)
    }

    /** A wavy handwriting-like stroke starting at ([x], [y]). */
    private fun scribble(r: Random, id: String, x: Float, y: Float, tool: String): RenderStroke {
        val n = 3 + r.nextInt(40)
        val xy = FloatArray(n * 2)
        var cx = x
        var cy = y
        for (i in 0 until n) {
            xy[i * 2] = cx
            xy[i * 2 + 1] = cy
            cx += 2f + r.nextFloat() * 14f
            cy += (r.nextFloat() - 0.5f) * 18f
        }
        val width = if (tool == "marker") 6f + r.nextFloat() * 6f else 1.5f + r.nextFloat() * 6f
        return stroke(id, tool, COLORS[r.nextInt(COLORS.size)], width, xy, 0.1f + r.nextFloat() * 0.9f, 0.1f + r.nextFloat() * 0.9f)
    }

    /**
     * About 220 strokes over the region `[x0, x0 + w) × [y0, y0 + h)` (default: page (0,0)
     * plus a margin around it, so some strokes cross the page edges or lie off the page).
     */
    fun handwriting(
        seed: Long = 32L,
        x0: Float = -300f,
        y0: Float = -300f,
        w: Float = PAGE_W + 600f,
        h: Float = PAGE_H + 600f,
        idPrefix: String = "s",
    ): List<RenderStroke> {
        val r = Random(seed)
        val out = ArrayList<RenderStroke>()
        for (i in 0 until 200) {
            val tool = if (r.nextInt(6) == 0) "marker" else "pen"
            out.add(scribble(r, "$idPrefix$i", x0 + r.nextFloat() * w, y0 + r.nextFloat() * h, tool))
        }
        // Single-point dots (drawPoint path).
        for (i in 0 until 12) {
            out.add(
                stroke(
                    "${idPrefix}dot$i", "pen", COLORS[i % COLORS.size], 2f + i,
                    floatArrayOf(x0 + r.nextFloat() * w, y0 + r.nextFloat() * h), 0.3f + i * 0.05f, 0f,
                ),
            )
        }
        // Strokes straddling each page edge and corner of page (0,0).
        out.add(stroke("${idPrefix}edgeR", "pen", Color.BLACK, 5f, floatArrayOf(2400f, 500f, 2560f, 520f), 0.5f, 1f))
        out.add(stroke("${idPrefix}edgeB", "marker", COLORS[4], 9f, floatArrayOf(800f, 3450f, 820f, 3600f), 0.4f, 0.9f))
        out.add(stroke("${idPrefix}edgeTL", "pen", COLORS[1], 4f, floatArrayOf(-40f, -30f, 60f, 70f), 1f, 0.2f))
        out.add(stroke("${idPrefix}corner", "pen", COLORS[2], 7f, floatArrayOf(2470f, 3500f, 2490f, 3515f), 0.8f, 0.8f))
        // Entirely off page (0,0): never visible on a single-page canvas.
        out.add(stroke("${idPrefix}offR", "pen", Color.BLACK, 3f, floatArrayOf(3000f, 100f, 3100f, 200f), 0.5f, 0.5f))
        out.add(stroke("${idPrefix}offNeg", "pen", Color.BLACK, 3f, floatArrayOf(-500f, -600f, -400f, -500f), 0.5f, 0.5f))
        // An eraser-tool stroke is never drawn.
        out.add(stroke("${idPrefix}eraser", "eraser", Color.BLACK, 12f, floatArrayOf(100f, 100f, 900f, 900f), 1f, 1f))
        return out
    }
}
