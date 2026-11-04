package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FilteredListMemoTest {
    private var now = 1_000L
    private val memo = FilteredListMemo { now }

    @Test fun sameSourceAndRulesReturnTheRememberedInstance() {
        val source = mutableListOf<Any?>("a", "b")
        val result = listOf("a")
        val rules = arrayOf<Any?>(setOf(1L), null)
        memo.remember(source, rules, result)
        assertSame(result, memo.reuse(source, arrayOf(rules[0], rules[1]))?.result)
    }

    @Test fun anEqualButDifferentSourceListIsNotAHit() {
        memo.remember(mutableListOf<Any?>("a"), emptyArray(), emptyList<Any>())
        assertNull(memo.reuse(mutableListOf<Any?>("a"), emptyArray()))
    }

    @Test fun aSourceThatChangedLengthIsRecomputedAndForgotten() {
        val source = mutableListOf<Any?>("a")
        memo.remember(source, emptyArray(), emptyList<Any>())
        source += "b"
        assertNull(memo.reuse(source, emptyArray()))
        source.removeAt(1)
        // 旧记录已在上一次未命中时丢弃，长度改回来也不会复活过期的结果。
        assertNull(memo.reuse(source, emptyArray()))
    }

    @Test fun aReplacedRuleSnapshotInvalidatesEvenWhenItsContentIsEqual() {
        val source = listOf("a")
        memo.remember(source, arrayOf(setOf(1L)), emptyList<Any>())
        assertNull(memo.reuse(source, arrayOf(setOf(1L))))
    }

    @Test fun entriesExpireAfterTheTimeBoundAndAClockGoingBackwardsIsNotTrusted() {
        val source = listOf("a")
        memo.remember(source, emptyArray(), emptyList<Any>())
        now += FilteredListMemo.MAX_AGE_MS
        assertNull(memo.reuse(source, emptyArray()))
        memo.remember(source, emptyArray(), emptyList<Any>())
        now -= 1
        assertNull(memo.reuse(source, emptyArray()))
    }

    @Test fun capacityIsBoundedAndTheOldestEntryIsEvictedFirst() {
        val sources = (0..FilteredListMemo.CAPACITY).map { listOf(it) }
        sources.forEach { memo.remember(it, emptyArray(), it) }
        assertEquals(FilteredListMemo.CAPACITY, memo.size)
        assertNull(memo.reuse(sources.first(), emptyArray()))
        assertSame(sources.last(), memo.reuse(sources.last(), emptyArray())?.result)
    }

    @Test fun rememberingTheSameSourceAgainReplacesItsEntry() {
        val source = listOf("a")
        memo.remember(source, emptyArray(), listOf(1))
        val newer = listOf(2)
        memo.remember(source, emptyArray(), newer)
        assertEquals(1, memo.size)
        assertSame(newer, memo.reuse(source, emptyArray())?.result)
    }

    @Test fun anUnsettledResultIsReturnedAsIsUntilItIsSettledUnderTheSameSource() {
        val source = listOf("a", "b")
        val filtered = listOf("a")
        memo.remember(source, emptyArray(), filtered, settled = false)
        val pending = requireNotNull(memo.reuse(source, emptyArray()))
        assertSame(filtered, pending.result)
        assertFalse(pending.settled)
        val arranged = listOf("a")
        memo.remember(source, emptyArray(), arranged)
        val settled = requireNotNull(memo.reuse(source, emptyArray()))
        assertSame(arranged, settled.result)
        assertTrue(settled.settled)
        assertEquals(1, memo.size)
    }
}
