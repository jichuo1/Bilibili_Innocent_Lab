package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentComposeViewAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostComposeCardPositionAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopTabCenteringPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Independent of glass: keep the host's tabs, indicator, pointer input and scroll state together. */
internal object HostComposeTopTabCentering {
    private enum class Role { TABS, ACTIONS }
    private class Content(val bridge: HostTopTabLayoutBridge) { var aligned = false; var measured = false }
    private data class Root(val state: Dock, val role: Role)
    private val contents = HostChromeCompositionScope<Content>()
    private val roots = HostChromeCompositionScope<Root>()
    private val docks = WeakHashMap<View, Dock>()

    fun install(environment: HookEnvironment, points: Map<String, HookPoint>, onError: (Throwable) -> Unit,
                onApplied: () -> Unit = {}, onObserved: () -> Unit = {}): Boolean = runCatching {
        val loader = environment.classLoader ?: return false
        val resolved = HomeChromeComposeLocator.resolve(loader, points)
        fun point(family: String) = resolved[family]?.let { HomeChromeComposeLocator.method(loader, it) }
        val top = point("top") ?: return false
        val content = point("top_content") ?: return false
        val actions = point("top_right_actions") ?: return false
        val access = Access.resolve(loader) ?: return false
        val viewAccess = CommentComposeViewAccess.resolve(loader) ?: return false
        fun dock(composer: Any): Dock? {
            val view = viewAccess.read(composer) ?: return null
            return docks.getOrPut(view) { Dock(access, onError) }
        }
        for ((method, role) in listOf(top to Role.TABS, actions to Role.ACTIONS)) {
            val index = method.parameterTypes.indexOfFirst { it.name == "androidx.compose.runtime.Composer" }
            if (index < 0) return false
            val key = "home.top.centering.root.${role.name.lowercase()}"
            environment.registrar.exact(key, method.declaringClass, method.name, *method.parameterTypes) {
                before {
                    runCatching {
                        val state = argOrNull(index)?.let(::dock) ?: return@runCatching
                        val entry = HostChromeCompositionScope.Entry(Root(state, role))
                        setObjectExtra(key, entry)
                        roots.push(entry)
                    }.onFailure(onError)
                }
                after {
                    @Suppress("UNCHECKED_CAST") val entry = getObjectExtra(key) as? HostChromeCompositionScope.Entry<Root> ?: return@after
                    roots.exit(entry); setObjectExtra(key, null)
                }
            }
        }
        environment.registrar.exact("home.top.centering.position", access.updater.declaringClass,
            access.updater.name, *access.updater.parameterTypes) {
            before {
                if (roots.current() == null || argOrNull(2) !== access.setter || !access.modifier.isInstance(argOrNull(1))) return@before
                val root = roots.claim() ?: return@before
                val original = argOrNull(1) ?: return@before
                runCatching {
                    val sized = if (root.role == Role.TABS) root.state.modifier(original) else original
                    access.observe(sized, onError) { root.state.observe(root.role, it) }
                }
                    .onSuccess { args[1] = it }.onFailure(onError)
            }
        }
        val contentKey = "home.top.centering.content"
        environment.registrar.exact(contentKey, content.declaringClass, content.name, *content.parameterTypes) {
            before {
                runCatching {
                    if (!HomeChromeComposeLocator.topContentAccepts(content, instance) { argOrNull(it) }) return@runCatching
                    val state = argOrNull(HomeChromeComposeLocator.topContentComposerIndex(content))?.let(::dock) ?: return@runCatching
                    val bridge = HostTopTabLayoutBridge(access.alignment, access.function2, access.scope,
                        access.subcompose, access.align, access.invoke, state::center, onApplied, onError, state::measured)
                    val entry = HostChromeCompositionScope.Entry(Content(bridge))
                    setObjectExtra(contentKey, entry)
                    runCatching(onObserved).onFailure(onError)
                    contents.push(entry)
                }.onFailure(onError)
            }
            after {
                @Suppress("UNCHECKED_CAST") val entry = getObjectExtra(contentKey) as? HostChromeCompositionScope.Entry<Content> ?: return@after
                contents.exit(entry); setObjectExtra(contentKey, null)
            }
        }
        for (wrap in access.wraps) environment.registrar.exact("home.top.centering.alignment.${wrap.parameterCount}", wrap.declaringClass,
            wrap.name, *wrap.parameterTypes) {
            before {
                val current = contents.current() ?: return@before
                if (current.aligned) return@before
                val native = argOrNull(1)?.takeIf(access.alignment::isInstance) ?: return@before
                val mask = if (wrap.parameterCount == 5) argOrNull(3) as? Int ?: return@before else 0
                if (mask and 1 != 0 || mask and 2 == 0 && argOrNull(2) != false) return@before
                runCatching { current.bridge.alignment(native) }.onSuccess {
                    args[1] = it; current.aligned = true
                }.onFailure(onError)
            }
        }
        environment.registrar.exact("home.top.centering.measure", access.layout.declaringClass,
            access.layout.name, *access.layout.parameterTypes) {
            before {
                val current = contents.current() ?: return@before
                if (!current.aligned || current.measured) return@before
                val native = argOrNull(1)?.takeIf(access.function2::isInstance) ?: return@before
                runCatching { current.bridge.measure(native) }.onSuccess {
                    args[1] = it; current.measured = true
                }.onFailure(onError)
            }
        }
        true
    }.onFailure(onError).getOrDefault(false)

    private data class Bounds(val left: Int, val top: Int, val width: Int, val height: Int) {
        val right get() = left + width
        val bottom get() = top + height
        fun contains(other: Bounds) = other.left >= left - 1 && other.right <= right + 1 &&
            other.top >= top - 1 && other.bottom <= bottom + 1
    }
    private data class Geometry(val dockWidth: Int, val tabLeft: Int, val tabWidth: Int, val safeLeft: Int, val safeRight: Int)

    private class Dock(val access: Access, val onError: (Throwable) -> Unit) {
        private var tabs: WeakReference<Any>? = null
        private var actions: WeakReference<Any>? = null
        private val signal = access.position.signal()
        private val width = HostTopTabWidthState(signal::invalidate)
        private var geometry: Geometry? = null
        fun measured(count: Int) = width.measured(count)
        fun modifier(original: Any): Any {
            // This read occurs inside the native Composable. A Tabs count change re-composes
            // only this root, then restores its exact original constraints for 0/3+ items.
            signal.read()
            return if (width.fill) access.fill(original) else original
        }
        fun observe(role: Role, coordinates: Any) {
            try {
                if (role == Role.TABS) tabs = WeakReference(coordinates) else actions = WeakReference(coordinates)
                refresh()
            } catch (failure: Throwable) {
                geometry = null
                runCatching { signal.invalidate() }
                runCatching { onError(failure) }
            }
        }
        private fun refresh() {
            if (!access.isAttached(actions?.get())) actions = null
            val next = access.geometry(tabs?.get(), actions?.get())
            if (next != geometry) { geometry = next; signal.invalidate() }
        }
        fun center(count: Int, contentWidth: Int, viewportWidth: Int): Int? {
            signal.read()
            // Recheck attachment as a menu may disappear without another layout callback.
            if (!access.isAttached(tabs?.get()) || actions?.get()?.let { !access.isAttached(it) } == true) refresh()
            val g = geometry ?: return null
            if (abs(viewportWidth - g.tabWidth) > 1) return null
            val x = HostTopTabCenteringPolicy.centeredOffset(count, g.dockWidth, g.safeLeft, g.safeRight, contentWidth) ?: return null
            return x + g.safeLeft - g.tabLeft
        }
    }

    private class Access(val modifier: Class<*>, val alignment: Class<*>, val function2: Class<*>, val scope: Class<*>,
                         val subcompose: Method, val align: Method, val invoke: Method, val wraps: List<Method>, val fillWidth: Method, val layout: Method,
                         val updater: Method, val setter: Any, val positioned: Method,
                         val function1: Class<*>, val unit: Any, val attached: Method, val parent: Method,
                         val size: Method, val local: Method, val position: HostComposeCardPositionAccess) {
        fun isAttached(value: Any?): Boolean = value != null && attached.invoke(value) == true
        fun fill(original: Any): Any = requireNotNull(if (fillWidth.parameterCount == 2) fillWidth.invoke(null, original, 1f)
            else fillWidth.invoke(null, original, 1f, 0, null))
        fun observe(original: Any, onError: (Throwable) -> Unit, callback: (Any) -> Unit): Any {
            val proxy = Proxy.newProxyInstance(function1.classLoader, arrayOf(function1)) { proxy, method, args ->
                when (method.name) {
                    "invoke" -> {
                        try { args?.getOrNull(0)?.let(callback) }
                        catch (failure: Throwable) { runCatching { onError(failure) } }
                        unit
                    }
                    "equals" -> proxy === args?.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "BIL top tab bounds"
                    else -> null
                }
            }
            return requireNotNull(positioned.invoke(null, original, proxy))
        }
        private fun bounds(value: Any, ancestor: Any): Bounds? {
            if (attached.invoke(value) != true) return null
            val packedSize = size.invoke(value) as Long
            val width = (packedSize shr 32).toInt()
            val height = packedSize.toInt()
            val packedPosition = if (value === ancestor) 0L else local.invoke(ancestor, value, 0L) as Long
            val x = Float.fromBits((packedPosition ushr 32).toInt())
            val y = Float.fromBits(packedPosition.toInt())
            if (!x.isFinite() || !y.isFinite()) return null
            if (value !== ancestor) {
                val end = (width.toFloat().toBits().toLong() shl 32) or (height.toFloat().toBits().toLong() and 0xFFFFFFFFL)
                val mapped = local.invoke(ancestor, value, end) as Long
                val dx = Float.fromBits((mapped ushr 32).toInt()) - x
                val dy = Float.fromBits(mapped.toInt()) - y
                // Layout alignment uses tab-local pixels. Decline a separately scaled/rotated
                // child; ancestor/window transforms cancel in localPositionOf.
                if (!dx.isFinite() || !dy.isFinite() || abs(dx - width) > 1f || abs(dy - height) > 1f) return null
            }
            return Bounds(x.roundToInt(), y.roundToInt(), width, height)
        }
        private fun ancestry(value: Any): List<Any> = buildList {
            var current: Any? = value
            repeat(12) {
                val item = current ?: return@buildList
                if (any { it === item }) return@buildList
                add(item); current = parent.invoke(item)
            }
        }
        fun geometry(top: Any?, right: Any?): Geometry? {
            if (top == null) return null
            val nativeTop = bounds(top, top)?.takeIf { it.width > 0 && it.height > 0 } ?: return null
            val chain = ancestry(top)
            val hasMenu = right != null && bounds(right, right)?.let { it.width > 0 && it.height > 0 } == true
            val other = if (hasMenu) ancestry(requireNotNull(right)) else emptyList()
            val candidates = if (hasMenu) chain.filter { a -> other.any { a === it } } else chain
            for (ancestor in candidates) {
                val d = bounds(ancestor, ancestor) ?: continue
                val t = bounds(top, ancestor) ?: continue
                val menu = if (hasMenu) bounds(requireNotNull(right), ancestor) ?: continue else null
                if (!d.contains(t) || menu != null && (!d.contains(menu) ||
                        min(menu.bottom, t.bottom) - max(menu.top, t.top) < min(menu.height, t.height) / 2) ||
                    d.height > max(t.height, menu?.height ?: 0) * 3 / 2 + 1 || !hasMenu && d.width <= t.width) continue
                var left = t.left
                var rightEdge = t.right
                if (menu != null) {
                    if (menu.left + menu.width / 2 >= d.width / 2) rightEdge = min(rightEdge, menu.left)
                    else left = max(left, menu.right)
                }
                return Geometry(d.width, t.left, t.width, left, rightEdge)
            }
            return if (!hasMenu) Geometry(nativeTop.width, 0, nativeTop.width, 0, nativeTop.width) else null
        }
        companion object {
            fun resolve(loader: ClassLoader): Access? = runCatching {
                fun type(name: String) = requireNotNull(Lookup.classOrNull(loader, name))
                fun method(owner: Class<*>, prefix: String, result: Class<*>, vararg args: Class<*>) =
                    Lookup.declaredMethods(owner, makeAccessible = true).single {
                        it.name.startsWith(prefix) && !it.name.endsWith("\$default") && it.returnType == result && it.parameterTypes.contentEquals(args)
                    }
                val mod = type("androidx.compose.ui.Modifier")
                val alignment = type("androidx.compose.ui.Alignment")
                val f1 = type("kotlin.jvm.functions.Function1")
                val f2 = type("kotlin.jvm.functions.Function2")
                val composer = type("androidx.compose.runtime.Composer")
                val coordinates = type("androidx.compose.ui.layout.LayoutCoordinates")
                val scope = type("androidx.compose.ui.layout.SubcomposeMeasureScope")
                val long = Long::class.javaPrimitiveType!!
                val int = Int::class.javaPrimitiveType!!
                val bool = Boolean::class.javaPrimitiveType!!
                val node = type("androidx.compose.ui.node.ComposeUiNode\$Companion")
                val nodeCompanion = requireNotNull(Lookup.fieldOrNull(type("androidx.compose.ui.node.ComposeUiNode"), "Companion")).get(null)
                Access(mod, alignment, f2, scope,
                    method(scope, "subcompose", List::class.java, Any::class.java, f2),
                    method(alignment, "align-", long, long, long, type("androidx.compose.ui.unit.LayoutDirection")),
                    method(f2, "invoke", Any::class.java, Any::class.java, Any::class.java),
                    Lookup.declaredMethods(type("androidx.compose.foundation.layout.SizeKt"), makeAccessible = true).filter {
                        Modifier.isStatic(it.modifiers) && it.returnType == mod &&
                            (it.name == "wrapContentSize" && it.parameterTypes.contentEquals(arrayOf(mod, alignment, bool)) ||
                                it.name == "wrapContentSize\$default" && it.parameterTypes.contentEquals(arrayOf(mod, alignment, bool, int, Any::class.java)))
                    }.also { check(it.isNotEmpty()) },
                    Lookup.declaredMethods(type("androidx.compose.foundation.layout.SizeKt"), makeAccessible = true).filter {
                        Modifier.isStatic(it.modifiers) && it.returnType == mod &&
                            (it.name == "fillMaxWidth" && it.parameterTypes.contentEquals(arrayOf(mod, Float::class.javaPrimitiveType!!)) ||
                                it.name == "fillMaxWidth\$default" && it.parameterTypes.contentEquals(arrayOf(mod, Float::class.javaPrimitiveType!!, int, Any::class.java)))
                    }.minByOrNull { it.parameterCount } ?: error("Missing local fillMaxWidth"),
                    method(type("androidx.compose.ui.layout.SubcomposeLayoutKt"), "SubcomposeLayout", Void.TYPE, mod, f2, composer, int, int),
                    method(type("androidx.compose.runtime.Updater"), "set-", Void.TYPE, composer, Any::class.java, f2),
                    requireNotNull(method(node, "getSetModifier", f2).invoke(nodeCompanion)),
                    method(type("androidx.compose.ui.layout.OnGloballyPositionedModifierKt"), "onGloballyPositioned", mod, mod, f1),
                    f1, requireNotNull(Lookup.fieldOrNull(type("kotlin.Unit"), "INSTANCE")).get(null)!!,
                    method(coordinates, "isAttached", bool), method(coordinates, "getParentLayoutCoordinates", coordinates),
                    method(coordinates, "getSize-", long),
                    method(coordinates, "localPositionOf-", long, coordinates, long),
                    requireNotNull(HostComposeCardPositionAccess.resolve(loader)))
            }.getOrNull()
        }
    }
}
