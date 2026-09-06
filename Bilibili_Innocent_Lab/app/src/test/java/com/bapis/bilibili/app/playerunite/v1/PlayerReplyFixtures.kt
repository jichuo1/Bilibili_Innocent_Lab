package com.bapis.bilibili.app.playerunite.v1

import com.bapis.bilibili.playershared.PlayArcConf
import com.bilibili.lib.moss.api.MossResponseHandler

class PlayViewUniteReply(private var configuration: PlayArcConf = PlayArcConf.getDefaultInstance(),
    private val video: Boolean = true, val videoPayload: Any = Any()) {
    fun hasVodInfo() = video
    fun getPlayArcConf() = configuration
    private fun setPlayArcConf(value: PlayArcConf) { configuration = value }
    companion object {
        private val DEFAULT = PlayViewUniteReply(video = false)
        @JvmStatic fun getDefaultInstance() = DEFAULT
    }
}
class PlayViewUniteReq
@Suppress("UNUSED_PARAMETER")
class PlayerMoss {
    fun executePlayViewUnite(req: PlayViewUniteReq) = PlayViewUniteReply()
    fun playViewUnite(req: PlayViewUniteReq, handler: MossResponseHandler) = Unit
}
