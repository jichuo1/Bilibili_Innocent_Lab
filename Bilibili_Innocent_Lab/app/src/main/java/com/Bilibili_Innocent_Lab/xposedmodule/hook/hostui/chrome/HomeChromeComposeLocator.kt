package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HomeChromeAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HomeChromeAdapter.REPACKAGED_TOP_CONTENT
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** The source facade and full ABI identify a semantic container; R8 method letters do not. */
internal object HomeChromeComposeLocator {
    private const val TOP = "tv.danmaku.bili.khome.widget.topbar.HomeTopTabKt"
    private const val BOTTOM = "tv.danmaku.bili.khome.widget.bottomtab."
    private const val MODIFIER = "androidx.compose.ui.Modifier"
    private const val COMPOSER = "androidx.compose.runtime.Composer"
    private const val F1 = "kotlin.jvm.functions.Function1"
    private const val F2 = "kotlin.jvm.functions.Function2"

    fun verified(loader: ClassLoader, family: String, method: Method): Boolean = runCatching {
        if (Modifier.isAbstract(method.modifiers)) return false
        val owner = method.declaringClass
        if (Lookup.classOrNull(loader, owner.name) !== owner) return false
        if (family == "top_content") return verifiedTopContent(method)
        if (family == "bottom_content") return verifiedContent(method)
        if (!Modifier.isStatic(method.modifiers) || method.returnType != Void.TYPE) return false
        accepts(family, owner.name, method.parameterTypes.map { it.name })
    }.getOrDefault(false)

    internal fun accepts(family: String, owner: String, p: List<String>): Boolean {
        fun model(name: String) = name !in setOf("java.lang.Object", "java.lang.String", MODIFIER, COMPOSER) &&
            !name.startsWith("java.") && !name.startsWith("kotlin.") && !name.startsWith("androidx.") &&
            name.contains('.') && !name.startsWith('[')
        val repackagedTop = owner in HomeChromeAdapter.repackagedOwners("top_backdrop")
        val repackagedBottom = owner in HomeChromeAdapter.repackagedOwners("bottom")
        fun topModel(name: String) = if (repackagedTop) name == "rco0" else owner == TOP && name == "rco0" || model(name)
        fun bottomModel(name: String, flat: String) = if (repackagedBottom) name == flat else model(name)
        fun ownerMatches() = HomeChromeAdapter.acceptsOwner(family, owner)
        return when (family) {
            "top" -> owner == TOP && p.size == 12 && topModel(p[0]) &&
                p.subList(1, 7) == listOf(MODIFIER, "float", "float", "float", "long", "long") &&
                (p.subList(7, 12) == listOf("boolean", F1, F1, COMPOSER, "int") ||
                    p.subList(7, 12) == listOf(F1, F1, COMPOSER, "int", "int"))
            "top_right_actions" -> ownerMatches() && p.size in 5..6 && topModel(p[0]) &&
                p.subList(1, 4) == listOf(MODIFIER, F1, COMPOSER) && p.drop(4).all { it == "int" }
            "top_backdrop" -> ownerMatches() && '$' !in owner &&
                p.size in 4..5 && topModel(p[0]) && p.subList(1, 3) == listOf(F1, COMPOSER) &&
                p.drop(3).all { it == "int" }
            "top_foreground" -> ownerMatches() && '$' !in owner &&
                p.size in 8..9 && topModel(p[0]) &&
                p.subList(1, 7) == listOf("float", MODIFIER, "boolean", "float", F1, COMPOSER) &&
                p.drop(7).all { it == "int" }
            "top_layout" -> ownerMatches() && p.size in 12..13 &&
                p.take(9) == listOf("int", MODIFIER, "long", "long", "float", "float", F1,
                    "kotlin.jvm.functions.Function3", "kotlin.jvm.functions.Function2") &&
                p[9] in setOf("androidx.compose.runtime.internal.ComposableLambda", "kotlin.jvm.functions.Function2") &&
                p[10] == COMPOSER && p.drop(11).all { it == "int" }
            "bottom_item" -> ownerMatches() && '$' !in owner && p.size in 8..9 && p[0] == "int" &&
                (model(p[1]) && p[2].startsWith(BOTTOM) ||
                    owner == "${BOTTOM}a" && p[1] == "i1o0" && p[2] == "h8k") &&
                p[3] == MODIFIER && p[4] == "boolean" && p[5] == "kotlin.jvm.functions.Function3" &&
                p[6] == COMPOSER && p.drop(7).all { it == "int" }
            "bottom" -> ownerMatches() && '$' !in owner && p.size in 11..12 &&
                p.take(5) == listOf(MODIFIER, "java.util.List", F1, F1, "kotlin.jvm.functions.Function4") &&
                bottomModel(p[5], "dlu1") && p[6] == "boolean" && p[7] == F1 && bottomModel(p[8], "klu1") &&
                p[9] == COMPOSER && p.drop(10).all { it == "int" }
            else -> false
        }
    }

    private val contentFields = setOf("java.util.List", "kotlin.jvm.functions.Function4",
        "androidx.compose.runtime.MutableState", "androidx.compose.runtime.snapshots.SnapshotStateMap")

    private val topContentFields = setOf("androidx.compose.foundation.ScrollState",
        "androidx.compose.runtime.internal.ComposableLambda", "kotlin.jvm.functions.Function3")
    private val repackagedTopContentFields = setOf("androidx.compose.foundation.ScrollState",
        "kotlin.jvm.functions.Function3", "androidx.compose.runtime.State", "androidx.compose.runtime.MutableIntState")

    private fun verifiedTopContent(method: Method): Boolean = acceptsTopContent(method.declaringClass.name,
        Modifier.isStatic(method.modifiers), method.returnType.name, method.parameterTypes.map { it.name },
        Lookup.declaredFields(method.declaringClass).filterNot { Modifier.isStatic(it.modifiers) }.map { it.type.name })

    internal fun acceptsTopContent(owner: String, static: Boolean, result: String, p: List<String>, fields: List<String>): Boolean {
        if (!HomeChromeAdapter.acceptsOwner("top_content", owner)) return false
        if (owner == REPACKAGED_TOP_CONTENT) {
            // 9.19 widens the tabs capture to Function2. The second Function2 is the optional divider.
            return !static && result == "java.lang.Object" && p == List(2) { "java.lang.Object" } &&
                fields.containsAll(repackagedTopContentFields) && fields.count { it == F2 } == 2
        }
        if (static) return result in setOf("void", "kotlin.Unit") && p == listOf(
            "androidx.compose.foundation.ScrollState", "float", "kotlin.jvm.functions.Function2", "float",
            "androidx.compose.runtime.State", "kotlin.jvm.functions.Function2", "kotlin.jvm.functions.Function3", "int",
            "androidx.compose.runtime.MutableIntState", COMPOSER, "int")
        return (result == "java.lang.Object" && p == List(2) { "java.lang.Object" } ||
            result == "void" && p == listOf(COMPOSER, "int")) && fields.containsAll(topContentFields)
    }

    fun topContentAccepts(method: Method, instance: Any?, argument: (Int) -> Any? = { null }): Boolean {
        if (Modifier.isStatic(method.modifiers)) return topContentStaticValues(method.parameterTypes, argument)
        if (instance == null || !method.declaringClass.isInstance(instance)) return false
        val fields = Lookup.declaredFields(method.declaringClass, makeAccessible = true).filterNot { Modifier.isStatic(it.modifiers) }
        val required = if (method.declaringClass.name == REPACKAGED_TOP_CONTENT) {
            if (fields.singleOrNull { it.name == "b" && it.type.name == F2 }?.get(instance) == null) return false
            repackagedTopContentFields
        } else topContentFields
        return required.all { type -> fields.any { it.type.name == type && it.get(instance) != null } }
    }

    internal fun topContentStaticValues(types: Array<Class<*>>, argument: (Int) -> Any?): Boolean {
        val divider = argument(2)
        // Divider is an optional Function2; Tabs (the other Function2) is required.
        return (divider == null || types[2].isInstance(divider)) &&
            listOf(0, 4, 5, 6, 8).all { types[it].isInstance(argument(it)) }
    }

    fun topContentComposerIndex(method: Method): Int = if (Modifier.isStatic(method.modifiers)) 9 else 0

    private fun verifiedContent(method: Method): Boolean {
        if (!HomeChromeAdapter.acceptsOwner("bottom_content", method.declaringClass.name)) return false
        val p = method.parameterTypes.map { it.name }
        if (Modifier.isStatic(method.modifiers)) {
            return method.returnType.name in setOf("void", "kotlin.Unit") && p.size == 12 && p[0] == "java.util.List" &&
                p[1] !in setOf("java.lang.Object", MODIFIER) && p[2] !in setOf("java.lang.Object", MODIFIER) &&
                p.subList(3, 12) == listOf("boolean", F1, F1, F1, "kotlin.jvm.functions.Function4",
                    "androidx.compose.runtime.MutableState", "androidx.compose.runtime.snapshots.SnapshotStateMap", COMPOSER, "int")
        }
        val arity = when {
            method.returnType == Any::class.java && p == List(3) { "java.lang.Object" } -> 3
            method.returnType == Any::class.java && p == List(2) { "java.lang.Object" } -> 2
            method.returnType == Void.TYPE && p == listOf("androidx.compose.foundation.layout.BoxWithConstraintsScope", COMPOSER, "int") -> 3
            method.returnType == Void.TYPE && p == listOf(COMPOSER, "int") -> 2
            else -> return false
        }
        val fields = Lookup.declaredFields(method.declaringClass).filterNot { Modifier.isStatic(it.modifiers) }.map { it.type.name }
        // New hosts also contain an outer Function2 wrapper with these captures AND a Modifier.
        // Only the older content Function2 without that Modifier owns the foreground Row.
        return fields.containsAll(contentFields) && (arity != 2 || MODIFIER !in fields)
    }

    fun contentAccepts(method: Method, instance: Any?): Boolean {
        if (Modifier.isStatic(method.modifiers)) return true
        if (instance == null || !method.declaringClass.isInstance(instance)) return false
        // R8 may merge generic lambda owners. A different instance/branch must not acquire a
        // chrome scope merely because it shares invoke(); require all native captures at runtime.
        val fields = Lookup.declaredFields(method.declaringClass, makeAccessible = true)
        return contentFields.all { type -> fields.any { it.type.name == type && it.get(instance) != null } }
    }

    fun contentComposerIndex(method: Method): Int = if (Modifier.isStatic(method.modifiers)) 10 else
        if (method.parameterCount == 3) 1 else 0

    fun direct(loader: ClassLoader): Map<String, HookPoint> {
        val result = linkedMapOf<String, HookPoint>()
        listOf("top" to TOP, "top_layout" to TOP.substringBeforeLast('.') + ".CustomScrollableTabRowKt",
            "top_backdrop" to TOP.substringBeforeLast('.') + ".HomeTopBarKt",
            "bottom" to "${BOTTOM}HomeBottomTabContainerKt").forEach { (family, name) ->
            val owner = Lookup.classOrNull(loader, name) ?: return@forEach
            Lookup.declaredMethods(owner).filter { verified(loader, family, it) }.singleOrNull()?.let {
                result[family] = HookPoint(it.declaringClass.name, it.name, it.parameterTypes.map { type -> type.name })
            }
        }
        return result
    }

    fun resolve(loader: ClassLoader, cachedMap: Map<String, HookPoint>): Map<String, HookPoint> =
        direct(loader).toMutableMap().apply {
            for ((family, point) in cachedMap) {
                val method = method(loader, point) ?: continue
                if (verified(loader, family, method)) put(family, point)
            }
        }

    fun method(loader: ClassLoader, point: HookPoint): Method? {
        val owner = Lookup.classOrNull(loader, point.className) ?: return null
        val names = point.paramClassNames ?: return null
        return Lookup.declaredMethods(owner, makeAccessible = true).singleOrNull {
            it.name == point.methodName && it.parameterTypes.map { type -> type.name } == names
        }
    }

    /** Both foreground siblings are certified in the backdrop's own facade and model ABI. */
    fun backdropForeground(loader: ClassLoader, backdrop: Method): List<Method>? {
        val candidates = Lookup.declaredMethods(backdrop.declaringClass, makeAccessible = true)
            .filter { it.parameterTypes.firstOrNull() === backdrop.parameterTypes.firstOrNull() }
        return listOf("top_foreground", "top_right_actions").map { family ->
            candidates.filter { verified(loader, family, it) }.singleOrNull() ?: return null
        }
    }
}
