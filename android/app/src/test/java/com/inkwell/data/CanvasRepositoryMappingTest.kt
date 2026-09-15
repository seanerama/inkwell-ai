package com.inkwell.data

import com.inkwell.ink.BuiltStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure stroke → entity mapping must pack points stride-5 little-endian so the
 * contract invariant `point_count * 5 * 4 == length(points)` holds, and the stored
 * bytes must decode back to exactly the filtered input (contract `ink-storage`).
 */
class CanvasRepositoryMappingTest {

    @Test
    fun maps_built_stroke_to_entity_and_round_trips_points() {
        val points = floatArrayOf(
            10f, 20f, 0.3f, 0.0f, 0f,
            12f, 23f, 0.5f, 0.05f, 16f,
        )
        val built = BuiltStroke(
            points = points,
            pointCount = 2,
            bboxX = 10f, bboxY = 20f, bboxW = 2f, bboxH = 3f,
        )
        val entity = CanvasRepository.toStrokeEntity(
            id = "s1",
            layerId = "l1",
            built = built,
            tool = "pen",
            colorHex = "#111111",
            widthCu = 3f,
            createdAt = 12345L,
        )

        assertEquals("s1", entity.id)
        assertEquals("l1", entity.layerId)
        assertEquals(2, entity.pointCount)
        assertTrue(entity.pointCount * PackedPoints.BYTES_PER_POINT == entity.points.size)

        val decoded = PackedPoints.decode(entity.points, entity.pointCount)
        assertEquals(points.size, decoded.size)
        for (i in points.indices) assertEquals(points[i], decoded[i], 0f)

        assertEquals(10f, entity.bboxX, 0f)
        assertEquals(3f, entity.bboxH, 0f)
    }
}
