package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SkinId

/** 解码来源或需要修复的原因，供诊断状态直接展示。 */
internal enum class SkinPreferenceDecodeIssue {
    NONE,
    MISSING,
    MISSING_FIELD,
    TYPE_MISMATCH,
    UNSUPPORTED_SCHEMA,
    UNKNOWN_SKIN,
    NEGATIVE_RENDERER_VERSION,
    INVALID_ACTIVATION_ATTEMPT,
    INCONSISTENT_STATE
}

/**
 * 纯 Kotlin 偏好解码结果。
 *
 * [needsRepair] 为 true 时，[state] 必定是 Material You 安全默认值；调用方可尝试把该默认值
 * 写回独立 SharedPreferences。完全缺失是首次安装的正常状态，不标记为损坏。
 */
internal data class SkinPreferenceDecodeResult(
    val state: SkinPreferenceState,
    val needsRepair: Boolean,
    val issue: SkinPreferenceDecodeIssue
)

/**
 * SkinPreferences 的稳定 Map 协议。
 *
 * Android 层以后只需把 `SharedPreferences.all` 传给 [decode]，并按 [encode] 的键值原子提交；
 * 本对象不依赖 Android，也不会把错误类型隐式转换为合法值。
 */
internal object SkinPreferenceCodec {
    const val CURRENT_SCHEMA_VERSION = 1

    const val KEY_SCHEMA_VERSION = "schema_version"
    const val KEY_SELECTED_SKIN = "selected_skin"
    const val KEY_LAST_KNOWN_GOOD_SKIN = "last_known_good_skin"
    const val KEY_PENDING_SKIN = "pending_skin"
    const val KEY_LIQUID_RENDERER_VERSION = "liquid_renderer_version"
    const val KEY_ACTIVATION_ATTEMPT_ID = "activation_attempt_id"

    private val requiredKeys = setOf(
        KEY_SCHEMA_VERSION,
        KEY_SELECTED_SKIN,
        KEY_LAST_KNOWN_GOOD_SKIN,
        KEY_LIQUID_RENDERER_VERSION
    )
    private val knownKeys = requiredKeys + setOf(
        KEY_PENDING_SKIN,
        KEY_ACTIVATION_ATTEMPT_ID
    )

    /** 将规范状态编码为可直接写入 SharedPreferences 的原始键值。 */
    fun encode(state: SkinPreferenceState): Map<String, Any?> = buildMap {
        put(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
        put(KEY_SELECTED_SKIN, state.selectedSkin.storageValue)
        put(KEY_LAST_KNOWN_GOOD_SKIN, state.lastKnownGoodSkin.storageValue)
        state.pendingSkin?.let { put(KEY_PENDING_SKIN, it.storageValue) }
        put(KEY_LIQUID_RENDERER_VERSION, state.liquidRendererVersion)
        state.activationAttemptId?.let { put(KEY_ACTIVATION_ATTEMPT_ID, it) }
    }

    /** 严格解码原始偏好；任何已知字段损坏都封闭回退到 Material You。 */
    fun decode(values: Map<String, Any?>): SkinPreferenceDecodeResult {
        if (knownKeys.none { values.containsKey(it) }) {
            return SkinPreferenceDecodeResult(
                state = SkinPreferenceState.MATERIAL_DEFAULT,
                needsRepair = false,
                issue = SkinPreferenceDecodeIssue.MISSING
            )
        }
        if (requiredKeys.any { !values.containsKey(it) }) {
            return invalid(SkinPreferenceDecodeIssue.MISSING_FIELD)
        }

        val schemaVersion = values[KEY_SCHEMA_VERSION]
        if (schemaVersion !is Int) return invalid(SkinPreferenceDecodeIssue.TYPE_MISMATCH)
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            return invalid(SkinPreferenceDecodeIssue.UNSUPPORTED_SCHEMA)
        }

        val selectedRaw = values[KEY_SELECTED_SKIN]
        val lastKnownGoodRaw = values[KEY_LAST_KNOWN_GOOD_SKIN]
        val rendererVersion = values[KEY_LIQUID_RENDERER_VERSION]
        if (selectedRaw !is String || lastKnownGoodRaw !is String || rendererVersion !is Int) {
            return invalid(SkinPreferenceDecodeIssue.TYPE_MISMATCH)
        }

        val selected = SkinId.fromStorageValue(selectedRaw)
            ?: return invalid(SkinPreferenceDecodeIssue.UNKNOWN_SKIN)
        val lastKnownGood = SkinId.fromStorageValue(lastKnownGoodRaw)
            ?: return invalid(SkinPreferenceDecodeIssue.UNKNOWN_SKIN)
        val pending = when (val pendingRaw = values[KEY_PENDING_SKIN]) {
            null -> null
            is String -> SkinId.fromStorageValue(pendingRaw)
                ?: return invalid(SkinPreferenceDecodeIssue.UNKNOWN_SKIN)
            else -> return invalid(SkinPreferenceDecodeIssue.TYPE_MISMATCH)
        }
        if (rendererVersion < 0) {
            return invalid(SkinPreferenceDecodeIssue.NEGATIVE_RENDERER_VERSION)
        }
        val activationAttemptId = when (val raw = values[KEY_ACTIVATION_ATTEMPT_ID]) {
            null -> null
            is String -> raw
            else -> return invalid(SkinPreferenceDecodeIssue.TYPE_MISMATCH)
        }
        if (activationAttemptId != null &&
            !SkinPreferenceState.isValidActivationAttemptId(activationAttemptId)
        ) {
            return invalid(SkinPreferenceDecodeIssue.INVALID_ACTIVATION_ATTEMPT)
        }

        val state = when {
            selected == SkinId.MATERIAL_YOU &&
                lastKnownGood == SkinId.MATERIAL_YOU &&
                pending == null &&
                rendererVersion == 0 &&
                activationAttemptId == null -> SkinPreferenceState.MATERIAL_DEFAULT

            selected == SkinId.LIQUID &&
                lastKnownGood == SkinId.LIQUID &&
                pending == null &&
                activationAttemptId != null -> SkinPreferenceState.confirmedLiquid(
                    rendererVersion,
                    activationAttemptId
                )

            selected == SkinId.LIQUID &&
                lastKnownGood == SkinId.MATERIAL_YOU &&
                pending == SkinId.LIQUID &&
                activationAttemptId != null -> SkinPreferenceState.pendingLiquid(
                    rendererVersion,
                    activationAttemptId
                )

            else -> return invalid(SkinPreferenceDecodeIssue.INCONSISTENT_STATE)
        }
        return SkinPreferenceDecodeResult(
            state = state,
            needsRepair = false,
            issue = SkinPreferenceDecodeIssue.NONE
        )
    }

    private fun invalid(issue: SkinPreferenceDecodeIssue) = SkinPreferenceDecodeResult(
        state = SkinPreferenceState.MATERIAL_DEFAULT,
        needsRepair = true,
        issue = issue
    )
}
