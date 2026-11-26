package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Concrete Compose layout-node entry; the public ComposeUiNode interface itself cannot be hooked. */
internal data class HostComposeLayoutNodeAccess(val owner: Class<*>, val setModifier: Method) {
    companion object {
        fun resolve(loader: ClassLoader): HostComposeLayoutNodeAccess? {
            val node = Lookup.classOrNull(loader, "androidx.compose.ui.node.ComposeUiNode") ?: return null
            val view = Lookup.classOrNull(loader, "androidx.compose.ui.platform.AndroidComposeView") ?: return null
            val modifier = Lookup.classOrNull(loader, "androidx.compose.ui.Modifier") ?: return null
            val layoutNode = Lookup.declaredMethods(view, makeAccessible = true).singleOrNull {
                it.name == "getRoot" && it.parameterCount == 0 && node.isAssignableFrom(it.returnType)
            }?.returnType?.takeIf { !it.isInterface } ?: node
            val setModifier = Lookup.methods(layoutNode, includeSuperclasses = true, makeAccessible = true).singleOrNull {
                it.name == "setModifier" && it.returnType == Void.TYPE && !Modifier.isAbstract(it.modifiers) &&
                    it.parameterTypes.contentEquals(arrayOf(modifier))
            } ?: return null
            return HostComposeLayoutNodeAccess(layoutNode, setModifier)
        }
    }
}
