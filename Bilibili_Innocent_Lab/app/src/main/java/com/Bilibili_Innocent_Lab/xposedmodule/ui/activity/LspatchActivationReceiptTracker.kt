package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsSnapshot

/**
 * 主页只在当前前台会话和 LSPatch 服务连接的一次性宿主回执中保留证据。它不持久化
 * 回执，也不发起轮询；同一会话的有效回执复用，暂停/恢复或连接改变后的迟到回调被拒绝。
 */
internal data class LspatchActivationReceiptRequest(
    val token: Long,
    val connectionId: Long
)

internal class LspatchActivationReceiptTracker {
    private var nextToken = 0L
    private var sessionActive = false
    private var sessionConnectionId = -1L
    private var inFlight: LspatchActivationReceiptRequest? = null
    private var receiptConnectionId = -1L
    private var receipt: HostRuntimeDiagnosticsSnapshot? = null

    /** 开始一次新的前台会话；服务可以在会话开始后才异步到达。 */
    fun startSession() {
        sessionActive = true
        clearConnectionEvidence()
    }

    /** 结束前台会话；暂停期间的迟到回调不得重新成为有效证据。 */
    fun endSession() {
        sessionActive = false
        clearConnectionEvidence()
    }

    /** 服务断开或能力暂不可用时只清空连接证据，不结束仍在交互的前台会话。 */
    fun clearConnectionEvidence() {
        sessionConnectionId = -1L
        invalidate()
    }

    fun begin(connectionId: Long): LspatchActivationReceiptRequest? {
        if (!sessionActive) return null
        if (sessionConnectionId != connectionId) {
            sessionConnectionId = connectionId
            invalidate()
        }
        if (inFlight?.connectionId == connectionId) return null
        if (receiptConnectionId == connectionId && receipt != null) return null
        receipt = null
        receiptConnectionId = -1L
        return LspatchActivationReceiptRequest(++nextToken, connectionId).also { inFlight = it }
    }

    fun accept(
        request: LspatchActivationReceiptRequest,
        currentConnectionId: Long,
        value: HostRuntimeDiagnosticsSnapshot?
    ): Boolean {
        if (!sessionActive) return false
        if (inFlight != request) return false
        if (currentConnectionId != request.connectionId) {
            invalidate()
            return false
        }
        inFlight = null
        receipt = value
        receiptConnectionId = if (value == null) -1L else request.connectionId
        return true
    }

    fun receiptFor(connectionId: Long): HostRuntimeDiagnosticsSnapshot? =
        receipt?.takeIf {
            sessionActive && sessionConnectionId == connectionId && receiptConnectionId == connectionId
        }

    private fun invalidate() {
        nextToken += 1L
        inFlight = null
        receiptConnectionId = -1L
        receipt = null
    }
}
