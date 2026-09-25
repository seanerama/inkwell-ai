package com.inkwell.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 32: the tile LRU evicts least-recently-used, unpinned entries under a byte budget. */
class TileLruTest {

    @Test
    fun evicts_least_recently_used_first_to_stay_under_budget() {
        val evicted = mutableListOf<String>()
        val lru = TileLru<String, Int>(budgetBytes = 100) { k, _ -> evicted.add(k) }
        lru.put("a", 1, 40)
        lru.put("b", 2, 40)
        lru["a"] // a is now most recent
        lru.makeRoomFor(40)
        lru.put("c", 3, 40)
        assertEquals(listOf("b"), evicted)
        assertEquals(80, lru.totalBytes)
        assertNull(lru["b"])
        assertEquals(1, lru["a"])
        assertEquals(3, lru["c"])
    }

    @Test
    fun pinned_entries_are_never_evicted() {
        val evicted = mutableListOf<String>()
        val lru = TileLru<String, Int>(budgetBytes = 100) { k, _ -> evicted.add(k) }
        lru.put("a", 1, 60)
        lru.put("b", 2, 60)
        lru.trim(pinned = setOf("a", "b"))
        assertTrue(evicted.isEmpty())
        assertEquals(120, lru.totalBytes) // over budget only while both are pinned
        lru.trim(pinned = setOf("b"))
        assertEquals(listOf("a"), evicted)
        assertEquals(60, lru.totalBytes)
    }

    @Test
    fun replacing_and_removing_keep_the_byte_count_exact() {
        val evicted = mutableListOf<Int>()
        val lru = TileLru<String, Int>(budgetBytes = 1000) { _, v -> evicted.add(v) }
        lru.put("a", 1, 10)
        lru.put("a", 2, 30)
        assertEquals(30, lru.totalBytes)
        assertEquals(listOf(1), evicted) // the replaced value is released
        assertEquals(2, lru.remove("a"))
        assertEquals(0, lru.totalBytes)
        assertFalse(lru.containsKey("a"))
    }

    @Test
    fun a_long_pan_never_exceeds_the_budget() {
        val budget = 10L * 2_200_000
        var live = 0
        val lru = TileLru<Int, Int>(budget) { _, _ -> live-- }
        for (frame in 0 until 200) {
            val visible = (frame until frame + 4).toSet()
            for (k in visible) {
                if (lru[k] == null) {
                    lru.makeRoomFor(2_200_000, visible)
                    lru.put(k, k, 2_200_000)
                    live++
                }
            }
            lru.trim(visible)
            assertTrue("frame $frame: ${lru.totalBytes}", lru.totalBytes <= budget)
            assertEquals(lru.size, live)
        }
    }

    @Test
    fun clear_releases_everything() {
        val evicted = mutableListOf<String>()
        val lru = TileLru<String, Int>(budgetBytes = 100) { k, _ -> evicted.add(k) }
        lru.put("a", 1, 10)
        lru.put("b", 2, 10)
        lru.clear()
        assertEquals(setOf("a", "b"), evicted.toSet())
        assertEquals(0, lru.totalBytes)
        assertEquals(0, lru.size)
    }
}
