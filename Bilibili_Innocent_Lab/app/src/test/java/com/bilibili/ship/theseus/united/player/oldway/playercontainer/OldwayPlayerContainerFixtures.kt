package com.bilibili.ship.theseus.united.player.oldway.playercontainer

import android.content.Context
import com.bilibili.ship.theseus.keel.player.RenamedSpeedSessionOwner
import com.bilibili.ship.theseus.keel.player.TheseusKeelPlayer
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import tv.danmaku.biliplayerv2.PlayerContainer
import tv.danmaku.biliplayerv2.service.IPlayerCoreService

// From RenamedSponsorFixtures.kt
class RenamedSponsorWrapper(core: IPlayerCoreService, owner: TheseusKeelPlayer,
    scope: CoroutineScope) : IPlayerCoreService by core

open class RenamedSponsorSeek(owner: TheseusKeelPlayer, position: Int, accurate: Boolean,
    continuation: Continuation<Any?>?) : Continuation<Any?> {
    @JvmField var label = 0
    override val context = EmptyCoroutineContext
    override fun resumeWith(result: Result<Any?>) {}
    fun create(value: Any, continuation: Continuation<Any?>): Continuation<Any?> = this
    fun invokeSuspend(value: Any): Any = Unit
}
class SecondSponsorSeek(owner: TheseusKeelPlayer, position: Int, accurate: Boolean,
    continuation: Continuation<Any?>?) : Continuation<Any?> {
    @JvmField var label = 0
    override val context = EmptyCoroutineContext
    override fun resumeWith(result: Result<Any?>) {}
    fun create(value: Any, continuation: Continuation<Any?>): Continuation<Any?> = this
    fun invokeSuspend(value: Any): Any = Unit
}

// From SpeedSessionWrapperFixture.kt
/** Container wrapper whose constructor binds the renamed session owner, as the host wrapper does. */
class RenamedSpeedSessionWrapper(core: IPlayerCoreService, owner: RenamedSpeedSessionOwner, scope: CoroutineScope) {
    fun seekTo(position: Int, accurate: Boolean) = Unit
}

// From SponsorContainerScopeFixture.kt
class `TheseusPlayerContainerProvider$providePlayerContainer$2`(views: Flow<*>, created: MutableStateFlow<Boolean>,
    container: PlayerContainer, context: Context, performance: Any, ratio: Any, lifecycle: Any,
    continuation: Continuation<Any?>?)

// From SponsorScopeFixture.kt
class BadNetworkTipService(core: IPlayerCoreService, context: Context, owner: TheseusKeelPlayer,
    toast: Any, reporter: Any, controls: Any, scope: kotlinx.coroutines.CoroutineScope)

// From SponsorWrapperFixture.kt
class `TheseusPlayerContainerProvider$providePlayerContainer$playerContainer$1$1$1`(
    core: IPlayerCoreService, owner: TheseusKeelPlayer, scope: CoroutineScope
) : IPlayerCoreService by core

class `TheseusPlayerContainerProvider$providePlayerContainer$playerContainer$1$1$1$seekTo$1`(
    owner: TheseusKeelPlayer, position: Int, accurate: Boolean, continuation: Continuation<Any?>?
) : Continuation<Any?> {
    @JvmField var label = 0
    override val context = EmptyCoroutineContext
    override fun resumeWith(result: Result<Any?>) {}
    fun create(value: Any, continuation: Continuation<Any?>): Continuation<Any?> = this
    fun invokeSuspend(value: Any): Any = Unit
}
