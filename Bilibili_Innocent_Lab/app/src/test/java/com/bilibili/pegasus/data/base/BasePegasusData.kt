package com.bilibili.pegasus.data.base

import com.bilibili.app.comm.list.common.api.model.PlayerArgs

open class BasePegasusData {
    open fun getBizType(): String = "UGC"
    open fun getCardType(): String = "CARD_TYPE_AV"
    open fun getAdInfo(): Object? = null
    open fun getCardGoto(): String = "av"
    open fun getGoTo(): String = "av"
    open fun getUri(): String = "bilibili://video/test"
    open fun getParam(): String = "BV1TEST"
    open fun getTitle(): String = "title"
    open fun getSubtitle(): String = "subtitle"
    open fun getDesc(): String = "desc"
    open fun getPlayerArgs(): PlayerArgs = PlayerArgs()
}
