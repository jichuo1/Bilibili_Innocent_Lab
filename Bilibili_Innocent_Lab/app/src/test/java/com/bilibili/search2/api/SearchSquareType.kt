package com.bilibili.search2.api

class SearchSquareType(private val kind: String?, val token: Any = Any(), val broken: Boolean = false) {
    fun getType(): String? { check(!broken); return kind }
    fun getTitle(): String = error("Filtering must never read a title")
}
class SearchReferral { class Guess }
class NegativeFeedback
