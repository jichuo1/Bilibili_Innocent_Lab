package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.reflect.Field
import java.lang.reflect.Method

/** 仅保存成员。9.16.1 IntroAdapter 移除 getter，updateData/getItemCount 共用唯一 List 字段。 */
internal class InsightAdapterItems private constructor(private val getter: Method?, private val field: Field?, val write: Method) {
    val readKind: String get() = if (getter != null) "method" else "field"
    fun read(adapter: Any): List<*>? = (getter?.invoke(adapter) ?: field?.get(adapter)) as? List<*>
    companion object {
        fun resolve(type: Class<*>): InsightAdapterItems? {
            val write = Lookup.declaredMethods(type, true) { !it.isStatic && it.returnType == Void.TYPE &&
                it.parameterTypes.toList() == listOf(List::class.java) }.singleOrNull() ?: return null
            val getter = Lookup.inheritedMethodOrNull(type, "getItems")?.takeIf {
                !it.isStatic && List::class.java.isAssignableFrom(it.returnType)
            }
            // 只查 adapter 本类，排除父类缓存；多个候选明确拒绝，不猜混淆字段名。
            val field = if (getter == null) Lookup.declaredFields(type, true) {
                !it.isStatic && List::class.java.isAssignableFrom(it.type)
            }.singleOrNull() ?: return null else null
            return InsightAdapterItems(getter, field, write)
        }
    }
}
