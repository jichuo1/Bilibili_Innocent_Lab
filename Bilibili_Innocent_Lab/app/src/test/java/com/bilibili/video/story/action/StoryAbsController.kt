package com.bilibili.video.story.action

import com.bilibili.video.story.player.StoryPlayer

class LegacyToggle

class StoryAbsController {
    var toggle: LegacyToggle? = null
    var failure: RuntimeException? = null
    protected fun getMDanmakuToggle(): LegacyToggle? { failure?.let { throw it }; return toggle }
    protected fun setMDanmakuToggle(value: LegacyToggle?) { toggle = value }
    protected fun getMPlayer(): StoryPlayer? = null
    fun bind(player: StoryPlayer?, index: Int) = Unit
}
