package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentComposeViewAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 追番/影视共用的 OperableCardUi；只替换首页内容卡片的 Modifier，不接管 Compose 页面。 */
internal object HostOgvCardHooks {
    fun install(environment: HookEnvironment, host: HostVideoCardHostAccess, radiusDp: Int,
        onApplied: () -> Unit): Int = runCatching {
        val loader = environment.classLoader ?: return@runCatching 0
        val access = CommentComposeViewAccess.resolve(loader) ?: return@runCatching 0
        val failed = AtomicBoolean(false)
        val api = Api.resolve(loader) {
            if (failed.compareAndSet(false, true)) environment.logError("host_video_cards_ogv_draw_error", "[BIL] 追番/影视卡片表面绘制失败: $it")
        } ?: return@runCatching 0
        val owner = sequenceOf("com.bilibili.ogv.operation3.module.operablecard.OperableCardUi", "hf3.x")
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }.firstOrNull { type ->
                type.declaredFields.any { it.type.name == "com.bilibili.ogv.operation3.module.operablecard.a" }
            } ?: return@runCatching 0
        val content = owner.declaredMethods.singleOrNull { method ->
            method.returnType == Void.TYPE && method.parameterTypes.map { it.name } == listOf(
                "androidx.compose.ui.Modifier", "float", "androidx.compose.runtime.Composer", "int", "int")
        } ?: return@runCatching 0
        val styled = WeakHashMap<Any, Pair<Boolean, WeakReference<Any>>>()
        val caches = WeakHashMap<View, WeakHashMap<Any, Pair<Boolean, WeakReference<Any>>>>()
        fun register(method: Method, modifierIndex: Int, composerIndex: Int, radiusIndex: Int? = null) {
            environment.registrar.exact("host_video_cards.ogv.${method.declaringClass.name}.${method.name}",
                method.declaringClass, method.name, *method.parameterTypes) {
                before {
                    val composer = argOrNull(composerIndex) ?: return@before
                    val view = access.read(composer) ?: return@before
                    if (!isHome(view)) return@before
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
                    args[modifierIndex] = modifier
                    // 默认值掩码不能覆盖修改后的 Modifier/封面半径；清除对应 changed 位以正确重组。
                    val changed = (14 shl (modifierIndex * 3)) or (radiusIndex?.let { 14 shl (it * 3) } ?: 0)
                    val defaults = (1 shl modifierIndex) or (radiusIndex?.let { 1 shl it } ?: 0)
                    if (radiusIndex != null) args[radiusIndex] = if (radiusDp == HostVideoCardStyleSpec.DEFAULT_RADIUS) 12f else radiusDp.toFloat()
                    args[composerIndex + 1] = (argOrNull(composerIndex + 1) as? Int ?: 0) and changed.inv()
                    args[composerIndex + 2] = (argOrNull(composerIndex + 2) as? Int ?: 0) and defaults.inv()
                    onApplied()
                }
            }
        }
        register(content, 0, 2, 1)
        var count = 1
        // KMM 热播、双列推荐、即将上线和追番进度卡不经过 OperableCardUi。
        // 用各自模型类型与完整尾部 ABI 校验，避免把同文件的整页/标签组件套成卡片。
        val standalone = listOf(
            Triple("ed3.r", "ed3.f\$c", "com.bilibili.ogv.operation3.module.feedback.v"),
            Triple("sc3.n", "sc3.f\$c", "com.bilibili.ogv.operation3.module.feedback.v"),
            Triple("rc3.l", "rc3.b\$c", "com.bilibili.ogv.operation3.module.followable.i"),
            Triple("com.bilibili.ogv.kmm.operation.following.b",
                "com.bilibili.ogv.kmm.operation.following.BangumiFollowing\$b", "wc3.o")
        )
        for ((className, model, helper) in standalone) {
            val type = KavaMemberLookup.classOrNull(loader, className) ?: continue
            val method = type.declaredMethods.singleOrNull { it.returnType == Void.TYPE &&
                it.parameterTypes.map { parameter -> parameter.name } == listOf(model, helper,
                    "androidx.compose.ui.Modifier", "androidx.compose.runtime.Composer", "int", "int")
            } ?: continue
            register(method, 2, 3)
            count++
        }
        environment.logInfo("host_video_cards_ogv_installed", "[BIL] 追番/影视卡片已接入（组件=$count）")
        count
    }.getOrElse {
        environment.logError("host_video_cards_ogv_error", "[BIL] 追番/影视卡片接入失败: $it")
        0
    }

    private fun isHome(view: View): Boolean {
        var node: View? = view
        var compose = false
        while (node != null) {
            val current = node
            val name = if (current.id == View.NO_ID) "" else
                runCatching { current.resources.getResourceEntryName(current.id) }.getOrDefault("")
            if (name == "cvContainer") compose = true
            if (name == "primary_multi_page_layout") return compose
            node = current.parent as? View
        }
        return false
    }

    /** 全部使用宿主 ABI，不能把模块的 Compose Modifier 强转到宿主 ClassLoader。 */
    private class Api(
        val empty: Any, private val shape: Method, private val shadow: Method,
        private val clip: Method, private val behind: Method, private val padding: Method,
        private val function: Class<*>, private val unit: Any, private val size: Method,
        private val context: Method, private val canvas: Method, private val nativeCanvas: Method,
        private val onError: (Throwable) -> Unit
    ) {
        fun decorate(original: Any, view: View, radiusDp: Int, night: Boolean): Any {
            val radius = (if (radiusDp == HostVideoCardStyleSpec.DEFAULT_RADIUS) 16 else radiusDp).toFloat()
            val outline = shape.invoke(null, radius)!!
            var modifier = shadow.invoke(null, original, 2f, outline, false, 0x1670707000000000L, 0x1670707000000000L)!!
            modifier = clip.invoke(null, modifier, outline)!!
            val painter = Painter(view, radius, night)
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
            fun draw(scope: Any) {
                val owner = view.get() ?: return
                val packed = size.invoke(scope) as Long
                val width = Float.fromBits((packed ushr 32).toInt()).toInt()
                val height = Float.fromBits(packed.toInt()).toInt()
                if (width <= 0 || height <= 0) return
                val drawable = surface ?: HostVideoCardSurface.create(owner,
                    radius * owner.resources.displayMetrics.density, night)?.also { surface = it } ?: return
                val target = nativeCanvas.invoke(null, canvas.invoke(context.invoke(scope))) as Canvas
                drawable.setBounds(0, 0, width, height)
                drawable.draw(target)
            }
        }

        companion object {
            fun resolve(loader: ClassLoader, onError: (Throwable) -> Unit): Api? = runCatching {
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
                    method("androidx.compose.ui.graphics.AndroidCanvas_androidKt", "getNativeCanvas", 1), onError)
            }.onFailure(onError).getOrNull()
        }
    }
}
