package com.bilibili.ship.theseus.united.player.oldway.playercontainer

import com.bilibili.ship.theseus.keel.player.RenamedSpeedSessionOwner
import kotlinx.coroutines.CoroutineScope
import tv.danmaku.biliplayerv2.service.IPlayerCoreService

/** Container wrapper whose constructor binds the renamed session owner, as the host wrapper does. */
class RenamedSpeedSessionWrapper(core: IPlayerCoreService, owner: RenamedSpeedSessionOwner, scope: CoroutineScope) {
    fun seekTo(position: Int, accurate: Boolean) = Unit
}
