package com.bapis.bilibili.polymer.app.search.v1

// 有意缺少作者字段和 hasSpecial：验证两个独立条件不能连带关闭标题/普通广告过滤。
class SearchAv(private val title: String) { fun getTitle() = title }
class Item(private val cm: Boolean, private val av: SearchAv) {
    fun hasCm() = cm
    fun hasAv() = true
    fun getAv() = av
}
class SearchAllResponse(private val items: List<Item> = emptyList()) { fun getItemList() = items }
