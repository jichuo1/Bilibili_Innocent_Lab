package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec

/** 仅在模块 uid 内落盘经查询协议校验过的有界快照。 */
internal object MineComponentSnapshotStore {
    private const val KEY_SOURCE_PRESENT = "mine_component_scan_source_present"
    private const val KEY_TARGET_VERSION = "mine_component_scan_target_version"
    private const val KEY_TARGET_UPDATE_TIME = "mine_component_scan_target_update_time"
    private const val KEY_MODULE_VERSION = "mine_component_scan_module_version"

    fun write(
        context: Context,
        payload: String,
        source: MineComponentSnapshotSource
    ): Boolean {
        MineComponentSnapshotCodec.decodeOrNull(payload, allowLegacy = false) ?: return false
        if (!source.isComplete) return false
        val prefsName = "${context.packageName}_preferences"
        return runCatching {
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit()
                .putString(FeaturePreferences.MINE_COMPONENT_SCAN_SNAPSHOT, payload)
                .putBoolean(KEY_SOURCE_PRESENT, true)
                .putLong(KEY_TARGET_VERSION, source.targetVersionCode)
                .putLong(KEY_TARGET_UPDATE_TIME, source.targetUpdateTime)
                .putLong(KEY_MODULE_VERSION, source.moduleVersionCode)
                .commit()
        }.getOrDefault(false)
    }

    fun read(context: Context): MineComponentSnapshot? = runCatching {
        val prefsName = "${context.packageName}_preferences"
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val payload = prefs.getString(
            FeaturePreferences.MINE_COMPONENT_SCAN_SNAPSHOT,
            null
        ).orEmpty()
        val snapshot = MineComponentSnapshotCodec.decodeOrNull(payload) ?: return@runCatching null
        if (!prefs.getBoolean(KEY_SOURCE_PRESENT, false)) return@runCatching snapshot

        val storedSource = MineComponentSnapshotSource(
            targetVersionCode = prefs.getLong(KEY_TARGET_VERSION, 0L),
            targetUpdateTime = prefs.getLong(KEY_TARGET_UPDATE_TIME, 0L),
            moduleVersionCode = prefs.getLong(KEY_MODULE_VERSION, 0L)
        )
        val currentSource = currentSource(context) ?: return@runCatching null
        snapshot.takeIf { storedSource == currentSource }
    }.getOrNull()

    private fun currentSource(context: Context): MineComponentSnapshotSource? = runCatching {
        val info = context.packageManager.getPackageInfo(
            MineComponentSnapshotQueryContract.TARGET_PACKAGE,
            0
        )
        MineComponentSnapshotSource(
            targetVersionCode = info.versionCodeCompat(),
            targetUpdateTime = info.lastUpdateTime,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong()
        ).takeIf { it.isComplete }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
}
