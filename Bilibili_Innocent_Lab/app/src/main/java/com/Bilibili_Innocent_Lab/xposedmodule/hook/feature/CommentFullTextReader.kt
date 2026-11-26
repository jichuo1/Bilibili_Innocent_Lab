package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** 8.90.2 C2 的三个输入依次是完整正文、紧凑正文和点赞尾部；只读本次控件保留的完整源。 */
internal class CommentFullTextReader {
    private data class Plan(val full: Field?)
    private val plans = ConcurrentHashMap<Class<*>, Plan>()
    private data class ClickPlan(val enabled: Method?)
    private val clickPlans = ConcurrentHashMap<Class<*>, ClickPlan>()

    /** 8.90.2 回复预览的前置昵称 Link.b 使用 f00.u / f00.i（或混淆后的同名结构类），而非 Android ClickableSpan。 */
    fun isNicknameSpan(span: Any): Boolean {
        val getter = clickPlans.computeIfAbsent(span.javaClass) { type ->
            if (type.name != "f00.u" && View::class.java.isAssignableFrom(type)) return@computeIfAbsent ClickPlan(null)
            val enabled = Lookup.methodOrNull(type, "getClickable")
            val click = Lookup.methodOrNull(type, "onClick", View::class.java)
            ClickPlan(enabled?.takeIf {
                it.returnType == Boolean::class.javaPrimitiveType && click?.returnType == Void.TYPE
            })
        }.enabled ?: return false
        return runCatching { getter.invoke(span) == true }.getOrDefault(false)
    }

    fun read(view: Any): CharSequence? {
        val field = plans.computeIfAbsent(view.javaClass) { type ->
            if (!isNextLikableTextView(type)) {
                return@computeIfAbsent Plan(null)
            }
            val getter = Lookup.inheritedMethodOrNull(type, "getLikableTextMaxLines")
            if (getter?.returnType != Int::class.javaPrimitiveType) return@computeIfAbsent Plan(null)
            val inputs = listOf("d", "e", "f").map { Lookup.fieldOrNull(type, it) }
            val resolved = if (inputs.all { it?.type == CharSequence::class.java }) {
                inputs[0]
            } else {
                Lookup.declaredFields(type, makeAccessible = true) {
                    !it.isStatic && it.type == CharSequence::class.java
                }.takeIf { it.size == 3 }?.first()
            }
            Plan(resolved)
        }.full ?: return null
        return runCatching { field.get(view) as? CharSequence }.getOrNull()
            ?.takeIf { it.length in 1..6000 && it.isNotBlank() }
    }

    private fun isNextLikableTextView(type: Class<*>): Boolean {
        if (type.name == NEXT_LIKABLE_TEXT_VIEW) return true
        var current: Class<*>? = type.superclass
        repeat(8) {
            val next = current ?: return false
            if (next.name == NEXT_LIKABLE_TEXT_VIEW) return true
            current = next.superclass
        }
        return false
    }

    private companion object {
        const val NEXT_LIKABLE_TEXT_VIEW = "com.bilibili.app.comment3.ui.nextwidget.NextLikableTextView"
    }
}
