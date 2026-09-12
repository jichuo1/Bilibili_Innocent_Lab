package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

/** 只沿闭包的实例字段找当前卡片；不进入 View、Activity、集合或静态全局状态。 */
internal object FeedbackCardGraph {
    fun findUnique(roots: List<Any>, accepts: (Any) -> Boolean): Any? {
        if (roots.size > 32) return null
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        var level = roots
        var edges = 0
        repeat(4) {
            val found = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
            val next = ArrayList<Any>()
            for (node in level) {
                if (!seen.add(node)) continue
                if (seen.size > 64) return null
                if (accepts(node)) {
                    found.add(node)
                    continue
                }
                var owner: Class<*>? = node.javaClass
                while (owner != null && traversable(owner)) {
                    for (field in owner.declaredFields) {
                        if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                        if (++edges > 256) return null
                        val value = runCatching {
                            field.isAccessible = true
                            field.get(node)
                        }.getOrNull() ?: continue
                        if (accepts(value)) found.add(value)
                        else if (traversable(value.javaClass)) next.add(value)
                    }
                    owner = owner.superclass
                }
            }
            if (found.size > 1) return null
            if (found.size == 1) return found.single()
            level = next
        }
        return null
    }

    private fun traversable(type: Class<*>): Boolean {
        val name = type.name
        return !type.isArray && !type.isEnum &&
            listOf("java.", "javax.", "android.", "androidx.", "kotlin.", "kotlinx.")
                .none(name::startsWith) &&
            generateSequence(type) { it.superclass }.none {
                it.name == "android.view.View" || it.name == "android.content.Context" ||
                    it.name == "androidx.fragment.app.Fragment"
            }
    }
}
