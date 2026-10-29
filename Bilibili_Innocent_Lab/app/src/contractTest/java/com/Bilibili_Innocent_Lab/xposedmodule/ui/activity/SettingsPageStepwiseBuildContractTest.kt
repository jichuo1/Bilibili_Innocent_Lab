package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 性能报告 P1-02：设置页的最小工作单元是步骤，不是整页。
 * 计划本身由 SettingsPagePlanTest 覆盖；这里锁定接线、预建合同和不能回退成整页构建的约束。
 */
class SettingsPageStepwiseBuildContractTest {
    private val presenter = SettingsUiSource.file("SettingsHomePresenter")

    @Test fun prewarmDrivesOneBoundedUnitPerFrameAndKeepsTheCursor() {
        val submit = SettingsUiSource.functions(presenter, "submitPrewarm").single()
        // maxUnitsPerWork 必须覆盖整页计划；仍为 1 会在第一个单元未完成时按 UNIT_LIMIT 误丢弃。
        assertTrue(submit.contains("maxUnitsPerWork = SettingsPagePlan.MAX_UNITS"))
        assertFalse(submit.contains("maxUnitsPerWork = 1"))
        val work = submit.after("PrewarmWork(").before("ready = {}")
        assertTrue(work.contains("advancePage(index, untilComplete = false)"))
        assertFalse("预建不得再同步补完整页", work.contains("ensurePage("))
        // 刚推进过的页面仍是「当前」结果，否则完成的单元会被当成过期丢弃。
        assertTrue(work.contains("(ran || !builtPages[index])"))
        assertTrue(work.contains("ran = true"))
        // 超预算只让出本轮；未完成页面的计划游标保留。
        val discard = submit.after("discard = { reason ->")
        assertTrue(discard.contains("PrewarmStop.OVER_BUDGET"))
        assertTrue(discard.contains("queue.cancel()"))
        assertFalse(discard.contains("plans["))
    }

    @Test fun userFacingEntriesCompleteThePageSynchronouslyBeforeUse() {
        val ensure = SettingsUiSource.functions(presenter, "ensurePage").single()
        assertTrue(ensure.contains("advancePage(index, untilComplete = true)"))
        val advance = SettingsUiSource.functions(presenter, "advancePage").single()
        assertTrue(advance.contains("if (untilComplete) plan.complete() else plan.advance()"))
        // 只有计划完成（含收尾）才记为 built；未完成的页面不会被当成已建好。
        assertTrue(advance.indexOf("if (!plan.isComplete) return false") in 0 until advance.indexOf("builtPages[index] = true"))
        assertTrue(advance.contains("failedPages[index] = failure.javaClass.name"))
        assertTrue(presenter.contains("plans.fill(null)"))
    }

    @Test fun everyCardRegistersItsAdvancedCategoriesAsDeferredStepsInOrder() {
        for ((card, count) in listOf("purifySettingsCard" to 9, "enhancementSettingsCard" to 6)) {
            val body = SettingsUiSource.function(card)
            val deferred = Regex("""deferStep \{ (\w+)\(advancedCategoryScope\(AdvancedSettingsCategory\.(\w+)\)""").findAll(body).toList()
            assertEquals("$card should defer every category", count, deferred.size)
            // 壳 + 分类必须在上限之内。
            assertTrue(1 + deferred.size <= 16)
            // 分类调用不得再直接写在卡片里（那样会回到整页构建）。
            assertFalse(body.contains("\n                purifyHomeCategory(") || body.contains("\n                enhanceBrowsingCategory("))
        }
    }

    @Test fun pagePlansReadPreferenceSnapshotsAtStepTimeNotAtPlanCreation() {
        val plan = SettingsUiSource.function("settingsPagePlan")
        assertFalse("预读的快照会被后续步骤当作过期值使用", plan.contains("val uiSettings ="))
        assertTrue(plan.contains("fun uiSettings() = ModuleUiSettings.read("))
        assertTrue(plan.contains("enhancementSettingsCard(page(), ::uiSettings)"))
        assertTrue(plan.contains("experimentalFeaturesCard(this, uiSettings())"))
        val enhancement = SettingsUiSource.function("enhancementSettingsCard")
        assertTrue(enhancement.contains("deferStep { enhancePlaybackCategory(advancedCategoryScope(AdvancedSettingsCategory.ENHANCE_PLAYBACK), uiSettings()) }"))
    }

    @Test fun modulePageIsSplitIntoIndependentCardsInTheOriginalOrder() {
        val plan = SettingsUiSource.function("settingsPagePlan")
        val module = plan.after("4 -> listOf(").before("else -> error")
        val order = listOf("activationPageCard(this)", "settingsBackupEntryCard(this)", "module:backup-gap",
            "experimentalFeaturesCard(this, uiSettings())", "module:experimental-gap", "logSettingsCard(this)", "module:log-gap",
            "about-module\"", "module:about-gap", "about-module-extension")
        var last = -1
        for (item in order) {
            val at = module.indexOf(item)
            assertTrue("$item missing", at >= 0)
            assertTrue("$item out of order", at > last)
            last = at
        }
        assertEquals(4, Regex("""with\(page\(\)\) \{""").findAll(module).count())
    }

    @Test fun finishRunsOncePerPageAfterAllSteps() {
        val plan = SettingsUiSource.function("settingsPagePlan")
        val finish = plan.after("return SettingsPagePlan(steps) {")
        for (call in listOf("applyRestoredSettingsExpansion(index)", "styleHomeControls(parent)", "settingDependencies.start(settingModelPreferences)",
            "refreshSettingAvailability()", "renderActivationUi(); renderNoRootUi()")) {
            assertTrue(call, finish.contains(call))
        }
        // 收尾不在步骤里。
        assertFalse(plan.before("return SettingsPagePlan(steps)").contains("styleHomeControls"))
    }
}
