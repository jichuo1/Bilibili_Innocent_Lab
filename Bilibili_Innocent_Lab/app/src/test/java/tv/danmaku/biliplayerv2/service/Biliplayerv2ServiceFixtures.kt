package tv.danmaku.biliplayerv2.service

import tv.danmaku.videoplayer.coreV2.VideoBizType

// From SponsorCoreFixture.kt
interface PlayerProgressObserver { fun onPlayerProgressChange(position: Int, duration: Int) }
interface PlayerSeekObserver { fun onSeekStart(position: Long); fun onSeekComplete(position: Long) }
interface PlayerStateObserver { fun onPlayerStateChanged(state: Int) }
interface IPlayerReleaseObserver { fun onPlayerWillRelease() }
interface IPlayerCoreService {
    fun getMediaResource(): com.bilibili.lib.media.resource.MediaResource? = null
    fun getCurrentPosition(): Int = 0
    fun getRealCurrentPosition(): Int = getCurrentPosition()
    fun getDuration(): Int = 100_000
    fun getRealDuration(): Int = getDuration()
    fun getCurrentMediaItem(): tv.danmaku.videoplayer.coreV2.MediaItem? = null
    fun getState(): Int = 4
    fun seekTo(position: Int, accurate: Boolean) {}
    fun registerPlayerProgressObserver(observer: PlayerProgressObserver) {}
    fun unregisterPlayerProgressObserver(observer: PlayerProgressObserver) {}
    fun registerSeekObserver(observer: PlayerSeekObserver) {}
    fun unregisterSeekObserver(observer: PlayerSeekObserver) {}
    fun registerState(observer: PlayerStateObserver, states: IntArray) {}
    fun unregisterState(observer: PlayerStateObserver) {}
    fun addPlayerReleaseObserver(observer: IPlayerReleaseObserver) {}
    fun removePlayerReleaseObserver(observer: IPlayerReleaseObserver) {}
}

// From Video.kt
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
        open fun getResolveParams(): com.bilibili.lib.media.resolver2.IResolveParams =
            getDisplayParams().let { com.bilibili.app.gemini.base.player.GeminiCommonResolverParams(it.getAvid(), it.getCid()) }
    }
}

// 与真实宿主一样，公开类型只声明抽象契约，实际播放条目提供其实现。
class FixturePlayableParams(private val display: Video.DisplayParams, kind: VideoBizType = VideoBizType.UGC) :
    Video.PlayableParams(display.getFrom(), kind) {
    override fun getDisplayParams() = display
}

open class FixtureResolveParams(private val origin: String) {
    fun getFrom() = origin
    open fun id(): String = ""
}
class CurrentVideoPointer(var index: Int = 0)
