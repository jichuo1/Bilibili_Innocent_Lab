package com.bapis.bilibili.app.interfaces.v1

import com.bilibili.lib.moss.api.MossResponseHandler

class DefaultWordsReq
class DefaultWordsReply(val showRaw: String = "", val wordRaw: String = "", val valueRaw: String = "",
    val route: Any = Any(), val failAt: String = "") {
    fun getShow() = showRaw
    fun getWord() = wordRaw
    fun getValue() = valueRaw
    class Builder(private val original: DefaultWordsReply) {
        private var show = original.showRaw
        private var word = original.wordRaw
        private var value = original.valueRaw
        fun clearShow() = apply { check(original.failAt != "show"); show = "" }
        fun clearWord() = apply { check(original.failAt != "word"); word = "" }
        fun clearValue() = apply { check(original.failAt != "value"); value = "" }
        fun build() = DefaultWordsReply(show, word, value, original.route)
    }
    companion object { @JvmStatic fun newBuilder(original: DefaultWordsReply) = Builder(original) }
}
class SearchMoss {
    fun executeDefaultWords(request: DefaultWordsReq) = DefaultWordsReply(route = request)
    fun defaultWords(request: DefaultWordsReq, callback: MossResponseHandler) { callback.onNext(executeDefaultWords(request)); callback.onCompleted() }
}
