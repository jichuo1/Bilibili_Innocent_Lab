package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 性能报告 P2-03：关注页每次 update 一次建立附着行索引，各遍复用；滚动时仍更新必需的 panel.place。 */
class FollowFeedUpdateIndexWiringContractTest {
    private val update = SourceContract.read("hook/hostui/follow/FollowFeedController.kt")
        .after("fun update() {").before("private fun updateBottomPadding()")

    @Test fun rowsAndReferenceKeysAreCollectedOnceThenReused() {
        assertEquals(1, Regex("""updateIndex\.rebuild\(""").findAll(update).count())
        assertEquals("the controller map is consulted once per child for the snapshot, plus the decoration pass's RowState",
            2, Regex("""rows\[""").findAll(update).count())
        assertFalse("no per-pass key allocation of the reference identity", update.contains("referenceKey(it)") || update.contains("::referenceKey"))
        // 清理离屏引用不再为每个存量引用重扫所有子 View。
        val stale = update.after("val staleReferences = references.iterator()").before("for (i in 0 until list.childCount)")
        assertTrue(stale.contains("updateIndex.hasReference(entry.key)"))
        assertFalse(stale.contains("list.getChildAt"))
        assertFalse(stale.contains("none {"))
    }

    @Test fun scrollingStillPlacesEveryPanelAndKeepsTheSurfaceBudget() {
        assertTrue(update.contains("panel.place(cardOptions, 0)"))
        assertTrue(update.contains("panel.place(referenceOptions, FollowFeedStyle.REFERENCE_INSET_DP)"))
        assertTrue(update.contains("FollowFeedStyle.MAX_CARD_SURFACES"))
        assertTrue(update.contains("generation++"))
        assertTrue(update.contains("updateBottomPadding()"))
    }

    @Test fun decorationPassFallsBackToTheCurrentRowWhenTheSnapshotIsStale() {
        assertTrue(update.contains("if (state.row === updateIndex.rowAt(i)) updateIndex.keyAt(i) else state.row?.referenceKey()"))
    }
}
