package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.os.Build
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSelectionCodec

/** 仅在模块 uid 内落盘经查询协议校验过的有界快照。 */
internal object MineComponentSnapshotStore {
    private const val KEY_SOURCE_PRESENT = "mine_component_scan_source_present"
    private const val KEY_TARGET_VERSION = "mine_component_scan_target_version"
    private const val KEY_TARGET_UPDATE_TIME = "mine_component_scan_target_update_time"
    private const val KEY_MODULE_VERSION = "mine_component_scan_module_version"

    /** "我的"页沿用旧键，其余面各自一个键，避免互相覆盖也不破坏旧数据。 */
    private fun snapshotKey(surface: String): String =
        if (surface == MineComponentSnapshotCodec.SURFACE_MINE) {
            FeaturePreferences.MINE_COMPONENT_SCAN_SNAPSHOT
        } else {
            FeaturePreferences.MINE_COMPONENT_SCAN_SNAPSHOT + "_" + surface
        }

    fun write(
        context: Context,
        payload: String,
        source: MineComponentSnapshotSource
    ): Boolean = runCatching {
        val prefsName = "${context.packageName}_preferences"
        write(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE), payload, source)
    }.getOrDefault(false)

    /** 只由用户发起的、已验真查询调用；来源与勾选一起保存，手填键不参与清理。 */
    internal fun write(
        prefs: SharedPreferences,
        payload: String,
        source: MineComponentSnapshotSource
    ): Boolean {
        val decoded = MineComponentSnapshotCodec.decodeOrNull(payload, allowLegacy = false)
            ?: return false
        if (!source.isComplete || decoded.entries.isEmpty()) return false
        return runCatching {
            val previous = storedSource(prefs, decoded.surface)
            val selectorsKey = ComponentSelectionReconciler.selectorsKey(decoded.surface)
            val raw = prefs.getString(selectorsKey, "").orEmpty()
            val selected = MineComponentSelectionCodec.decode(raw)
            val retained = ComponentSelectionReconciler.retain(
                selected, previous?.targetVersionCode, source.targetVersionCode, decoded
            )
            val editor = prefs.edit()
                .putString(snapshotKey(decoded.surface), payload)
                .putBoolean(sourceKey(KEY_SOURCE_PRESENT, decoded.surface), true)
                .putLong(sourceKey(KEY_TARGET_VERSION, decoded.surface), source.targetVersionCode)
                .putLong(sourceKey(KEY_TARGET_UPDATE_TIME, decoded.surface), source.targetUpdateTime)
                .putLong(sourceKey(KEY_MODULE_VERSION, decoded.surface), source.moduleVersionCode)
            if (retained != selected) {
                editor.putString(selectorsKey, MineComponentSelectionCodec.encode(retained))
            }
            editor.commit()
        }.getOrDefault(false)
    }

    private fun sourceKey(key: String, surface: String) = "${key}_$surface"

    private fun storedSource(prefs: SharedPreferences, surface: String): MineComponentSnapshotSource? {
        // 不再改写旧的全局来源：未迁移的其他面仍需要它判定自身版本变化。
        val perSurface = prefs.getBoolean(sourceKey(KEY_SOURCE_PRESENT, surface), false)
        if (!perSurface && !prefs.getBoolean(KEY_SOURCE_PRESENT, false)) return null
        fun key(value: String) = if (perSurface) sourceKey(value, surface) else value
        return MineComponentSnapshotSource(
            prefs.getLong(key(KEY_TARGET_VERSION), 0L),
            prefs.getLong(key(KEY_TARGET_UPDATE_TIME), 0L),
            prefs.getLong(key(KEY_MODULE_VERSION), 0L)
        )
    }

    fun read(
        context: Context,
        surface: String = MineComponentSnapshotCodec.SURFACE_MINE
    ): MineComponentSnapshot? = runCatching {
        val prefsName = "${context.packageName}_preferences"
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val payload = prefs.getString(snapshotKey(surface), null).orEmpty()
        val snapshot = MineComponentSnapshotCodec.decodeOrNull(payload)
            ?.takeIf { it.surface == surface }
            ?: return@runCatching null
        val storedSource = storedSource(prefs, surface) ?: return@runCatching snapshot
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
