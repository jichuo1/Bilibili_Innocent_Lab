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
 * 当前进程同一 Liquid 激活状态下的 renderer 所有权登记表。
 *
 * MainActivity 与透明/二级 Activity 可以同时可见，因此同一 renderer 版本与 activationAttemptId
 * 下允许多个 owner 并存。皮肤切换会整体 invalidate；单个 Activity close 只释放自己的 owner。
 */
internal class SkinRenderSessionRegistry {
    private val lock = Any()
    private val activeOwners = LinkedHashSet<LiquidRenderSessionOwner>()

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
        activeOwners += owner
        owner
    }

    fun isActiveFor(
        owner: LiquidRenderSessionOwner,
        state: SkinPreferenceState
    ): Boolean = synchronized(lock) {
        owner in activeOwners &&
            state.selectedSkin == SkinId.LIQUID &&
            state.liquidRendererVersion == owner.liquidRendererVersion &&
            state.activationAttemptId == owner.activationAttemptId
    }

    /** Activity 只能释放自身；不得撤销同时存活的其他 Activity owner。 */
    fun release(owner: LiquidRenderSessionOwner): Boolean = synchronized(lock) {
        activeOwners.remove(owner)
    }

    fun invalidate() = synchronized(lock) {
        activeOwners.clear()
    }
}
