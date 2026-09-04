package com.bapis.bilibili.app.view.v1

open class Relate {
    open fun getGoto(): String = "av"
    open fun getFromSourceType(): Long = 2L
    open fun getDuration(): Long = 300L
    open fun getRcmdReason(): String = "3万点赞"
    open fun getRcmdReasonExtra(): String = ""
    open fun getRcmdReasonStyle(): ReasonStyle = ReasonStyle()
}
