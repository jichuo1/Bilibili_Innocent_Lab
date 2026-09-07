package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.isStatic
import java.lang.reflect.Method

/** 安装期解析；只在确有变化时复制。所有修改在私有 builder 内完成，失败不发布半成品。 */
internal class ProtobufBuilderPlan private constructor(
    private val owner: Class<*>,
    private val factory: Method,
    private val build: Method
) {
    fun method(name: String, vararg parameters: Class<*>): Method? =
        KavaMemberLookup.inheritedMethodOrNull(factory.returnType, name, *parameters)
            ?.takeIf { !it.isStatic }

    fun edit(original: Any, change: (Any) -> Unit): Any {
        require(owner.isInstance(original))
        val builder = checkNotNull(factory.invoke(null, original))
        check(builder !== original)
        change(builder)
        return checkNotNull(build.invoke(builder)).also {
            check(owner.isInstance(it) && it !== original)
        }
    }

    companion object {
        fun resolve(owner: Class<*>): ProtobufBuilderPlan? = runCatching {
            val factory = KavaMemberLookup.methodOrNull(owner, "newBuilder", owner)
                ?.takeIf { it.isStatic && !it.returnType.isPrimitive && it.returnType != owner }
                ?: return null
            val build = KavaMemberLookup.inheritedMethodOrNull(factory.returnType, "build")
                ?.takeIf { !it.isStatic && !it.returnType.isPrimitive } ?: return null
            ProtobufBuilderPlan(owner, factory, build)
        }.getOrNull()
    }
}
