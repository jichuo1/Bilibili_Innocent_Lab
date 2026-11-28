package tv.danmaku.videoplayer.coreV2

// From MediaItem.kt
class MediaItem(private val id: String = "") {
    fun getId(): String = id
}

// From VideoBizType.kt
enum class VideoBizType { UGC, PGC, UNKNOWN }
