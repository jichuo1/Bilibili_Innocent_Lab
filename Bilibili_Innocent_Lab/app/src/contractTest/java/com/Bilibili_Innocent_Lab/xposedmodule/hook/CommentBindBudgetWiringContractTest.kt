package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 生产 drain 必须复用经过伪时钟验证的预算与归还逻辑；真实设备帧时收益另验。 */
class CommentBindBudgetWiringContractTest {
    private val entry = SourceContract.read("hook/HookEntry.kt")
    private val drain = entry.after("private fun drainCommentBinds() {")

    @Test fun productionBindPathUsesTheBudgetQueueAndSafeGiveBack() {
        assertTrue(entry.contains("private val pendingCommentBinds = CommentBindQueue<PendingCommentBind>()"))
        assertTrue(entry.contains("pendingCommentBinds.enqueue("))
        assertTrue(drain.contains("pendingCommentBinds.takeBatch(drainStartedNs, commentBindBudgetPolicy.starvationLimitNs)"))
        assertTrue(drain.contains("CommentBindBudget.run("))
        assertTrue(drain.contains("pendingCommentBinds.giveBack(outcome.unprocessed)"))
        assertFalse(entry.contains("private val pendingCommentBinds = java.util.ArrayList<PendingCommentBind>()"))
    }

    @Test fun starvationSelectionCoversTheWholeQueueWithoutChangingTheNormalLifoPath() {
        val budget = SourceContract.read("hook/feature/CommentBindBudget.kt")
        val prioritized = budget.after("fun takeBatch(nowNanos: Long, starvationLimitNanos: Long)")
        assertTrue(prioritized.contains("entries.indexOfFirst"))
        assertTrue(prioritized.contains("entries.removeAt(starvedIndex)"))
        assertTrue(prioritized.contains("if (starvedIndex < 0) return takeBatch()"))
        assertTrue(drain.contains("latestCommentBindingGeneration[v] == generation"))
        assertTrue(drain.contains("if (v.isAttachedToWindow)"))
    }
}
