package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Concrete Compose layout-node entry; the public ComposeUiNode interface itself cannot be hooked. */
internal data class HostComposeLayoutNodeAccess(val owner: Class<*>, val setModifier: Method) {
    companion object {
        fun resolve(loader: ClassLoader, modifier: Class<*>? = null): HostComposeLayoutNodeAccess? {
            val node = Lookup.classOrNull(loader, "androidx.compose.ui.node.ComposeUiNode") ?: return null
            val mod = modifier ?: Lookup.classOrNull(loader, "androidx.compose.ui.Modifier") ?: return null

            fun accept(candidate: Class<*>?): HostComposeLayoutNodeAccess? {
                if (candidate == null || candidate.isInterface || Modifier.isAbstract(candidate.modifiers) ||
                    !node.isAssignableFrom(candidate)
                ) return null
                val setModifier = Lookup.methods(candidate, includeSuperclasses = true, makeAccessible = true).singleOrNull {
                    it.name == "setModifier" && !Modifier.isAbstract(it.modifiers) && !Modifier.isStatic(it.modifiers) &&
                        it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(mod))
                } ?: return null
                return HostComposeLayoutNodeAccess(candidate, setModifier)
            }

            // 1. 首选无实例化副作用的 AndroidComposeView.getRoot() 返回类型（适配 R8 改名的具体 LayoutNode）。
            val view = Lookup.classOrNull(loader, "androidx.compose.ui.platform.AndroidComposeView")
            if (view != null) {
                val rootReturn = Lookup.declaredMethods(view, makeAccessible = true).singleOrNull {
                    it.name == "getRoot" && it.parameterCount == 0 && node.isAssignableFrom(it.returnType)
                }?.returnType
                accept(rootReturn)?.let { return it }
            }

            // 2. 次选标准公开类名 androidx.compose.ui.node.LayoutNode。
            accept(Lookup.classOrNull(loader, "androidx.compose.ui.node.LayoutNode"))?.let { return it }

            // 3. 兜底经 ComposeUiNode.Companion.getConstructor() 取构造目标具体类。
            val tempClass = runCatching {
                val companionClass = Lookup.classOrNull(loader, "androidx.compose.ui.node.ComposeUiNode\$Companion")
                    ?: Lookup.fieldOrNull(node, "Companion")?.type
                val companionField = Lookup.fieldOrNull(node, "Companion")
                    ?: companionClass?.let { cc ->
                        Lookup.declaredFields(node, makeAccessible = true).singleOrNull { it.type == cc }
                    }
                val companion = companionField?.get(null) ?: return@runCatching null
                val function0 = Lookup.classOrNull(loader, "kotlin.jvm.functions.Function0")
                val getConstructor = Lookup.methodOrNull(companion.javaClass, "getConstructor")
                    ?: Lookup.declaredMethods(companion.javaClass, makeAccessible = true).singleOrNull {
                        it.parameterCount == 0 && !Modifier.isStatic(it.modifiers) &&
                            (function0 != null && function0.isAssignableFrom(it.returnType) ||
                                it.returnType.name == "kotlin.jvm.functions.Function0")
                    } ?: return@runCatching null
                val constructorFn = getConstructor.invoke(companion) ?: return@runCatching null
                val invoke = Lookup.methodOrNull(getConstructor.returnType, "invoke")
                    ?: Lookup.methods(constructorFn.javaClass, includeSuperclasses = true, makeAccessible = true).firstOrNull {
                        it.name == "invoke" && it.parameterCount == 0 && !Modifier.isStatic(it.modifiers) &&
                            !it.isBridge && !it.isSynthetic
                    } ?: Lookup.methods(constructorFn.javaClass, includeSuperclasses = true, makeAccessible = true).first {
                        it.name == "invoke" && it.parameterCount == 0 && !Modifier.isStatic(it.modifiers)
                    }
                invoke.invoke(constructorFn)?.javaClass
            }.getOrNull()
            return accept(tempClass)
        }
    }
}
