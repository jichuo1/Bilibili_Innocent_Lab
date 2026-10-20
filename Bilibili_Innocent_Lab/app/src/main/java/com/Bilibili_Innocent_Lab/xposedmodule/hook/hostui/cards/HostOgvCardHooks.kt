package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostCardMaterial
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentComposeViewAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.reportRuntimeEvidence
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 追番/影视共用的单卡入口；只替换内容卡片的 Modifier。 */
internal object HostOgvCardHooks {
    private val playerNames = setOf("video_container", "video_area", "control_container", "story_fragment_root")
    fun install(environment: HookEnvironment, host: HostVideoCardHostAccess, radiusDp: Int, material: HostCardMaterial = HostCardMaterial.ORIGINAL,
        cachedPoints: Map<String, HookPoint> = emptyMap(),
        onApplied: () -> Unit): HostCardHookCoverage = runCatching {
        val loader = environment.classLoader ?: return@runCatching HostCardHookCoverage.NONE
        val resolution = HostOgvCardAdapter.resolve(loader, cachedPoints)
        val missing = resolution.families.filter { it.applicable }.associate { it.id to it.reason }
        fun unavailable() = HostCardHookCoverage(0, resolution.expected, 0, missing)
        val access = CommentComposeViewAccess.resolve(loader) ?: return@runCatching unavailable()
        val failed = AtomicBoolean(false)
        val api = Api.resolve(loader, material, onApplied) {
            if (failed.compareAndSet(false, true)) environment.logError("host_video_cards_ogv_draw_error", "[BIL] 追番/影视卡片表面绘制失败: $it")
        } ?: return@runCatching unavailable()
        val styled = WeakHashMap<Any, Pair<Boolean, WeakReference<Any>>>()
        val caches = WeakHashMap<View, WeakHashMap<Any, Pair<Boolean, WeakReference<Any>>>>()
        fun register(point: HostOgvCardAdapter.Point) {
            val method = point.method
            val modifierIndex = point.modifierIndex
            val composerIndex = point.composerIndex
            environment.registrar.exact("host_video_cards.ogv.${method.declaringClass.name}.${method.name}",
                method.declaringClass, method.name, *method.parameterTypes) {
                before {
                    if (!point.accepts(instance, args)) return@before
                    val composer = argOrNull(composerIndex) ?: return@before
                    val view = access.read(composer) ?: return@before
                    if (!isCardContent(view)) return@before
                    val supplied = argOrNull(modifierIndex) ?: api.empty
                    val previous = styled[supplied]
                    val original = previous?.second?.get() ?: supplied
                    val night = host.isNight(view.context)
                    val cache = caches.getOrPut(view) { WeakHashMap() }
                    val cached = cache[original]?.takeIf { it.first == night }?.second?.get()
                    val modifier = if (previous?.first == night) supplied else cached ?: api.decorate(
                        original, view, radiusDp, night).also {
                        styled[it] = night to WeakReference(original)
                        cache[original] = night to WeakReference(it)
                    }
                    point.rewrite(args, modifier,
                        if (radiusDp == HostVideoCardStyleSpec.DEFAULT_RADIUS) 12f else radiusDp.toFloat())
                }
                after {
                    if (hasThrowable) return@after
                    val composer = argOrNull(composerIndex) ?: return@after
                    val view = access.read(composer) ?: return@after
                    if (isCardContent(view) && styled[argOrNull(modifierIndex)] != null) {
                        environment.reportRuntimeEvidence("host_video_cards", FeatureRuntimeStage.OBSERVED)
                    }
                }
            }
        }
        var count = 0
        var ready = 0
        val states = linkedMapOf<String, String>()
        resolution.families.filter { it.applicable }.forEach { family ->
            if (family.points.isEmpty()) {
                states[family.id] = family.reason
            } else {
                var installed = 0
                family.points.forEach { point ->
                    runCatching { register(point) }.onSuccess { count++; installed++ }.onFailure {
                        environment.logError("host_video_cards_ogv_${family.id}_register", "[BIL] OGV卡片入口注册失败: $it")
                    }
                }
                if (installed == family.points.size) { ready++; states[family.id] = "installed" }
                else states[family.id] = "registration-failed"
            }
        }
        environment.logInfo("host_video_cards_ogv_installed", "[BIL] OGV卡片家族=$ready/${resolution.expected}: $states")
        HostCardHookCoverage(count, resolution.expected, ready, states)
    }.getOrElse {
        environment.logError("host_video_cards_ogv_error", "[BIL] 追番/影视卡片接入失败: $it")
        val expected = environment.classLoader?.let { HostOgvCardAdapter.resolve(it, cachedPoints).expected } ?: 0
        HostCardHookCoverage(0, expected, 0, mapOf("ogv" to "initialization-failed"))
    }

    private fun isCardContent(view: View): Boolean {
        var node: View? = view
        while (node != null) {
            val current = node
            val name = if (current.id == View.NO_ID) "" else
                runCatching { current.resources.getResourceEntryName(current.id) }.getOrDefault("")
            if (name in playerNames ||
                current.javaClass.name == "tv.danmaku.biliplayerimpl.controlcontainer.ControlContainer") return false
            node = current.parent as? View
        }
        // Hook 点本身已验证单张追番/影视卡片语义，详情/专题和独立页面也要复用材质。
        return true
    }

    /** 全部使用宿主 ABI，不能把模块的 Compose Modifier 强转到宿主 ClassLoader。 */
    private class Api(
        val empty: Any, private val shape: Method, private val shadow: Method,
        private val clip: Method, private val behind: Method, private val padding: Method,
        private val function: Class<*>, private val unit: Any, private val size: Method,
        private val context: Method, private val canvas: Method, private val nativeCanvas: Method,
        private val onError: (Throwable) -> Unit, private val onApplied: () -> Unit,
        private val material: HostCardMaterial, private val position: HostComposeCardPositionAccess?
    ) {
        fun decorate(original: Any, view: View, radiusDp: Int, night: Boolean): Any {
            val radius = (if (radiusDp == HostVideoCardStyleSpec.DEFAULT_RADIUS) 16 else radiusDp).toFloat()
            val outline = shape.invoke(null, radius)!!
            var modifier = shadow.invoke(null, original, 2f, outline, false, 0x1670707000000000L, 0x1670707000000000L)!!
            modifier = clip.invoke(null, modifier, outline)!!
            val painter = Painter(view, radius, night)
            if (material.usesSharedField) modifier = requireNotNull(position).attach(modifier, painter::position)
            val draw = Proxy.newProxyInstance(function.classLoader, arrayOf(function)) { proxy, method, arguments ->
                when (method.name) {
                    "invoke" -> {
                        runCatching { arguments?.firstOrNull()?.let { painter.draw(it) } }.onFailure(onError)
                        unit
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    "toString" -> "HostOgvCardSurface"
                    else -> null
                }
            }
            modifier = behind.invoke(null, modifier, draw)!!
            // 海报和标题在同一表面内，给文字保留横向留白，避免背景纹理干扰可读性。
            return padding.invoke(null, modifier, 8f, 8f, 8f, 10f)!!
        }

        private inner class Painter(view: View, private val radius: Float, private val night: Boolean) {
            private val view = WeakReference(view)
            private var surface: Drawable? = null
            private var surfaceColor: Int? = null
            private var materialPaint: HostCardMaterialScope.CardPaint? = null
            private var scope: WeakReference<HostCardMaterialScope>? = null
            private var x = 0f
            private var y = 0f
            private var positioned = false
            private val signal = this@Api.position?.signal()
            fun position(x: Float, y: Float) {
                if (positioned && this.x == x && this.y == y) return
                this.x = x; this.y = y; positioned = true
                signal?.invalidate()
            }
            fun draw(scope: Any) {
                val owner = view.get() ?: return
                signal?.read()
                val packed = size.invoke(scope) as Long
                val width = Float.fromBits((packed ushr 32).toInt()).toInt()
                val height = Float.fromBits(packed.toInt()).toInt()
                if (width <= 0 || height <= 0) return
                if (material.usesSharedField && positioned) {
                    val shared = HostCardMaterialRegistry.scope(owner, night, material) ?: return
                    if (this.scope?.get() !== shared || materialPaint == null) {
                        materialPaint?.close()
                        materialPaint = shared.composePaint(owner, radius * owner.resources.displayMetrics.density)
                        this.scope = WeakReference(shared)
                    }
                    val paint = requireNotNull(materialPaint)
                    paint.position(x, y); paint.setBounds(0, 0, width, height)
                    val target = nativeCanvas.invoke(null, canvas.invoke(context.invoke(scope))) as Canvas
                    paint.draw(target); onApplied(); return
                }
                val color = HostVideoCardSurface.color(night, owner.context)
                if (surfaceColor != color) { surface = null; surfaceColor = color }
                val drawable = surface ?: HostVideoCardSurface.create(owner,
                    radius * owner.resources.displayMetrics.density, night)?.also { surface = it } ?: return
                val target = nativeCanvas.invoke(null, canvas.invoke(context.invoke(scope))) as Canvas
                drawable.setBounds(0, 0, width, height)
                drawable.draw(target)
                onApplied()
            }
        }

        companion object {
            fun resolve(loader: ClassLoader, material: HostCardMaterial, onApplied: () -> Unit, onError: (Throwable) -> Unit): Api? = runCatching {
                fun type(name: String) = requireNotNull(KavaMemberLookup.classOrNull(loader, name))
                fun method(name: String, prefix: String, count: Int) = type(name).declaredMethods.single {
                    it.name.startsWith(prefix) && it.parameterCount == count && !it.name.endsWith("\$default")
                }
                val empty = requireNotNull(requireNotNull(KavaMemberLookup.fieldOrNull(type("androidx.compose.ui.Modifier"), "Companion")).get(null))
                Api(empty, method("androidx.compose.foundation.shape.RoundedCornerShapeKt", "RoundedCornerShape-", 1),
                    method("androidx.compose.ui.draw.ShadowKt", "shadow-", 6),
                    method("androidx.compose.ui.draw.ClipKt", "clip", 2),
                    method("androidx.compose.ui.draw.DrawModifierKt", "drawBehind", 2),
                    method("androidx.compose.foundation.layout.PaddingKt", "padding-qDBjuR0", 5),
                    type("kotlin.jvm.functions.Function1"), requireNotNull(requireNotNull(KavaMemberLookup.fieldOrNull(type("kotlin.Unit"), "INSTANCE")).get(null)),
                    method("androidx.compose.ui.graphics.drawscope.DrawScope", "getSize-", 0),
                    method("androidx.compose.ui.graphics.drawscope.DrawScope", "getDrawContext", 0),
                    method("androidx.compose.ui.graphics.drawscope.DrawContext", "getCanvas", 0),
                    method("androidx.compose.ui.graphics.AndroidCanvas_androidKt", "getNativeCanvas", 1), onError, onApplied,
                    material, if (material.usesSharedField) requireNotNull(HostComposeCardPositionAccess.resolve(loader)) else null)
            }.onFailure(onError).getOrNull()
        }
    }
}
