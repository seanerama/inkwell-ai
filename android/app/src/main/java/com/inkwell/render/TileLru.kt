package com.inkwell.render

/**
 * Stage 32 (ADR-0014 §3): a least-recently-used map whose entries carry a byte size, kept
 * under [budgetBytes]. [LayerRenderer] stores its committed-ink tiles here; pure Kotlin so
 * eviction is JVM unit-tested.
 *
 * Entries named in `pinned` (the tiles the current frame draws) are never evicted, so a
 * frame never throws away what it is about to draw. [LayerRenderer] picks the LOD so the
 * pinned set itself fits the budget ([TileMath.chooseLod]); the only way to exceed the
 * budget is a frame at the coarsest LOD whose tiles alone are larger, which the caller
 * logs.
 */
class TileLru<K, V>(
    val budgetBytes: Long,
    private val onEvict: (K, V) -> Unit = { _, _ -> },
) {
    private class Entry<V>(val value: V, val bytes: Long)

    // accessOrder = true: iteration runs least- to most-recently used.
    private val map = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)

    var totalBytes: Long = 0L
        private set

    val size: Int get() = map.size

    /** The value for [key], marking it most recently used. */
    operator fun get(key: K): V? = map[key]?.value

    fun containsKey(key: K): Boolean = map.containsKey(key)

    /** Snapshot of the keys, least recently used first (does not touch the order). */
    fun keys(): List<K> = map.keys.toList()

    /** Snapshot of the values, least recently used first. */
    fun values(): List<V> = map.values.map { it.value }

    /**
     * Insert (or replace) [key] as the most recently used entry. Does not evict; call
     * [makeRoomFor] first and [trim] after.
     */
    fun put(key: K, value: V, bytes: Long) {
        val old = map.remove(key)
        if (old != null) {
            totalBytes -= old.bytes
            if (old.value !== value) onEvict(key, old.value)
        }
        map[key] = Entry(value, bytes)
        totalBytes += bytes
    }

    fun remove(key: K): V? {
        val e = map.remove(key) ?: return null
        totalBytes -= e.bytes
        onEvict(key, e.value)
        return e.value
    }

    /** Evict least-recently-used unpinned entries until [bytes] more would fit. */
    fun makeRoomFor(bytes: Long, pinned: Set<K> = emptySet()) = trimTo(budgetBytes - bytes, pinned)

    /** Evict least-recently-used unpinned entries until the total fits [budgetBytes]. */
    fun trim(pinned: Set<K> = emptySet()) = trimTo(budgetBytes, pinned)

    /** Evict least-recently-used unpinned entries until `totalBytes ≤ limit` (or none left). */
    fun trimTo(limit: Long, pinned: Set<K> = emptySet()) {
        if (totalBytes <= limit) return
        val it = map.entries.iterator()
        while (totalBytes > limit && it.hasNext()) {
            val e = it.next()
            if (e.key in pinned) continue
            it.remove()
            totalBytes -= e.value.bytes
            onEvict(e.key, e.value.value)
        }
    }

    /** Evict everything (e.g. on release or a page-size change). */
    fun clear() {
        val snapshot = map.entries.map { it.key to it.value.value }
        map.clear()
        totalBytes = 0L
        for ((k, v) in snapshot) onEvict(k, v)
    }
}
