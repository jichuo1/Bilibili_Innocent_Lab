package com.bilibili.search2.discover

import com.bilibili.search2.api.SearchSquareType
import com.bilibili.search2.api.SearchReferral
import com.bilibili.search2.api.NegativeFeedback

class History(val value: String)
class Cell<T>(private var value: T?) {
    var writes = 0
    var ignore = false
    fun getValue(): T? = value
    fun setValue(next: T?) { writes++; if (!ignore) value = next }
}
class g(@JvmField val values: List<SearchReferral.Guess>, val title: String?, @JvmField val feedback: NegativeFeedback?)
class r {
    @JvmField var sections: List<SearchSquareType>? = null
    var hot = false
    @JvmField val recommendation = Cell<g>(null)
    val discovery get() = recommendation.getValue()?.values?.isNotEmpty() == true
    val feedback get() = recommendation.getValue()?.feedback != null
    var history: List<History> = emptyList()
}
class q(@JvmField val model: r) {
    fun getHistoryList(): List<History> = model.history
    fun f(values: List<SearchSquareType>) {
        model.sections = values
        for (value in values) when (value.getType()) {
            "trending" -> model.hot = true
            "recommend" -> model.recommendation.setValue(g(listOf(SearchReferral.Guess()),"",NegativeFeedback()))
        }
    }
    fun b(values: List<SearchReferral.Guess>) {
        model.recommendation.setValue(g(values,"",model.recommendation.getValue()?.feedback))
    }
    fun e(values: List<History>) { model.history = values }
}

class LegacyPayload(@JvmField val values: List<SearchReferral.Guess>, val title: String?,
    @JvmField val feedback: NegativeFeedback?, val expand: Long, val close: Long)
class LegacyVM {
    @JvmField var sections: List<SearchSquareType>? = null
    @JvmField val recommendation = Cell<LegacyPayload>(null)
    inner class Callback {
        fun getHistoryList(): List<History> = emptyList()
        fun accept(items: List<SearchSquareType>) { sections = items }
        @Suppress("UNUSED_PARAMETER")
        fun update(items: List<SearchReferral.Guess>) = Unit
    }
}
