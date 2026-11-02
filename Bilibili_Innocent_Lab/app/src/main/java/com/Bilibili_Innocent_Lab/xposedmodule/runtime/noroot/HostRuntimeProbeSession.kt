package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

/** 调用方串行访问；每个请求保留自己的结算回调，同一窗口只需一条重发链。 */
internal class HostRuntimeProbeSession(now: () -> Long, private val deliver: (() -> Unit) -> Unit) {
    data class Request(val nonce: String, val newWindow: Boolean, val scheduleTimeout: Boolean)

    private val tracker = HostRuntimeProbeTracker(now)
    private var pendingNonce: String? = null
    private val callbacks = mutableListOf<(HostRuntimeProbeStatus) -> Unit>()
    val status: HostRuntimeProbeStatus get() = tracker.status
    val hasPendingRequest: Boolean get() = pendingNonce != null

    fun request(nonce: String, verify: Boolean, timeoutMs: Long,
                callback: (HostRuntimeProbeStatus) -> Unit): Request {
        tracker.merge(verify, timeoutMs)?.let { active ->
            callbacks += callback
            return Request(active, newWindow = false, scheduleTimeout = verify)
        }
        // 定时任务可能晚到；先结算已过期窗口，不能丢掉旧请求的回调。
        pendingNonce?.let { expired -> tracker.onTimeout(expired)?.let(::settle) }
        check(tracker.begin(nonce, verify, timeoutMs))
        pendingNonce = nonce
        callbacks += callback
        return Request(nonce, newWindow = true, scheduleTimeout = true)
    }

    fun onReply(nonce: String, hooksInstalled: Boolean): HostRuntimeProbeTracker.ReplyResult =
        tracker.onReply(nonce, hooksInstalled).also {
            when (it) {
                HostRuntimeProbeTracker.ReplyResult.CONFIRMED -> settle(HostRuntimeProbeStatus.CONFIRMED)
                // 计时任务可能还未运行；拒绝过期回执的同时按原有超时语义结算每个回调。
                HostRuntimeProbeTracker.ReplyResult.REJECTED -> onTimeout(nonce)
                HostRuntimeProbeTracker.ReplyResult.PENDING -> Unit
            }
        }

    fun onHeartbeat() {
        tracker.onHeartbeat()
        settle(HostRuntimeProbeStatus.CONFIRMED)
    }

    fun onTimeout(nonce: String): Boolean {
        val status = tracker.onTimeout(nonce) ?: return false
        settle(status)
        return true
    }

    fun isPending(nonce: String): Boolean = tracker.isPending(nonce)

    private fun settle(status: HostRuntimeProbeStatus) {
        pendingNonce = null
        val completed = callbacks.toList()
        callbacks.clear()
        completed.forEach { callback -> deliver { callback(status) } }
    }
}
