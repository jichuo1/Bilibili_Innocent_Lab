package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote

import java.util.concurrent.atomic.AtomicBoolean

/** 一次明确的同步请求；取消仅撤销逻辑所有权，不声称撤销已发送的远端写入。 */
internal class RemotePublicationAttempt(
    val id: Long,
    val consentRevision: Long,
    val intentEpoch: Long,
    private val deadline: Long,
    private val clock: () -> Long,
    private val callback: (RemoteHookConfigPublishResult) -> Unit,
    val origin: Origin = Origin.MANUAL
) {
    enum class Origin { MANUAL, MODE_ENABLE }
    private val closed = AtomicBoolean(false)
    private val cancellation = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
    fun onCancel(action: () -> Unit) {
        cancellation.set(action)
        if (closed.get()) cancellation.getAndSet(null)?.invoke()
    }
    fun isActive(): Boolean = !closed.get() && clock() <= deadline
    fun complete(result: RemoteHookConfigPublishResult): Boolean {
        if (clock() > deadline || !closed.compareAndSet(false, true)) return false
        callback(result)
        return true
    }
    fun cancel() { closed.set(true); cancellation.getAndSet(null)?.invoke() }
}
