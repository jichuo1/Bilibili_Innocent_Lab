package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SkinId

/**
 * 已持久化的皮肤选择状态。
 *
 * Liquid 激活必须先进入 pending：此时 [selectedSkin] 是待验证的 Liquid，
 * [lastKnownGoodSkin] 保持为 Material You。健康确认后才能把 Liquid 提升为最后已知健康皮肤。
 * Material You 是无 pending 的安全状态，且渲染器版本固定为 0。
 */
internal data class SkinPreferenceState(
    val selectedSkin: SkinId,
    val lastKnownGoodSkin: SkinId,
    val pendingSkin: SkinId?,
    val liquidRendererVersion: Int,
    val activationAttemptId: String?
) {
    init {
        require(liquidRendererVersion >= 0) { "Liquid renderer version must not be negative" }
        require(isCanonical()) { "Skin preference state is not canonical" }
    }

    /** 当前是否正在验证 Liquid。 */
    val isLiquidPending: Boolean
        get() = selectedSkin == SkinId.LIQUID && pendingSkin == SkinId.LIQUID

    /** 当前是否为已经健康确认的 Liquid。 */
    val isLiquidConfirmed: Boolean
        get() = selectedSkin == SkinId.LIQUID &&
            lastKnownGoodSkin == SkinId.LIQUID &&
            pendingSkin == null

    private fun isCanonical(): Boolean = when (selectedSkin) {
        SkinId.MATERIAL_YOU ->
            lastKnownGoodSkin == SkinId.MATERIAL_YOU &&
                pendingSkin == null &&
                liquidRendererVersion == 0 &&
                activationAttemptId == null

        SkinId.LIQUID -> when (pendingSkin) {
            null -> lastKnownGoodSkin == SkinId.LIQUID &&
                isValidActivationAttemptId(activationAttemptId)
            SkinId.LIQUID -> lastKnownGoodSkin == SkinId.MATERIAL_YOU &&
                isValidActivationAttemptId(activationAttemptId)
            SkinId.MATERIAL_YOU -> false
        }
    }

    companion object {
        /** 首次安装、损坏回退和 Liquid 失败后的唯一安全默认值。 */
        val MATERIAL_DEFAULT = SkinPreferenceState(
            selectedSkin = SkinId.MATERIAL_YOU,
            lastKnownGoodSkin = SkinId.MATERIAL_YOU,
            pendingSkin = null,
            liquidRendererVersion = 0,
            activationAttemptId = null
        )

        /** 构造尚未完成健康确认的 Liquid 状态。 */
        fun pendingLiquid(
            rendererVersion: Int,
            activationAttemptId: String
        ): SkinPreferenceState = SkinPreferenceState(
            selectedSkin = SkinId.LIQUID,
            lastKnownGoodSkin = SkinId.MATERIAL_YOU,
            pendingSkin = SkinId.LIQUID,
            liquidRendererVersion = rendererVersion,
            activationAttemptId = activationAttemptId
        )

        /** 构造已经完成健康确认的 Liquid 状态。 */
        fun confirmedLiquid(
            rendererVersion: Int,
            activationAttemptId: String
        ): SkinPreferenceState = SkinPreferenceState(
            selectedSkin = SkinId.LIQUID,
            lastKnownGoodSkin = SkinId.LIQUID,
            pendingSkin = null,
            liquidRendererVersion = rendererVersion,
            activationAttemptId = activationAttemptId
        )

        /** 尝试标识只作为不透明令牌比较；限制长度避免损坏偏好造成无界字符串常驻。 */
        fun isValidActivationAttemptId(value: String?): Boolean =
            !value.isNullOrBlank() && value.length <= 128
    }
}
