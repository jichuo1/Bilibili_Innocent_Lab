package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinMossReplyBridgeTest {

    // ---- 纯往返逻辑 ----------------------------------------------------------------------------

    private class Counters {
        var decodes = 0
    }

    private fun bridge(counters: Counters = Counters()) = KotlinMossReplyBridge(
        encode = { (it as String).toByteArray() },
        parseJava = { String(it) },
        toBytes = { (it as String).toByteArray() },
        decode = { counters.decodes++; "decoded:" + String(it) }
    )

    @Test
    fun `an unchanged purify result returns the original kotlin reply without writing back`() {
        val counters = Counters()
        val original = "reply"
        val result = bridge(counters).transform(original) { javaReply -> javaReply }
        assertSame(original, result)
        assertEquals(0, counters.decodes)
    }

    @Test
    fun `a changed purify result is written back through the kotlin decoder`() {
        val counters = Counters()
        val result = bridge(counters).transform("reply") { javaReply -> "$javaReply|filtered" }
        assertEquals("decoded:reply|filtered", result)
        assertEquals(1, counters.decodes)
    }

    @Test
    fun `failures propagate so the caller can fall back to the original reply`() {
        val failing = KotlinMossReplyBridge(
            encode = { error("encode failed") },
            parseJava = { it },
            toBytes = { it as ByteArray },
            decode = { it }
        )
        assertThrows(IllegalStateException::class.java) { failing.transform("reply") { it } }
        val decodeFails = KotlinMossReplyBridge(
            encode = { (it as String).toByteArray() },
            parseJava = { String(it) },
            toBytes = { (it as String).toByteArray() },
            decode = { error("decode failed") }
        )
        assertThrows(IllegalStateException::class.java) { decodeFails.transform("reply") { "changed" } }
    }

    // ---- 反射解析（夹具里的 kotlinx.serialization 与假 Java 响应类）---------------------------------

    /** 形状模仿 Java protobuf 响应：静态 `parseFrom(byte[])` + 实例 `toByteArray()`。 */
    class FakeJavaReply(val text: String) {
        fun toByteArray(): ByteArray = text.toByteArray()

        companion object {
            @JvmStatic
            fun parseFrom(bytes: ByteArray): FakeJavaReply = FakeJavaReply(String(bytes))
        }
    }

    /** 真实的 Java protobuf 形状：`toByteArray()` 声明在**父类**，本类只声明静态 `parseFrom(byte[])`。 */
    open class FakeBaseReply(val text: String) {
        fun toByteArray(): ByteArray = text.toByteArray()
    }

    class FakeDerivedReply(text: String) : FakeBaseReply(text) {
        companion object {
            @JvmStatic
            fun parseFrom(bytes: ByteArray): FakeDerivedReply = FakeDerivedReply(String(bytes))
        }
    }

    private class NoParse {
        @Suppress("unused")
        fun toByteArray(): ByteArray = ByteArray(0)
    }

    private class FakeSerializer : SerializationStrategy<Any>, DeserializationStrategy<Any>

    private class SerializerOnly : SerializationStrategy<Any>

    private val loader: ClassLoader get() = javaClass.classLoader!!

    @Test
    fun `resolves the kotlinx members and round trips through a real reflective bridge`() {
        val members = KotlinMossBridgeMembers.resolve(loader)
        assertNotNull(members)
        val codec = KotlinMossBridgeMembers.JavaReplyCodec.resolve(FakeJavaReply::class.java)
        assertNotNull(codec)
        val bridge = members!!.bridgeFor(ProtoBuf(), FakeSerializer(), codec!!)
        assertNotNull(bridge)
        val result = bridge!!.transform("A") { javaReply ->
            FakeJavaReply((javaReply as FakeJavaReply).text + "|filtered")
        }
        // 夹具 ProtoBuf：encode="K:"+value，decode="K:"+bytes；桥中间经过 FakeJavaReply。
        assertEquals("K:K:A|filtered", result)
        // 没有改动：返回原对象。
        assertSame("A", bridge.transform("A") { it })
    }

    @Test
    fun `bridge is refused when instances do not match the resolved kotlinx types`() {
        val members = KotlinMossBridgeMembers.resolve(loader)!!
        val codec = KotlinMossBridgeMembers.JavaReplyCodec.resolve(FakeJavaReply::class.java)!!
        assertNull(members.bridgeFor(null, FakeSerializer(), codec))
        assertNull(members.bridgeFor(ProtoBuf(), null, codec))
        assertNull(members.bridgeFor(Any(), FakeSerializer(), codec))
        // 序列化器必须同时实现序列化与反序列化两个接口，缺一不可。
        assertNull(members.bridgeFor(ProtoBuf(), SerializerOnly(), codec))
    }

    @Test
    fun `java reply codec finds a toByteArray that is inherited from a superclass`() {
        val codec = KotlinMossBridgeMembers.JavaReplyCodec.resolve(FakeDerivedReply::class.java)
        assertNotNull(codec)
        val parsed = codec!!.parse("abc".toByteArray())
        assertEquals("abc", String(codec.serialize(parsed)))
    }

    @Test
    fun `java reply codec needs both a static parseFrom and an instance toByteArray`() {
        assertNotNull(KotlinMossBridgeMembers.JavaReplyCodec.resolve(FakeJavaReply::class.java))
        assertNull(KotlinMossBridgeMembers.JavaReplyCodec.resolve(NoParse::class.java))
        assertNull(KotlinMossBridgeMembers.JavaReplyCodec.resolve(String::class.java))
    }

    // ---- 真实类自检的分档 ------------------------------------------------------------------------

    @Test
    fun `self test is unavailable rather than failed when the host classes are absent`() {
        // 测试环境里没有完整的 DmViewReply / KDmViewReply：既无法验证也无法否定，必须放行（UNAVAILABLE），
        // 只有"跑起来了但结果不对"才允许拦住 Kotlin 通道。
        val members = KotlinMossBridgeMembers.resolve(loader)!!
        val report = KotlinMossBridgeSelfTest.run(loader, members)
        assertEquals(KotlinMossBridgeSelfTest.Outcome.UNAVAILABLE, report.outcome)
        // 其它测试夹具里可能有 Java 版 DmViewReply，所以缺的可能是 Java 版也可能是 Kotlin 版；总之是"缺东西"。
        assertTrue(report.detail, report.detail.startsWith("no-"))
        // 每进程只跑一次：第二次返回同一个报告对象。
        assertSame(report, KotlinMossBridgeSelfTest.run(loader, members))
    }

    // ---- Java moss → Kotlin moss 的名字映射 --------------------------------------------------------

    @Test
    fun `maps java moss class and rpc names to their kotlin counterparts`() {
        assertEquals(
            "com.bapis.bilibili.community.service.dm.v1.KDMMoss",
            KotlinMossBridgeMembers.kotlinMossClassName("com.bapis.bilibili.community.service.dm.v1.DMMoss")
        )
        assertEquals(
            "com.bapis.bilibili.app.viewunite.v1.KViewMoss",
            KotlinMossBridgeMembers.kotlinMossClassName("com.bapis.bilibili.app.viewunite.v1.ViewMoss")
        )
        assertEquals("KViewMoss", KotlinMossBridgeMembers.kotlinMossClassName("ViewMoss"))
        // 内部类 / 空名字不映射。
        assertNull(KotlinMossBridgeMembers.kotlinMossClassName("com.x.Outer\$Inner"))
        assertNull(KotlinMossBridgeMembers.kotlinMossClassName("com.x."))

        assertEquals("viewProgress", KotlinMossBridgeMembers.rpcName("executeViewProgress"))
        assertEquals("viewProgress", KotlinMossBridgeMembers.rpcName("viewProgress"))
        assertEquals("dmView", KotlinMossBridgeMembers.rpcName("executeDmView"))
        assertEquals("dmView", KotlinMossBridgeMembers.rpcName("dmView"))
        // 不是 "execute" 前缀 + 后缀的名字原样返回。
        assertEquals("execute", KotlinMossBridgeMembers.rpcName("execute"))
        assertEquals("executor", KotlinMossBridgeMembers.rpcName("executor"))
    }

    // ---- 回调形态泛型入口的定位 ------------------------------------------------------------------

    interface FakeHandler {
        fun onNext(reply: Any?)
    }

    /** 形状模仿 9.14.0 `KDynamicMoss`：类型化入口、suspend 泛型入口、回调泛型入口、其它接口。 */
    @Suppress("unused", "UNUSED_PARAMETER")
    private class FakeKotlinMoss {
        fun dynAll(request: String, continuation: kotlin.coroutines.Continuation<Any?>): Any? = null
        fun dynAll(request: String, handler: FakeHandler) = Unit
        fun dynAll(
            request: Any,
            serializer: SerializationStrategy<Any>,
            deserializer: DeserializationStrategy<Any>,
            protoBuf: ProtoBuf,
            continuation: kotlin.coroutines.Continuation<Any?>
        ): Any? = null
        fun dynAll(
            request: Any,
            serializer: SerializationStrategy<Any>,
            deserializer: DeserializationStrategy<Any>,
            handler: FakeHandler,
            protoBuf: ProtoBuf
        ) = Unit

        fun dynVideo(
            request: Any,
            serializer: SerializationStrategy<Any>,
            deserializer: DeserializationStrategy<Any>,
            handler: FakeHandler,
            protoBuf: ProtoBuf
        ) = Unit

        fun dynDetail(
            request: Any,
            serializer: SerializationStrategy<Any>,
            deserializer: DeserializationStrategy<Any>,
            handler: FakeHandler,
            protoBuf: ProtoBuf
        ) = Unit
    }

    /** 形状模仿 8.84.0–9.8.0 `KReplyMoss`：回调泛型入口少 ProtoBuf 参数，suspend 泛型入口返回 Object。 */
    @Suppress("unused", "UNUSED_PARAMETER")
    private class FakeLegacyKotlinMoss {
        fun mainList(request: String, continuation: kotlin.coroutines.Continuation<Any?>): Any? = null
        fun mainList(
            request: Any,
            serializer: SerializationStrategy<Any>,
            deserializer: DeserializationStrategy<Any>,
            continuation: kotlin.coroutines.Continuation<Any?>
        ): Any? = null
        fun mainList(
            request: Any,
            serializer: SerializationStrategy<Any>,
            deserializer: DeserializationStrategy<Any>,
            handler: FakeHandler
        ) = Unit
    }

    @Test
    fun `legacy four parameter callback entry is found separately from the current ABI`() {
        assertNull(KotlinMossBridgeMembers.callbackEntry(FakeLegacyKotlinMoss::class.java, "mainList"))
        val legacy = KotlinMossBridgeMembers.legacyCallbackEntry(FakeLegacyKotlinMoss::class.java, "mainList")
        assertNotNull(legacy)
        assertEquals(4, legacy!!.parameterCount)
        assertEquals(Void.TYPE, legacy.returnType)
        assertTrue(legacy.parameterTypes[3].isInterface)
        // 当前 5 参入口不会被旧版判定误选。
        assertNull(KotlinMossBridgeMembers.legacyCallbackEntry(FakeKotlinMoss::class.java, "dynVideo"))
    }

    @Test
    fun `selects only the callback shaped generic entry of the requested method`() {
        val all = KotlinMossBridgeMembers.callbackEntry(FakeKotlinMoss::class.java, "dynAll")
        assertNotNull(all)
        assertEquals(5, all!!.parameterCount)
        assertTrue(all.parameterTypes[3].isInterface)
        assertEquals(ProtoBuf::class.java, all.parameterTypes[4])

        val video = KotlinMossBridgeMembers.callbackEntry(FakeKotlinMoss::class.java, "dynVideo")
        assertNotNull(video)
        assertEquals("dynVideo", video!!.name)

        // 不同名的接口不会被误选；不存在的方法返回 null。
        assertNull(KotlinMossBridgeMembers.callbackEntry(FakeKotlinMoss::class.java, "dynMissing"))
        assertFalse(all.parameterTypes.last().name.contains("Continuation"))
    }
}
