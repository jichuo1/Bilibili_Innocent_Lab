package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType

internal data class FeedbackTag(val id: Long, val name: String)

/**
 * Theseus 的卡片 → 三点数据 → 原因列表。只认同一模型包中的唯一结构路径。
 * 原因主构造器的语义由 RelateDislike mapper 证实为 id/mid/tagId/rid/name；
 * 混淆字段通过哨兵回读标定，不依赖字段名、声明顺序或面板文案。
 */
internal class RelatedFeedbackTags private constructor(
    val cardClass: Class<*>,
    private val paths: List<List<Field>>,
    private val tagField: Field,
    private val nameField: Field
) {
    /** 推荐构建路径只读取 id，空名单时连反射也不做。 */
    fun matches(card: Any, ids: Set<Long>, sessionIds: Set<Long>): Boolean {
        if ((ids.isEmpty() && sessionIds.isEmpty()) || !cardClass.isInstance(card)) return false
        for (path in paths) {
            var value: Any? = card
            for (field in path) value = value?.let { field.get(it) }
            val reasons = value as? List<*> ?: continue
            for (index in 0 until minOf(reasons.size, 64)) {
                val reason = reasons[index] ?: continue
                if (!tagField.declaringClass.isInstance(reason)) continue
                val id = tagField.getLong(reason)
                if (id > 0 && (id in ids || id in sessionIds)) return true
            }
        }
        return false
    }

    fun read(card: Any): List<FeedbackTag> {
        if (!cardClass.isInstance(card)) return emptyList()
        return paths.flatMap { path ->
            var value: Any? = card
            for (field in path) value = value?.let { field.get(it) }
            (value as? List<*>)?.take(64).orEmpty().mapNotNull { reason ->
                if (reason == null || !tagField.declaringClass.isInstance(reason)) return@mapNotNull null
                val id = tagField.getLong(reason)
                val name = (nameField.get(reason) as? String)?.trim().orEmpty()
                if (id > 0 && name.isNotEmpty()) FeedbackTag(id, name) else null
            }
        }.distinctBy(FeedbackTag::id).take(16)
    }

    companion object {
        fun resolve(cardClass: Class<*>): RelatedFeedbackTags? = runCatching {
            val prefix = cardClass.name.substringBeforeLast('.') + "."
            // Card type enum is an independent anchor; unrelated model graphs cannot qualify.
            if (fields(cardClass).count { it.type.name == prefix + "RelateCardType" } != 1) {
                return@runCatching null
            }
            val paths = ArrayList<Pair<List<Field>, Class<*>>>()
            fun visit(type: Class<*>, path: List<Field>, seen: Set<Class<*>>) {
                if (path.size >= 3 || type in seen) return
                for (field in fields(type)) {
                    if (List::class.java.isAssignableFrom(field.type)) {
                        val element = (field.genericType as? ParameterizedType)
                            ?.actualTypeArguments?.singleOrNull() as? Class<*> ?: continue
                        if (element.name.startsWith(prefix)) paths.add((path + field) to element)
                    } else if (field.type.name.startsWith(prefix) && !field.type.isEnum) {
                        visit(field.type, path + field, seen + type)
                    }
                }
            }
            visit(cardClass, emptyList(), emptySet())
            val signature = arrayOf(Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
            val candidates = paths.filter { (_, element) ->
                element.declaredConstructors.any { it.parameterTypes.contentEquals(signature) } &&
                    fields(element).map { it.type }.groupingBy { it }.eachCount() == mapOf(
                        Long::class.javaPrimitiveType to 3, Int::class.javaPrimitiveType to 1,
                        String::class.java to 1
                    )
            }
            val reasonClass = candidates.map { it.second }.distinct().singleOrNull()
                ?: return@runCatching null
            val ctor = reasonClass.declaredConstructors.single { it.parameterTypes.contentEquals(signature) }
            ctor.isAccessible = true
            val sample = ctor.newInstance(101L, 202L, 303L, 404, "BILabReasonProbe")
            val tag = fields(reasonClass).single {
                it.type == Long::class.javaPrimitiveType && it.getLong(sample) == 303L
            }
            val name = fields(reasonClass).single {
                it.type == String::class.java && it.get(sample) == "BILabReasonProbe"
            }
            RelatedFeedbackTags(cardClass, candidates.map { it.first }, tag, name)
        }.getOrNull()

        private fun fields(type: Class<*>): List<Field> = type.declaredFields.filter {
            !Modifier.isStatic(it.modifiers) && !it.isSynthetic
        }.onEach { it.isAccessible = true }
    }
}
