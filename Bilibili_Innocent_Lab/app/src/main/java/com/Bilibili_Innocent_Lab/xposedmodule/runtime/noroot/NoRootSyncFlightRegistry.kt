package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision

/**
 * 只保留一个当前 NPatch 写入航班；相同用户意图与快照 revision 的调用共享结果，
 * 新 revision 则替换旧航班。类本身不依赖 Android，便于锁定并发规则。
 */
internal class NoRootSyncFlightRegistry<R> {
    data class Key(
        val intentGeneration: Long,
        val snapshotRevision: Long,
        val enabled: Boolean,
        val termsDecision: UserTermsDecision
    )

    class Token internal constructor(
        val key: Key,
        internal val attemptId: Long
    )

    data class Registration<R>(
        val startsFlight: Boolean,
        val token: Token,
        val displacedToken: Token?,
        val displacedListeners: List<(R?) -> Unit>
    )

    data class Completion<R>(
        val resultHandler: (R) -> Unit,
        val listeners: List<(R?) -> Unit>
    )

    private data class Flight<R>(
        val resultHandler: (R) -> Unit,
        val listeners: MutableList<(R?) -> Unit>
    )

    private val lock = Any()
    private var activeKey: Key? = null
    private var activeToken: Token? = null
    private var activeFlight: Flight<R>? = null
    private var nextAttemptId = 0L

    fun register(
        key: Key,
        resultHandler: (R) -> Unit,
        listener: ((R?) -> Unit)?
    ): Registration<R> = synchronized(lock) {
        if (activeKey == key) {
            listener?.let { activeFlight?.listeners?.add(it) }
            return@synchronized Registration(
                startsFlight = false,
                token = checkNotNull(activeToken),
                displacedToken = null,
                displacedListeners = emptyList()
            )
        }

        val previousToken = activeToken
        val displaced = activeFlight?.listeners?.toList().orEmpty()
        val token = Token(key = key, attemptId = ++nextAttemptId)
        activeKey = key
        activeToken = token
        activeFlight = Flight(
            resultHandler = resultHandler,
            listeners = mutableListOf<(R?) -> Unit>().apply {
                listener?.let(::add)
            }
        )
        Registration(
            startsFlight = true,
            token = token,
            displacedToken = previousToken,
            displacedListeners = displaced
        )
    }

    fun isCurrent(token: Token): Boolean = synchronized(lock) { activeToken == token }

    fun takeCompletion(token: Token): Completion<R>? = synchronized(lock) {
        if (activeToken != token) return@synchronized null
        val flight = activeFlight ?: return@synchronized null
        activeKey = null
        activeToken = null
        activeFlight = null
        Completion(
            resultHandler = flight.resultHandler,
            listeners = flight.listeners.toList()
        )
    }

    fun cancel(token: Token): List<(R?) -> Unit> = synchronized(lock) {
        if (activeToken != token) return@synchronized emptyList()
        val listeners = activeFlight?.listeners?.toList().orEmpty()
        activeKey = null
        activeToken = null
        activeFlight = null
        listeners
    }

    fun cancelAll(): List<(R?) -> Unit> = synchronized(lock) {
        val listeners = activeFlight?.listeners?.toList().orEmpty()
        activeKey = null
        activeToken = null
        activeFlight = null
        listeners
    }
}
