package com.bapis.bilibili.app.viewunite.common

// From BadgeInfo.kt
open class BadgeInfo {
    open fun getText(): String = "去小程序"
}

// From CardBasicInfo.kt
open class CardBasicInfo {
    open fun getAuthor(): Owner? = null
    open fun getFromSourceType(): Long = 2L
    open fun getDesc(): String = "UP主 · 2021-06-04"
}

// From RelateAVCard.kt
open class RelateAVCard {
    open fun getDuration(): Long = 120L
    open fun getRcmdReason(): BadgeInfo = BadgeInfo()
    open fun getStat(): Stat = Stat()
}

// From RelateBangumiCard.kt
open class RelateBangumiCard {
    open fun getRcmdReason(): String = "番剧热播"
}

// From RelateBangumiResourceCard.kt
open class RelateBangumiResourceCard {
    open fun getRcmdReason(): String = "立即下载"
}

// From RelatedAICard.kt
open class RelatedAICard {
    open fun getDuration(): Long = 240L
    open fun getStat(): Stat = Stat()
}

// From RelateGameCard.kt
open class RelateGameCard {
    open fun getTagName(): String = ""
    open fun getGameRcmdReason(): String = "预约游戏"
}

// From RelateHistoryAVCard.kt
open class RelateHistoryAVCard {
    open fun getDuration(): Long = 180L
}

// From RelateSpecial.kt
open class RelateSpecial {
    open fun getRcmdReason(): BadgeInfo = BadgeInfo()
}

// From RelateThreePoint.kt
open class RelateThreePoint {
    open fun hasFeedback(): Boolean = false
}

// From Stat.kt
open class Stat {
    open fun getVt(): StatInfo = StatInfo()
}

// From StatInfo.kt
open class StatInfo {
    open fun getValue(): Long = 12_345L
}

// From UgcIntroduction.kt
class DescV2(val text: String)

class UgcIntroduction(val descList: List<DescV2>) {
    fun getDesc(index: Int): DescV2 = descList[index]
}
