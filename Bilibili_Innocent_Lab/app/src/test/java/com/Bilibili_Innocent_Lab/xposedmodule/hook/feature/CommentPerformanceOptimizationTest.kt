package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import kmossfixture.MainListReply
import kmossfixture.ReplyInfo
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.*
import org.junit.Test

class CommentPerformanceOptimizationTest {
    interface Handler {
        fun onNext(reply: Any?)
        fun onError(error: Throwable)
        fun onCompleted()
        fun onNextForAck(reply: Any?): Long
    }
    private class K(val text: String)
    private class J(val text: String)
    private class Counts { var reads = 0; var writes = 0 }
    private val endpoint = Handler::class.java.getMethod("onNext", Any::class.java)
    private val proto = Any()
    private val serializer = Any()
    private fun key(token: Any = proto) = KotlinMossResponseHandlerProxy.Key(endpoint, J::class.java, token, serializer)
    private fun bridge(c: Counts, failRead: Boolean = false, failWrite: Boolean = false) = KotlinMossReplyBridge(
        encode = { c.reads++; if (failRead) error("read"); (it as K).text.toByteArray() },
        parseJava = { J(String(it)) },
        toBytes = { (it as J).text.toByteArray() },
        decode = { c.writes++; if (failWrite) error("write"); K(String(it).lowercase()) }
    )
    private class Sink : Handler {
        var result: Any? = null
        var completed = 0
        var error: Throwable? = null
        override fun onNext(reply: Any?) { result = reply }
        override fun onError(error: Throwable) { this.error = error }
        override fun onCompleted() { completed++ }
        override fun onNextForAck(reply: Any?) = 42L
    }
    private fun wrap(delegate: Handler, counts: Counts, key: KotlinMossResponseHandlerProxy.Key = key(),
        failed: (Throwable) -> Unit = {}, codec: KotlinMossReplyBridge = bridge(counts), transform: (Any) -> Any): Handler =
        KotlinMossResponseHandlerProxy.wrap(Handler::class.java, delegate, key, codec, failed, transform) as Handler

    fun unchangedLayersShareOneMirrorInOriginalWrappingOrder() {
        val counts = Counts(); val sink = Sink(); val order = mutableListOf<Int>(); val mirrors = mutableListOf<Any>()
        var handler: Handler = sink
        for (i in 1..3) handler = wrap(handler, counts) { order += i; mirrors += it; it }
        val original = K("original")
        handler.onNext(original)
        assertSame(original, sink.result)
        assertEquals(listOf(3, 2, 1), order)
        assertEquals(1, counts.reads); assertEquals(0, counts.writes)
        assertTrue(mirrors.all { it === mirrors.first() })
    }

    fun changedStageIsWrittenAndNormalizedBeforeTheNextStage() {
        val counts = Counts(); val sink = Sink(); var seen = ""
        val inner = wrap(sink, counts) { seen = (it as J).text; it }
        val outer = wrap(inner, counts) { J("NORMALIZED") }
        outer.onNext(K("initial"))
        assertEquals("normalized", seen)
        assertEquals("normalized", (sink.result as K).text)
        assertEquals(2, counts.reads); assertEquals(1, counts.writes)
    }

    fun aFailingTransformOrWriteDoesNotDisableTheOtherStages() {
        for (failWrite in listOf(false, true)) {
            val counts = Counts(); val sink = Sink(); var errors = 0
            val inner = wrap(sink, counts) { J((it as J).text + "|kept") }
            val outer = wrap(inner, counts, failed = { errors++ }, codec = bridge(counts, failWrite = failWrite)) {
                if (!failWrite) error("transform")
                J("bad")
            }
            outer.onNext(K("initial"))
            assertEquals("initial|kept", (sink.result as K).text)
            assertEquals(1, errors)
        }
    }

    fun failedReadCanBeRetriedByTheNextIndependentStage() {
        val counts = Counts(); val sink = Sink(); var errors = 0
        val inner = wrap(sink, counts) { J((it as J).text + "|ok") }
        val outer = wrap(inner, counts, failed = { errors++ }, codec = bridge(counts, failRead = true)) { it }
        outer.onNext(K("initial"))
        assertEquals("initial|ok", (sink.result as K).text)
        assertEquals(1, errors); assertEquals(2, counts.reads)
    }

    fun differentCodecIdentityAndDifferentCallbacksCannotShareMirrors() {
        val counts = Counts(); val sink = Sink()
        val inner = wrap(sink, counts) { it }
        val outer = wrap(inner, counts, key = key(Any())) { it }
        outer.onNext(K("one")); outer.onNext(K("two"))
        assertEquals(4, counts.reads)
        val shared = wrap(wrap(sink, counts) { it }, counts) { it }
        val before = counts.reads
        shared.onNext(K("three")); shared.onNext(K("four"))
        assertEquals(2, counts.reads - before)
    }

    fun anOpaqueProxyBetweenFeaturesKeepsItsOriginalPosition() {
        val counts = Counts(); val sink = Sink(); val order = mutableListOf<String>()
        val inner = wrap(sink, counts) { order += "inner"; it }
        val opaque = MossResponseHandlerProxy.wrapTransform(Handler::class.java, inner) { order += "opaque"; it } as Handler
        val outer = wrap(opaque, counts) { order += "outer"; it }
        outer.onNext(K("body"))
        assertEquals(listOf("outer", "opaque", "inner"), order)
        assertEquals(2, counts.reads)
    }

    fun otherCallbacksAndHostExceptionsRemainTransparent() {
        val counts = Counts(); val sink = Sink(); val handler = wrap(wrap(sink, counts) { it }, counts) { it }
        val failure = IllegalStateException("host")
        handler.onError(failure); handler.onCompleted(); handler.onNext(null)
        assertSame(failure, sink.error); assertEquals(1, sink.completed)
        assertEquals(42L, handler.onNextForAck(K("ack"))); assertEquals(0, counts.reads)
        val throwing = object : Handler by sink { override fun onNext(reply: Any?) { throw failure } }
        val proxy = wrap(throwing, counts) { it }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { proxy.onNext(K("body")) })
    }

    fun reentrantHostDeliveryStartsItsOwnMirrorAndKeepsTheInFlightPlan() {
        val counts = Counts(); var once = false; lateinit var handler: Handler
        val delivered = mutableListOf<String>()
        val sink = object : Handler by Sink() {
            override fun onNext(reply: Any?) {
                delivered += (reply as K).text
                if (!once) { once = true; handler.onNext(K("nested")) }
            }
        }
        handler = wrap(wrap(sink, counts) { it }, counts) { it }
        handler.onNext(K("outer"))
        assertEquals(listOf("outer", "nested"), delivered)
        assertEquals(2, counts.reads)
    }

    private class Serializer : SerializationStrategy<Any>, DeserializationStrategy<Any>

    fun actualChannelHooksShareTheSameResponseMirrorAndKeepRequestProcessing() {
        val loader = javaClass.classLoader!!
        val registrar = PlayerPortTestRegistrar()
        val environment = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { _, _ -> })
        val members = KotlinMossBridgeMembers.resolve(loader)!!
        val seen = mutableListOf<Any>(); val requestSeen = mutableListOf<Any>()
        for (i in 1..3) assertTrue(KotlinMossChannel.install(environment, loader, members,
            "kmossfixture.ReplyMoss", "mainList", KotlinMossReplyBridgeTest.FakeJavaReply::class.java,
            "channel.$i", "test", "test", javaRequestClass = KotlinMossReplyBridgeTest.FakeJavaReply::class.java,
            transformRequest = { requestSeen += it; it }, shareUnchangedReply = true
        ) { seen += it; it })
        val serializer = Serializer(); val original = "response"; var result: Any? = null
        val sink = object : kmossfixture.KReplyMoss.Handler { override fun onNext(reply: Any?) { result = reply } }
        val args = arrayOf<Any?>("request", serializer, serializer, sink, ProtoBuf())
        registrar.invoke("channel.1", args = args) { a ->
            registrar.invoke("channel.2", args = a) { b ->
                registrar.invoke("channel.3", args = b) { c ->
                    (c[3] as kmossfixture.KReplyMoss.Handler).onNext(original)
                    null
                }
            }
        }
        assertSame(original, result)
        assertEquals(3, seen.size); assertTrue(seen.all { it === seen.first() })
        assertEquals(3, requestSeen.size) // Request stages retain independent conversions and failure boundaries.
    }

    fun visitorMatchesPostorderForListsAndBothSingleSlotsWithoutMutation() {
        val root = ReplyInfo.of("root", ReplyInfo.of("child", ReplyInfo.of("grandchild")))
        val page = MainListReply(listOf(root), ReplyInfo.of("top"), ReplyInfo.of("detail"))
        val expected = mutableListOf<String>(); val actual = mutableListOf<String>()
        ProtobufReplyTreeRewriter(ReplyInfo::class.java, emptySet(), mapReply = { expected += (it as ReplyInfo).text; it }) {
            emptySet()
        }.rewrite(page)
        ProtobufReplyTreeVisitor(ReplyInfo::class.java) { actual += (it as ReplyInfo).text }.visit(page)
        assertEquals(expected, actual)
        assertEquals(listOf("grandchild", "child", "root", "top", "detail").sorted(), actual.sorted())
        assertEquals(1, page.repliesList.size); assertTrue(page.hasRoot()); assertTrue(page.hasUpTop())
    }

    fun visitorPreservesTheDepthBoundaryAndAlwaysReleasesRawScope() {
        val page = MainListReply(listOf(ReplyInfo.of("a", ReplyInfo.of("b", ReplyInfo.of("c", ReplyInfo.of("d"))))), null, null)
        val seen = mutableListOf<String>()
        ProtobufReplyTreeVisitor(ReplyInfo::class.java) { seen += (it as ReplyInfo).text }.visit(page)
        assertEquals(listOf("c", "b", "a"), seen)
        assertThrows(IllegalStateException::class.java) {
            ProtobufReplyTreeVisitor(ReplyInfo::class.java) { error("observe") }.visit(page)
        }
        assertFalse(KotlinMossChannel.isRaw())
    }

    private class ReadOnlyEnvelope(private val replies: List<ReplyInfo>, private val root: ReplyInfo) {
        fun getRepliesList(): List<ReplyInfo> = if (KotlinMossChannel.isRaw()) replies else emptyList()
        fun hasRoot() = KotlinMossChannel.isRaw()
        fun getRoot() = root
        fun getOtherList() = listOf("unrelated")
    }

    fun visitorReadsRawListsAndSinglesWithoutRequiringBuilders() {
        val seen = mutableListOf<String>()
        val envelope = ReadOnlyEnvelope(listOf(ReplyInfo.of("a")), ReplyInfo.of("root"))
        ProtobufReplyTreeVisitor(ReplyInfo::class.java) { seen += (it as ReplyInfo).text }.visit(envelope)
        assertEquals(listOf("a", "root").sorted(), seen.sorted())
        assertFalse(KotlinMossChannel.isRaw())
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (CommentPerformanceOptimizationTest) -> Unit) {
            val s = CommentPerformanceOptimizationTest()
            block(s)
        }
        runIsolated { s -> s.unchangedLayersShareOneMirrorInOriginalWrappingOrder() }
        runIsolated { s -> s.changedStageIsWrittenAndNormalizedBeforeTheNextStage() }
        runIsolated { s -> s.aFailingTransformOrWriteDoesNotDisableTheOtherStages() }
        runIsolated { s -> s.failedReadCanBeRetriedByTheNextIndependentStage() }
        runIsolated { s -> s.differentCodecIdentityAndDifferentCallbacksCannotShareMirrors() }
        runIsolated { s -> s.anOpaqueProxyBetweenFeaturesKeepsItsOriginalPosition() }
        runIsolated { s -> s.otherCallbacksAndHostExceptionsRemainTransparent() }
        runIsolated { s -> s.reentrantHostDeliveryStartsItsOwnMirrorAndKeepsTheInFlightPlan() }
        runIsolated { s -> s.actualChannelHooksShareTheSameResponseMirrorAndKeepRequestProcessing() }
        runIsolated { s -> s.visitorMatchesPostorderForListsAndBothSingleSlotsWithoutMutation() }
        runIsolated { s -> s.visitorPreservesTheDepthBoundaryAndAlwaysReleasesRawScope() }
        runIsolated { s -> s.visitorReadsRawListsAndSinglesWithoutRequiringBuilders() }
    }
}
