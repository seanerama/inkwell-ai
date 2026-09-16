package com.inkwell.render

import com.inkwell.contracts.Annotation

/**
 * Pure (Android-free, JVM-unit-testable) hit-testing for the two-way card ↔ canvas anchor
 * interaction of SPEC §4.7 (Stage 10):
 *
 *  - **card → canvas**: [rectsForAnchors] resolves a card's anchors to the CU rectangles
 *    the UI pulses — an `annotation_id` anchor uses that annotation's
 *    [AnnotationGeometry.boundsCu]; a `region` anchor is its normalized `[x,y,w,h]`
 *    scaled to CU.
 *  - **canvas → card**: [cardIndexForTap] maps a CU tap to the index of the first card
 *    whose any anchor rect contains the point, so tapping an agent mark scrolls the panel
 *    to its card.
 *
 * Kept independent of the Compose/UI card types: callers adapt their cards into the
 * neutral [AnchorRegion] shape.
 */
object AnchorHitTest {

    /** A card anchor: an annotation id, or a normalized `[x,y,w,h]` region rect. */
    data class AnchorRegion(val annotationId: String? = null, val region: List<Double>? = null)

    /** Resolve one anchor to a CU rect `[x,y,w,h]`, or null if it cannot be placed. */
    fun rectForAnchor(
        anchor: AnchorRegion,
        annotationsById: Map<String, Annotation>,
        widthCu: Int = CoordinateMapping.DEFAULT_WIDTH_CU,
        heightCu: Int = CoordinateMapping.DEFAULT_HEIGHT_CU,
    ): DoubleArray? {
        anchor.annotationId?.let { id ->
            val annotation = annotationsById[id] ?: return null
            return AnnotationGeometry.boundsCu(annotation, widthCu, heightCu)
        }
        val region = anchor.region
        if (region != null && region.size == 4) {
            return doubleArrayOf(
                region[0] * widthCu,
                region[1] * heightCu,
                region[2] * widthCu,
                region[3] * heightCu,
            )
        }
        return null
    }

    /** All placeable CU rects for a card's anchors (empty if none resolve). */
    fun rectsForAnchors(
        anchors: List<AnchorRegion>,
        annotationsById: Map<String, Annotation>,
        widthCu: Int = CoordinateMapping.DEFAULT_WIDTH_CU,
        heightCu: Int = CoordinateMapping.DEFAULT_HEIGHT_CU,
    ): List<DoubleArray> =
        anchors.mapNotNull { rectForAnchor(it, annotationsById, widthCu, heightCu) }

    /**
     * The index of the first card (in list order) with an anchor rect containing the CU
     * tap `(xCu, yCu)`, or null when the tap hits no anchored card. A zero-area rect (a
     * `text`/`margin_note` bound) is padded by [pointPaddingCu] so a point mark stays
     * tappable.
     */
    fun cardIndexForTap(
        xCu: Double,
        yCu: Double,
        cardAnchors: List<List<AnchorRegion>>,
        annotationsById: Map<String, Annotation>,
        widthCu: Int = CoordinateMapping.DEFAULT_WIDTH_CU,
        heightCu: Int = CoordinateMapping.DEFAULT_HEIGHT_CU,
        pointPaddingCu: Double = DEFAULT_POINT_PADDING_CU,
    ): Int? {
        cardAnchors.forEachIndexed { index, anchors ->
            val rects = rectsForAnchors(anchors, annotationsById, widthCu, heightCu)
            if (rects.any { contains(it, xCu, yCu, pointPaddingCu) }) return index
        }
        return null
    }

    private fun contains(rect: DoubleArray, x: Double, y: Double, pad: Double): Boolean {
        val rx = rect[0]
        val ry = rect[1]
        val rw = rect[2]
        val rh = rect[3]
        val padX = if (rw <= 0.0) pad else 0.0
        val padY = if (rh <= 0.0) pad else 0.0
        return x >= rx - padX && x <= rx + rw + padX && y >= ry - padY && y <= ry + rh + padY
    }

    /** Default padding (CU) for hit-testing zero-area (point) anchors like `text`. */
    const val DEFAULT_POINT_PADDING_CU = 24.0
}
