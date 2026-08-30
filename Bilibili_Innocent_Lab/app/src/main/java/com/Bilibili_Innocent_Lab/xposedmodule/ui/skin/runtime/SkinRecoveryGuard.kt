package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SkinId

/** 皮肤恢复或切换状态机作出本次决定的原因。 */
internal enum class SkinRecoveryReason {
    READY,
    INCOMPLETE_PENDING_ROLLED_BACK,
    RENDERER_REVALIDATION_REQUIRED,
    MATERIAL_SELECTED,
    LIQUID_VALIDATION_PENDING,
    LIQUID_HEALTH_CONFIRMED,
    LIQUID_VALIDATION_FAILED,
    STALE_HEALTH_CONFIRMATION_IGNORED,
    STALE_VALIDATION_FAILURE_IGNORED
}

/**
 * 纯状态机决定。
 *
 * 当 [shouldPersist] 为 true 且 [state] 会启用 Liquid 时，调用方必须先成功持久化该状态，
 * 再安装 Liquid 渲染器；否则进程崩溃后无法识别未完成的 pending。
 */
internal data class SkinRecoveryDecision(
    val state: SkinPreferenceState,
    val shouldPersist: Boolean,
    val reason: SkinRecoveryReason
) {
    val selectedSkin: SkinId
        get() = state.selectedSkin
}

/**
 * Liquid 两阶段激活与进程启动恢复策略。
 *
 * 本对象只计算状态，不执行 I/O、重建 Activity 或探测渲染能力，因此可由 JVM 单测完整覆盖。
 */
internal object SkinRecoveryGuard {

    /**
     * 解析一次进程冷启动。
     *
     * 未完成 pending 回退到最后已知健康皮肤；已确认 Liquid 遇到渲染器版本变化时，先把
     * Material You 设为回退点，再进入新版本 pending。
     */
    fun onProcessStart(
        persisted: SkinPreferenceState,
        currentLiquidRendererVersion: Int,
        newActivationAttemptId: String
    ): SkinRecoveryDecision {
        requireRendererVersion(currentLiquidRendererVersion)
        requireActivationAttemptId(newActivationAttemptId)

        if (persisted.pendingSkin != null) {
            val rolledBack = stableStateFor(
                skin = persisted.lastKnownGoodSkin,
                rendererVersion = persisted.liquidRendererVersion
            )
            return SkinRecoveryDecision(
                state = rolledBack,
                shouldPersist = rolledBack != persisted,
                reason = SkinRecoveryReason.INCOMPLETE_PENDING_ROLLED_BACK
            )
        }

        if (persisted.isLiquidConfirmed &&
            persisted.liquidRendererVersion != currentLiquidRendererVersion
        ) {
            return SkinRecoveryDecision(
                state = SkinPreferenceState.pendingLiquid(
                    currentLiquidRendererVersion,
                    newActivationAttemptId
                ),
                shouldPersist = true,
                reason = SkinRecoveryReason.RENDERER_REVALIDATION_REQUIRED
            )
        }

        return SkinRecoveryDecision(
            state = persisted,
            shouldPersist = false,
            reason = SkinRecoveryReason.READY
        )
    }

    /** 开始用户选择；Material You 立即成为稳定状态，Liquid 必须先进入 pending。 */
    fun beginSelection(
        current: SkinPreferenceState,
        target: SkinId,
        currentLiquidRendererVersion: Int,
        newActivationAttemptId: String
    ): SkinRecoveryDecision {
        requireRendererVersion(currentLiquidRendererVersion)
        requireActivationAttemptId(newActivationAttemptId)

        if (target == SkinId.MATERIAL_YOU) {
            return SkinRecoveryDecision(
                state = SkinPreferenceState.MATERIAL_DEFAULT,
                shouldPersist = current != SkinPreferenceState.MATERIAL_DEFAULT,
                reason = SkinRecoveryReason.MATERIAL_SELECTED
            )
        }

        if (current.isLiquidConfirmed &&
            current.liquidRendererVersion == currentLiquidRendererVersion
        ) {
            return SkinRecoveryDecision(
                state = current,
                shouldPersist = false,
                reason = SkinRecoveryReason.READY
            )
        }

        val pending = SkinPreferenceState.pendingLiquid(
            currentLiquidRendererVersion,
            newActivationAttemptId
        )
        return SkinRecoveryDecision(
            state = pending,
            shouldPersist = pending != current,
            reason = SkinRecoveryReason.LIQUID_VALIDATION_PENDING
        )
    }

    /**
     * 提升与当前渲染器版本匹配的 pending Liquid。
     *
     * Material 切换后的迟到回调或旧渲染器回调只会被忽略，不得把用户选择改回 Liquid。
     */
    fun confirmLiquidHealthy(
        current: SkinPreferenceState,
        confirmedRendererVersion: Int,
        activationAttemptId: String
    ): SkinRecoveryDecision {
        requireRendererVersion(confirmedRendererVersion)
        requireActivationAttemptId(activationAttemptId)

        if (current.isLiquidConfirmed &&
            current.liquidRendererVersion == confirmedRendererVersion &&
            current.activationAttemptId == activationAttemptId
        ) {
            return SkinRecoveryDecision(
                state = current,
                shouldPersist = false,
                reason = SkinRecoveryReason.READY
            )
        }

        if (!current.isLiquidPending ||
            current.liquidRendererVersion != confirmedRendererVersion ||
            current.activationAttemptId != activationAttemptId
        ) {
            return SkinRecoveryDecision(
                state = current,
                shouldPersist = false,
                reason = SkinRecoveryReason.STALE_HEALTH_CONFIRMATION_IGNORED
            )
        }

        return SkinRecoveryDecision(
            state = SkinPreferenceState.confirmedLiquid(
                confirmedRendererVersion,
                activationAttemptId
            ),
            shouldPersist = true,
            reason = SkinRecoveryReason.LIQUID_HEALTH_CONFIRMED
        )
    }

    /** 只有当前激活尝试的失败才回退；renderer 实例所有权由 Repository 额外校验。 */
    fun onLiquidValidationFailed(
        current: SkinPreferenceState,
        failedRendererVersion: Int,
        activationAttemptId: String
    ): SkinRecoveryDecision {
        requireRendererVersion(failedRendererVersion)
        requireActivationAttemptId(activationAttemptId)

        if (current.selectedSkin != SkinId.LIQUID ||
            current.liquidRendererVersion != failedRendererVersion ||
            current.activationAttemptId != activationAttemptId
        ) {
            return SkinRecoveryDecision(
                state = current,
                shouldPersist = false,
                reason = SkinRecoveryReason.STALE_VALIDATION_FAILURE_IGNORED
            )
        }

        return SkinRecoveryDecision(
            state = SkinPreferenceState.MATERIAL_DEFAULT,
            shouldPersist = true,
            reason = SkinRecoveryReason.LIQUID_VALIDATION_FAILED
        )
    }

    private fun stableStateFor(skin: SkinId, rendererVersion: Int): SkinPreferenceState =
        when (skin) {
            SkinId.MATERIAL_YOU -> SkinPreferenceState.MATERIAL_DEFAULT
            SkinId.LIQUID -> error(
                "Canonical pending Liquid cannot use Liquid as its rollback state: $rendererVersion"
            )
        }

    private fun requireRendererVersion(rendererVersion: Int) {
        require(rendererVersion >= 0) { "Liquid renderer version must not be negative" }
    }

    private fun requireActivationAttemptId(activationAttemptId: String) {
        require(SkinPreferenceState.isValidActivationAttemptId(activationAttemptId)) {
            "Liquid activation attempt id must be non-blank and at most 128 characters"
        }
    }
}
