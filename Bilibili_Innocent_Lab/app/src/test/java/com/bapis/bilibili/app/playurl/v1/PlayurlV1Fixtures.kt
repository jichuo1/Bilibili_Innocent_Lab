package com.bapis.bilibili.app.playurl.v1

import com.bapis.bilibili.app.playurl.v1.PlayArcConf
import com.bilibili.lib.moss.api.MossResponseHandler

// From PlayerCapabilityFixtures.kt
class ArcConf(val blocked: Boolean = true, val allowed: Boolean = false, val unrelated: String = "original") {
    fun getDisabled() = blocked
    fun getIsSupport() = allowed
    class Builder(original: ArcConf) {
        private var blocked = original.blocked
        private var allowed = original.allowed
        private val unrelated = original.unrelated
        fun setDisabled(value: Boolean) = apply { blocked = value }
        fun setIsSupport(value: Boolean) = apply { allowed = value }
        fun build() = ArcConf(blocked, allowed, unrelated)
    }
    companion object {
        private val DEFAULT = ArcConf(false, false, "default")
        @JvmStatic fun getDefaultInstance() = DEFAULT
        @JvmStatic fun newBuilder(original: ArcConf) = Builder(original)
    }
}
class PlayArcConf(val arcs: Map<Int, ArcConf> = emptyMap(), val unrelated: String = "keep-container") {
    fun getBackgroundPlayConf() = arcs[9] ?: ArcConf.getDefaultInstance()
    fun getSmallWindowConf() = arcs[23] ?: ArcConf.getDefaultInstance()
    fun getCastConf() = arcs[2] ?: ArcConf.getDefaultInstance()
    class Builder(original: PlayArcConf) {
        private val arcs = original.arcs.toMutableMap()
        private val unrelated = original.unrelated
        fun setBackgroundPlayConf(value: ArcConf) = apply { arcs[9] = value }
        fun setSmallWindowConf(value: ArcConf) = apply { arcs[23] = value }
        fun setCastConf(value: ArcConf) = apply { arcs[2] = value }
        fun build() = PlayArcConf(arcs.toMap(), unrelated)
    }
    companion object {
        private val DEFAULT = PlayArcConf()
        @JvmStatic fun getDefaultInstance() = DEFAULT
        @JvmStatic fun newBuilder(original: PlayArcConf) = Builder(original)
    }
}

// From PlayerReplyFixtures.kt
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
enum class CodeType {
    CODE_UNKNOWN,
    CODE264,
    CODE265,
    CODEAV1
}

class PlayViewReq(
    private val preferCodecType: CodeType = CodeType.CODE_UNKNOWN,
    private val fnval: Int = 16
) {
    fun getPreferCodecType() = preferCodecType
    fun getFnval() = fnval

    companion object {
        @JvmStatic
        fun newBuilder(source: PlayViewReq) = Builder(source)
    }

    class Builder(source: PlayViewReq) {
        private var preferCodecType = source.preferCodecType
        private var fnval = source.fnval

        fun setPreferCodecType(value: CodeType): Builder {
            preferCodecType = value
            return this
        }

        fun setFnval(value: Int): Builder {
            fnval = value
            return this
        }

        fun build() = PlayViewReq(preferCodecType, fnval)
    }
}
@Suppress("UNUSED_PARAMETER")
class PlayURLMoss {
    fun executePlayView(req: PlayViewReq) = PlayViewReply()
    fun playView(req: PlayViewReq, handler: MossResponseHandler) = Unit
}
