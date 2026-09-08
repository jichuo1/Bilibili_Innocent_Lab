package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

internal object SearchHomeRecommendPolicy {
    fun blocked(type: String?): Boolean = type == "trending" || type == "recommend"

    /** No title/keyword/history reads. Never invent a history record or erase an unknown section. */
    fun filter(source: List<*>, unsafeEmpty: () -> Unit = {}, typeOf: (Any) -> String?): List<*> {
        val result = CopyOnFilter.list(source) { blocked(typeOf(it)) }
        // Legacy delivery ignores an empty section list and can strand local history state.
        // An anomalous all-recommendation response must fail open instead of hiding history.
        return if (source.isNotEmpty() && result.isEmpty()) {
            unsafeEmpty()
            source
        } else result
    }
}
