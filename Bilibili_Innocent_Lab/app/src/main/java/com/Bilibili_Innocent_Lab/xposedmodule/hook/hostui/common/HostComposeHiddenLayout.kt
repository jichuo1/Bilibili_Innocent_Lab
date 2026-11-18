package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Method
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Measures the native card, but never places its child: space remains, drawing/input/semantics do not. */
internal class HostComposeHiddenLayout private constructor(
    private val modifier: Class<*>, private val empty: Any, private val factory: Method,
    private val block: Any
) {
    private val decorated = WeakHashMap<Any, Boolean>()
    private val cache = WeakHashMap<Any, WeakReference<Any>>()

    fun wrap(original: Any?): Any {
        val source = original?.takeIf(modifier::isInstance) ?: empty
        if (decorated.containsKey(source)) return source
        return cache[source]?.get() ?: requireNotNull(nativeCall { factory.invoke(null, source, block) }).also {
            decorated[it] = true; cache[source] = WeakReference(it)
        }
    }

    companion object {
        private inline fun <T> nativeCall(block: () -> T): T = try {
            block()
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }

        fun resolve(loader: ClassLoader, onApplied: () -> Unit): HostComposeHiddenLayout? = runCatching {
            fun type(name: String) = requireNotNull(Lookup.classOrNull(loader, name))
            val modifier = type("androidx.compose.ui.Modifier")
            val f1 = type("kotlin.jvm.functions.Function1")
            val f3 = type("kotlin.jvm.functions.Function3")
            val scope = type("androidx.compose.ui.layout.MeasureScope")
            val measurable = type("androidx.compose.ui.layout.Measurable")
            val placeable = type("androidx.compose.ui.layout.Placeable")
            val result = type("androidx.compose.ui.layout.MeasureResult")
            val measure = Lookup.methods(measurable, includeSuperclasses = true, makeAccessible = true).single {
                it.name.startsWith("measure-") && it.returnType == placeable &&
                    it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType))
            }
            // 宿主 layout 元素把 Constraints 装箱后交给 Function3（9.18/9.19 真机实证：
            // `invoke(scope, measurable, Constraints.box-impl(j))`）；裸 long 只出现在
            // Measurable.measure 一侧。旧实现直接 `as Long` 在真机必抛 ClassCastException。
            val constraints = type("androidx.compose.ui.unit.Constraints")
            val unbox = Lookup.methods(constraints, includeSuperclasses = false, makeAccessible = true).single {
                it.name.startsWith("unbox-") && it.returnType == Long::class.javaPrimitiveType &&
                    it.parameterCount == 0
            }
            val layout = scope.getMethod("layout", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Map::class.java, f1).also { check(it.returnType == result) }
            val width = placeable.getMethod("getWidth")
            val height = placeable.getMethod("getHeight")
            val unit = requireNotNull(Lookup.fieldOrNull(type("kotlin.Unit"), "INSTANCE")).get(null)!!
            val placement = function(f1, "MineVipUnplaced") { unit }
            val block = function(f3, "MineVipMeasure") { args ->
                val receiver = requireNotNull(args?.getOrNull(0)).also { check(scope.isInstance(it)) }
                val child = requireNotNull(args?.getOrNull(1)).also { check(measurable.isInstance(it)) }
                val packed = when (val raw = args?.getOrNull(2)) {
                    is Long -> raw
                    else -> unbox.invoke(requireNotNull(raw).also { check(constraints.isInstance(it)) }) as Long
                }
                nativeCall {
                    val measured = requireNotNull(measure.invoke(child, packed))
                    requireNotNull(layout.invoke(receiver, width.invoke(measured), height.invoke(measured),
                        emptyMap<Any, Any>(), placement))
                }.also { runCatching(onApplied) }
            }
            val factory = type("androidx.compose.ui.layout.LayoutModifierKt").getMethod("layout", modifier, f3)
            check(factory.returnType == modifier)
            HostComposeHiddenLayout(modifier, requireNotNull(Lookup.fieldOrNull(modifier, "Companion")).get(null)!!,
                factory, block)
        }.getOrNull()

        private fun function(type: Class<*>, label: String, invoke: (Array<out Any?>?) -> Any?): Any =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "invoke" -> invoke(args)
                    "toString" -> label
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    else -> null
                }
            }
    }
}
