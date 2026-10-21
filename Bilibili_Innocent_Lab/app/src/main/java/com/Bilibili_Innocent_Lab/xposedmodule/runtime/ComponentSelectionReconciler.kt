package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec

/** 勾选面的存储键目录。扫描只证明已观察到的条目，不能以未观察到为由删除用户选择。 */
internal object ComponentSelectionReconciler {
    /**
     * 勾选面 → 勾选存储键。**新增一个勾选面就必须在这里加一行**。
     *
     * 2026-09-15 真机故障：组件库资源池面加进了 [MineComponentSnapshotCodec.ALLOWED_SURFACES]，
     * 却漏了这张表，于是 [selectorsKey] 走到 `error(...)`；那个异常被
     * `MineComponentSnapshotStore.write` 的 `runCatching` 吞成 `false`，
     * 一路变成 `STORE_FAILED` —— 面板照常弹出，但每次都提示"模块缓存写入失败"，
     * 快照也从来没落过盘。用 Map 而不是 when 是为了能被
     * [ComponentSelectionReconciliationTest] 逐面点名，漏登记会红在单测而不是真机。
     *
     * `section_picks` / `author_picks` 不在这里：它们是反馈面板的**草稿面**，
     * 落到 RecommendationBlocklistDraft 等用户确认，不走勾选存储这条路。
     * （注意别和 `ACCUMULATING_SURFACES` 混为一谈——`component_pools` 两边都在：
     * 它既要跨分片累积，也有自己的勾选存储键。）
     */
    val SELECTOR_KEYS: Map<String, String> = mapOf(
        MineComponentSnapshotCodec.SURFACE_MINE to FeaturePreferences.MINE_COMPONENT_HIDDEN_SELECTORS,
        MineComponentSnapshotCodec.SURFACE_BOTTOM_BAR to FeaturePreferences.BOTTOM_BAR_HIDDEN_SELECTORS,
        MineComponentSnapshotCodec.SURFACE_HOME_TABS to FeaturePreferences.HOME_TAB_HIDDEN_SELECTORS,
        MineComponentSnapshotCodec.SURFACE_HOME_COMPONENTS to FeaturePreferences.HOME_COMPONENT_HIDDEN_SELECTORS,
        MineComponentSnapshotCodec.SURFACE_COMPONENT_POOLS to FeaturePreferences.COMPONENT_POOL_BLOCKED_SELECTORS
    )

    fun selectorsKey(surface: String): String =
        SELECTOR_KEYS[surface] ?: error("Unsupported scan surface: $surface")
}
