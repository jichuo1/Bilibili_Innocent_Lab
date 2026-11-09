package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import android.graphics.Canvas
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopTabArcMask
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Draw-only edge mask on the host's top tab row. Scrolling, pointer input and the indicator stay
 * native; the row's ScrollState (captured from its own rememberScrollState) only drives strength.
 * Module failures fall open to the host's plain draw; host exceptions keep their identity.
 */
internal class HostComposeTopTabFade private constructor(
    private val drawWithContent: Method, private val drawContent: Method, private val size: Method,
    private val context: Method, private val canvas: Method, private val native: Method,
    private val function1: Class<*>, private val unit: Any,
    private val value: Method, private val maxValue: Method, val remember: Method
) {
    /**
     * Row-local pixels: content fades out toward [start] and [end] along arcs of the outer capsule
     * ([centerY], [radius]); strengths scale each fade length. [band] is the arc sampling step.
     */
    data class Edges(val start: Float, val end: Float, val leading: Float, val trailing: Float, val fadePx: Float,
                     val centerY: Float, val radius: Float, val band: Float)

    private val arc = HostTopTabArcMask()
    private var failed = false

    /** Snapshot reads inside the draw lambda re-draw the row on every scroll frame. */
    fun scrollValue(state: Any): Int = value.invoke(state) as Int
    fun scrollMax(state: Any): Int = maxValue.invoke(state) as Int

    fun decorate(original: Any, edges: () -> Edges?, onError: (Throwable) -> Unit): Any {
        val draw = Proxy.newProxyInstance(function1.classLoader, arrayOf(function1)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> {
                    val scope = args?.firstOrNull()
                    if (scope != null) {
                        val current = if (failed) null else try { edges() } catch (failure: Throwable) { fail(failure, onError); null }
                        if (current == null || current.leading <= 0f && current.trailing <= 0f) content(scope)
                        else masked(scope, current, onError)
                    }
                    unit
                }
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "BIL top tab edge fade"
                else -> null
            }
        }
        return requireNotNull(drawWithContent.invoke(null, original, draw))
    }

    /** Same shape as the chrome's outer clip: the host content draws exactly once on every path. */
    private fun masked(scope: Any, edges: Edges, onError: (Throwable) -> Unit) {
        var target: Canvas? = null
        var saved = -1
        var width = 0f
        var height = 0f
        try {
            val packed = size.invoke(scope) as Long
            width = Float.fromBits((packed ushr 32).toInt())
            height = Float.fromBits(packed.toInt())
            if (width.isFinite() && height.isFinite() && width > 0f && height > 0f) {
                target = native.invoke(null, canvas.invoke(context.invoke(scope))) as Canvas
                // One row height above and below: badges and the indicator outside the row stay intact
                // without paying for an offscreen layer as large as the whole window clip.
                saved = target.saveLayer(0f, -height, width, height * 2f, null)
            }
        } catch (failure: Throwable) {
            saved = -1
            fail(failure, onError)
        }
        try {
            content(scope)
            if (saved >= 0) try {
                arc.draw(requireNotNull(target), 0f, -height, width, height * 2f, edges.centerY, edges.radius, edges.start,
                    edges.end, edges.fadePx * edges.leading, edges.fadePx * edges.trailing, edges.band)
            } catch (failure: Throwable) { fail(failure, onError) }
        } finally {
            if (saved >= 0) try { target?.restoreToCount(saved) } catch (failure: Throwable) { fail(failure, onError) }
        }
    }

    private fun content(scope: Any) {
        try { drawContent.invoke(scope) } catch (failure: InvocationTargetException) { throw failure.targetException }
    }

    private fun fail(failure: Throwable, onError: (Throwable) -> Unit) {
        failed = true
        runCatching { onError(failure) }
    }

    companion object {
        fun resolve(loader: ClassLoader, onError: (Throwable) -> Unit = {}): HostComposeTopTabFade? = runCatching {
            fun type(name: String) = requireNotNull(Lookup.classOrNull(loader, name))
            fun exact(owner: Class<*>, prefix: String, result: Class<*>, vararg p: Class<*>) =
                Lookup.declaredMethods(owner, makeAccessible = true).single {
                    it.name.startsWith(prefix) && !it.name.endsWith("\$default") &&
                        it.returnType == result && it.parameterTypes.contentEquals(p)
                }
            val modifier = type("androidx.compose.ui.Modifier")
            val f1 = type("kotlin.jvm.functions.Function1")
            val drawScope = type("androidx.compose.ui.graphics.drawscope.DrawScope")
            val drawContext = type("androidx.compose.ui.graphics.drawscope.DrawContext")
            val hostCanvas = type("androidx.compose.ui.graphics.Canvas")
            val scroll = type("androidx.compose.foundation.ScrollState")
            val int = Int::class.javaPrimitiveType!!
            HostComposeTopTabFade(
                exact(type("androidx.compose.ui.draw.DrawModifierKt"), "drawWithContent", modifier, modifier, f1),
                exact(type("androidx.compose.ui.graphics.drawscope.ContentDrawScope"), "drawContent", Void.TYPE),
                exact(drawScope, "getSize-", Long::class.javaPrimitiveType!!),
                exact(drawScope, "getDrawContext", drawContext), exact(drawContext, "getCanvas", hostCanvas),
                exact(type("androidx.compose.ui.graphics.AndroidCanvas_androidKt"), "getNativeCanvas", Canvas::class.java, hostCanvas),
                f1, requireNotNull(Lookup.fieldOrNull(type("kotlin.Unit"), "INSTANCE")).get(null)!!,
                exact(scroll, "getValue", int), exact(scroll, "getMaxValue", int),
                exact(type("androidx.compose.foundation.ScrollKt"), "rememberScrollState", scroll,
                    int, type("androidx.compose.runtime.Composer"), int, int))
        }.onFailure { runCatching { onError(it) } }.getOrNull()
    }
}
