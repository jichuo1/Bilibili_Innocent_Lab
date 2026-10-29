package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预算分批组件（CommentBindQueue / CommentBindBudget）在拿到真实宿主测量数据之前不接入生产路径。
 * 接入的人需要先更新这条契约，并在状态记录里写明依据的数据；见组件 KDoc。
 */
class CommentBindBudgetUnwiredContractTest {
    @Test fun productionBindPathDoesNotUseTheBudgetComponents() {
        val entry = SourceContract.read("hook/HookEntry.kt")
        assertFalse(entry.contains("CommentBindBudget"))
        assertFalse(entry.contains("CommentBindQueue"))
        // 现有队列仍是内联实现，批大小与上限未变。
        assertTrue(entry.contains("private val pendingCommentBinds = java.util.ArrayList<PendingCommentBind>()"))
    }

    @Test fun componentStatesWhyItIsNotWired() {
        val budget = SourceContract.read("hook/feature/CommentBindBudget.kt")
        assertTrue(budget.contains("**没有接入生产路径。**"))
        assertTrue(budget.contains("为什么暂不接入"))
    }
}
