package com.bilibili.search2.discover
import com.bilibili.search2.api.SearchSquareType
import com.bilibili.search2.api.SearchReferral
class RenamedDelivery(@JvmField val model: r) {
    fun getHistoryList(): List<History> = model.history
    fun receive(items: List<SearchSquareType>) { model.sections = items }
    fun refresh(items: List<SearchReferral.Guess>) { model.recommendation.setValue(g(items, "", null)) }
}
class WrongDelivery { fun getHistoryList(): List<String> = emptyList(); fun receive(items: List<String>) = Unit }
