package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/** 列表过滤公共边界：无命中返回原实例，首次命中时才复制已遍历前缀。 */
internal object CopyOnFilter {

    inline fun list(source: List<*>, shouldRemove: (Any) -> Boolean): List<*> {
        var filtered: ArrayList<Any?>? = null
        source.forEachIndexed { index, item ->
            if (item != null && shouldRemove(item)) {
                if (filtered == null) {
                    val target = ArrayList<Any?>(source.size)
                    for (copyIndex in 0 until index) target.add(source[copyIndex])
                    filtered = target
                }
            } else {
                filtered?.add(item)
            }
        }
        return filtered ?: source
    }
}
