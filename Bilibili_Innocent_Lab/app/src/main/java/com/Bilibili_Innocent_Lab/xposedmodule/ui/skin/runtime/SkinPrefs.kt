package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/** Android 偏好读取结果；只保留可诊断的枚举和纯状态，不持有 Context。 */
internal data class SkinPrefsReadResult(
    val state: SkinPreferenceState,
    val decodeIssue: SkinPreferenceDecodeIssue,
    val recoveryReason: SkinRecoveryReason?,
    val repairPersisted: Boolean,
    val storageAvailable: Boolean
)

/**
 * 模块界面皮肤的独立偏好适配器。
 *
 * 它不使用 Yuki prefs、不进入设置备份或 NPatch 快照；所有写入均检查同步 commit 结果。
 */
internal object SkinPrefs {
    internal const val PREF_FILE = "ui_skin_preferences"

    /**
     * 设置存储迁移只导出已稳定确认的选择；进程在 Liquid 两阶段激活中断时，
     * 按既有恢复语义回到 Material You，避免把 pending 尝试跨构建放大。
     */
    fun readStableForMigration(context: Context): SkinPreferenceState {
        val preferences = runCatching { preferences(context) }.getOrNull()
            ?: return SkinPreferenceState.MATERIAL_DEFAULT
        val decoded = runCatching {
            SkinPreferenceCodec.decode(preferences.all.toMap())
        }.getOrNull() ?: return SkinPreferenceState.MATERIAL_DEFAULT
        if (decoded.needsRepair) return SkinPreferenceState.MATERIAL_DEFAULT
        return decoded.state.takeUnless { it.isLiquidPending }
            ?: SkinPreferenceState.MATERIAL_DEFAULT
    }

    /**
     * 读取并修复皮肤状态。
     *
     * [currentLiquidRendererVersion] 为 null 表示当前版本尚未开放 Liquid renderer（M0）；此时
     * 仍会回滚未完成 pending，但不会把已确认 Liquid 错误地标记为新版本待验证。
     */
    fun readForProcessStart(
        context: Context,
        currentLiquidRendererVersion: Int?
    ): SkinPrefsReadResult {
        val preferences = runCatching { preferences(context) }.getOrNull()
            ?: return storageFailure()
        val decoded = runCatching {
            SkinPreferenceCodec.decode(preferences.all.toMap())
        }.getOrElse {
            SkinPreferenceDecodeResult(
                state = SkinPreferenceState.MATERIAL_DEFAULT,
                needsRepair = true,
                issue = SkinPreferenceDecodeIssue.TYPE_MISMATCH
            )
        }

        var state = decoded.state
        var persisted = false
        if (decoded.needsRepair) {
            persisted = write(preferences, SkinPreferenceState.MATERIAL_DEFAULT)
            state = SkinPreferenceState.MATERIAL_DEFAULT
            if (!persisted) return storageFailure(decoded.issue)
        }

        val shouldResolveLaunch = state.pendingSkin != null || currentLiquidRendererVersion != null
        if (shouldResolveLaunch) {
            val rendererVersion = currentLiquidRendererVersion ?: state.liquidRendererVersion
            val recovery = runCatching {
                SkinRecoveryGuard.onProcessStart(
                    persisted = state,
                    currentLiquidRendererVersion = rendererVersion,
                    newActivationAttemptId = UUID.randomUUID().toString()
                )
            }.getOrElse { return storageFailure(decoded.issue) }
            if (recovery.shouldPersist) {
                if (!write(preferences, recovery.state)) {
                    return storageFailure(decoded.issue, recovery.reason)
                }
                persisted = true
            }
            state = recovery.state
            return SkinPrefsReadResult(
                state = state,
                decodeIssue = decoded.issue,
                recoveryReason = recovery.reason,
                repairPersisted = persisted,
                storageAvailable = true
            )
        }

        return SkinPrefsReadResult(
            state = state,
            decodeIssue = decoded.issue,
            recoveryReason = null,
            repairPersisted = persisted,
            storageAvailable = true
        )
    }

    /** 写入规范状态；调用方只有在返回 true 后才能据此切换 renderer 或重建 Activity。 */
    fun write(context: Context, state: SkinPreferenceState): Boolean =
        runCatching { write(preferences(context), state) }.getOrDefault(false)

    private fun preferences(context: Context): SharedPreferences {
        val appContext = context.applicationContext ?: context
        return appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    }

    @SuppressLint("UseKtx") // 必须检查 commit() 返回值；KTX edit 会隐藏结果。
    private fun write(
        preferences: SharedPreferences,
        state: SkinPreferenceState
    ): Boolean = runCatching {
        val encoded = SkinPreferenceCodec.encode(state)
        val editor = preferences.edit()
            .remove(SkinPreferenceCodec.KEY_SCHEMA_VERSION)
            .remove(SkinPreferenceCodec.KEY_SELECTED_SKIN)
            .remove(SkinPreferenceCodec.KEY_LAST_KNOWN_GOOD_SKIN)
            .remove(SkinPreferenceCodec.KEY_PENDING_SKIN)
            .remove(SkinPreferenceCodec.KEY_LIQUID_RENDERER_VERSION)
            .remove(SkinPreferenceCodec.KEY_ACTIVATION_ATTEMPT_ID)
        encoded.forEach { (key, value) ->
            when (value) {
                is Int -> editor.putInt(key, value)
                is String -> editor.putString(key, value)
                null -> editor.remove(key)
                else -> error("Unsupported skin preference type for $key")
            }
        }
        if (!editor.commit()) return@runCatching false
        val readBack = SkinPreferenceCodec.decode(preferences.all.toMap())
        !readBack.needsRepair && readBack.state == state
    }.getOrDefault(false)

    private fun storageFailure(
        issue: SkinPreferenceDecodeIssue = SkinPreferenceDecodeIssue.TYPE_MISMATCH,
        recoveryReason: SkinRecoveryReason? = null
    ) = SkinPrefsReadResult(
        state = SkinPreferenceState.MATERIAL_DEFAULT,
        decodeIssue = issue,
        recoveryReason = recoveryReason,
        repairPersisted = false,
        storageAvailable = false
    )
}
