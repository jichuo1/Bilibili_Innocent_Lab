package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime

/** 皮肤状态写入结果；失败时调用方不得重建 Activity 或安装新 renderer。 */
internal data class SkinStateMutationResult(
    val state: SkinPreferenceState,
    val persisted: Boolean,
    val reason: SkinRecoveryReason
)

/**
 * 只保存纯偏好状态的进程级协调器。
 *
 * loader 在一个进程生命周期内最多调用一次，因此“遗留 pending 回滚”不会被 Activity 重建误触发。
 */
internal class SkinProcessStateHolder {
    private val lock = Any()
    private var processState: SkinPreferenceState? = null

    fun current(loader: () -> SkinPreferenceState): SkinPreferenceState = synchronized(lock) {
        stateLocked(loader)
    }

    fun mutate(
        loader: () -> SkinPreferenceState,
        decide: (SkinPreferenceState) -> SkinRecoveryDecision,
        persist: (SkinPreferenceState) -> Boolean
    ): SkinStateMutationResult = synchronized(lock) {
        val current = stateLocked(loader)
        val decision = decide(current)
        if (!decision.shouldPersist) {
            processState = decision.state
            return@synchronized SkinStateMutationResult(
                state = decision.state,
                persisted = true,
                reason = decision.reason
            )
        }
        if (!persist(decision.state)) {
            return@synchronized SkinStateMutationResult(
                state = current,
                persisted = false,
                reason = decision.reason
            )
        }
        processState = decision.state
        SkinStateMutationResult(
            state = decision.state,
            persisted = true,
            reason = decision.reason
        )
    }

    private fun stateLocked(loader: () -> SkinPreferenceState): SkinPreferenceState =
        processState ?: loader().also { processState = it }
}
