package com.bilibili.ship.theseus.keel.player

import com.bilibili.app.gemini.base.player.GeminiCommonPlayableParams
import kotlin.coroutines.Continuation

interface SponsorPlayable { fun values(): GeminiCommonPlayableParams }
class TheseusKeelPlayer(@JvmField var current: SponsorPlayable? = null) {
    fun active(): SponsorPlayable? = current
    fun runPlayable(playable: SponsorPlayable, continuation: Continuation<Any?>): Any? {
        current = playable
        return null
    }
}
class `TheseusKeelPlayer$runPlayable$1`(val owner: TheseusKeelPlayer) {
    @JvmField var label = 0
}

/** Renamed keel owner carrying the speed manager field the session locator requires. */
class RenamedSpeedSessionOwner(@JvmField var current: SponsorPlayable? = null) {
    @JvmField val speedManager = com.bilibili.player.tangram.basic.PlaySpeedManagerImpl()
    fun active(): SponsorPlayable? = current
    fun runPlayable(playable: SponsorPlayable, continuation: Continuation<Any?>): Any? {
        current = playable
        return null
    }
}

/** Same-shaped second owner: two verified owners must stay ambiguous, never guessed. */
class SecondSpeedSessionOwner(@JvmField var current: SponsorPlayable? = null) {
    @JvmField val speedManager = com.bilibili.player.tangram.basic.PlaySpeedManagerImpl()
    fun active(): SponsorPlayable? = current
    fun runPlayable(playable: SponsorPlayable, continuation: Continuation<Any?>): Any? {
        current = playable
        return null
    }
}
