package com.bapis.bilibili.polymer.app.search.v1

// 有意缺少作者字段和 hasSpecial：验证两个独立条件不能连带关闭标题/普通广告过滤。
class SearchAv(private val title: String) { fun getTitle() = title }
class Item(private val cm: Boolean, private val av: SearchAv) {
    fun hasCm() = cm
    fun hasAv() = true
    fun getAv() = av
}
class SearchAllResponse(private val items: List<Item> = emptyList()) {
    fun getItemList() = items
    fun toByteArray(): ByteArray = java.io.ByteArrayOutputStream().also { out ->
        java.io.DataOutputStream(out).use { s ->
            s.writeInt(items.size)
            items.forEach { item ->
                s.writeBoolean(item.hasCm())
                s.writeUTF(item.getAv().getTitle())
            }
        }
    }.toByteArray()
    class Builder(source: SearchAllResponse) {
        private val list = source.items.toMutableList()
        fun clearItem() = apply { list.clear() }
        fun addAllItem(values: Iterable<Item>) = apply { list.addAll(values) }
        fun build() = SearchAllResponse(list.toList())
    }
    companion object {
        @JvmStatic fun newBuilder(source: SearchAllResponse) = Builder(source)
        @JvmStatic fun parseFrom(bytes: ByteArray): SearchAllResponse =
            java.io.DataInputStream(java.io.ByteArrayInputStream(bytes)).use { s ->
                val count = s.readInt()
                SearchAllResponse(List(count) { Item(s.readBoolean(), SearchAv(s.readUTF())) })
            }
    }
}
class SearchByTypeResponse(private val items: List<Item> = emptyList()) { fun getItemsList() = items }
