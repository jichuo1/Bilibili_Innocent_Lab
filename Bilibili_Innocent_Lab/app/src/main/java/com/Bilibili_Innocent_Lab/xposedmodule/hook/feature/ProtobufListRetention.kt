package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.util.Collections

/**
 * protobuf 列表边界共用的保留/过滤原语。
 *
 * 三种写回方式共用同一条判定：
 * - [retainOrNull] 用于**就地改写消息**（`clear*` + `addAll*`）：全部保留时返回 null，
 *   调用方据此完全跳过写回，既省一次列表重建，也不会把"本来就没东西可删"误报成生效。
 * - [filterOrSame] 用于**改写 getter 返回值**：无命中时返回原 List 本身（调用方可用
 *   `!==` 判断有无变化），有命中才创建不可变副本，不改写宿主内部集合。
 */
internal object ProtobufListRetention {

    /** @return 需要写回的新列表；全部保留时返回 null。 */
    fun retainOrNull(source: List<*>, keep: (Any) -> Boolean): List<Any>? {
        var retained: ArrayList<Any>? = null
        source.forEachIndexed { index, item ->
            if (item != null && keep(item)) {
                retained?.add(item)
            } else {
                if (retained == null) {
                    val target = ArrayList<Any>(source.size)
                    for (copyIndex in 0 until index) {
                        source[copyIndex]?.let(target::add)
                    }
                    retained = target
                }
            }
        }
        return retained
    }

    /** @return 无命中时是原 List 本身；有命中时是过滤后的不可变副本。 */
    fun filterOrSame(source: List<*>, remove: (Any) -> Boolean): List<*> {
        var filtered: ArrayList<Any?>? = null
        source.forEachIndexed { index, item ->
            if (item != null && remove(item)) {
                if (filtered == null) {
                    val target = ArrayList<Any?>(source.size)
                    for (copyIndex in 0 until index) target.add(source[copyIndex])
                    filtered = target
                }
            } else {
                filtered?.add(item)
            }
        }
        return filtered?.let(Collections::unmodifiableList) ?: source
    }
}
