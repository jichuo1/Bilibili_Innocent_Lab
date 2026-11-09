package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.TileFlightTable.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TileFlightTableTest {
    private class Sub(val name: String)
    private fun table(capacity: Int = 32) = TileFlightTable<String, Sub>(capacity)

    @Test fun secondColdMissOnTheSameKeyJoinsInsteadOfStartingAnotherGeneration() {
        val table = table()
        val a = Sub("a"); val b = Sub("b")
        assertEquals(Outcome.CREATED, table.request("t0", a))
        assertEquals(Outcome.JOINED, table.request("t0", b))
        assertEquals(1, table.pendingCount())
        assertEquals(2, table.subscriberCount("t0"))
        assertEquals("t0", table.take())
        assertNull("单个任务只能被取走一次", table.take())
        assertEquals(listOf(a, b), table.finish("t0"))
        assertEquals(0, table.pendingCount())
        assertEquals(1L, table.created); assertEquals(1L, table.joined); assertEquals(1L, table.completed)
    }

    @Test fun concurrentColdMissesProduceOneProducerAndEverySubscriberIsNotifiedOnce() {
        val subscribers = 8
        val keys = (0 until 6).map { "tile-$it" }
        val table = table()
        val renders = ConcurrentHashMap<String, AtomicInteger>()
        val notified = ConcurrentHashMap<Pair<Sub, String>, AtomicInteger>()
        val all = List(subscribers) { Sub("s$it") }
        val barrier = CyclicBarrier(subscribers)
        val requesters = all.map { sub ->
            Thread {
                barrier.await(5, TimeUnit.SECONDS)
                keys.forEach { table.request(it, sub) }
            }
        }
        requesters.forEach(Thread::start)
        requesters.forEach { it.join(10_000) }
        assertEquals(keys.size.toLong(), table.created)
        assertEquals((subscribers * keys.size - keys.size).toLong(), table.joined)
        // 两个工作线程消费（与 Scene 的固定 2 线程池一致）。
        val workers = List(2) {
            Thread {
                while (true) {
                    val key = table.take() ?: break
                    renders.getOrPut(key) { AtomicInteger() }.incrementAndGet()
                    table.finish(key).forEach { notified.getOrPut(it to key) { AtomicInteger() }.incrementAndGet() }
                }
            }
        }
        workers.forEach(Thread::start)
        workers.forEach { it.join(10_000) }
        assertEquals(keys.toSet(), renders.keys)
        renders.values.forEach { assertEquals("每个 key 只生成一次", 1, it.get()) }
        for (sub in all) for (key in keys) assertEquals("$sub $key", 1, notified[sub to key]?.get())
        assertEquals(0, table.pendingCount())
    }

    @Test fun cancellingOneSubscriberDoesNotRemoveTheFlightTheOtherStillNeeds() {
        val table = table()
        val a = Sub("a"); val b = Sub("b")
        table.request("t", a); table.request("t", b)
        table.retain(a, emptyList())
        assertTrue(table.isPending("t"))
        assertEquals(1, table.subscriberCount("t"))
        assertEquals("t", table.take())
        assertEquals(listOf(b), table.finish("t"))
    }

    @Test fun unstartedFlightsNobodyWantsAnymoreAreDroppedButStartedOnesFinish() {
        val table = table()
        val s = Sub("scene")
        listOf("a", "b", "c").forEach { table.request(it, s) }
        assertEquals("a", table.take()) // a 已开始
        // 快速 fling：视口移到 d/e，旧的 a/b/c 都不再需要。
        table.retain(s, listOf("d", "e"))
        assertEquals(2L, table.cancelled) // b、c 未开始：移出待办
        assertTrue("已开始的任务继续（结果会进共享缓存）", table.isPending("a"))
        assertFalse(table.isPending("b")); assertFalse(table.isPending("c"))
        assertTrue(table.finish("a").isEmpty()) // 但不再发布给放弃它的订阅者
        table.request("d", s); table.request("e", s)
        assertEquals(listOf("d", "e"), listOf(table.take(), table.take()))
    }

    @Test fun anOrphanedStartedFlightIsReusedWhenTheViewportReturns() {
        val table = table()
        val s = Sub("scene")
        table.request("a", s)
        assertEquals("a", table.take())
        table.retain(s, emptyList())
        assertEquals(Outcome.JOINED, table.request("a", s))
        assertEquals(1L, table.created)
        assertEquals(listOf(s), table.finish("a"))
    }

    @Test fun failedGenerationEndsEveryValidSubscriberAndLeavesNoFlightBehind() {
        val table = table()
        val a = Sub("a"); val b = Sub("b")
        table.request("t", a); table.request("t", b)
        table.take()
        val ended = table.finish("t") // 失败路径同样调用 finish：订阅者拿到「结束」，由调用方按 null 位图处理
        assertEquals(setOf(a, b), ended.toSet())
        assertEquals(0, table.pendingCount())
        assertFalse(table.isPending("t"))
        assertEquals("失败后下一次请求重新创建，而不是卡在残留任务上", Outcome.CREATED, table.request("t", a))
        assertTrue(table.finish("missing").isEmpty())
    }

    @Test fun pendingCountIsBoundedAndJoiningStillWorksAtCapacity() {
        val table = table(capacity = 3)
        val s = Sub("s")
        assertEquals(Outcome.CREATED, table.request("1", s)); assertEquals(Outcome.CREATED, table.request("2", s))
        assertEquals(Outcome.CREATED, table.request("3", s))
        assertEquals(Outcome.REJECTED, table.request("4", s))
        assertEquals(Outcome.JOINED, table.request("3", Sub("other")))
        assertEquals(1L, table.rejected)
        table.take(); table.finish("1")
        assertEquals(Outcome.CREATED, table.request("4", s))
        assertEquals(3, table.pendingCount())
    }

    @Test fun flightsWhoseSubscribersAreAllGoneAreSkippedAtTakeTime() {
        val table = table()
        val dead = Sub("dead"); val alive = Sub("alive")
        table.request("x", dead)
        table.request("y", dead); table.request("y", alive)
        assertEquals("y", table.take { it === alive })
        assertEquals(1L, table.cancelled)
        assertFalse(table.isPending("x"))
    }

    @Test fun randomOperationsKeepTheInvariants() {
        val random = Random(20261029L)
        val table = table(capacity = 8)
        val subs = List(4) { Sub("s$it") }
        val keys = (0 until 12).map { "k$it" }
        val started = Collections.synchronizedSet(HashSet<String>())
        repeat(20_000) {
            when (random.nextInt(5)) {
                0, 1 -> table.request(keys[random.nextInt(keys.size)], subs[random.nextInt(subs.size)])
                2 -> table.retain(subs[random.nextInt(subs.size)], keys.shuffled(random).take(random.nextInt(5)))
                3 -> table.take()?.let { started += it }
                else -> started.toList().takeIf { it.isNotEmpty() }?.let { list ->
                    val key = list[random.nextInt(list.size)]
                    started -= key
                    table.finish(key)
                }
            }
            assertTrue("pending ${table.pendingCount()} exceeds capacity", table.pendingCount() <= 8)
            for (key in keys) if (table.isPending(key) && key !in started)
                assertTrue("未开始的任务必须至少有一个订阅者: $key", table.subscriberCount(key) > 0)
        }
    }

    @Test fun createdEqualsCompletedPlusCancelledPlusPending() {
        val table = table()
        val s = Sub("s")
        repeat(10) { table.request("k$it", s) }
        repeat(3) { table.finish(table.take()!!) }
        table.retain(s, listOf("k9"))
        assertEquals(10L, table.created)
        assertEquals(3L, table.completed)
        assertEquals(6L, table.cancelled)
        assertEquals(table.created, table.completed + table.cancelled + table.pendingCount())
    }
}
