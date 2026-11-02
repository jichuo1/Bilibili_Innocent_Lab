package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Kotlin 序列化版 moss 响应 ↔ Java protobuf 响应的往返桥。
 *
 * 9.14.0 起动态页新列表走 `KDynamicMoss`，响应是 kotlinx.serialization 数据类，且 R8 把字段名混淆成
 * `a`/`b`/`c`……（getter 也被内联掉），既没法按名字读、也没法按名字写。但**同一个 proto 的 Java 版
 * 响应类（`DynAllReply` / `DynVideoReply`）仍在宿主里**，动态过滤的全部判据都已经建立在它上面。
 * 所以这里不去解析 Kotlin 数据类，而是走 protobuf 线格式：
 *
 *     Kotlin 响应 --ProtoBuf.encodeToByteArray--> 字节 --Java parseFrom--> Java 响应
 *         --（既有的动态过滤，原样复用）--> 过滤后的 Java 响应
 *         --toByteArray--> 字节 --ProtoBuf.decodeFromByteArray--> 新的 Kotlin 响应
 *
 * 好处：判据（关键词/发布者/带货卡/充电专属/智能过滤/话题栏/UP 栏）一条都不用重写；字段混淆无关；
 * 线格式两边生成自同一份 proto，往返无损。**过滤没有改动任何东西时直接返回原 Kotlin 对象，不做回写**，
 * 所以最常见的路径只多一次序列化 + 解析。
 *
 * 任何一步抛异常都由调用方回退到原响应；这里不吞异常，也不缓存任何宿主实例。
 */
internal class KotlinMossReplyBridge(
    private val encode: (Any) -> ByteArray,
    private val parseJava: (ByteArray) -> Any,
    private val toBytes: (Any) -> ByteArray,
    private val decode: (ByteArray) -> Any
) {
    internal fun read(kotlinReply: Any): Any = parseJava(encode(kotlinReply))
    internal fun write(javaReply: Any): Any = decode(toBytes(javaReply))

    /** @return 过滤后的 Kotlin 响应；[purifyJava] 返回同一个对象表示没有改动，此时返回 [kotlinReply] 本身。 */
    fun transform(kotlinReply: Any, purifyJava: (Any) -> Any): Any {
        val javaReply = read(kotlinReply)
        val updated = purifyJava(javaReply)
        return if (updated === javaReply) kotlinReply else write(updated)
    }
}

/**
 * 安装期解析出的 kotlinx.serialization 与 Java protobuf 成员；只持有 Class/Method，不持有宿主实例。
 * 这些名字（`ProtoBuf#encodeToByteArray` / `decodeFromByteArray`、`parseFrom([B)` / `toByteArray`）在宿主里
 * 都没有被混淆（2026-09-30 dexq 对 9.14.0 核对）；任何一个缺失就整体不可用（返回 null），
 * 由调用方退回观测措施，而不是装一个半截的桥。
 */
internal class KotlinMossBridgeMembers private constructor(
    private val strategyClass: Class<*>,
    private val deserializationClass: Class<*>,
    private val protoBufClass: Class<*>,
    private val encodeMethod: Method,
    private val decodeMethod: Method
) {
    /** Java 响应类的解析/序列化入口。 */
    class JavaReplyCodec(private val parseFrom: Method, private val toByteArray: Method) {
        fun parse(bytes: ByteArray): Any = unwrap { parseFrom.invoke(null, bytes) }
            ?: error("parseFrom returned null")

        fun serialize(reply: Any): ByteArray = unwrap { toByteArray.invoke(reply) } as? ByteArray
            ?: error("toByteArray returned non-bytes")

        companion object {
            /**
             * 用 [Class.getMethod]（含继承的公开方法）而不是"只看本类声明"的查找：Java protobuf 消息的
             * `toByteArray()` 声明在父类 `AbstractMessageLite` 里，本类只声明静态 `parseFrom(byte[])`。
             * 2026-10-01 真机上因为只查本类，两处 Kotlin 通道都静默没装上；单测夹具把两个方法声明在同一个类里，没能暴露。
             */
            fun resolve(javaReplyClass: Class<*>): JavaReplyCodec? {
                val parse = runCatching { javaReplyClass.getMethod("parseFrom", classOf<ByteArray>()) }.getOrNull()
                    ?.takeIf { it.isStatic && it.returnType isSubclassOf javaReplyClass }
                val bytes = runCatching { javaReplyClass.getMethod("toByteArray") }.getOrNull()
                    ?.takeIf { !it.isStatic && it.returnType == classOf<ByteArray>() }
                return if (parse != null && bytes != null) JavaReplyCodec(parse, bytes) else null
            }
        }
    }

    /** 宿主的默认 `ProtoBuf` 实例（`ProtoBuf.Default`）；自检用，取不到返回 null。 */
    fun defaultProtoBuf(): Any? = runCatching { protoBufClass.getField("Default").get(null) }.getOrNull()
        ?.takeIf(protoBufClass::isInstance)

    fun encodeKotlin(protoBuf: Any, serializer: Any, value: Any): ByteArray =
        unwrap { encodeMethod.invoke(protoBuf, serializer, value) } as? ByteArray
            ?: error("encodeToByteArray returned non-bytes")

    fun decodeKotlin(protoBuf: Any, serializer: Any, bytes: ByteArray): Any =
        unwrap { decodeMethod.invoke(protoBuf, serializer, bytes) } ?: error("decodeFromByteArray returned null")

    /**
     * 为一次响应回调构造桥。[serializer] 是宿主传给 `dynAll/dynVideo` 的响应反序列化器
     * （生成的 `$serializer`，同时实现序列化与反序列化两个接口），[protoBuf] 是它用的 `ProtoBuf` 实例。
     * 类型对不上就返回 null，调用方原样放行。
     */
    fun bridgeFor(protoBuf: Any?, serializer: Any?, codec: JavaReplyCodec): KotlinMossReplyBridge? {
        if (protoBuf == null || serializer == null) return null
        if (!protoBufClass.isInstance(protoBuf)) return null
        if (!strategyClass.isInstance(serializer) || !deserializationClass.isInstance(serializer)) return null
        return KotlinMossReplyBridge(
            encode = { reply -> unwrap { encodeMethod.invoke(protoBuf, serializer, reply) } as? ByteArray
                ?: error("encodeToByteArray returned non-bytes") },
            parseJava = codec::parse,
            toBytes = codec::serialize,
            decode = { bytes -> unwrap { decodeMethod.invoke(protoBuf, serializer, bytes) }
                ?: error("decodeFromByteArray returned null") }
        )
    }

    companion object {
        const val SERIALIZATION_STRATEGY = "kotlinx.serialization.SerializationStrategy"
        const val DESERIALIZATION_STRATEGY = "kotlinx.serialization.DeserializationStrategy"
        const val PROTO_BUF = "kotlinx.serialization.protobuf.ProtoBuf"

        fun resolve(loader: ClassLoader): KotlinMossBridgeMembers? {
            val strategy = KavaMemberLookup.classOrNull(loader, SERIALIZATION_STRATEGY) ?: return null
            val deserialization = KavaMemberLookup.classOrNull(loader, DESERIALIZATION_STRATEGY) ?: return null
            val protoBuf = KavaMemberLookup.classOrNull(loader, PROTO_BUF) ?: return null
            val encode = KavaMemberLookup.methodOrNull(protoBuf, "encodeToByteArray", strategy, classOf<Any>())
                ?.takeIf { !it.isStatic && it.returnType == classOf<ByteArray>() } ?: return null
            val decode = KavaMemberLookup.methodOrNull(protoBuf, "decodeFromByteArray", deserialization, classOf<ByteArray>())
                ?.takeIf { !it.isStatic } ?: return null
            return KotlinMossBridgeMembers(strategy, deserialization, protoBuf, encode, decode)
        }

        /**
         * Java moss 类名 → 同包的 Kotlin 版类名：`…v1.ViewMoss` → `…v1.KViewMoss`（宿主里 K 版一律是 `K` + 原类名，
         * 且 `K*Moss` 类名没有被混淆）。类名里带 `$` 或最后一段为空时返回 null。
         */
        fun kotlinMossClassName(javaMossClassName: String): String? {
            val dot = javaMossClassName.lastIndexOf('.')
            val simple = javaMossClassName.substring(dot + 1)
            if (simple.isEmpty() || '$' in simple) return null
            return javaMossClassName.substring(0, dot + 1) + "K" + simple
        }

        /** 同步入口 `executeViewProgress` 与异步入口 `viewProgress` 对应同一个 RPC：统一成 `viewProgress`。 */
        fun rpcName(methodName: String): String =
            if (methodName.startsWith("execute") && methodName.length > "execute".length &&
                methodName["execute".length].isUpperCase()
            ) {
                methodName.substring("execute".length).replaceFirstChar { it.lowercaseChar() }
            } else methodName

        /**
         * `KDynamicMoss` 里所有 Kotlin 版请求最终汇入的**回调形态泛型入口**：
         * `(Object req, SerializationStrategy, DeserializationStrategy, Handler, ProtoBuf)V`。
         * suspend 形态的 `dynAll/dynVideo` 也是转成它（内联的 `suspendCall` 匿名回调）再发请求，
         * 所以挂它一处就覆盖 suspend / 回调两种调用方式。Handler 类型被混淆，只认"接口"，
         * 响应回调方法名（`onNext`）没有被混淆，由 [MossResponseHandlerProxy] 处理。
         */
        fun callbackEntry(mossClass: Class<*>, name: String): Method? =
            KavaMemberLookup.declaredMethods(mossClass, makeAccessible = true) { method ->
                method.name == name && isCallbackEntry(method)
            }.singleOrNull()

        /** 名字由常规定位或 DEX 语义定位决定；回调 ABI 的复核共用一份。 */
        fun isCallbackEntry(method: Method): Boolean {
            val types = method.parameterTypes
            return !method.isStatic && method.returnType == Void.TYPE &&
                types.size == 5 && types[0] == classOf<Any>() &&
                types[1].name == SERIALIZATION_STRATEGY && types[2].name == DESERIALIZATION_STRATEGY &&
                types[3].isInterface && types[4].name == PROTO_BUF
        }

        /**
         * 8.84.0–9.8.0 的同一回调入口少最后一个 `ProtoBuf` 参数：`(Object, SerializationStrategy,
         * DeserializationStrategy, Handler)V`，序列化使用 [defaultProtoBuf]（与播放编码偏好的旧版路径一致）。
         * Hostlab 38 版矩阵：9.9.0 起才是 5 参；8.96.0 起评论、9.5.0 起弹幕已有宿主调用走 K 通道。
         */
        fun legacyCallbackEntry(mossClass: Class<*>, name: String): Method? =
            KavaMemberLookup.declaredMethods(mossClass, makeAccessible = true) { method ->
                method.name == name && isLegacyCallbackEntry(method)
            }.singleOrNull()

        fun isLegacyCallbackEntry(method: Method): Boolean {
            val types = method.parameterTypes
            return !method.isStatic && method.returnType == Void.TYPE &&
                types.size == 4 && types[0] == classOf<Any>() &&
                types[1].name == SERIALIZATION_STRATEGY && types[2].name == DESERIALIZATION_STRATEGY &&
                types[3].isInterface
        }
    }
}

/** 反射调用的异常统一还原成宿主/库自己抛出的那个。 */
private inline fun unwrap(block: () -> Any?): Any? = try {
    block()
} catch (invocation: InvocationTargetException) {
    throw invocation.targetException ?: invocation
}
