package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import android.graphics.Canvas
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostComposeCardPositionAccess
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/** Host-loader public Compose ABI only. No module Compose object crosses this boundary. */
internal class HostComposeChromeApi private constructor(
    val modifier: Class<*>, val updater: Method, val setter: Any, val setterInvoke: Method, private val getModifier: Method,
    private val empty: Any, private val fold: Method, private val then: Method, private val fill: HostChromeRootFill,
    private val wholeArea: Any?,
    val backdropLeaves: List<BackdropLeaf>, val backdropImagesReady: Boolean,
    private val behind: Method, private val withContent: Method, private val drawContent: Method, val rowPolicy: Method, private val height: Method, private val padding: Method, private val f1: Class<*>, private val f2: Class<*>, private val unit: Any,
    private val size: Method, private val context: Method, private val canvas: Method, private val native: Method,
    private val position: HostComposeCardPositionAccess
) {
    internal data class BackdropLeaf(val method: Method, val modifierIndex: Int)

    /** Match the host's own fillMaxSize element, never a guessed R8 class or an image URL. */
    fun fillsBackdrop(original: Any): Boolean {
        val marker = wholeArea ?: return false
        // Certified background image call sites supply only fillMaxSize. A chain with sizing,
        // input or semantics is foreground/unknown and must not become eligible by containing it.
        return original === marker || original == marker
    }

    fun decorate(original: Any, painter: HostComposeChromePainter, layer: HostChromeLayer): Any {
        if (layer == HostChromeLayer.TOP_BACKDROP) {
            if (!painter.glass) return original
            return HostChromeBackdropPaint.suppress(original, empty, withContent, then, f1, unit,
                { try { painter.backdropActive } catch (failure: Throwable) { painter.failure(failure); false } }) { scope ->
                try { drawContent.invoke(scope) } catch (failure: InvocationTargetException) { throw failure.targetException }
            }
        }
        // Replace only the scoped root's opaque fill. All layout, clip, click and pointer elements survive.
        val base = (if (layer == HostChromeLayer.BOTTOM_ROW) null else painter.layout())?.let { dock ->
            val padded = padding.invoke(null, empty, dock.marginDp, 0f, dock.marginDp, dock.bottomDp)!!
            then.invoke(dock.heightDp?.let { height.invoke(null, padded, it) } ?: padded, original)!!
        } ?: original
        if (layer == HostChromeLayer.BOTTOM_OUTER) {
            if (!painter.glass) return base
            val clipped = function(f1, "HostChromeOuterClip") { args ->
                args?.firstOrNull()?.let { scope ->
                    painter.clipRoot({ dimensions(scope) }, { targetCanvas(scope) }) {
                        try { drawContent.invoke(scope) } catch (failure: InvocationTargetException) { throw failure.targetException }
                    }
                }
                unit
            }
            return requireNotNull(withContent.invoke(null, base, clipped))
        }
        val withoutFill = if (painter.glass) fold.invoke(base, empty, function(f2, "HostChromeRootFill") { args ->
            val accumulated = requireNotNull(args?.getOrNull(0))
            val element = requireNotNull(args?.getOrNull(1))
            if (fill.matches(element)) accumulated
            else requireNotNull(then.invoke(accumulated, element))
        })!! else base
        // 9.16+ moves the pinned row's ComposeView by AppBarLayout offsets, which never re-run the
        // positioned callback. A root-local offset lets the painter add the View's live location.
        val positioned = if (position.rootPositions) position.attach(withoutFill, painter::rootPosition, painter::failure, root = true)
            else position.attach(withoutFill, painter::position, painter::failure)
        val draw = function(f1, "HostChromeSurface") { args ->
            args?.firstOrNull()?.let { scope ->
                painter.draw({ dimensions(scope) }, { targetCanvas(scope) })
            }
            unit
        }
        return requireNotNull(behind.invoke(null, positioned, draw))
    }

    private fun dimensions(scope: Any): Long = size.invoke(scope) as Long
    private fun targetCanvas(scope: Any) = native.invoke(null, canvas.invoke(context.invoke(scope))) as Canvas
    fun signal() = position.signal()

    fun restore(node: Any, expected: Any, original: Any) {
        if (getModifier.invoke(node) === expected) setterInvoke.invoke(setter, node, original)
    }

    private fun function(type: Class<*>, label: String, invoke: (Array<out Any?>?) -> Any?): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> invoke(args)
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> label
                else -> null
            }
        }

    companion object {
        fun resolve(loader: ClassLoader, onError: (Throwable) -> Unit = {}): HostComposeChromeApi? = runCatching {
            fun type(name: String) = requireNotNull(Lookup.classOrNull(loader, name))
            fun exact(owner: Class<*>, prefix: String, result: Class<*>, vararg p: Class<*>) =
                Lookup.declaredMethods(owner, makeAccessible = true).singleOrNull {
                    it.name.startsWith(prefix) && !it.name.endsWith("\$default") &&
                        it.returnType == result && it.parameterTypes.contentEquals(p)
                } ?: error("Missing or ambiguous host Compose ABI ${owner.name}.$prefix/${p.size}")
            val modifier = type("androidx.compose.ui.Modifier")
            val f1 = type("kotlin.jvm.functions.Function1")
            val empty = requireNotNull(Lookup.fieldOrNull(modifier, "Companion")).get(null)!!
            val shape = type("androidx.compose.ui.graphics.Shape")
            val fill = HostChromeRootFill(modifier, type("androidx.compose.ui.graphics.Brush"),
                shape, f1, empty, exact(type("androidx.compose.foundation.BackgroundKt"), "background-", modifier, modifier, Long::class.javaPrimitiveType!!, shape))
            val f2 = type("kotlin.jvm.functions.Function2")
            val composer = type("androidx.compose.runtime.Composer")
            val obj = Any::class.java
            val wholeArea = Lookup.declaredMethods(type("androidx.compose.foundation.layout.SizeKt"), makeAccessible = true)
                .singleOrNull { it.name == "fillMaxSize\$default" && Modifier.isStatic(it.modifiers) &&
                    it.returnType == modifier && it.parameterTypes.toList() == listOf(modifier,
                        Float::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, obj) }
                ?.invoke(null, empty, 0f, 1, null)
            val node = type("androidx.compose.ui.node.ComposeUiNode")
            val companion = requireNotNull(Lookup.fieldOrNull(node, "Companion")).get(null)!!
            val setter = exact(type("androidx.compose.ui.node.ComposeUiNode\$Companion"), "getSetModifier", f2).invoke(companion)!!
            val setterInvoke = Lookup.methods(setter.javaClass, includeSuperclasses = true, makeAccessible = true).first {
                it.name == "invoke" && it.parameterTypes.contentEquals(arrayOf(obj, obj))
            }
            val drawScope = type("androidx.compose.ui.graphics.drawscope.DrawScope")
            val drawContext = type("androidx.compose.ui.graphics.drawscope.DrawContext")
            val hostCanvas = type("androidx.compose.ui.graphics.Canvas")
            val image = Lookup.classOrNull(loader, "kntr.base.imageloader.BiliImageKt")
            val imageLeaf = image?.let {
                Lookup.declaredMethods(it, makeAccessible = true).filter { method ->
                    Modifier.isStatic(method.modifiers) && method.name == "BiliImage" && method.returnType == Void.TYPE &&
                        method.parameterTypes.map { p -> p.name } == listOf(
                            "kntr.base.imageloader.ImmutableImageRequest", modifier.name, "java.lang.String",
                            "androidx.lifecycle.Lifecycle", f1.name, f1.name, f1.name, f1.name,
                            f2.name, "kotlin.jvm.functions.Function4", composer.name, "int", "int")
                }.singleOrNull()?.let { method -> BackdropLeaf(method, 1) }
            }
            HostComposeChromeApi(modifier,
                exact(type("androidx.compose.runtime.Updater"), "set-impl", Void.TYPE, composer, obj, f2).also { check(Modifier.isStatic(it.modifiers)) }, setter, setterInvoke, exact(node, "getModifier", modifier),
                empty,
                exact(modifier, "foldIn", obj, obj, f2), exact(modifier, "then", modifier, modifier), fill, wholeArea,
                listOf("BoxKt" to "Box", "SpacerKt" to "Spacer").mapNotNull { (owner, name) ->
                    runCatching { exact(type("androidx.compose.foundation.layout.$owner"), name, Void.TYPE,
                        modifier, composer, Int::class.javaPrimitiveType!!).also { check(Modifier.isStatic(it.modifiers)) } }
                        .getOrNull()?.let { BackdropLeaf(it, 0) }
                } + listOfNotNull(imageLeaf), image == null || imageLeaf != null && wholeArea != null,
                exact(type("androidx.compose.ui.draw.DrawModifierKt"), "drawBehind", modifier, modifier, f1),
                exact(type("androidx.compose.ui.draw.DrawModifierKt"), "drawWithContent", modifier, modifier, f1),
                exact(type("androidx.compose.ui.graphics.drawscope.ContentDrawScope"), "drawContent", Void.TYPE),
                exact(type("androidx.compose.foundation.layout.RowKt"), "rowMeasurePolicy", type("androidx.compose.ui.layout.MeasurePolicy"),
                    type("androidx.compose.foundation.layout.Arrangement\$Horizontal"), type("androidx.compose.ui.Alignment\$Vertical"), composer, Int::class.javaPrimitiveType!!),
                exact(type("androidx.compose.foundation.layout.SizeKt"), "height-", modifier, modifier, Float::class.javaPrimitiveType!!),
                exact(type("androidx.compose.foundation.layout.PaddingKt"), "padding-", modifier, modifier,
                    Float::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!), f1, f2,
                requireNotNull(Lookup.fieldOrNull(type("kotlin.Unit"), "INSTANCE")).get(null)!!,
                exact(drawScope, "getSize-", Long::class.javaPrimitiveType!!),
                exact(drawScope, "getDrawContext", drawContext), exact(drawContext, "getCanvas", hostCanvas),
                exact(type("androidx.compose.ui.graphics.AndroidCanvas_androidKt"), "getNativeCanvas", Canvas::class.java, hostCanvas),
                requireNotNull(HostComposeCardPositionAccess.resolve(loader)))
        }.onFailure { runCatching { onError(it) } }.getOrNull()
    }
}
