package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.locators

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.util.ArrayDeque

/**
 * 领域定位器共享的反射与 HookPoint 元数据辅助函数。
 */
internal object LocatorReflectUtils {

    internal fun Type.rawClassOrNull(): Class<*>? = when (this) {
        is Class<*> -> this
        is ParameterizedType -> rawType as? Class<*>
        is WildcardType -> upperBounds.firstNotNullOfOrNull { it.rawClassOrNull() }
        else -> null
    }

    internal fun Method.toHookPoint(): HookPoint = HookPoint(
        className = declaringClass.name,
        methodName = name,
        paramClassNames = parameterTypes.map { it.name }
    )

    internal fun HookPoint.label(): String = buildString {
        append(className)
        append('#')
        append(methodName)
        paramClassNames?.let { params ->
            append('(')
            append(params.joinToString(","))
            append(')')
        }
    }

    /**
     * 按宿主类层级名称判断类型，避免模块与宿主各自打包 AndroidX 时 Class 身份不同。
     * 仅用于一次性版本探测，不进入任何 Hook 回调热路径。
     */
    internal fun Class<*>.hasSuperclassNamed(expectedName: String): Boolean {
        var current: Class<*>? = this
        while (current != null) {
            if (current.name == expectedName) return true
            current = current.superclass
        }
        return false
    }

    /** 通过类名遍历接口层级，避免宿主 AndroidX 与模块 ClassLoader 身份差异。 */
    internal fun Class<*>.implementsInterfaceNamed(expectedName: String): Boolean {
        val pending = ArrayDeque<Class<*>>()
        val visited = HashSet<Class<*>>()
        pending.add(this)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            current.interfaces.forEach { implemented ->
                if (implemented.name == expectedName) return true
                pending.addLast(implemented)
            }
            current.superclass?.let(pending::addLast)
        }
        return false
    }
}
