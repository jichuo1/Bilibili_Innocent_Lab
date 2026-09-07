package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec

/** 仅归并勾选 selector；复用宿主实际匹配的键，不根据标题猜测不同组件等价。 */
internal object ComponentSelectionReconciler {
    fun selectorsKey(surface: String): String = when (surface) {
        MineComponentSnapshotCodec.SURFACE_MINE -> FeaturePreferences.MINE_COMPONENT_HIDDEN_SELECTORS
        MineComponentSnapshotCodec.SURFACE_BOTTOM_BAR -> FeaturePreferences.BOTTOM_BAR_HIDDEN_SELECTORS
        MineComponentSnapshotCodec.SURFACE_HOME_TABS -> FeaturePreferences.HOME_TAB_HIDDEN_SELECTORS
        MineComponentSnapshotCodec.SURFACE_HOME_COMPONENTS -> FeaturePreferences.HOME_COMPONENT_HIDDEN_SELECTORS
        else -> error("Unsupported scan surface: $surface")
    }

    fun retain(
        selected: Set<String>,
        previousVersion: Long?,
        currentVersion: Long,
        snapshot: MineComponentSnapshot
    ): Set<String> {
        if (previousVersion == null || previousVersion <= 0L || currentVersion <= 0L ||
            previousVersion == currentVersion || snapshot.entries.isEmpty()
        ) return selected
        // showing=false 仍是扫描到的组件；不可勾选也不等于不存在。
        val present = snapshot.entries.mapTo(HashSet()) { it.key }
        return selected.intersect(present)
    }
}
