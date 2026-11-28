package com.bapis.bilibili.app.interfaces.v1

import com.bilibili.lib.moss.api.CallOptions
import com.bilibili.lib.moss.api.MossResponseHandler

// From SearchMoss.kt
class DefaultWordsReq
class DefaultWordsReply(val showRaw: String = "", val wordRaw: String = "", val valueRaw: String = "",
    val route: Any = Any(), val failAt: String = "") {
    fun getShow() = showRaw
    fun getWord() = wordRaw
    fun getValue() = valueRaw
    fun toByteArray(): ByteArray = java.io.ByteArrayOutputStream().use { output ->
        java.io.DataOutputStream(output).use { stream ->
            listOf(showRaw, wordRaw, valueRaw, route.toString(), failAt).forEach(stream::writeUTF)
        }
        output.toByteArray()
    }
    class Builder(private val original: DefaultWordsReply) {
        private var show = original.showRaw
        private var word = original.wordRaw
        private var value = original.valueRaw
        fun clearShow() = apply { check(original.failAt != "show"); show = "" }
        fun clearWord() = apply { check(original.failAt != "word"); word = "" }
        fun clearValue() = apply { check(original.failAt != "value"); value = "" }
        fun build() = DefaultWordsReply(show, word, value, original.route)
    }
    companion object {
        @JvmStatic fun newBuilder(original: DefaultWordsReply) = Builder(original)
        @JvmStatic fun parseFrom(bytes: ByteArray): DefaultWordsReply =
            java.io.DataInputStream(java.io.ByteArrayInputStream(bytes)).use {
                DefaultWordsReply(it.readUTF(), it.readUTF(), it.readUTF(), it.readUTF(), it.readUTF())
            }
    }
}
class SearchMoss {
    fun executeDefaultWords(request: DefaultWordsReq) = DefaultWordsReply(route = request)
    fun defaultWords(request: DefaultWordsReq, callback: MossResponseHandler) { callback.onNext(executeDefaultWords(request)); callback.onCompleted() }
}

// From ContentInterfaceFixtures.kt
/** 行为测试的协议替身，只复现已验真的签名；不包含宿主反编译实现。 */
abstract class ContentFixtureMessage
abstract class ContentFixtureBuilder { abstract fun build(): ContentFixtureMessage }

class Cursor(private val max: Long, private val type: Int) : ContentFixtureMessage() {
    fun getMax(): Long = max
    fun getMaxTp(): Int = type
    class Builder : ContentFixtureBuilder() {
        private var max = 0L
        private var type = 0
        fun setMax(value: Long): Builder = apply { max = value }
        fun setMaxTp(value: Int): Builder = apply { type = value }
        override fun build(): ContentFixtureMessage = Cursor(max, type)
    }
    companion object { @JvmStatic fun newBuilder(): Builder = Builder() }
}

class CursorV2Req(val cursor: Cursor?, val local: Boolean) : ContentFixtureMessage() {
    class Builder : ContentFixtureBuilder() {
        private var cursor: Cursor? = null
        private var local = true
        fun setCursor(value: Cursor): Builder = apply { cursor = value }
        fun setIsLocal(value: Boolean): Builder = apply { local = value }
        override fun build(): ContentFixtureMessage = CursorV2Req(cursor, local)
    }
    companion object { @JvmStatic fun newBuilder(): Builder = Builder() }
}

class CardUGC(private val mid: Long, private val name: String, private val duration: Long) {
    fun getMid(): Long = mid
    fun getName(): String = name
    fun getDuration(): Long = duration
}

class CursorItem(private val oid: Long, private val title: String, private val uri: String,
    private val viewed: Long, private val ugc: CardUGC?) {
    fun hasCardUgc(): Boolean = ugc != null
    fun getCardUgc(): CardUGC = ugc ?: CardUGC(0, "", 0)
    fun getOid(): Long = oid
    fun getTitle(): String = title
    fun getUri(): String = uri
    fun getViewAt(): Long = viewed
}

class CursorV2Reply(private val items: List<CursorItem>, private val cursor: Cursor?, private val more: Boolean) {
    fun getItemsList(): List<CursorItem> = items
    fun getCursor(): Cursor = cursor ?: Cursor(0, 0)
    fun hasCursor(): Boolean = cursor != null
    fun getHasMore(): Boolean = more
}

class HistoryMoss(val address: String, val port: Int, val options: CallOptions) {
    fun executeCursorV2(request: CursorV2Req): CursorV2Reply {
        calls++
        lastRequest = request
        lastTimeout = options.getTimeoutInMs()
        return reply
    }
    companion object {
        var calls = 0
        var lastRequest: CursorV2Req? = null
        var lastTimeout: Long? = null
        var reply = CursorV2Reply(emptyList(), null, false)
    }
}

class SearchArchiveReq(val mid: Long, val pn: Long, val ps: Long, val keyword: String) : ContentFixtureMessage() {
    class Builder : ContentFixtureBuilder() {
        private var mid = 0L
        private var pn = 0L
        private var ps = 0L
        private var keyword = "wrong"
        fun setMid(value: Long): Builder = apply { mid = value }
        fun setPn(value: Long): Builder = apply { pn = value }
        fun setPs(value: Long): Builder = apply { ps = value }
        fun setKeyword(value: String): Builder = apply { keyword = value }
        override fun build(): ContentFixtureMessage = SearchArchiveReq(mid, pn, ps, keyword)
    }
    companion object { @JvmStatic fun newBuilder(): Builder = Builder() }
}

class Arc(private val archive: com.bapis.bilibili.app.archive.v1.Arc?, private val uri: String) {
    fun hasArchive(): Boolean = archive != null
    fun getArchive(): com.bapis.bilibili.app.archive.v1.Arc = archive ?: com.bapis.bilibili.app.archive.v1.Arc(0, "", 0, null)
    fun getUri(): String = uri
}

class SearchArchiveReply(private val archives: List<Arc>, private val total: Long) {
    fun getArchivesList(): List<Arc> = archives
    fun getTotal(): Long = total
}

class SpaceMoss(val address: String, val port: Int, val options: CallOptions) {
    fun executeSearchArchive(request: SearchArchiveReq): SearchArchiveReply {
        calls++
        lastRequest = request
        lastTimeout = options.getTimeoutInMs()
        return reply
    }
    companion object {
        var calls = 0
        var lastRequest: SearchArchiveReq? = null
        var lastTimeout: Long? = null
        var reply = SearchArchiveReply(emptyList(), 0)
    }
}
