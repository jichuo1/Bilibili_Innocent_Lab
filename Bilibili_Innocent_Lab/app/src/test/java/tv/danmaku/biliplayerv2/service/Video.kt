package tv.danmaku.biliplayerv2.service

import tv.danmaku.videoplayer.coreV2.VideoBizType

class Video {
    class DisplayParams(private val aidValue: Long, private val cidValue: Long, private val origin: String = "vupload") {
        fun getAvid() = aidValue
        fun getCid() = cidValue
        fun getFrom() = origin
    }
    // 有意不继承 DisplayParams，也不定义 getAvid/getCid，必须走真实中转 API。
    abstract class PlayableParams(origin: String, var kind: VideoBizType = VideoBizType.UGC) : FixtureResolveParams(origin) {
        abstract fun getDisplayParams(): DisplayParams
        fun getBizType() = kind
    }
}

// 与真实宿主一样，公开类型只声明抽象契约，实际播放条目提供其实现。
class FixturePlayableParams(private val display: Video.DisplayParams, kind: VideoBizType = VideoBizType.UGC) :
    Video.PlayableParams(display.getFrom(), kind) {
    override fun getDisplayParams() = display
}

open class FixtureResolveParams(private val origin: String) { fun getFrom() = origin }
class CurrentVideoPointer(var index: Int = 0)
