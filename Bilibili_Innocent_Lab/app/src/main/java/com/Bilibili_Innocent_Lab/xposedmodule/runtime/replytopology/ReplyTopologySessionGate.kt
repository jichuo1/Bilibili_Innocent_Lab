package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

internal data class ReplyTopologySessionToken(
    val sessionId: Long,
    val pageEpoch: Long,
    val key: ReplyTopologyThreadKey
)

internal data class ReplyTopologyRequestToken(
    val session: ReplyTopologySessionToken,
    val requestId: Long
)

/**
 * 一个进程仅放行一个活动分析会话及一个在飞请求。所有异步结果都必须持 token 回来校验，
 * 因而无法物理取消的宿主请求也不会把迟到数据写入新页面。
 */
internal class ReplyTopologySessionGate {
    private var generation = 0L
    private var requestGeneration = 0L
    private var activeSession: ReplyTopologySessionToken? = null
    private var activeRequest: ReplyTopologyRequestToken? = null

    @Synchronized
    fun open(key: ReplyTopologyThreadKey, pageEpoch: Long): ReplyTopologySessionToken {
        require(key.isValid)
        generation = nextGeneration(generation)
        val token = ReplyTopologySessionToken(generation, pageEpoch, key)
        activeSession = token
        activeRequest = null
        return token
    }

    /** 已有请求在飞时返回 null，保持宿主 MOSS 严格顺序分页。 */
    @Synchronized
    fun beginRequest(session: ReplyTopologySessionToken): ReplyTopologyRequestToken? {
        if (session != activeSession || activeRequest != null) return null
        requestGeneration = nextGeneration(requestGeneration)
        return ReplyTopologyRequestToken(session, requestGeneration).also { activeRequest = it }
    }

    /** 返回 true 表示结果仍属于当前 session/request/pageEpoch，并已放行下一页。 */
    @Synchronized
    fun completeRequest(request: ReplyTopologyRequestToken): Boolean {
        if (request != activeRequest || request.session != activeSession) return false
        activeRequest = null
        return true
    }

    @Synchronized
    fun accepts(session: ReplyTopologySessionToken): Boolean = session == activeSession

    @Synchronized
    fun accepts(request: ReplyTopologyRequestToken): Boolean =
        request == activeRequest && request.session == activeSession

    /** 幂等关闭；旧 session 不能关闭后来打开的新面板。 */
    @Synchronized
    fun close(session: ReplyTopologySessionToken): Boolean {
        if (session != activeSession) return false
        activeSession = null
        activeRequest = null
        generation = nextGeneration(generation)
        return true
    }

    private fun nextGeneration(current: Long): Long = if (current == Long.MAX_VALUE) 1L else current + 1L
}
