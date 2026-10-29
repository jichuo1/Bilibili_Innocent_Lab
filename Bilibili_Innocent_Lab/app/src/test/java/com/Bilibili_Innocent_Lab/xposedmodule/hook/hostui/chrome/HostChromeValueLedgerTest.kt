package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import java.util.Random
import java.util.WeakHashMap

/** 性能报告 P2-06：稳定重复 apply 不再分配相同的 Entry；所有权与恢复语义不变。 */
class HostChromeValueLedgerTest {
    private class Box(var value: Int)

    /** 优化前的实现，逐字保留，用来对照语义并比较分配。 */
    private class LegacyLedger<K : Any, V>(private val read: (K) -> V, private val write: (K, V) -> Unit) {
        private class Entry<V>(val base: V, val applied: V)
        private val entries = WeakHashMap<K, Entry<V>>()
        fun owns(key: K) = entries.containsKey(key)
        fun apply(key: K, target: (base: V) -> V): Boolean {
            val current = read(key)
            val previous = entries[key]
            val base = if (previous != null && current == previous.applied) previous.base else current
            val next = target(base)
            entries[key] = Entry(base, next)
            if (current == next) return false
            write(key, next)
            return true
        }
        fun restore(key: K): Boolean {
            val entry = entries.remove(key) ?: return false
            if (entry.applied == entry.base || read(key) != entry.applied) return false
            write(key, entry.base)
            return true
        }
    }

    private fun ledger() = HostChromeValueLedger<Box, Int>({ it.value }, { box, value -> box.value = value })
    private fun legacy() = LegacyLedger<Box, Int>({ it.value }, { box, value -> box.value = value })

    @Test fun firstApplyEstablishesOwnershipEvenWhenNoWriteIsNeeded() {
        val box = Box(300)
        val ledger = ledger()
        assertFalse("the host value already equals the target: nothing to write", ledger.apply(box) { 300 })
        assertTrue("but the key is owned from the first observation", ledger.owns(box))
        assertFalse(ledger.apply(box) { 300 })
        assertFalse("applied == base: restore writes nothing", ledger.restore(box))
        assertFalse(ledger.owns(box))
    }

    @Test fun aHostRewriteRecordsTheNewBaseWithoutReusingTheOldEntry() {
        val box = Box(0)
        val ledger = ledger()
        ledger.apply(box) { it + 300 }
        ledger.apply(box) { it + 300 } // 稳定重复
        box.value = 8
        assertTrue(ledger.apply(box) { it + 300 })
        assertEquals(308, box.value)
        box.value = 40
        assertFalse("restore leaves a value the host wrote after us", ledger.restore(box))
        assertEquals(40, box.value)
    }

    @Test fun changedTargetReplacesTheEntryAndStillRestoresTheOriginalBase() {
        val box = Box(12)
        val ledger = ledger()
        ledger.apply(box) { it + 300 }
        ledger.apply(box) { it + 300 }
        assertTrue(ledger.apply(box) { it + 250 })
        assertEquals(262, box.value)
        assertTrue(ledger.restore(box))
        assertEquals(12, box.value)
    }

    @Test fun randomOperationsMatchTheLegacyLedgerExactly() {
        val random = Random(20261029L)
        val boxes = List(3) { val initial = random.nextInt(50); Box(initial) to Box(initial) }
        val ledger = ledger(); val legacy = legacy()
        repeat(30_000) {
            val (a, b) = boxes[random.nextInt(boxes.size)]
            when (random.nextInt(6)) {
                0 -> { val host = random.nextInt(60); a.value = host; b.value = host } // 宿主改写
                1, 2, 3 -> {
                    val shift = random.nextInt(4) * 100
                    val x = ledger.apply(a) { it + shift }; val y = legacy.apply(b) { it + shift }
                    assertEquals("apply result", y, x)
                }
                4 -> assertEquals("restore result", legacy.restore(b), ledger.restore(a))
                else -> assertEquals("owns", legacy.owns(b), ledger.owns(a))
            }
            assertEquals("value drift", b.value, a.value)
        }
    }

    @Test fun steadyStateApplyAllocatesLessThanTheLegacyLedger() {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        fun allocated() = threads.getThreadAllocatedBytes(Thread.currentThread().id)
        val box = Box(7); val twin = Box(7)
        val ledger = ledger(); val legacy = legacy()
        val target: (Int) -> Int = { it + 100 } // 小整数落在装箱缓存内，隔离出 Entry 本身的分配
        repeat(20_000) { ledger.apply(box, target); legacy.apply(twin, target) } // 预热 JIT 与 WeakHashMap
        val rounds = 100_000
        var start = allocated()
        repeat(rounds) { ledger.apply(box, target) }
        val optimized = allocated() - start
        start = allocated()
        repeat(rounds) { legacy.apply(twin, target) }
        val before = allocated() - start
        println("HostChromeValueLedger steady-state apply: optimized=${optimized / rounds.toDouble()} B/call legacy=${before / rounds.toDouble()} B/call")
        assertTrue("legacy allocates an Entry per call: ${before / rounds.toDouble()} B", before / rounds.toDouble() >= 16.0)
        assertTrue("optimized steady state allocates nothing per call: ${optimized / rounds.toDouble()} B", optimized / rounds.toDouble() < 2.0)
    }
}
