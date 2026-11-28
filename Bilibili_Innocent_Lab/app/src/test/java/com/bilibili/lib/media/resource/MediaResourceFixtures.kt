package com.bilibili.lib.media.resource

// From MediaResource.kt
enum class FixtureDrm { No, WideVine }
class PlayIndex(@JvmField var mQuality: Int) {
    @JvmField var mNeedLogin = false
    @JvmField var mNeedVip = false
    @JvmField var mDrmType = FixtureDrm.No
    @JvmField var mPlayError: Any? = null
}
class VodIndex(@JvmField var mVodList: ArrayList<PlayIndex>)

/** 最小宿主 ABI；详细权限/选档失败矩阵保留在 ResourceAccessTest。 */
class MediaResource(vararg qns: Int = intArrayOf(16, 80, 120)) {
    @JvmField var mVodIndex = VodIndex(qns.map(::PlayIndex).toCollection(arrayListOf()))
    var resolved = 0
    var from = SOURCE_FROM_FLASH
    var hdr = false
    var dolby = false
    var drm = false
    var vivid = false
    var writes = 0
    var language = "zh-Hans"
    var productionType = 7
    fun sourceFrom() = from
    fun getPlayIndex(): PlayIndex? = mVodIndex.mVodList.getOrNull(resolved)
    fun getResolvedIndex() = resolved
    fun setResolvedIndex(index: Int) { writes++; resolved = index }
    fun isHdr() = hdr
    fun isDolbyVision() = dolby
    fun isHdrVivid() = vivid
    fun isDrm() = drm
    fun getCurrentLanguage() = language
    fun getCurProductionType() = productionType
    fun findMaxAvailableQuality(target: Int): Pair<Int, Int>? = mVodIndex.mVodList
        .mapIndexed { index, value -> index to value.mQuality }
        .filter { it.second in 1..target }.maxByOrNull { it.second }
    companion object {
        const val SOURCE_FROM_FLASH = 1
        const val SOURCE_FROM_NORMAL = 0
        const val QUALITY_HDR = 125
        const val QUALITY_DOLBY = 126
        const val QUALITY_HDRVivid = 130
    }
}

// From PlayConfig.kt
class PlayConfig {
    enum class PlayConfigType { MINIPLAYER, OTHER }
    class PlayMenuConfig(val allowed: Boolean, val type: PlayConfigType, val items: List<*>?) {
        constructor(allowed: Boolean, type: PlayConfigType) : this(allowed, type, null)
    }
}
