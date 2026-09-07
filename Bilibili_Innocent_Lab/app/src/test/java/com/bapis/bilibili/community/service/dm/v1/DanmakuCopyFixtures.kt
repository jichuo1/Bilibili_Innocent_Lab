package com.bapis.bilibili.community.service.dm.v1

class DanmakuElem(private val weight: Int) { fun getWeight() = weight }
class DmColorful(private val type: Int) { fun getTypeValue() = type }
class DmSegMobileReply(
    val elems: List<DanmakuElem> = emptyList(),
    val colorful: List<DmColorful> = emptyList(),
    val unrelated: Any = Any(),
    val failAt: String = ""
) {
    fun getElemsList() = elems
    fun getColorfulSrcList() = colorful
    class Builder(private val original: DmSegMobileReply) {
        private var elems = original.elems
        private var colorful = original.colorful
        fun clearElems() = apply { elems = emptyList() }
        fun addAllElems(values: Iterable<DanmakuElem>) = apply {
            check(original.failAt != "elems"); elems = values.toList()
        }
        fun clearColorfulSrc() = apply { colorful = emptyList() }
        fun addAllColorfulSrc(values: Iterable<DmColorful>) = apply {
            check(original.failAt != "colorful"); colorful = values.toList()
        }
        fun build(): DmSegMobileReply {
            check(original.failAt != "build")
            return DmSegMobileReply(elems, colorful, original.unrelated)
        }
    }
    companion object {
        private val DEFAULT = DmSegMobileReply()
        @JvmStatic fun getDefaultInstance() = DEFAULT
        @JvmStatic fun newBuilder(original: DmSegMobileReply) = Builder(original)
    }
}
