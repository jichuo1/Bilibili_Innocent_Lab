package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentBindQueue.Entry
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预算分批的纯逻辑。假时钟的纳秒数是手工推进的，只验证调度与归还语义，不是设备耗时；
 * 生产 drain 与这里共用同一实现；这里不构造 View，也不把假时钟数值当作设备性能。
 */
class CommentBindBudgetTest {
    private class FakeClock(var now: Long = 0L) : CommentBindProbe.Clock {
        override fun nanoTime() = now
    }

    private class View(val name: String)

    private var serial = 0L
    private fun entry(view: View, enqueuedAt: Long = 0L) = Entry(view, ++serial, enqueuedAt, view.name)
    private fun CommentBindQueue<String>.names() = snapshot().map { it.payload }
    private fun ms(value: Long) = value * 1_000_000L

    /** 与现有 drainCommentBinds / scheduleCommentBind 内联实现逐行对应的参照物。 */
    private class Reference {
        val list = ArrayList<Entry<String>>()
        fun enqueue(e: Entry<String>) {
            for (i in list.lastIndex downTo 0) if (list[i].key === e.key) list.removeAt(i)
            list.add(e)
            while (list.size > 96) list.removeAt(0)
        }
        fun take(): List<Entry<String>> {
            val take = minOf(list.size, 6)
            val b = ArrayList<Entry<String>>(take)
            val start = list.size - take
            for (i in start until list.size) b.add(list[i])
            list.subList(start, list.size).clear()
            return b
        }
    }

    @Test fun withoutAnyBudgetTheQueueBehavesExactlyLikeTheInlineImplementation() {
        val random = Random(20261029)
        val views = List(40) { View("v$it") }
        val queue = CommentBindQueue<String>()
        val reference = Reference()
        repeat(5_000) {
            if (random.nextInt(10) < 7) {
                val e = entry(views[random.nextInt(views.size)])
                val expected = reference.list.count { it.key === e.key }
                val result = queue.enqueue(e)
                reference.enqueue(e)
                assertEquals(expected, result.replaced)
            } else {
                val got = queue.takeBatch().map { it.generation }
                assertEquals(reference.take().map { it.generation }, got)
            }
            assertEquals(reference.list.map { it.generation }, queue.snapshot().map { it.generation })
            assertTrue(queue.size <= 96)
        }
    }

    @Test fun capacityEvictsTheOldestAndCountsIt() {
        val queue = CommentBindQueue<String>()
        val views = List(100) { View("v$it") }
        var overflow = 0
        views.forEach { overflow += queue.enqueue(entry(it)).overflowDropped }
        assertEquals(96, queue.size)
        assertEquals(4, overflow)
        assertEquals("v4", queue.names().first())
        assertEquals("v99", queue.names().last())
    }

    @Test fun batchIsTheNewestSixInEnqueueOrder() {
        val queue = CommentBindQueue<String>()
        val views = List(10) { View("v$it") }
        views.forEach { queue.enqueue(entry(it)) }
        assertEquals(listOf("v4", "v5", "v6", "v7", "v8", "v9"), queue.takeBatch().map { it.payload })
        assertEquals(listOf("v0", "v1", "v2", "v3"), queue.names())
    }

    @Test fun everyBatchHasAtLeastOneItemEvenWithAZeroBudget() {
        val clock = FakeClock()
        val batch = List(6) { entry(View("v$it")) }
        val processed = ArrayList<String>()
        val out = CommentBindBudget.run(batch, startNs = 0, CommentBindBudget.Policy(0L, Long.MAX_VALUE), clock) { processed += it.payload }
        assertEquals(listOf("v0"), processed)
        assertEquals(1, out.processed)
        assertEquals(listOf("v1", "v2", "v3", "v4", "v5"), out.unprocessed.map { it.payload })
        assertTrue(out.stoppedEarly)
    }

    @Test fun stopsBetweenItemsOnceTheBudgetIsSpentAndReportsTheRest() {
        val clock = FakeClock()
        val batch = List(6) { entry(View("v$it")) }
        val processed = ArrayList<String>()
        // 每项 3ms，预算 8ms：第 1..3 项后累计 9ms，才在第 4 项前停止。
        val out = CommentBindBudget.run(batch, 0, CommentBindBudget.Policy(ms(8), Long.MAX_VALUE), clock) {
            processed += it.payload; clock.now += ms(3)
        }
        assertEquals(listOf("v0", "v1", "v2"), processed)
        assertEquals(listOf("v3", "v4", "v5"), out.unprocessed.map { it.payload })
    }

    @Test fun aBatchThatFitsTheBudgetIsProcessedCompletely() {
        val clock = FakeClock()
        val batch = List(6) { entry(View("v$it")) }
        val out = CommentBindBudget.run(batch, 0, CommentBindBudget.Policy(ms(100), Long.MAX_VALUE), clock) { clock.now += ms(1) }
        assertEquals(6, out.processed)
        assertFalse(out.stoppedEarly)
    }

    @Test fun anItemOverTheBudgetStillRunsToCompletionBecauseItemsAreNotInterruptible() {
        val clock = FakeClock()
        val batch = List(3) { entry(View("v$it")) }
        val out = CommentBindBudget.run(batch, 0, CommentBindBudget.Policy(ms(2), Long.MAX_VALUE), clock) { clock.now += ms(40) }
        assertEquals("one 40ms item overran a 2ms budget; only a resumable cursor could change that", 1, out.processed)
        assertEquals(ms(40), clock.now)
    }

    @Test fun givenBackItemsKeepLifoPositionBelowTasksQueuedWhileTheBatchRan() {
        val clock = FakeClock()
        val queue = CommentBindQueue<String>()
        List(8) { View("v$it") }.forEach { queue.enqueue(entry(it)) }
        val batch = queue.takeBatch() // v2..v7
        val lateA = View("lateA"); val lateB = View("lateB")
        val out = CommentBindBudget.run(batch, 0, CommentBindBudget.Policy(ms(5), Long.MAX_VALUE), clock) {
            if (it.payload == "v2") { queue.enqueue(entry(lateA)); queue.enqueue(entry(lateB)) } // 处理期间又有新任务入队
            clock.now += ms(3)
        }
        val back = queue.giveBack(out.unprocessed)
        assertEquals(CommentBindQueue.GivenBack(restored = 4, supersededDropped = 0, overflowDropped = 0), back)
        // 归还项在新任务之下、旧队列内容之上；下一批仍是最新的那些。
        assertEquals(listOf("v0", "v1", "v4", "v5", "v6", "v7", "lateA", "lateB"), queue.names())
        assertEquals(listOf("v4", "v5", "v6", "v7", "lateA", "lateB"), queue.takeBatch().map { it.payload })
    }

    @Test fun aNewerTaskForTheSameViewIsNeverOverwrittenByTheReturnedOne() {
        val queue = CommentBindQueue<String>()
        val a = View("a"); val b = View("b"); val c = View("c")
        listOf(a, b, c).forEach { queue.enqueue(entry(it)) }
        val batch = queue.takeBatch()
        val newerB = entry(b) // b 在批处理期间被重新绑定
        queue.enqueue(newerB)
        val back = queue.giveBack(batch.filter { it.key !== a }) // a 已经处理完，b、c 是未处理项
        assertEquals(CommentBindQueue.GivenBack(restored = 1, supersededDropped = 1, overflowDropped = 0), back)
        assertEquals(listOf("c", "b"), queue.names())
        assertSame("the surviving b entry is the newer generation", newerB, queue.snapshot().last())
    }

    @Test fun givingBackRespectsTheCapAndEvictsTheOldest() {
        val queue = CommentBindQueue<String>()
        List(96) { View("v$it") }.forEach { queue.enqueue(entry(it)) }
        val batch = queue.takeBatch() // v90..v95
        List(96) { View("n$it") }.forEach { queue.enqueue(entry(it)) } // 队列重新满到 96，全部比批次新
        assertEquals(96, queue.size)
        val back = queue.giveBack(batch)
        assertEquals(96, queue.size)
        assertEquals("old returned items are the first evicted", 6, back.overflowDropped)
        assertEquals("n0", queue.names().first())
    }

    @Test fun repeatedPartialDrainsAlwaysFinishAndLoseNothing() {
        // 不变量：被处理 + 被淘汰 + 被替换 = 入队总数；每轮至少前进一项，所以终会清空。
        val random = Random(7)
        val clock = FakeClock()
        val queue = CommentBindQueue<String>()
        val views = List(30) { View("v$it") }
        var enqueued = 0; var replaced = 0; var overflow = 0; var superseded = 0; var processed = 0
        repeat(20_000) {
            if (random.nextInt(3) > 0) {
                val r = queue.enqueue(entry(views[random.nextInt(views.size)], enqueuedAt = clock.now.coerceAtLeast(1)))
                enqueued++; replaced += r.replaced; overflow += r.overflowDropped
            } else {
                val batch = queue.takeBatch()
                val budget = ms(random.nextInt(8).toLong())
                val out = CommentBindBudget.run(batch, clock.now, CommentBindBudget.Policy(budget, ms(50)), clock) {
                    clock.now += ms(1 + random.nextInt(3).toLong())
                    if (random.nextInt(4) == 0) queue.enqueue(entry(it.key as View, clock.now)).also { r -> enqueued++; replaced += r.replaced; overflow += r.overflowDropped }
                }
                processed += out.processed
                val back = queue.giveBack(out.unprocessed)
                superseded += back.supersededDropped; overflow += back.overflowDropped
                assertTrue("progress", batch.isEmpty() || out.processed >= 1)
            }
            assertTrue(queue.size <= 96)
            assertEquals("unique key per queued view", queue.snapshot().map { it.key }.toSet().size, queue.size)
            assertEquals("ascending generation order", queue.snapshot().map { it.generation }.sorted(), queue.snapshot().map { it.generation })
        }
        // 清空：每轮至少前进一项，所以有限轮数内结束。
        var guard = 0
        while (queue.size > 0 && guard++ < 1_000) {
            val batch = queue.takeBatch()
            val out = CommentBindBudget.run(batch, clock.now, CommentBindBudget.Policy(0L, Long.MAX_VALUE), clock) { clock.now += ms(1) }
            processed += out.processed
            val back = queue.giveBack(out.unprocessed)
            superseded += back.supersededDropped; overflow += back.overflowDropped
        }
        assertEquals(0, queue.size)
        assertEquals(enqueued, processed + replaced + overflow + superseded)
    }

    @Test fun anOldWaitingItemLiftsTheBudgetSoLatecomersCannotStarveIt() {
        val clock = FakeClock(now = ms(1_000))
        val old = entry(View("old"), enqueuedAt = ms(100)) // 已等 900ms
        val fresh = List(5) { entry(View("f$it"), enqueuedAt = ms(999)) }
        val policy = CommentBindBudget.Policy(budgetNs = ms(1), starvationLimitNs = ms(500))
        val processed = ArrayList<String>()
        val out = CommentBindBudget.run(listOf(old) + fresh, clock.now, policy, clock) { processed += it.payload; clock.now += ms(5) }
        assertTrue(out.starvationOverride)
        assertEquals(6, processed.size)
        assertFalse(out.stoppedEarly)
        // 没有久等的任务：预算照常生效。
        val clock2 = FakeClock(now = ms(1_000))
        val calm = CommentBindBudget.run(fresh, clock2.now, policy, clock2) { clock2.now += ms(5) }
        assertFalse(calm.starvationOverride)
        assertEquals(1, calm.processed)
    }

    @Test fun untimedEntriesNeverTriggerTheStarvationOverride() {
        val clock = FakeClock(now = ms(10_000))
        val batch = List(3) { entry(View("v$it"), enqueuedAt = 0L) }
        val out = CommentBindBudget.run(batch, clock.now, CommentBindBudget.Policy(0L, ms(1)), clock) { }
        assertFalse(out.starvationOverride)
        assertEquals(1, out.processed)
    }

    @Test fun starvationPriorityLooksBeyondTheNewestSixAndCompletesTheOldestItem() {
        val queue = CommentBindQueue<String>()
        queue.enqueue(entry(View("old"), enqueuedAt = ms(100)))
        List(10) { View("f$it") }.forEach { queue.enqueue(entry(it, enqueuedAt = ms(999))) }

        val batch = queue.takeBatch(nowNanos = ms(1_000), starvationLimitNanos = ms(500))
        assertEquals(listOf("old", "f5", "f6", "f7", "f8", "f9"), batch.map { it.payload })
        assertEquals(listOf("f0", "f1", "f2", "f3", "f4"), queue.names())

        val clock = FakeClock(now = ms(1_000))
        val processed = ArrayList<String>()
        val out = CommentBindBudget.run(batch, clock.now, CommentBindBudget.Policy(0L, ms(500)), clock) {
            processed += it.payload
            clock.now += ms(1)
        }
        assertTrue(out.starvationOverride)
        assertEquals(batch.map { it.payload }, processed)
        assertFalse(out.stoppedEarly)
    }

    @Test fun prioritizedSelectionWithoutAStarvedItemMatchesTheOriginalLifoBatch() {
        val queue = CommentBindQueue<String>()
        List(10) { View("v$it") }.forEach { queue.enqueue(entry(it, enqueuedAt = ms(999))) }
        val batch = queue.takeBatch(nowNanos = ms(1_000), starvationLimitNanos = ms(500))
        assertEquals(listOf("v4", "v5", "v6", "v7", "v8", "v9"), batch.map { it.payload })
    }
}
