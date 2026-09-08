package com.bapis.bilibili.app.dynamic.v2

class DynamicItem
class DynamicList(val items: List<DynamicItem> = emptyList()) {
    fun getListList() = items
    fun getList(index: Int) = items[index]
    class Builder(original: DynamicList) {
        private var items = original.items
        fun clearList() = apply { items = emptyList() }
        fun addAllList(values: Iterable<DynamicItem>) = apply { items = values.toList() }
        fun build() = DynamicList(items)
    }
    companion object { @JvmStatic fun newBuilder(original: DynamicList) = Builder(original) }
}
class UpListItem(val live: Int, val position: Long, val fail: Boolean = false) {
    fun getLiveStateValue() = live
    class Builder(private val original: UpListItem) {
        private var position = original.position
        fun setPos(value: Long) = apply { check(!original.fail); position = value }
        fun build() = UpListItem(original.live, position)
    }
    companion object { @JvmStatic fun newBuilder(original: UpListItem) = Builder(original) }
}
class CardVideoUpList(
    val first: List<UpListItem> = emptyList(), val second: List<UpListItem> = emptyList(),
    val failSecond: Boolean = false
) {
    fun getListList() = first
    fun getListSecondList() = second
    class Builder(private val original: CardVideoUpList) {
        private var first = original.first
        private var second = original.second
        fun clearList() = apply { first = emptyList() }
        fun addAllList(values: Iterable<UpListItem>) = apply { first = values.toList() }
        fun clearListSecond() = apply { second = emptyList() }
        fun addAllListSecond(values: Iterable<UpListItem>) = apply {
            check(!original.failSecond); second = values.toList()
        }
        fun build() = CardVideoUpList(first, second)
    }
    companion object { @JvmStatic fun newBuilder(original: CardVideoUpList) = Builder(original) }
}
class DynAllReply(
    val items: DynamicList = DynamicList(), val ups: CardVideoUpList = CardVideoUpList(),
    val topic: Boolean = true, val unrelated: Any = Any(), val failBuild: Boolean = false,
    val upPresent: Boolean = true, val failClear: Boolean = false
) {
    fun getDynamicList() = items
    fun getUpList() = ups
    fun hasUpList() = upPresent
    fun hasTopicList() = topic
    class Builder(private val original: DynAllReply) {
        private var items = original.items
        private var ups = original.ups
        private var topic = original.topic
        private var upPresent = original.upPresent
        fun clearUpList() = apply { if (!original.failClear) { ups = CardVideoUpList(); upPresent = false } }
        fun setDynamicList(value: DynamicList) = apply { items = value }
        fun setUpList(value: CardVideoUpList) = apply { ups = value }
        fun clearTopicList() = apply { topic = false }
        fun build(): DynAllReply {
            check(!original.failBuild)
            return DynAllReply(items, ups, topic, original.unrelated, upPresent = upPresent)
        }
    }
    companion object {
        private val DEFAULT = DynAllReply(topic = false, upPresent = false)
        @JvmStatic fun getDefaultInstance() = DEFAULT
        @JvmStatic fun newBuilder(original: DynAllReply) = Builder(original)
    }
}
class DynVideoReply(val ups: CardVideoUpList = CardVideoUpList(), val upPresent: Boolean = true,
    val items: DynamicList = DynamicList(), val unrelated: Any = Any()) {
    fun getVideoUpList() = ups
    fun hasVideoUpList() = upPresent
    fun getDynamicList() = items
    class Builder(private val original: DynVideoReply) {
        private var present = original.upPresent
        private var ups = original.ups
        fun clearVideoUpList() = apply { present = false; ups = CardVideoUpList() }
        fun build() = DynVideoReply(ups, present, original.items, original.unrelated)
    }
    companion object {
        private val DEFAULT = DynVideoReply(upPresent = false)
        @JvmStatic fun getDefaultInstance() = DEFAULT
        @JvmStatic fun newBuilder(original: DynVideoReply) = Builder(original)
    }
}
@Suppress("UNUSED_PARAMETER")
class DynamicMoss {
    fun executeDynAll(request: Any): DynAllReply = DynAllReply(unrelated = request)
    fun executeDynVideo(request: Any): DynVideoReply = DynVideoReply(unrelated = request)
    fun dynAll(request: Any, handler: com.bilibili.lib.moss.api.MossResponseHandler) = Unit
    fun dynVideo(request: Any, handler: com.bilibili.lib.moss.api.MossResponseHandler) = Unit
}
