package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.Canvas
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method

/** Compose 绘制作用域的宿主结构入口；具体实现类在部分版本会被 R8 改名。 */
internal class HostComposeDrawScopeAccess private constructor(
    val scope: Class<*>,
    val dispatch: Method,
    val draw: Method
) {
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
            return HostComposeDrawScopeAccess(scope, dispatch, draw)
        }
    }
}
