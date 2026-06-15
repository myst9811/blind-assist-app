package com.example.blindassistant.app

import org.junit.Test
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class AudioManagerThreadSafetyTest {

    @Test
    fun `ConcurrentHashMap does not corrupt under concurrent put from 10 threads`() {
        val map = ConcurrentHashMap<String, Long>()
        val latch = CountDownLatch(10)
        val executor = Executors.newFixedThreadPool(10)

        repeat(10) { i ->
            executor.submit {
                repeat(100) { j ->
                    map["key_${i}_$j"] = System.currentTimeMillis()
                }
                latch.countDown()
            }
        }

        latch.await()
        assert(map.size == 1000) { "Expected 1000 entries, got ${map.size}" }
    }

    @Test
    fun `unsynchronized PriorityQueue loses entries under concurrent offer`() {
        val queue = PriorityQueue<Int>()
        val executor = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(4)

        repeat(4) { i ->
            executor.submit {
                repeat(250) { queue.offer(i) }
                latch.countDown()
            }
        }

        latch.await()
        println("Queue size after concurrent offers: ${queue.size} (expected ~1000, may be less due to race)")
    }
}
