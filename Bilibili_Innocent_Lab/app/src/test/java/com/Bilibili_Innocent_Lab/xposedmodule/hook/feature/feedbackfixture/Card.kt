package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.feedbackfixture

enum class RelateCardType { AV }
class Card(@JvmField val type: RelateCardType, @JvmField val menu: Menu?)
class Menu(@JvmField val dislike: Reasons?, @JvmField val feedback: Reasons?)
class Reasons(@JvmField val list: List<Reason>)
class Reason(id: Long, mid: Long, tagId: Long, rid: Int, name: String) {
    // Deliberately reorder fields: declaration order is not constructor order.
    @JvmField val c: Int = rid
    @JvmField val e: String = name
    @JvmField val b: Long = mid
    @JvmField val d: Long = tagId
    @JvmField val a: Long = id
}
