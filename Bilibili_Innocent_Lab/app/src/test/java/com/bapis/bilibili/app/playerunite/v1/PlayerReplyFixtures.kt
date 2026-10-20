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
class PlayViewUniteReq(
    private val vod: VideoVod = VideoVod(CodeType.CODE_UNKNOWN, 16),
    val unrelated: String = "keep"
) {
    fun getVod() = vod
    fun toByteArray(): ByteArray = "${vod.getPreferCodecType().name}|${vod.getFnval()}|$unrelated".toByteArray()

    companion object {
        @JvmStatic fun parseFrom(bytes: ByteArray): PlayViewUniteReq {
            val parts = String(bytes).split('|', limit = 3)
            return PlayViewUniteReq(VideoVod(CodeType.valueOf(parts[0]), parts[1].toInt()), parts[2])
        }
        @JvmStatic
        fun newBuilder(source: PlayViewUniteReq) = Builder(source)
    }

    class Builder(source: PlayViewUniteReq) {
        private var vod = source.vod
        private val unrelated = source.unrelated

        fun setVod(value: VideoVod): Builder {
            vod = value
            return this
        }

        fun build() = PlayViewUniteReq(vod, unrelated)
    }
}
@Suppress("UNUSED_PARAMETER")
class PlayerMoss {
    fun executePlayViewUnite(req: PlayViewUniteReq) = PlayViewUniteReply()
    fun playViewUnite(req: PlayViewUniteReq, handler: MossResponseHandler) = Unit
}
