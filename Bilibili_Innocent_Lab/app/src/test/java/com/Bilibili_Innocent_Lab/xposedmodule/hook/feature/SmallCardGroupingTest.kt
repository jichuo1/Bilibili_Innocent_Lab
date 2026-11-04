package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SmallCardGroupingTest {
    private data class Card(val name: String, val small: Boolean?)

    private fun s(name: String) = Card(name, true)
    private fun l(name: String) = Card(name, false)
    private val style: (Any?) -> Boolean? = { (it as? Card)?.small }

    @Test fun discardMirrorsTheHostWindowRuleForPairsAndWiderGroups() {
        // 宿主：小卡按顺序凑满一组；遇到大卡或列表结束时未凑满的整组丢弃。
        assertEquals(0, SmallCardGrouping.discarded(listOf(s("1"), s("2"), l("A"), s("3"), s("4")), 2, style))
        assertEquals(2, SmallCardGrouping.discarded(listOf(s("1"), l("A"), s("2"), s("3"), s("4")), 2, style))
        // 三列：大卡前待定的两张与末尾两张都凑不满三张，宿主整组丢弃。
        assertEquals(4, SmallCardGrouping.discarded(listOf(s("1"), s("2"), l("A"), s("3"), s("4")), 3, style))
        assertEquals(0, SmallCardGrouping.discarded(listOf(s("1"), s("2"), s("3"), l("A")), 3, style))
        assertEquals(0, SmallCardGrouping.discarded(listOf(l("A"), l("B")), 2, style))
        assertEquals(0, SmallCardGrouping.discarded(emptyList<Any>(), 2, style))
    }

    @Test fun unknownStylesOrAnInvalidGroupSizeAreNotGuessed() {
        assertNull(SmallCardGrouping.discarded(listOf(s("1"), Card("?", null)), 2, style))
        assertNull(SmallCardGrouping.discarded(listOf(s("1"), Any()), 2, style))
        assertNull(SmallCardGrouping.discarded(listOf(s("1")), 0, style))
    }

    @Test fun regroupLetsAnIncompleteGroupFloatPastLargeCardsAndKeepsBothOrders() {
        val items = listOf(s("1"), l("A"), s("2"), s("3"), l("B"), s("4"))
        val regrouped = SmallCardGrouping.regroup(items, 2, style)
        assertEquals(listOf(l("A"), s("1"), s("2"), l("B"), s("3"), s("4")), regrouped)
        assertEquals(0, SmallCardGrouping.discarded(regrouped, 2, style))
        assertEquals(items.toSet(), regrouped.toSet())
        assertEquals(items.filter { (it as Card).small == true }, regrouped.filter { (it as Card).small == true })
        assertEquals(items.filter { (it as Card).small == false }, regrouped.filter { (it as Card).small == false })
    }

    @Test fun regroupUsesTheHostGroupSize() {
        val items = listOf(s("1"), s("2"), l("A"), s("3"))
        assertEquals(listOf(l("A"), s("1"), s("2"), s("3")), SmallCardGrouping.regroup(items, 3, style))
    }

    @Test fun completeGroupsUnknownStylesAndSingleColumnsKeepTheOriginalInstance() {
        val complete = listOf(s("1"), s("2"), l("A"), s("3"), s("4"))
        assertSame(complete, SmallCardGrouping.regroup(complete, 2, style))
        val unknown = listOf(s("1"), l("A"), Card("?", null), s("2"))
        assertSame(unknown, SmallCardGrouping.regroup(unknown, 2, style))
        val single = listOf(s("1"), l("A"), s("2"))
        assertSame(single, SmallCardGrouping.regroup(single, 1, style))
    }

    @Test fun aTrailingIncompleteGroupStaysLastAndIsNotCarriedAcrossPages() {
        val items = listOf(s("1"), l("A"), s("2"), s("3"))
        val regrouped = SmallCardGrouping.regroup(items, 2, style)
        assertEquals(listOf(l("A"), s("1"), s("2"), s("3")), regrouped)
        assertEquals(1, SmallCardGrouping.discarded(regrouped, 2, style))
    }
}
