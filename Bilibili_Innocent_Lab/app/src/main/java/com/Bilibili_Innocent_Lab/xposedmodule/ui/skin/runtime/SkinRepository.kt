package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime

import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SkinId
import java.util.UUID

/**
 * 进程级皮肤选择入口，只缓存不含 Android 对象的纯状态。
 *
 * “发现 pending 即回滚”只能在新进程第一次读取时执行。同一进程写入 pending 后的 Activity
 * recreate 必须继续看到 pending，等待 renderer 健康确认；否则两阶段激活永远无法完成。
 */
internal object SkinRepository {
    /** UI 调用方不得自行传入 renderer 版本；协议升级只在此处单点递增。 */
    private const val CURRENT_LIQUID_RENDERER_VERSION = 6

    private val stateHolder = SkinProcessStateHolder()
    private val renderSessions = SkinRenderSessionRegistry()

    fun resolveRequestedSkin(context: Context): SkinId = state(context).selectedSkin

    @Synchronized
    fun beginSelection(
        context: Context,
        target: SkinId
    ): SkinStateMutationResult {
        val result = stateHolder.mutate(
            loader = { loadForProcessStart(context) },
            decide = { current ->
                SkinRecoveryGuard.beginSelection(
                    current = current,
                    target = target,
                    currentLiquidRendererVersion = CURRENT_LIQUID_RENDERER_VERSION,
                    newActivationAttemptId = newOpaqueId()
                )
            },
            persist = { state -> SkinPrefs.write(context, state) }
        )
        if (result.persisted && result.reason != SkinRecoveryReason.READY) {
            renderSessions.invalidate()
        }
        return result
    }

    /** 新 renderer 接管后，旧 Activity 持有的 owner 会立即失效。 */
    @Synchronized
    fun claimLiquidRenderSession(context: Context): LiquidRenderSessionOwner? =
        renderSessions.claim(state(context), newOpaqueId())

    @Synchronized
    fun confirmLiquidHealthy(
        context: Context,
        owner: LiquidRenderSessionOwner
    ): SkinStateMutationResult {
        val current = state(context)
        if (!renderSessions.isActiveFor(owner, current)) {
            return staleResult(current, SkinRecoveryReason.STALE_HEALTH_CONFIRMATION_IGNORED)
        }
        return stateHolder.mutate(
            loader = { current },
            decide = { state ->
                SkinRecoveryGuard.confirmLiquidHealthy(
                    current = state,
                    confirmedRendererVersion = owner.liquidRendererVersion,
                    activationAttemptId = owner.activationAttemptId
                )
            },
            persist = { state -> SkinPrefs.write(context, state) }
        )
    }

    @Synchronized
    fun reportLiquidValidationFailure(
        context: Context,
        owner: LiquidRenderSessionOwner
    ): SkinStateMutationResult {
        val current = state(context)
        if (!renderSessions.isActiveFor(owner, current)) {
            return staleResult(current, SkinRecoveryReason.STALE_VALIDATION_FAILURE_IGNORED)
        }
        val result = stateHolder.mutate(
            loader = { current },
            decide = { state ->
                SkinRecoveryGuard.onLiquidValidationFailed(
                    current = state,
                    failedRendererVersion = owner.liquidRendererVersion,
                    activationAttemptId = owner.activationAttemptId
                )
            },
            persist = { state -> SkinPrefs.write(context, state) }
        )
        if (result.persisted && result.reason == SkinRecoveryReason.LIQUID_VALIDATION_FAILED) {
            renderSessions.release(owner)
        }
        return result
    }

    @Synchronized
    fun releaseLiquidRenderSession(owner: LiquidRenderSessionOwner) {
        renderSessions.release(owner)
    }

    private fun state(context: Context): SkinPreferenceState =
        stateHolder.current { loadForProcessStart(context) }

    private fun loadForProcessStart(context: Context): SkinPreferenceState =
        SkinPrefs.readForProcessStart(context, CURRENT_LIQUID_RENDERER_VERSION).state

    private fun staleResult(
        state: SkinPreferenceState,
        reason: SkinRecoveryReason
    ) = SkinStateMutationResult(
        state = state,
        persisted = true,
        reason = reason
    )

    private fun newOpaqueId(): String = UUID.randomUUID().toString()
}
