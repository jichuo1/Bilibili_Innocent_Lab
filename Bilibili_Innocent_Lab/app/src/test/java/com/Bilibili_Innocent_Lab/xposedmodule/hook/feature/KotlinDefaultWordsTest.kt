package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KotlinDefaultWordsLocator
import com.bapis.bilibili.app.interfaces.v1.DefaultWordsReply
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.*
import org.junit.Test

class KotlinDefaultWordsTest {
    // 响应名字不含 KDefaultWordsReply；模拟 9140400 的已混淆响应，入口只使用实际传入的策略。
    class OpaqueWords(val wire: DefaultWordsReply)
    class Serializer : SerializationStrategy<Any>, DeserializationStrategy<Any>
    class WordsProtoBuf(private val fail: Boolean = false) : ProtoBuf() {
        override fun encodeToByteArray(serializer: SerializationStrategy<*>?, value: Any?): ByteArray {
            check(!fail) { "serialization failure" }
            return (value as OpaqueWords).wire.toByteArray()
        }
        override fun decodeFromByteArray(deserializer: DeserializationStrategy<*>?, bytes: ByteArray): Any =
            OpaqueWords(DefaultWordsReply.parseFrom(bytes))
    }
    interface Callback {
        fun onNext(reply: Any)
        fun onError(error: Throwable)
        fun onCompleted()
        fun onUpstreamAck(value: Long): Long
    }
    class KotlinSearch {
        @Suppress("UNUSED_PARAMETER")
        fun defaultWords(request: Any, encoder: SerializationStrategy<*>, decoder: DeserializationStrategy<*>,
            callback: Callback, protoBuf: ProtoBuf) = Unit
    }
    class RenamedSearch {
        @Suppress("UNUSED_PARAMETER")
        fun x(request: Any, encoder: SerializationStrategy<*>, decoder: DeserializationStrategy<*>,
            callback: Callback, protoBuf: ProtoBuf) = Unit
    }
    private class Loader(parent: ClassLoader, private val present: Boolean) : ClassLoader(parent) {
        var kotlinLookups = 0
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name == "com.bapis.bilibili.app.interfaces.v1.KSearchMoss") {
                kotlinLookups++
                if (!present) throw ClassNotFoundException(name)
                return KotlinSearch::class.java
            }
            return super.loadClass(name, resolve)
        }
    }
    private class Harness(present: Boolean = true, failId: String? = null) {
        val loader = Loader(KotlinDefaultWordsTest::class.java.classLoader!!, present)
        val registrar = PlayerPortTestRegistrar(failId)
        var status = ""
        val environment = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { _, value -> status = value })
        fun install(enabled: Boolean = true, points: VersionAdapter.HomeTopBarPoints? = null) =
            HomeTopBarFeatureInstaller(false, enabled, points).install(environment)
        fun callback(delegate: Callback, proto: ProtoBuf = WordsProtoBuf()): Callback {
            val strategy = Serializer()
            var result: Callback? = null
            registrar.invoke(ID, args = arrayOf(Any(), strategy, strategy, delegate, proto)) { args ->
                result = args[3] as Callback
                null
            }
            return requireNotNull(result)
        }
    }
    private class Sink : Callback {
        var value: Any? = null
        var error: Throwable? = null
        var completed = false
        override fun onNext(reply: Any) { value = reply }
        override fun onError(error: Throwable) { this.error = error }
        override fun onCompleted() { completed = true }
        override fun onUpstreamAck(value: Long) = value + 1
    }

    fun opaqueKotlinResponseClearsOnlyTextAndLeavesNativeHooksInstalled() {
        val harness = Harness().apply { install() }
        assertTrue(harness.registrar.hooks.containsKey("home.top_bar.search_default_words.false"))
        assertTrue(harness.registrar.hooks.containsKey("home.top_bar.search_default_words.true"))
        assertTrue(harness.registrar.hooks.containsKey(ID))
        val original = OpaqueWords(DefaultWordsReply("show", "word", "value", "bilibili://search?from=test"))
        val sink = Sink()
        harness.callback(sink).onNext(original)
        val updated = sink.value as OpaqueWords
        assertNotSame(original, updated)
        assertEquals("", updated.wire.getShow())
        assertEquals("", updated.wire.getWord())
        assertEquals("", updated.wire.getValue())
        assertEquals(original.wire.route, updated.wire.route)
        assertEquals("word", original.wire.getWord())
    }

    fun emptyTextDoesNotReplaceTheKotlinResponse() {
        val harness = Harness().apply { install() }
        val original = OpaqueWords(DefaultWordsReply(route = "bilibili://search"))
        val sink = Sink()
        harness.callback(sink).onNext(original)
        assertSame(original, sink.value)
    }

    fun codecAndBuilderFailuresDeliverTheOriginalResponse() {
        for ((reply, proto) in listOf(
            OpaqueWords(DefaultWordsReply("show", "word", "value")) to WordsProtoBuf(true),
            OpaqueWords(DefaultWordsReply("show", "word", "value", failAt = "word")) to WordsProtoBuf())) {
            val harness = Harness().apply { install() }
            val sink = Sink()
            harness.callback(sink, proto).onNext(reply)
            assertSame(reply, sink.value)
        }
    }

    fun errorsCompletionAndAcknowledgementsRemainTransparent() {
        val harness = Harness().apply { install() }
        val sink = Sink()
        val proxy = harness.callback(sink)
        val error = IllegalStateException("host error")
        proxy.onError(error)
        proxy.onCompleted()
        assertSame(error, sink.error)
        assertTrue(sink.completed)
        assertEquals(43L, proxy.onUpstreamAck(42L))
    }

    fun disabledFeatureDoesNotResolveKotlinOrInstallAnyHooks() {
        val harness = Harness()
        harness.install(enabled = false)
        assertEquals(0, harness.loader.kotlinLookups)
        assertTrue(harness.registrar.hooks.isEmpty())
    }

    fun missingKotlinClassKeepsTheJavaBoundaries() {
        val harness = Harness(present = false).apply { install() }
        assertFalse(harness.registrar.hooks.containsKey(ID))
        assertFalse(harness.status.contains("search-protocol-kotlin"))
        assertTrue(harness.registrar.hooks.containsKey("home.top_bar.search_default_words.false"))
        assertTrue(harness.registrar.hooks.containsKey("home.top_bar.search_default_words.true"))
    }

    fun failedKotlinRegistrationRemainsVisibleWithoutDiscardingJavaHooks() {
        val harness = Harness(failId = ID).apply { install() }
        assertFalse(harness.registrar.hooks.containsKey(ID))
        assertTrue(harness.status.contains("search-protocol-kotlin"))
        assertTrue(harness.registrar.hooks.containsKey("home.top_bar.search_default_words.false"))
        assertTrue(harness.registrar.hooks.containsKey("home.top_bar.search_default_words.true"))
    }

    fun cachedRenamedOwnerAndMethodInstallWithoutTheStableMossClass() {
        val method = RenamedSearch::class.java.declaredMethods.single { it.name == "x" }
        val points = VersionAdapter.HomeTopBarPoints(null, null, null, emptyList(), KotlinDefaultWordsLocator.point(method))
        val harness = Harness(present = false).apply { install(points = points) }
        assertEquals("x", harness.registrar.hooks.getValue(ID).member.name)
        val original = OpaqueWords(DefaultWordsReply("show", "word", "value", "bilibili://search"))
        val sink = Sink()
        harness.callback(sink).onNext(original)
        assertEquals("", (sink.value as OpaqueWords).wire.getWord())
        assertEquals(original.wire.route, (sink.value as OpaqueWords).wire.route)
    }

    private companion object { const val ID = "home.top_bar.search_default_words.kotlin" }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (KotlinDefaultWordsTest) -> Unit) {
            val s = KotlinDefaultWordsTest()
            block(s)
        }
        runIsolated { s -> s.opaqueKotlinResponseClearsOnlyTextAndLeavesNativeHooksInstalled() }
        runIsolated { s -> s.emptyTextDoesNotReplaceTheKotlinResponse() }
        runIsolated { s -> s.codecAndBuilderFailuresDeliverTheOriginalResponse() }
        runIsolated { s -> s.errorsCompletionAndAcknowledgementsRemainTransparent() }
        runIsolated { s -> s.disabledFeatureDoesNotResolveKotlinOrInstallAnyHooks() }
        runIsolated { s -> s.missingKotlinClassKeepsTheJavaBoundaries() }
        runIsolated { s -> s.failedKotlinRegistrationRemainsVisibleWithoutDiscardingJavaHooks() }
        runIsolated { s -> s.cachedRenamedOwnerAndMethodInstallWithoutTheStableMossClass() }
    }
}
