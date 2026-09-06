package com.bapis.bilibili.app.playurl.v1

import com.bapis.bilibili.app.playurl.v1.PlayArcConf
import com.bilibili.lib.moss.api.MossResponseHandler

class PlayViewReply(private var configuration: PlayArcConf = PlayArcConf.getDefaultInstance(),
    private val video: Boolean = true, val videoPayload: Any = Any()) {
    fun hasVideoInfo() = video
    fun getPlayArc() = configuration
    private fun setPlayArc(value: PlayArcConf) { configuration = value }
    companion object {
        private val DEFAULT = PlayViewReply(video = false)
        @JvmStatic fun getDefaultInstance() = DEFAULT
    }
}
class PlayViewReq
@Suppress("UNUSED_PARAMETER")
class PlayURLMoss {
    fun executePlayView(req: PlayViewReq) = PlayViewReply()
    fun playView(req: PlayViewReq, handler: MossResponseHandler) = Unit
}
