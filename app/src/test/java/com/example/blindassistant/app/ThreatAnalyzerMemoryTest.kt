package com.example.blindassistant.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreatAnalyzerMemoryTest {

    private fun makeBoundedMap(maxSize: Int): LinkedHashMap<String, Long> {
        return object : LinkedHashMap<String, Long>(maxSize + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Long>) = size > maxSize
        }
    }

    @Test
    fun `bounded map stays at max 50 entries after 200 inserts`() {
        val map = makeBoundedMap(50)
        repeat(200) { i -> map["key_$i"] = i.toLong() }
        assertTrue("Map size ${map.size} exceeds 50", map.size <= 50)
    }

    @Test
    fun `bounded map evicts oldest entry when full`() {
        val map = makeBoundedMap(3)
        map["a"] = 1L
        map["b"] = 2L
        map["c"] = 3L
        map["d"] = 4L
        assertFalse("Key 'a' should have been evicted", map.containsKey("a"))
        assertTrue("Key 'd' should be present", map.containsKey("d"))
    }
}
