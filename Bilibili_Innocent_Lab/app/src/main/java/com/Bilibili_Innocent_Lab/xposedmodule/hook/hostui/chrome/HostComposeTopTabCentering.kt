package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentComposeViewAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostComposeCardPositionAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopTabCenteringPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopTabFadePolicy
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

    internal fun resolvedSetModifier(loader: ClassLoader): Method? = Access.resolve(loader)?.applyModifier

    fun install(environment: HookEnvironment, points: Map<String, HookPoint>, edgeFade: Boolean, onError: (Throwable) -> Unit,
                onApplied: () -> Unit = {}, onObserved: () -> Unit = {}): Boolean = runCatching {
        val loader = environment.classLoader ?: return false
        val resolved = HomeChromeComposeLocator.resolve(loader, points)
        fun point(family: String) = resolved[family]?.let { HomeChromeComposeLocator.method(loader, it) }
        val top = point("top") ?: return false
        val content = point("top_content") ?: return false
        val actions = point("top_right_actions") ?: return false
        val access = Access.resolve(loader) ?: return false
        val viewAccess = CommentComposeViewAccess.resolve(loader) ?: return false
        // Optional: without the draw ABI the tabs keep the host's hard clip and centering still runs,
        // so a fade failure gets its own log and never reports the centering as broken.
        val fadeError: (Throwable) -> Unit = { environment.logError("home_top_tabs_fade_error", "[BIL] 首页标签渐隐失败: $it") }
        val fade = if (edgeFade) HostComposeTopTabFade.resolve(loader, fadeError) else null
        fun dock(composer: Any): Dock? {
            val view = viewAccess.read(composer) ?: return null
            return docks.getOrPut(view) { Dock(WeakReference(view), access, onError, view.resources.displayMetrics.density, fade) }
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
        // 与 chrome 主链同一拦截点：setModifier 稳定接口，不再依赖 Updater.set-impl。
        environment.registrar.exact("home.top.centering.position", access.applyModifier.declaringClass,
            access.applyModifier.name, *access.applyModifier.parameterTypes) {
            before {
                if (roots.current() == null || !access.modifier.isInstance(argOrNull(0))) return@before
                val root = roots.claim() ?: return@before
                val original = argOrNull(0) ?: return@before
                runCatching {
                    val sized = if (root.role == Role.TABS) root.state.modifier(original) else original
                    val faded = if (root.role == Role.TABS && fade != null) {
                        runCatching { fade.decorate(sized, root.state::edges, fadeError) }.onFailure(fadeError).getOrDefault(sized)
                    } else sized
                    access.observe(faded, onError) { root.state.observe(root.role, it) }
                }
                    .onSuccess { args[0] = it }.onFailure(onError)
            }
        }
        // The tab row remembers its ScrollState inside the TABS root invocation (9.16.11 topbar/g).
        // A failed registration only loses the fade; centering below must still install.
        if (fade != null) runCatching {
            environment.registrar.exact("home.top.fade.scroll", fade.remember.declaringClass,
                fade.remember.name, *fade.remember.parameterTypes) {
                after {
                    if (hasThrowable) return@after
                    runCatching { roots.peek { it.role == Role.TABS }?.state?.capture(result) }.onFailure(fadeError)
                }
            }
        }.onFailure(fadeError)
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
    private data class Geometry(val dockWidth: Int, val tabLeft: Int, val tabWidth: Int, val safeLeft: Int, val safeRight: Int,
                                val dockHeight: Int = 0, val tabTop: Int = 0)

    internal data class FadeLayoutParams(
        val start: Float,
        val end: Float,
        val leading: Float,
        val trailing: Float,
        val fadePx: Float
    )

    internal fun resolveFadeLayout(
        tabLeft: Int,
        tabWidth: Int,
        safeLeft: Int,
        safeRight: Int,
        scrollValue: Int,
        scrollMax: Int,
        density: Float,
        fontScale: Float = 1f,
        isRtl: Boolean = false
    ): FadeLayoutParams {
        val safeDensity = if (density.isFinite() && density > 0f) density else 1f
        val fontFactor = if (fontScale.isFinite()) fontScale.coerceIn(1f, 1.35f) else 1f
        val fadePx = HostTopTabFadePolicy.FADE_DP * safeDensity * fontFactor
        val actionGapPx = HostTopTabFadePolicy.ACTION_GAP_DP * safeDensity * fontFactor
        val leftGap = if (safeLeft > tabLeft) actionGapPx else 0f
        val rightGap = if (safeRight < tabLeft + tabWidth) actionGapPx else 0f
        val widthFloat = tabWidth.coerceAtLeast(0).toFloat()
        val start = (safeLeft - tabLeft + leftGap).coerceIn(0f, widthFloat)
        val end = (safeRight - tabLeft - rightGap).coerceIn(start, widthFloat)
        val startStrength = HostTopTabFadePolicy.leadingStrength(scrollValue, fadePx)
        val endStrength = HostTopTabFadePolicy.trailingStrength(scrollValue, scrollMax, fadePx)
        val leading = if (isRtl) endStrength else startStrength
        val trailing = if (isRtl) startStrength else endStrength
        return FadeLayoutParams(start, end, leading, trailing, fadePx)
    }

    internal fun resolveCenteredOffset(
        count: Int,
        dockWidth: Int,
        tabLeft: Int,
        tabWidth: Int,
        safeLeft: Int,
        safeRight: Int,
        viewportWidth: Int,
        contentWidth: Int,
        isRtl: Boolean = false
    ): Int? {
        val leftAnchored = safeRight < tabLeft + tabWidth || (!isRtl && safeLeft <= tabLeft)
        if (leftAnchored) {
            return HostTopTabCenteringPolicy.dockCenteredOffset(
                count, dockWidth, tabLeft, safeLeft, safeRight, viewportWidth, contentWidth
            )
        }
        val anchoredRight = tabLeft + tabWidth
        val adjustedTabLeft = (anchoredRight - viewportWidth).coerceIn(0, dockWidth)
        val adjustedSafeLeft = max(safeLeft, adjustedTabLeft)
        return HostTopTabCenteringPolicy.dockCenteredOffset(
            count, dockWidth, adjustedTabLeft, adjustedSafeLeft, safeRight, viewportWidth, contentWidth
        )
    }

    private class Dock(
        private val hostView: WeakReference<View>,
        val access: Access,
        val onError: (Throwable) -> Unit,
        private val initialDensity: Float,
        private val fade: HostComposeTopTabFade?
    ) {
        private var tabs: WeakReference<Any>? = null
        private var scroll: WeakReference<Any>? = null
        private var capsuleFor: Geometry? = null
        private var capsuleDensity = 0f
        private var capsuleRadius = 0f
        private var actions: WeakReference<Any>? = null
        private val signal = access.position.signal()
        private val width = HostTopTabWidthState(signal::invalidate)
        private var geometry: Geometry? = null
        private fun liveDensity(): Float =
            hostView.get()?.resources?.displayMetrics?.density?.takeIf { it > 0f } ?: initialDensity
        private fun liveFontScale(): Float =
            hostView.get()?.resources?.configuration?.fontScale ?: 1f
        private fun isRtl(): Boolean =
            hostView.get()?.layoutDirection == View.LAYOUT_DIRECTION_RTL
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
        fun capture(state: Any?) {
            if (state != null && scroll?.get() !== state) scroll = WeakReference(state)
        }
        /** Read inside the row's draw: geometry and scroll are snapshot reads, so both re-draw it. */
        fun edges(): HostComposeTopTabFade.Edges? {
            val fade = fade ?: return null
            signal.read()
            val g = geometry ?: return null
            val state = scroll?.get() ?: return null
            val max = fade.scrollMax(state)
            if (max <= 0) return null
            val density = liveDensity()
            val value = fade.scrollValue(state)
            val resolved = resolveFadeLayout(
                tabLeft = g.tabLeft,
                tabWidth = g.tabWidth,
                safeLeft = g.safeLeft,
                safeRight = g.safeRight,
                scrollValue = value,
                scrollMax = max,
                density = density,
                fontScale = liveFontScale(),
                isRtl = isRtl()
            )
            // The boundary follows the outer capsule's end cap: same radius as the drawn capsule.
            if (capsuleFor != g || capsuleDensity != density) {
                capsuleFor = g
                capsuleDensity = density
                capsuleRadius = HostChromeTopCapsule.fit(g.dockWidth.toFloat(), g.dockHeight.toFloat(), density)
                    ?.cornerRadius(g.dockHeight.toFloat()) ?: (g.dockHeight / 2f)
            }
            return HostComposeTopTabFade.Edges(
                resolved.start,
                resolved.end,
                resolved.leading,
                resolved.trailing,
                resolved.fadePx,
                g.dockHeight / 2f - g.tabTop,
                capsuleRadius,
                density
            )
        }
        fun center(count: Int, contentWidth: Int, viewportWidth: Int): Int? {
            signal.read()
            // Recheck attachment as a menu may disappear without another layout callback.
            if (!access.isAttached(tabs?.get()) || actions?.get()?.let { !access.isAttached(it) } == true) refresh()
            val g = geometry ?: return null
            // The positioned callback trails the layout by a frame while the actions padding animates;
            // requiring the old viewport width here dropped the tabs back to the native start each frame.
            return resolveCenteredOffset(
                count = count,
                dockWidth = g.dockWidth,
                tabLeft = g.tabLeft,
                tabWidth = g.tabWidth,
                safeLeft = g.safeLeft,
                safeRight = g.safeRight,
                viewportWidth = viewportWidth,
                contentWidth = contentWidth,
                isRtl = isRtl()
            )
        }
    }

    private class Access(val modifier: Class<*>, val alignment: Class<*>, val function2: Class<*>, val scope: Class<*>,
                         val subcompose: Method, val align: Method, val invoke: Method, val wraps: List<Method>, val fillWidth: Method, val layout: Method,
                         val applyModifier: Method, val positioned: Method,
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
                return Geometry(d.width, t.left, t.width, left, rightEdge, d.height, t.top)
            }
            return if (!hasMenu) Geometry(nativeTop.width, 0, nativeTop.width, 0, nativeTop.width, nativeTop.height, 0) else null
        }
        companion object {
            fun resolve(loader: ClassLoader): Access? = runCatching {
                fun type(name: String) = requireNotNull(Lookup.classOrNull(loader, name))
                fun method(owner: Class<*>, prefix: String, result: Class<*>, vararg args: Class<*>) =
                    Lookup.declaredMethods(owner, makeAccessible = true).single {
                        matchesComposeName(it.name, prefix) && it.returnType == result && it.parameterTypes.contentEquals(args)
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
                Access(mod, alignment, f2, scope,
                    method(scope, "subcompose", List::class.java, Any::class.java, f2),
                    method(alignment, "align", long, long, long, type("androidx.compose.ui.unit.LayoutDirection")),
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
                    // 绝不直接 Hook ComposeUiNode 接口抽象方法：共享具体 LayoutNode.setModifier(Modifier) 解析逻辑。
                    HostComposeChromeApi.resolveSetModifier(loader, mod),
                    method(type("androidx.compose.ui.layout.OnGloballyPositionedModifierKt"), "onGloballyPositioned", mod, mod, f1),
                    f1, requireNotNull(Lookup.fieldOrNull(type("kotlin.Unit"), "INSTANCE")).get(null)!!,
                    method(coordinates, "isAttached", bool), method(coordinates, "getParentLayoutCoordinates", coordinates),
                    method(coordinates, "getSize", long),
                    method(coordinates, "localPositionOf", long, coordinates, long),
                    requireNotNull(HostComposeCardPositionAccess.resolve(loader)))
            }.getOrNull()
        }
    }
}
