package com.bapis.bilibili.app.playerunite.v1

import com.bapis.bilibili.playershared.PlayArcConf
import com.bilibili.lib.moss.api.MossResponseHandler

class PlayViewUniteReply(private var configuration: PlayArcConf = PlayArcConf.getDefaultInstance(),
    private val video: Boolean = true, val videoPayload: Any = Any()) {
    fun hasVodInfo() = video
    fun getPlayArcConf() = configuration
    private fun setPlayArcConf(value: PlayArcConf) { configuration = value }
    fun toByteArray(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(out).use {
            it.writeBoolean(video)
            val entries = configuration.getArcConfsMap()
            it.writeInt(entries.size)
            entries.forEach { (k, v) ->
                it.writeInt(k)
                it.writeBoolean(v.getDisabled())
                it.writeBoolean(v.getIsSupport())
            }
        }
        return out.toByteArray()
    }
    class Builder(source: PlayViewUniteReply) {
        private var conf = source.configuration
        private val video = source.video
        private val payload = source.videoPayload
        fun setPlayArcConf(value: PlayArcConf): Builder { conf = value; return this }
        fun build() = PlayViewUniteReply(conf, video, payload)
    }
    companion object {
        private val DEFAULT = PlayViewUniteReply(video = false)
        @JvmStatic fun getDefaultInstance() = DEFAULT
        @JvmStatic fun newBuilder(source: PlayViewUniteReply) = Builder(source)
        @JvmStatic fun parseFrom(bytes: ByteArray): PlayViewUniteReply =
            java.io.DataInputStream(java.io.ByteArrayInputStream(bytes)).use { input ->
                val video = input.readBoolean()
                val count = input.readInt()
                val confBuilder = PlayArcConf.newBuilder(PlayArcConf.getDefaultInstance())
                repeat(count) {
                    val key = input.readInt()
                    val disabled = input.readBoolean()
                    val supported = input.readBoolean()
                    val arc = com.bapis.bilibili.playershared.ArcConf.newBuilder(
                        com.bapis.bilibili.playershared.ArcConf.getDefaultInstance()
                    ).setDisabled(disabled).setIsSupport(supported).build()
                    confBuilder.putArcConfs(key, arc)
                }
                PlayViewUniteReply(confBuilder.build(), video)
            }
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
