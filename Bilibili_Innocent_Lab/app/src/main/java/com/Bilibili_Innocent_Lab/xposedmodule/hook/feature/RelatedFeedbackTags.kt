package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType

internal data class FeedbackTag(val id: Long, val name: String)

/**
 * Theseus 的卡片 → 三点数据 → 原因列表。只认旧模型包和已核实的迁移类型。
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
            val relocated = cardClass.name == RELOCATED_CARD
            val cardTypeName = if (relocated) RELOCATED_CARD_TYPE else prefix + "RelateCardType"
            fun isCardModel(type: Class<*>): Boolean =
                if (relocated) type.name in RELOCATED_MODELS else type.name.startsWith(prefix)
            // Card type enum is an independent anchor; unrelated model graphs cannot qualify.
            if (fields(cardClass).count { it.type.name == cardTypeName } != 1) {
                return@runCatching null
            }
            val paths = ArrayList<Pair<List<Field>, Class<*>>>()
            fun visit(type: Class<*>, path: List<Field>, seen: Set<Class<*>>) {
                if (path.size >= 3 || type in seen) return
                for (field in fields(type)) {
                    if (List::class.java.isAssignableFrom(field.type)) {
                        val element = (field.genericType as? ParameterizedType)
                            ?.actualTypeArguments?.singleOrNull() as? Class<*> ?: continue
                        if (isCardModel(element)) paths.add((path + field) to element)
                    } else if (isCardModel(field.type) && !field.type.isEnum) {
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
            val ctor = KavaMemberLookup.declaredConstructors(reasonClass, makeAccessible = true) {
                it.parameterTypes.contentEquals(signature)
            }.single()
            val sample = ctor.newInstance(101L, 202L, 303L, 404, "BILabReasonProbe")
            val tag = fields(reasonClass).single {
                it.type == Long::class.javaPrimitiveType && it.getLong(sample) == 303L
            }
            val name = fields(reasonClass).single {
                it.type == String::class.java && it.get(sample) == "BILabReasonProbe"
            }
            RelatedFeedbackTags(cardClass, candidates.map { it.first }, tag, name)
        }.getOrNull()

        // 9.19.0: bjx1.d -> iox1.a/b -> hkx1.f -> List<wmx1>; b660.h maps DislikeReasons.tagId.
        private const val RELOCATED_CARD = "bjx1"
        private const val RELOCATED_CARD_TYPE = "com.bilibili.ship.theseus.united.page.intro.module.relate.RelateCardType"
        private val RELOCATED_MODELS = setOf("iox1", "hkx1", "wmx1")

        private fun fields(type: Class<*>): List<Field> = KavaMemberLookup.declaredFields(type, makeAccessible = true) {
            !Modifier.isStatic(it.modifiers) && !it.isSynthetic
        }
    }
}
