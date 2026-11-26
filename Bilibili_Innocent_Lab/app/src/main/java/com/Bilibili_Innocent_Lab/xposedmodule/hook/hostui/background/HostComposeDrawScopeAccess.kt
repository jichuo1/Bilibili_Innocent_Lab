package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.Canvas
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
    /** 当前记录画布平移；Compose 的 drawRect 坐标是局部坐标，必须结合画布矩阵判断视口边缘。 */
    fun translation(instance: Any): Pair<Float, Float>? = runCatching {
        if (drawContext == null || canvas == null || nativeCanvas == null) return@runCatching null
        val context = drawContext.invoke(instance) ?: return@runCatching null
        val composeCanvas = canvas.invoke(context) ?: return@runCatching null
        val native = nativeCanvas.invoke(null, composeCanvas) as? Canvas ?: return@runCatching null
        val values = FloatArray(9)
        native.matrix.getValues(values)
        values[2] to values[5]
    }.getOrNull()

    companion object {
        fun resolve(loader: ClassLoader): HostComposeDrawScopeAccess? {
            val view = KavaMemberLookup.classOrNull(loader, "androidx.compose.ui.platform.AndroidComposeView") ?: return null
            val dispatch = KavaMemberLookup.methodOrNull(view, "dispatchDraw", Canvas::class.java) ?: return null
            val shared = KavaMemberLookup.declaredMethods(view) {
                it.name == "getSharedDrawScope" && it.parameterCount == 0 && it.returnType != Void.TYPE
            }.singleOrNull()
            val scope = shared?.returnType?.takeIf { !it.isInterface }
                ?: KavaMemberLookup.classOrNull(loader, "androidx.compose.ui.node.LayoutNodeDrawScope") ?: return null
            val draw = KavaMemberLookup.declaredMethods(scope, true) {
                it.name.startsWith("drawRect-") && it.parameterCount == 7 && it.returnType == Void.TYPE &&
                    it.parameterTypes.take(3).all { parameter -> parameter == Long::class.javaPrimitiveType }
            }.singleOrNull() ?: return null
            val drawContext = KavaMemberLookup.declaredMethods(scope, true) {
                it.name == "getDrawContext" && it.parameterCount == 0 && it.returnType != Void.TYPE
            }.singleOrNull()
            val canvas = drawContext?.returnType?.let { context ->
                KavaMemberLookup.declaredMethods(context, true) {
                    it.name == "getCanvas" && it.parameterCount == 0 && it.returnType != Void.TYPE
                }.singleOrNull()
            }
            val androidCanvas = KavaMemberLookup.classOrNull(loader, "androidx.compose.ui.graphics.AndroidCanvas_androidKt")
            val nativeCanvas = canvas?.let { method ->
                androidCanvas?.let { KavaMemberLookup.methodOrNull(it, "getNativeCanvas", method.returnType) }
            }
            return HostComposeDrawScopeAccess(scope, dispatch, draw, drawContext, canvas, nativeCanvas)
        }
    }
}
