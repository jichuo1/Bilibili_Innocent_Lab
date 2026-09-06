package com.bilibili.lib.media.resource

class PlayConfig {
    enum class PlayConfigType { MINIPLAYER, OTHER }
    class PlayMenuConfig(val allowed: Boolean, val type: PlayConfigType, val items: List<*>?) {
        constructor(allowed: Boolean, type: PlayConfigType) : this(allowed, type, null)
    }
}
