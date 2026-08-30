package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SkinId

/**
 * 单个 Liquid renderer 会话的不透明所有权凭证。
 *
 * [activationAttemptId] 隔离用户选择/重试，[ownerId] 隔离同一尝试下的 Activity 或 renderer
 * 实例。凭证只在当前进程有效，不进入 SharedPreferences。
 */
internal data class LiquidRenderSessionOwner(
    val liquidRendererVersion: Int,
    val activationAttemptId: String,
    val ownerId: String
) {
    init {
        require(liquidRendererVersion >= 0) { "Liquid renderer version must not be negative" }
        require(SkinPreferenceState.isValidActivationAttemptId(activationAttemptId)) {
            "Liquid activation attempt id is invalid"
        }
        require(SkinPreferenceState.isValidActivationAttemptId(ownerId)) {
            "Liquid render owner id is invalid"
        }
    }
}

/**
 * 当前进程唯一的 Liquid renderer 所有权登记表。
 *
 * 新 renderer 取得所有权时会原子替换旧 owner；旧 Activity 即使仍持有相同的 renderer 版本与
 * activationAttemptId，也无法再提交健康或失败结果。
 */
internal class SkinRenderSessionRegistry {
    private val lock = Any()
    private var activeOwner: LiquidRenderSessionOwner? = null

    fun claim(
        state: SkinPreferenceState,
        newOwnerId: String
    ): LiquidRenderSessionOwner? = synchronized(lock) {
        if (state.selectedSkin != SkinId.LIQUID) return@synchronized null
        val owner = LiquidRenderSessionOwner(
            liquidRendererVersion = state.liquidRendererVersion,
            activationAttemptId = requireNotNull(state.activationAttemptId),
            ownerId = newOwnerId
        )
        activeOwner = owner
        owner
    }

    fun isActiveFor(
        owner: LiquidRenderSessionOwner,
        state: SkinPreferenceState
    ): Boolean = synchronized(lock) {
        activeOwner == owner &&
            state.selectedSkin == SkinId.LIQUID &&
            state.liquidRendererVersion == owner.liquidRendererVersion &&
            state.activationAttemptId == owner.activationAttemptId
    }

    /** 只有当前 owner 能释放自身；旧 Activity 的 close 不得撤销新 Activity 的所有权。 */
    fun release(owner: LiquidRenderSessionOwner): Boolean = synchronized(lock) {
        if (activeOwner != owner) return@synchronized false
        activeOwner = null
        true
    }

    fun invalidate() = synchronized(lock) {
        activeOwner = null
    }
}
