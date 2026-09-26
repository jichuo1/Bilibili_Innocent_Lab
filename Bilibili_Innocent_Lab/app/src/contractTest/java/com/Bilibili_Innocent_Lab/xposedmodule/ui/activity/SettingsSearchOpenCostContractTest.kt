package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索气泡打开那一帧不遍历设置视图树：收集可搜索文字真机 7.5–11ms，打开时是空查询、用不到，
 * 放在打开帧里会把气泡首帧推迟约 20ms（2026-09-27 atrace）。
 */
class SettingsSearchOpenCostContractTest {
    private val dialogs = SourceContract.read("ui/activity/ToolbarBubbleDialogs.kt")
    private val search = dialogs.after("internal fun MainActivity.showSettingsSearchDialog(")
        .before("private fun MainActivity.highlightedSettingsSearchText(")

    @Test fun targetsAreCollectedLazily() {
        assertTrue(search.contains("val runtimeTargets by lazy(LazyThreadSafetyMode.NONE) { collectSettingsSearchTargets() }"))
        assertTrue(search.contains("val targetByKey by lazy(LazyThreadSafetyMode.NONE)"))
    }

    @Test fun blankQueryNeverTouchesTheTargets() {
        val render = search.after("fun renderResults(query: String) {").before("results.forEach")
        assertTrue(render.contains("val results = if (query.isBlank()) emptyList() else SettingsSearchMatcher.searchMatches("))
    }
}
