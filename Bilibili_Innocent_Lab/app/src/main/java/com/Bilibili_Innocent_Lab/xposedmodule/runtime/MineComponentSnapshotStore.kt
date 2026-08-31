package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.Context
import androidx.core.content.edit
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec

/** 仅在模块 uid 内落盘宿主上报的有界快照。 */
internal object MineComponentSnapshotStore {
    fun write(context: Context, payload: String): Boolean {
        MineComponentSnapshotCodec.decodeOrNull(payload, allowLegacy = false) ?: return false
        val prefsName = "${context.packageName}_preferences"
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit(commit = true) {
            putString(FeaturePreferences.MINE_COMPONENT_SCAN_SNAPSHOT, payload)
        }
        return true
    }
}
