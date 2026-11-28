package com.bilibili.video.story.player

import androidx.lifecycle.LifecycleOwner
import com.bilibili.lib.media.resource.MediaResource
import com.bilibili.video.story.player.datasource.PlayableProvider
import com.bilibili.video.story.player.quality.StoryQualityService
import tv.danmaku.biliplayerv2.service.CurrentVideoPointer
import tv.danmaku.biliplayerv2.service.Video
import tv.danmaku.videoplayer.coreV2.MediaItem

// From StoryPlayer.kt
class StoryPlayer {
    val source = PlayableProvider()
    val controller = StoryVideoPlayHandler(source)
    val quality = StoryQualityService(controller)
    var currentFailure: RuntimeException? = null
    // 与真实宿主一致：内核与 Context 都是 StoryPlayer 上按类型唯一的实例字段。
    var core: tv.danmaku.biliplayerv2.service.IPlayerCoreService? = null
    var hostContext: android.content.Context? = null
    fun getCurrentPlayableParam(): Video.PlayableParams? {
        currentFailure?.let { throw it }
        return source.values.getOrNull(source.currentIndex)
    }
    fun onCreate(owner: LifecycleOwner?) = Unit
    fun ready(container: tv.danmaku.biliplayerv2.IPlayerContainer?, shared: tv.danmaku.biliplayerv2.PlayerSharingBundle?) = Unit
    fun release() = Unit
    fun onDestroy(owner: LifecycleOwner?) = Unit
}

// From StoryVideoPlayHandler.kt
class StoryVideoPlayHandler(@JvmField val source: PlayableProvider = PlayableProvider()) {
    var pointer: CurrentVideoPointer? = CurrentVideoPointer()
    var selectionResult = 0
    fun getCurrentVideoItem(): CurrentVideoPointer? = pointer
    fun select(index: Int): Int {
        if (pointer == null) return -1
        if (selectionResult != 0) return selectionResult
        source.currentIndex = index
        pointer?.index = index
        return 0
    }
}

class `StoryVideoPlayHandler$mPlayableConsumer$1` {
    fun consume(parameters: Video.PlayableParams, resource: MediaResource, previous: MediaItem?, position: Long?): MediaItem? = MediaItem()
}
