package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.reflect.Field
import java.lang.reflect.Method

/** 安装期解析；只在确有变化时复制。所有修改在私有 builder 内完成，失败不发布半成品。 */
internal class ProtobufBuilderPlan private constructor(
    private val owner: Class<*>,
    private val factory: Method,
    private val isInstanceFactory: Boolean,
    private val builderType: Class<*>,
    private val build: Method
) {
    fun method(name: String, vararg parameters: Class<*>): Method? =
        KavaMemberLookup.inheritedMethodOrNull(builderType, name, *parameters)
            ?.takeIf { !it.isStatic }

    fun edit(original: Any, change: (Any) -> Unit): Any {
        require(owner.isInstance(original))
        val builder = checkNotNull(
            if (isInstanceFactory) factory.invoke(original) else factory.invoke(null, original)
        )
        check(builder !== original)
        change(builder)
        return checkNotNull(build.invoke(builder)).also {
            check(owner.isInstance(it) && it !== original)
        }
    }

    companion object {
        fun resolve(owner: Class<*>): ProtobufBuilderPlan? = runCatching {
            val staticFactory = KavaMemberLookup.methodOrNull(owner, "newBuilder", owner)
                ?.takeIf { it.isStatic && !it.returnType.isPrimitive && it.returnType != owner }
            if (staticFactory != null) {
                val bType = staticFactory.returnType
                val build = KavaMemberLookup.inheritedMethodOrNull(bType, "build")
                    ?.takeIf { !it.isStatic && !it.returnType.isPrimitive } ?: return@runCatching null
                return@runCatching ProtobufBuilderPlan(owner, staticFactory, false, bType, build)
            }
            resolveToBuilderFallback(owner)
        }.getOrNull()

        private fun resolveToBuilderFallback(owner: Class<*>): ProtobufBuilderPlan? {
            val toBuilder = KavaMemberLookup.inheritedMethodOrNull(owner, "toBuilder")
                ?.takeIf { !it.isStatic && it.parameterCount == 0 && !it.returnType.isPrimitive && it.returnType != owner }
                ?: return null
            val defaultMethod = KavaMemberLookup.methodOrNull(owner, "getDefaultInstance")
                ?.takeIf { it.isStatic && it.parameterCount == 0 && owner.isAssignableFrom(it.returnType) }
            if (defaultMethod != null) {
                val defaultInstance = defaultMethod.invoke(null) ?: return null
                val sampleBuilder = toBuilder.invoke(defaultInstance) ?: return null
                if (sampleBuilder === defaultInstance) return null
                val concreteBuilderType = sampleBuilder.javaClass
                if (concreteBuilderType.isPrimitive || concreteBuilderType == owner) return null
                val build = KavaMemberLookup.inheritedMethodOrNull(concreteBuilderType, "build")
                    ?.takeIf { !it.isStatic && it.parameterCount == 0 && !it.returnType.isPrimitive }
                    ?: return null
                val rebuilt = build.invoke(sampleBuilder) ?: return null
                if (!owner.isInstance(rebuilt)) return null
                return ProtobufBuilderPlan(owner, toBuilder, true, concreteBuilderType, build)
            }
            val declaredBuilderType = toBuilder.returnType
            val directBuild = KavaMemberLookup.inheritedMethodOrNull(declaredBuilderType, "build")
                ?.takeIf { !it.isStatic && it.parameterCount == 0 && it.returnType == owner }
                ?: return null
            return ProtobufBuilderPlan(owner, toBuilder, true, declaredBuilderType, directBuild)
        }
    }
}

/** 读写 GeneratedMessageLite 的 serializedSize / hashCode 内部缓存并在改写后统一失效。 */
internal class ProtobufCachePlan private constructor(
    private val size: Method,
    private val resetSize: Method,
    private val hash: Field,
    val sentinel: Int,
    val defaultInstance: Any?
) {
    fun readSize(target: Any): Int? = size.invoke(target) as? Int
    fun readHash(target: Any): Int = hash.getInt(target)

    fun invalidate(target: Any) {
        resetSize.invoke(target, sentinel)
        hash.setInt(target, 0)
    }

    fun restore(target: Any, oldSize: Int, oldHash: Int) {
        resetSize.invoke(target, oldSize)
        hash.setInt(target, oldHash)
    }

    companion object {
        fun resolve(type: Class<*>): ProtobufCachePlan? {
            val size = KavaMemberLookup.inheritedMethodOrNull(type, "getMemoizedSerializedSize")
                ?.takeIf { !it.isStatic && it.parameterCount == 0 && it.returnType == Int::class.javaPrimitiveType }
                ?: return null
            val reset = KavaMemberLookup.inheritedMethodOrNull(type, "setMemoizedSerializedSize", Int::class.javaPrimitiveType!!)
                ?.takeIf { !it.isStatic && it.returnType == Void.TYPE }
                ?: return null
            val hash = KavaMemberLookup.fieldOrNull(type, "memoizedHashCode", includeSuperclasses = true)
                ?.takeIf { !it.isStatic && it.type == Int::class.javaPrimitiveType }
                ?: return null
            val defaultInstance = KavaMemberLookup.methodOrNull(type, "getDefaultInstance")
                ?.takeIf { it.isStatic && it.parameterCount == 0 }
                ?.let { method -> runCatching { method.invoke(null)?.takeIf { type.isInstance(it) } }.getOrNull() }
            val sentinel = probeSentinel(type, size, defaultInstance)
            return ProtobufCachePlan(size, reset, hash, sentinel, defaultInstance)
        }

        private fun probeSentinel(type: Class<*>, size: Method, defaultInstance: Any?): Int {
            val fromBuilder = runCatching {
                val builderMethod = KavaMemberLookup.methodOrNull(type, "newBuilder")
                    ?.takeIf { it.isStatic && it.parameterCount == 0 }
                    ?: KavaMemberLookup.inheritedMethodOrNull(type, "newBuilderForType")
                        ?.takeIf { !it.isStatic && it.parameterCount == 0 }
                val builder = when {
                    builderMethod == null -> null
                    builderMethod.isStatic -> builderMethod.invoke(null)
                    defaultInstance != null -> builderMethod.invoke(defaultInstance)
                    else -> null
                } ?: return@runCatching null
                val build = KavaMemberLookup.inheritedMethodOrNull(builder.javaClass, "build")
                    ?.takeIf { !it.isStatic && it.parameterCount == 0 }
                    ?: return@runCatching null
                val empty = build.invoke(builder) ?: return@runCatching null
                (size.invoke(empty) as? Int)?.takeIf { it < 0 || it == Int.MAX_VALUE }
            }.getOrNull()
            if (fromBuilder != null) return fromBuilder
            return defaultInstance?.let {
                runCatching { (size.invoke(it) as? Int)?.takeIf { s -> s < 0 || s == Int.MAX_VALUE } }.getOrNull()
            } ?: -1
        }
    }
}
