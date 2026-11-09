package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** 混排 Holder 和多套评论模型共存；成员缓存必须属于实际 Class，不能由首条数据决定。 */
internal class CommentRawTextReader {
    private data class ItemPlan(val modern: Method?, val legacy: Method?)
    private data class RichPlan(val reader: CommentRichTextReader?)
    private val items = ConcurrentHashMap<Class<*>, ItemPlan>()
    private val richTexts = ConcurrentHashMap<Class<*>, RichPlan>()

    fun read(item: Any?, modern: Boolean = true): String? {
        if (item == null) return null
        val plan = items.computeIfAbsent(item.javaClass) { type ->
            fun getter(name: String) = KavaMemberLookup.inheritedMethodOrNull(type, name)
                ?.takeUnless { it.returnType.isPrimitive }
            ItemPlan(getter("f"), getter("z"))
        }
        fun readWith(method: Method?): String? = runCatching {
            val rich = method?.invoke(item) ?: return@runCatching null
            val reader = richTexts.computeIfAbsent(rich.javaClass) {
                RichPlan(CommentRichTextReader.resolve(it))
            }.reader ?: return@runCatching null
            reader.read(rich)?.takeIf { it.isNotBlank() }
        }.getOrNull()
        return (if (modern) readWith(plan.modern) else null) ?: readWith(plan.legacy)
    }
}
