package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.Assert.*
import org.junit.Test

class LatestValuePublisherTest {
    @Test fun `coalesces by key and confirms only successful values`() {
        val tasks = ArrayDeque<() -> Unit>()
        val writes = mutableListOf<String>()
        val queue = LatestValuePublisher<String, String>({ tasks.addLast(it) }) { key, value ->
            writes += "$key:$value"; true
        }
        queue.submit("home", "old"); queue.submit("home", "new"); queue.submit("mine", "mine")
        assertEquals(1, tasks.size)
        tasks.removeFirst().invoke()
        assertEquals(listOf("home:new", "mine:mine"), writes)
        queue.submit("home", "new")
        assertTrue(tasks.isEmpty())
    }

    @Test fun `failed content retries once and can be resubmitted without being marked published`() {
        val tasks = ArrayDeque<() -> Unit>()
        var attempts = 0
        var healthy = false
        val queue = LatestValuePublisher<String, String>({ tasks.addLast(it) }) { _, _ ->
            attempts++; healthy
        }
        queue.submit("home", "same"); tasks.removeFirst().invoke()
        assertEquals(2, attempts); assertTrue(tasks.isEmpty())
        healthy = true
        queue.submit("home", "same"); tasks.removeFirst().invoke()
        assertEquals(3, attempts)
        queue.submit("home", "same"); assertTrue(tasks.isEmpty())
    }

    @Test fun `new value arriving during publication is always published after old value`() {
        val tasks = ArrayDeque<() -> Unit>()
        val writes = mutableListOf<String>()
        lateinit var queue: LatestValuePublisher<String, String>
        queue = LatestValuePublisher({ tasks.addLast(it) }) { _, value ->
            if (value == "old") queue.submit("home", "new")
            writes += value; true
        }
        queue.submit("home", "old"); tasks.removeFirst().invoke()
        assertEquals(listOf("old", "new"), writes)
        assertTrue(tasks.isEmpty())
    }

    @Test fun `scheduler rejection does not discard a later retry`() {
        var reject = true
        val tasks = ArrayDeque<() -> Unit>()
        var writes = 0
        val queue = LatestValuePublisher<String, String>({ if (reject) error("rejected") else tasks.addLast(it) }) { _, _ -> writes++; true }
        assertFalse(queue.submit("home", "value"))
        reject = false
        assertTrue(queue.submit("home", "value")); tasks.removeFirst().invoke()
        assertEquals(1, writes)
    }
}
