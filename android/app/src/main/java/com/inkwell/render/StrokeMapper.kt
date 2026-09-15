package com.inkwell.render

import android.graphics.Color
import com.inkwell.data.PackedPoints
import com.inkwell.data.StrokeEntity

/**
 * Maps a persisted [StrokeEntity] (contract `ink-storage`) to a [RenderStroke] the
 * [LayerRenderer] can draw: the packed points are decoded (the codec enforces the
 * `point_count * 5 * 4 == length(points)` invariant) and the hex color is parsed once.
 */
object StrokeMapper {
    fun toRenderStroke(e: StrokeEntity): RenderStroke = RenderStroke(
        id = e.id,
        points = PackedPoints.decode(e.points, e.pointCount),
        tool = e.tool,
        color = parseColor(e.color),
        widthCu = e.widthCu,
        bboxX = e.bboxX,
        bboxY = e.bboxY,
        bboxW = e.bboxW,
        bboxH = e.bboxH,
    )

    private fun parseColor(hex: String): Int = try {
        Color.parseColor(hex)
    } catch (_: IllegalArgumentException) {
        Color.BLACK
    }
}
