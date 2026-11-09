package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import java.util.UUID

internal class IncognitoSession(private val randomId: () -> String = { UUID.randomUUID().toString() }) {
    data class Identity(val generation: Long, val buvid: String, val sessionId: String)

    private var generation = 0L
    @Volatile private var current: Identity? = null

    fun identity(): Identity? = current

    @Synchronized
    fun enter(): Identity = current ?: newIdentity().also { current = it }

    @Synchronized
    fun refresh(): Identity {
        check(current != null) { "Incognito is not active" }
        return newIdentity().also { current = it }
    }

    /** Adopt a same-UID process refresh; closed sessions cannot be reopened by a late broadcast. */
    @Synchronized
    fun adopt(buvid: String, sessionId: String): Boolean {
        val before = current ?: return false
        // Network identities use canonical UUIDs; malformed/replayed identical messages are ignored.
        fun valid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        if (!valid(buvid) || !valid(sessionId) || buvid == sessionId) return false
        if (before.buvid == buvid && before.sessionId == sessionId) return false
        current = Identity(++generation, buvid, sessionId)
        return true
    }

    @Synchronized
    fun exit() {
        current = null
    }

    fun accepts(identity: Identity): Boolean = current === identity

    private fun newIdentity(): Identity = Identity(++generation, randomId(), randomId())
}
