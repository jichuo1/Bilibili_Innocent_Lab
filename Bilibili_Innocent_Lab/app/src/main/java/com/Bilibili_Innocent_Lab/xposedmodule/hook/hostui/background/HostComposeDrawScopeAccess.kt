package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.Canvas
import android.graphics.Matrix
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.matchesComposeName
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method

/** Compose 绘制作用域的宿主结构入口；具体实现类在部分版本会被 R8 改名。 */
internal class HostComposeDrawScopeAccess private constructor(
    val scope: Class<*>,
    val dispatch: Method,
    val draw: Method,
    val drawContext: Method?,
    val canvas: Method?,
    val nativeCanvas: Method?
) {
    private class Scratch {
        val matrix = Matrix()
        val values = FloatArray(9)
    }

    private val scratch = ThreadLocal.withInitial(::Scratch)

    private fun nativeCanvasOf(instance: Any): Canvas? {
        if (drawContext == null || canvas == null || nativeCanvas == null) return null
        val context = drawContext.invoke(instance) ?: return null
        val composeCanvas = canvas.invoke(context) ?: return null
        return nativeCanvas.invoke(null, composeCanvas) as? Canvas
    }

    /** 当前画布 Y 平移；Compose 的 drawRect 坐标是局部坐标，热路径复用缓冲区零堆分配读取。 */
    fun translationY(instance: Any): Float = runCatching {
        val native = nativeCanvasOf(instance) ?: return@runCatching Float.NaN
        val state = scratch.get() ?: Scratch()
        @Suppress("DEPRECATION")
        native.getMatrix(state.matrix)
        state.matrix.getValues(state.values)
        state.values[Matrix.MTRANS_Y]
    }.getOrDefault(Float.NaN)

    /** 当前记录画布平移；Compose 的 drawRect 坐标是局部坐标，必须结合画布矩阵判断视口边缘。 */
    fun translation(instance: Any): Pair<Float, Float>? = runCatching {
        val native = nativeCanvasOf(instance) ?: return@runCatching null
        val state = scratch.get() ?: Scratch()
        @Suppress("DEPRECATION")
        native.getMatrix(state.matrix)
        state.matrix.getValues(state.values)
        state.values[Matrix.MTRANS_X] to state.values[Matrix.MTRANS_Y]
    }.getOrNull()

    companion object {
        fun resolve(loader: ClassLoader): HostComposeDrawScopeAccess? {
            val view = KavaMemberLookup.classOrNull(loader, "androidx.compose.ui.platform.AndroidComposeView") ?: return null
            val dispatch = KavaMemberLookup.methodOrNull(view, "dispatchDraw", Canvas::class.java) ?: return null
            val shared = KavaMemberLookup.declaredMethods(view, makeAccessible = true) {
                it.name == "getSharedDrawScope" && it.parameterCount == 0 && it.returnType != Void.TYPE
            }.singleOrNull()
            val scope = shared?.returnType?.takeIf { !it.isInterface }
                ?: KavaMemberLookup.classOrNull(loader, "androidx.compose.ui.node.LayoutNodeDrawScope") ?: return null
            val draw = KavaMemberLookup.declaredMethods(scope, true) {
                matchesComposeName(it.name, "drawRect") && it.parameterCount == 7 && it.returnType == Void.TYPE &&
                    it.parameterTypes.take(3).all { parameter -> parameter == Long::class.javaPrimitiveType }
            }.singleOrNull() ?: return null
            val drawContext = findNoArgGetter(scope, "getDrawContext")
            val canvas = drawContext?.returnType?.let { context -> findNoArgGetter(context, "getCanvas") }
            val androidCanvas = KavaMemberLookup.classOrNull(loader, "androidx.compose.ui.graphics.AndroidCanvas_androidKt")
            val nativeCanvas = canvas?.let { method ->
                androidCanvas?.let { KavaMemberLookup.methodOrNull(it, "getNativeCanvas", method.returnType) }
            }
            return HostComposeDrawScopeAccess(scope, dispatch, draw, drawContext, canvas, nativeCanvas)
        }

        private fun findNoArgGetter(owner: Class<*>, name: String): Method? {
            return KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
                it.name == name && it.parameterCount == 0 && it.returnType != Void.TYPE
            }.singleOrNull() ?: KavaMemberLookup.methods(owner, includeSuperclasses = true, makeAccessible = true).singleOrNull {
                it.name == name && it.parameterCount == 0 && it.returnType != Void.TYPE
            }
        }
    }
}
