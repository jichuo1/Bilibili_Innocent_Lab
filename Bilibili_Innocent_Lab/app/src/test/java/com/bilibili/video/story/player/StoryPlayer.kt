package com.bilibili.video.story.player

import androidx.lifecycle.LifecycleOwner
import com.bilibili.video.story.player.quality.StoryQualityService
import com.bilibili.video.story.player.datasource.PlayableProvider
import tv.danmaku.biliplayerv2.service.Video

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
    fun release() = Unit
    fun onDestroy(owner: LifecycleOwner?) = Unit
}
