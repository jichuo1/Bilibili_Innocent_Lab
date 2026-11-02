package com.bapis.bilibili.app.view.v1

open class Relate {
    open fun getAuthor(): com.bapis.bilibili.app.archive.v1.Author? = null
    open fun getTagName(): String = ""
    open fun getGoto(): String = "av"
    open fun getFromSourceType(): Long = 2L
    open fun getDuration(): Long = 300L
    open fun getRcmdReason(): String = "3万点赞"
    open fun getRcmdReasonExtra(): String = ""
    open fun getRcmdReasonStyle(): ReasonStyle = ReasonStyle()
    open fun getDesc(): String = "UP主 · 3天前"
}
