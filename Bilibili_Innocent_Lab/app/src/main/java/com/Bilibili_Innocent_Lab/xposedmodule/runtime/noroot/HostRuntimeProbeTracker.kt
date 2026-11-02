package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

/** 存活探测的结算状态；[CONFIRMED] 是唯一可以支撑“已激活”的证据。 */
internal enum class HostRuntimeProbeStatus {
    IDLE,
    PROBING,
    CONFIRMED,
    UNCONFIRMED,
    NOT_RUNNING
}

/**
 * 存活探测的纯状态归并：单飞、nonce 关联、超时结算与“活着但 Hook 未装”的降级。
 *
 * 只回答“这次探测窗口里宿主是否证明了自己在运行”，不读写任何 Android API，
 * 因此可以完全用 JVM 测试钉住。超时语义：
 * - 用户主动验证（[begin] 的 `verify=true`）后仍无回执 → [HostRuntimeProbeStatus.NOT_RUNNING]；
 * - 回执说“模块代码在跑但 Hook 未装” → 同样 [HostRuntimeProbeStatus.NOT_RUNNING]；
 * - 其余超时（例如只是回到设置页时的顺手探测）→ [HostRuntimeProbeStatus.UNCONFIRMED]。
 */
internal class HostRuntimeProbeTracker(private val now: () -> Long) {

    enum class ReplyResult { REJECTED, PENDING, CONFIRMED }

    var status: HostRuntimeProbeStatus = HostRuntimeProbeStatus.IDLE
        private set

    private var pendingNonce: String? = null
    private var pendingDeadline = 0L
    private var pendingVerify = false
    private var aliveWithoutHooks = false

    /**
     * 开始一次探测窗口。返回 false 表示已有另一个窗口在飞，调用方应合并回调而不是再发一条。
     * 同一 nonce 重复调用只延长截止时间，保留窗口内已有的“活着但未装”线索。
     */
    fun begin(nonce: String, verify: Boolean, timeoutMs: Long): Boolean {
        val current = now()
        val inFlight = pendingNonce
        if (inFlight != null && inFlight != nonce && current < pendingDeadline) return false
        if (inFlight == nonce) {
            pendingDeadline = current + timeoutMs
        } else {
            pendingNonce = nonce
            pendingDeadline = current + timeoutMs
            pendingVerify = false
            aliveWithoutHooks = false
        }
        if (verify) pendingVerify = true
        // 已确认/已判定未生效都不在新一轮探测里闪回“确认中”，只有新证据才能改写结论。
        if (status != HostRuntimeProbeStatus.CONFIRMED &&
            status != HostRuntimeProbeStatus.NOT_RUNNING
        ) {
            status = HostRuntimeProbeStatus.PROBING
        }
        return true
    }

    /** 加入仍有效的窗口；只有主动验证延长期限，普通探测不能缩短或无限续期。 */
    fun merge(verify: Boolean, timeoutMs: Long): String? {
        val nonce = pendingNonce ?: return null
        val current = now()
        if (current >= pendingDeadline) return null
        if (verify) {
            pendingVerify = true
            pendingDeadline = maxOf(pendingDeadline, current + timeoutMs)
        }
        return nonce
    }

    /**
     * 宿主回执到达。返回本次回执的结论，不能把上轮保留的 status 当作本次确认。
     * `hooksInstalled=false` 说明模块入口在跑但 Hook 没装上：不确认、不清窗口，等后续
     * 回执/心跳或超时结算，避免宿主启动途中的瞬时回执被误判为“未生效”。
     */
    fun onReply(nonce: String, hooksInstalled: Boolean): ReplyResult {
        if (nonce != pendingNonce || now() >= pendingDeadline) return ReplyResult.REJECTED
        if (!hooksInstalled) {
            aliveWithoutHooks = true
            return ReplyResult.PENDING
        }
        clearPending()
        status = HostRuntimeProbeStatus.CONFIRMED
        return ReplyResult.CONFIRMED
    }

    /** 心跳是宿主装完 Hook 的直接证据，随时可以确认并结束窗口。 */
    fun onHeartbeat() {
        clearPending()
        status = HostRuntimeProbeStatus.CONFIRMED
    }

    /** 旧窗口或尚未到期的任务不能结算当前窗口。 */
    fun onTimeout(nonce: String): HostRuntimeProbeStatus? {
        if (pendingNonce != nonce || now() < pendingDeadline) return null
        // “未生效”是用户验证失败的结论，只有下一次确认（回执/心跳）才能推翻；
        // 回到设置页的顺手探测超时不能把它降级成“未确认”。
        val verifiedFailure = aliveWithoutHooks || pendingVerify ||
            status == HostRuntimeProbeStatus.NOT_RUNNING
        clearPending()
        status = if (verifiedFailure) {
            HostRuntimeProbeStatus.NOT_RUNNING
        } else {
            HostRuntimeProbeStatus.UNCONFIRMED
        }
        return status
    }

    fun isPending(nonce: String): Boolean = pendingNonce == nonce

    private fun clearPending() {
        pendingNonce = null
        pendingDeadline = 0L
        pendingVerify = false
        aliveWithoutHooks = false
    }
}
