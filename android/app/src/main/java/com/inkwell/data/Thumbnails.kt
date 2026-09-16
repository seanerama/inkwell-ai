package com.inkwell.data

/**
 * Pure, JVM-testable thumbnail path derivation (Stage 11). A canvas thumbnail is a cached
 * 256-px PNG, never the source of truth (contract `ink-storage` invariant 1: ink is
 * vectors). The bytes are rendered by the Android-only [ThumbnailRenderer]; this object
 * only names the file so the mapping `canvasId → relative path` can be unit-tested with
 * no Android dependency.
 */
object Thumbnails {

    /** Cache directory (relative to `filesDir`) that holds all canvas thumbnails. */
    const val DIR = "thumbs"

    /** Thumbnail long-edge size in pixels. */
    const val SIZE_PX = 256

    /** Relative path under `filesDir` for a canvas's thumbnail, e.g. `thumbs/<id>.png`. */
    fun relativePath(canvasId: String): String = "$DIR/$canvasId.png"
}
