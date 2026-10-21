package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/** 宿主 Compose 公共 ABI；位置通过布局回调读取，Snapshot 状态使绘制缓存随位置失效。 */
internal class HostComposeCardPositionAccess private constructor(
    private val positioned: Method, private val inWindow: Method,
    private val function: Class<*>, private val unit: Any,
    private val createState: Method, private val readState: Method, private val writeState: Method
) {
    internal inner class Signal {
        private val state = requireNotNull(createState.invoke(null, 0))
        private var version = 0
        fun read() { readState.invoke(state) }
        fun invalidate() { version++; writeState.invoke(state, version) }
    }
    fun signal() = Signal()
    fun attach(modifier: Any, callback: (Float, Float) -> Unit, onError: ((Throwable) -> Unit)? = null): Any {
        val observer = Proxy.newProxyInstance(function.classLoader, arrayOf(function)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> {
                    try {
                        args?.firstOrNull()?.let {
                            val packed = inWindow.invoke(null, it) as Long
                            val x = Float.fromBits((packed ushr 32).toInt())
                            val y = Float.fromBits(packed.toInt())
                            if (x.isFinite() && y.isFinite()) callback(x, y)
                        }
                    } catch (failure: Throwable) {
                        if (onError == null) throw failure
                        runCatching { onError(failure) }
                    }
                    unit
                }
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "HostCardPosition"
                else -> null
            }
        }
        return requireNotNull(positioned.invoke(null, modifier, observer))
    }

    companion object {
        fun resolve(loader: ClassLoader): HostComposeCardPositionAccess? = runCatching {
            fun type(name: String) = requireNotNull(Lookup.classOrNull(loader, name))
            val modifier = type("androidx.compose.ui.Modifier")
            val function = type("kotlin.jvm.functions.Function1")
            val coordinates = type("androidx.compose.ui.layout.LayoutCoordinates")
            val intType = Int::class.javaPrimitiveType!!
            fun exact(owner: Class<*>, prefix: String, result: Class<*>, vararg args: Class<*>) =
                Lookup.declaredMethods(owner, makeAccessible = true) {
                    it.name.startsWith(prefix) && !it.name.endsWith("\$default") &&
                        it.returnType == result && it.parameterTypes.contentEquals(args)
                }.single()
            HostComposeCardPositionAccess(
                exact(type("androidx.compose.ui.layout.OnGloballyPositionedModifierKt"), "onGloballyPositioned",
                    modifier, modifier, function),
                exact(type("androidx.compose.ui.layout.LayoutCoordinatesKt"), "positionInWindow",
                    Long::class.javaPrimitiveType!!, coordinates), function,
                requireNotNull(Lookup.fieldOrNull(type("kotlin.Unit"), "INSTANCE")).get(null)!!,
                exact(type("androidx.compose.runtime.SnapshotIntStateKt"), "mutableIntStateOf",
                    type("androidx.compose.runtime.MutableIntState"), intType).also {
                    check(Modifier.isStatic(it.modifiers))
                },
                exact(type("androidx.compose.runtime.IntState"), "getIntValue", intType),
                exact(type("androidx.compose.runtime.MutableIntState"), "setIntValue", Void.TYPE, intType)
            )
        }.getOrNull()
    }
}
