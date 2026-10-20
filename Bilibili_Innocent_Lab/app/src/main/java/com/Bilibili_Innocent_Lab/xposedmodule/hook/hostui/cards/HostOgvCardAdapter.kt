package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HostCardSemanticAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 仅接有已证单卡语义且实际提供 Modifier 的入口；列表/标签/inline LazyItemScope 保留原生。 */
internal object HostOgvCardAdapter {
    private const val MODEL = "com.bilibili.ogv.operation3.module.operablecard.a"
    private const val MODIFIER = "androidx.compose.ui.Modifier"
    private const val COMPOSER = "androidx.compose.runtime.Composer"
    private const val FOLLOW_CAPTURE = "com.bilibili.ogv.operation3.module.followable.CreateFollowableSeasonModel\$invoke\$1"
    internal const val HCARDS = "com.bilibili.ogv.operation3.module.hcards2x2.HCards2x2SupportModule\$provide\$1\$1\$1\$a"
    internal const val VCARDS = "com.bilibili.ogv.operation3.module.vcards3x2.VCards3x2SupportModule\$provide\$1\$1\$1\$a"
    private const val FOLLOW_ITEM = "com.bilibili.ogv.kmm.operation.following.BangumiFollowing\$b"

    class Point(val method: Method, val modifierIndex: Int, val composerIndex: Int,
        val changedIndex: Int, val defaultsIndex: Int? = null, val radiusIndex: Int? = null,
        private val modifier: Class<*>, private val composer: Class<*>, private val model: Field? = null) {
        fun accepts(receiver: Any?, args: Array<Any?>): Boolean = runCatching {
            args.size == method.parameterCount && (modifier.isInstance(args[modifierIndex]) ||
                defaultsIndex != null && args[modifierIndex] == null &&
                (args[defaultsIndex] as? Int)?.let { it and (1 shl modifierIndex) != 0 } == true) &&
                composer.isInstance(args[composerIndex]) && args[changedIndex] is Int &&
                (defaultsIndex == null || args[defaultsIndex] is Int) &&
                (model == null || receiver != null && model.get(receiver) != null)
        }.getOrDefault(false)

        fun rewrite(args: Array<Any?>, supplied: Any, radius: Float) {
            args[modifierIndex] = supplied
            radiusIndex?.let { args[it] = radius }
            val changed = (14 shl (modifierIndex * 3)) or (radiusIndex?.let { 14 shl (it * 3) } ?: 0)
            args[changedIndex] = (args[changedIndex] as Int) and changed.inv()
            defaultsIndex?.let { index ->
                val defaults = (1 shl modifierIndex) or (radiusIndex?.let { 1 shl it } ?: 0)
                args[index] = (args[index] as Int) and defaults.inv()
            }
        }
    }

    data class Family(val id: String, val applicable: Boolean, val points: List<Point>, val reason: String = "missing-entry")
    data class Resolution(val families: List<Family>) {
        val expected: Int get() = families.count { it.applicable }
    }

    fun resolve(loader: ClassLoader): Resolution = resolve(loader, emptyMap())

    fun resolve(loader: ClassLoader, cached: Map<String, HookPoint>): Resolution = resolve(
        { KavaMemberLookup.classOrNull(loader, it) }, cached.mapNotNull { (family, point) ->
            HostCardSemanticAdapter.resolve(loader, family, point)?.let { method ->
                semanticPoint(loader, method)?.let { family to it }
            }
        }.toMap())

    internal fun resolve(type: (String) -> Class<*>?, assisted: Map<String, Point> = emptyMap()): Resolution {
        val modifier = type(MODIFIER)
        val composer = type(COMPOSER)
        val model = type(MODEL)
        if (modifier == null || composer == null) return Resolution(listOf(
            Family("operable", model != null, emptyList(), "missing-compose-abi")))
        val families = ArrayList<Family>()
        val legacy = listOf("com.bilibili.ogv.operation3.module.operablecard.OperableCardUi", "hf3.x")
            .mapNotNull(type).filter { owner -> model != null && owner.declaredFields.any {
                !Modifier.isStatic(it.modifiers) && it.type == model } }
        val legacyPoints = legacy.mapNotNull { legacy(it, modifier, composer) }
        families += Family("operable_legacy", legacy.isNotEmpty(), legacyPoints.takeIf { it.size == 1 }.orEmpty())
        val followCapture = type(FOLLOW_CAPTURE)
        listOf("hcards2x2" to HCARDS, "vcards3x2" to VCARDS).forEach { (id, name) ->
            val owner = type(name)
            val point = if (owner != null && model != null) {
                captured(owner, model, followCapture, modifier, composer)
            } else null
            families += Family(id, owner != null, listOfNotNull(point))
        }
        val item = type(FOLLOW_ITEM)
        // R8 changes the helper type (9.16: Fo0.p); the stable single-card model and
        // full Compose ABI identify this boundary without a helper-name table.
        val following = listOf("com.bilibili.ogv.kmm.operation.following.b",
            "com.bilibili.ogv.kmm.operation.following.c").mapNotNull(type).mapNotNull { owner ->
            item?.let { following(owner, it, null, modifier, composer) }
        }

        families += Family("following", item != null, following.takeIf { it.size == 1 }.orEmpty())
        val standalone = listOf(
            Triple("trending", "ed3.r", "ed3.f\$c"), Triple("double", "sc3.n", "sc3.f\$c"),
            Triple("upcoming", "rc3.l", "rc3.b\$c")
        )
        standalone.forEach { (id, ownerName, modelName) ->
            val owner = type(ownerName)
            val card = type(modelName)
            val helper = type(if (id == "upcoming") "com.bilibili.ogv.operation3.module.followable.i"
                else "com.bilibili.ogv.operation3.module.feedback.v")
            val direct = if (owner != null && card != null && helper != null) {
                following(owner, card, helper, modifier, composer)
            } else null
            val point = listOfNotNull(direct, assisted[id]).distinctBy { it.method }.singleOrNull()
            // 9.15 完整 raw：这三族已变为 LazyItemScope / BoxWithConstraintsScope，均无传入 Modifier。
            val inline = type(when (id) { "trending" -> "Eo0.l"; "double" -> "Fo0.t"; else -> "Go0.d" })
            val inlinePresent = inline?.let { renderer ->
                val fields = renderer.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
                fields.any { it.type == List::class.java } && renderer.interfaces.any {
                    it.name == if (id == "upcoming") "kotlin.jvm.functions.Function3" else "kotlin.jvm.functions.Function4"
                }
            } == true
            families += Family(id, point != null || inlinePresent || owner != null && card != null,
                listOfNotNull(point), if (inlinePresent && point == null) "inline-without-modifier-input" else "missing-entry")
        }
        if (model != null && families.none { it.applicable }) families += Family("operable", true, emptyList())
        return Resolution(families)
    }

    internal fun semanticPoint(loader: ClassLoader, method: Method): Point? {
        val modifier = KavaMemberLookup.classOrNull(loader, MODIFIER) ?: return null
        val composer = KavaMemberLookup.classOrNull(loader, COMPOSER) ?: return null
        val params = method.parameterTypes
        if (params.size !in 5..6 || params[0].isPrimitive ||
            params[0] in setOf(classOf<Any>(), classOf<String>(), classOf<List<*>>(), modifier, composer)) return null
        return following(method.declaringClass, params[0], null, modifier, composer)?.takeIf { it.method == method }
    }

    internal fun captured(owner: Class<*>, model: Class<*>, follow: Class<*>?, modifier: Class<*>, composer: Class<*>): Point? {
        if (owner.interfaces.none { it.name == "kotlin.jvm.functions.Function3" }) return null
        val field = KavaMemberLookup.declaredFields(owner, makeAccessible = true) {
            !Modifier.isStatic(it.modifiers) && it.type == model
        }.singleOrNull() ?: return null
        if (owner.declaredConstructors.none { follow != null && it.parameterTypes.contentEquals(arrayOf(model, follow)) ||
            it.parameterTypes.contentEquals(arrayOf(model)) }) return null
        val entry = methods(owner) { it.name == "invoke" && it.returnType == Any::class.java &&
            it.parameterTypes.contentEquals(arrayOf(Any::class.java, Any::class.java, Any::class.java)) }.singleOrNull() ?: return null
        return Point(entry, 0, 1, 2, modifier = modifier, composer = composer, model = field)
    }

    internal fun following(owner: Class<*>, item: Class<*>, helper: Class<*>?, modifier: Class<*>, composer: Class<*>): Point? {
        val matches = methods(owner) { method ->
            val params = method.parameterTypes
            Modifier.isStatic(method.modifiers) && !method.isBridge && !method.isSynthetic && method.returnType == Void.TYPE && params.size in 5..6 &&
                params[0] == item && params[2] == modifier && params[3] == composer &&
                params[4] == Int::class.javaPrimitiveType && (params.size == 5 || params[5] == Int::class.javaPrimitiveType) &&
                (if (helper != null) params[1] == helper else !params[1].isPrimitive &&
                    params[1] !in setOf(classOf<Any>(), classOf<String>(), classOf<List<*>>(), modifier, composer))
        }
        val method = matches.singleOrNull() ?: return null
        return Point(method, 2, 3, 4, if (method.parameterCount == 6) 5 else null,
            modifier = modifier, composer = composer)
    }

    private fun legacy(owner: Class<*>, modifier: Class<*>, composer: Class<*>): Point? {
        val method = methods(owner) { !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE && it.parameterTypes.contentEquals(
            arrayOf(modifier, Float::class.javaPrimitiveType!!, composer, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!)) }.singleOrNull() ?: return null
        return Point(method, 0, 2, 3, 4, 1, modifier, composer)
    }

    private fun methods(owner: Class<*>, predicate: (Method) -> Boolean) =
        KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { !Modifier.isAbstract(it.modifiers) && predicate(it) }
}
