package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KotlinMossResponseHandlerProxy.ConfirmedReply
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.KmpMossBoundary
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.invoke
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import kmpfixture.modern.Descriptor
import kmpfixture.modern.GrpcEngine
import kmpfixture.modern.Handler
import kmpfixture.modern.KMossException
import kmpfixture.modern.Service
import kmossfixture.KReplyMoss
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass

class KmpGrpcWireChannelTest {

    data class WireJavaMessage(val body: String) {
        fun toByteArray(): ByteArray = body.toByteArray(Charsets.UTF_8)

        companion object {
            @JvmStatic
            fun parseFrom(bytes: ByteArray): WireJavaMessage = WireJavaMessage(String(bytes, Charsets.UTF_8))
        }
    }

    data class CorruptReadbackMessage(val body: String) {
        fun toByteArray(): ByteArray = "corrupted".toByteArray(Charsets.UTF_8)

        companion object {
            @JvmStatic
            fun parseFrom(bytes: ByteArray): CorruptReadbackMessage = CorruptReadbackMessage(String(bytes, Charsets.UTF_8))
        }
    }

    interface WireHandlerContract {
        fun onNext(bytes: ByteArray)
    }

    class DriftedWireEngine {
        @Suppress("UNUSED_PARAMETER")
        fun dispatch(service: Any, meta: WireDescriptor, payload: ByteArray, handler: WireHandlerContract) = Unit
    }

    class DuplicateWireEngine {
        @Suppress("UNUSED_PARAMETER")
        fun first(service: Any, meta: WireDescriptor, payload: ByteArray, handler: WireHandlerContract) = Unit

        @Suppress("UNUSED_PARAMETER")
        fun second(service: Any, meta: WireDescriptor, payload: ByteArray, handler: WireHandlerContract) = Unit
    }

    class WireDescriptor(
        val kotlinClass: KClass<*>,
        val serializer: SerializationStrategy<*>
    )

    private object DummyStrategy : SerializationStrategy<Any>
    private object DummyDeserStrategy : DeserializationStrategy<String>
    private class DummyCodecStrategy : SerializationStrategy<Any>, DeserializationStrategy<Any>

    private class CountingCodecJavaMessage(val text: String) {
        fun toByteArray(): ByteArray {
            serializes++
            return text.toByteArray(Charsets.UTF_8)
        }

        override fun equals(other: Any?): Boolean = other is CountingCodecJavaMessage && other.text == text
        override fun hashCode(): Int = text.hashCode()

        companion object {
            var parses = 0
            var serializes = 0
            fun reset() { parses = 0; serializes = 0 }
            @JvmStatic
            fun parseFrom(bytes: ByteArray): CountingCodecJavaMessage {
                parses++
                return CountingCodecJavaMessage(String(bytes, Charsets.UTF_8))
            }
        }
    }

    data class CodecEchoReply(val body: String) {
        fun toByteArray(): ByteArray = body.toByteArray(Charsets.UTF_8)
        companion object {
            var parseCalls = 0
            @JvmStatic
            fun parseFrom(bytes: ByteArray): CodecEchoReply {
                parseCalls++
                return CodecEchoReply(String(bytes, Charsets.UTF_8))
            }
        }
    }

    class CodecReq(val id: Long, val tag: String = "orig") {
        fun toByteArray(): ByteArray = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { s -> s.writeLong(id); s.writeUTF(tag) }
        }.toByteArray()
        companion object {
            @JvmStatic
            fun parseFrom(bytes: ByteArray): CodecReq =
                DataInputStream(ByteArrayInputStream(bytes)).use { CodecReq(it.readLong(), it.readUTF()) }
        }
    }

    class CodecReply(val marker: String) {
        fun toByteArray(): ByteArray = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { s -> s.writeUTF(marker) }
        }.toByteArray()
        companion object {
            @JvmStatic
            fun parseFrom(bytes: ByteArray): CodecReply =
                DataInputStream(ByteArrayInputStream(bytes)).use { CodecReply(it.readUTF()) }
        }
    }

    private class FakeKotlinMossEndpoint {
        @Suppress("UNUSED_PARAMETER")
        fun mainList(
            request: Any,
            serializer: SerializationStrategy<Any>,
            decoder: DeserializationStrategy<Any>,
            handler: KReplyMoss.Handler,
            protoBuf: ProtoBuf
        ) = Unit
    }

    private class DeserDescriptor(val k: KClass<*>, val d: DeserializationStrategy<*>) {
        fun getPackageName(): String = "bilibili.app.interface.v1"
        fun getServiceName(): String = "Search"
        fun getMethodName(): String = "DefaultWords"
        override fun toString(): String = "obf.D@abc1"
    }

    private class DeserEngine {
        @Suppress("UNUSED_PARAMETER")
        fun dispatch(service: Any, descriptor: DeserDescriptor, req: ByteArray, handler: Handler) = Unit
    }

    private class LeadingDotPkg {
        private val a = ".bilibili.v1"
        private val b = "Svc"
        private val c = "Mtd"
        private val k: KClass<*> = String::class
        private val s: DeserializationStrategy<*> = DummyDeserStrategy
        override fun toString(): String = "obf.L@12"
    }

    private class WhitespacePkg {
        private val a = "bilibili.v1 bad"
        private val b = "Svc"
        private val c = "Mtd"
        private val k: KClass<*> = String::class
        private val s: DeserializationStrategy<*> = DummyDeserStrategy
        override fun toString(): String = "obf.W@13"
    }

    private class SlashInSvc {
        private val a = "bilibili.v1"
        private val b = "Bad/Svc"
        private val c = "Mtd"
        private val k: KClass<*> = String::class
        private val s: DeserializationStrategy<*> = DummyDeserStrategy
        override fun toString(): String = "obf.S@14"
    }

    private class StrippedDescriptor(pkg: String, svc: String, mtd: String) :
        Descriptor(pkg, svc, mtd, Any::class, DummyStrategy) {
        override fun toString(): String = "kmp.Descriptor@${Integer.toHexString(System.identityHashCode(this))}"
    }

    private class GetterOnlyStrippedDescriptor(
        private val pkg: String,
        private val svc: String,
        private val mtd: String
    ) {
        @Suppress("unused") internal val reqClass = Any::class
        @Suppress("unused") internal val reqSerialization: SerializationStrategy<*> = DummyStrategy
        fun getPackageName(): String = pkg
        fun getServiceName(): String = svc
        fun getMethodName(): String = mtd
        override fun toString(): String = "ObfDesc@${Integer.toHexString(System.identityHashCode(this))}"
    }

    private class OtherToStringDescriptor(
        val packageName: String,
        val serviceName: String,
        val methodName: String
    ) {
        override fun toString(): String = "SomethingElse()"
    }

    private fun env(
        loader: ClassLoader = requireNotNull(javaClass.classLoader),
        registrar: HookRegistrar = PlayerPortTestRegistrar(),
        errors: MutableList<String> = mutableListOf()
    ) = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = loader,
        hookPoints = HookPointRegistry(loader),
        registrar = registrar,
        logInfo = { _, _ -> },
        logError = { key, _ -> errors += key },
        reportStatus = { _, _ -> }
    )

    @Test
    fun wireAdapterDescriptorResolverAndFullMethodName() {
        val noInterceptorLoader = TestRenamingClassLoader(
            parent = requireNotNull(javaClass.classLoader),
            hiddenPrefixes = setOf("kntr.base.moss.")
        )
        val directModern = KmpGrpcWireAdapter.inspectEntry(
            GrpcEngine::class.java.declaredMethods.single { it.name == "a" }
        )!!
        assertEquals(1, directModern.descriptorIndex)
        assertEquals(2, directModern.requestBytesIndex)
        assertEquals(3, directModern.handlerIndex)

        val driftedMethod = DriftedWireEngine::class.java.getDeclaredMethod(
            "dispatch", Any::class.java, WireDescriptor::class.java, ByteArray::class.java, WireHandlerContract::class.java
        )
        val cachedPoint = KmpGrpcWireAdapter.point(driftedMethod)
        val resolvedFromCache = KmpGrpcWireAdapter.resolve(noInterceptorLoader, cachedPoint)
        assertNotNull(resolvedFromCache)
        assertEquals(driftedMethod, resolvedFromCache!!.engineEntry)
        assertNull(
            KmpGrpcWireAdapter.resolve(
                noInterceptorLoader,
                VersionAdapter.HookPoint(DriftedWireEngine::class.java.name, "dispatch", listOf("int"))
            )
        )

        val dupA = DuplicateWireEngine::class.java.getDeclaredMethod(
            "first", Any::class.java, WireDescriptor::class.java, ByteArray::class.java, WireHandlerContract::class.java
        )
        val dupB = DuplicateWireEngine::class.java.getDeclaredMethod(
            "second", Any::class.java, WireDescriptor::class.java, ByteArray::class.java, WireHandlerContract::class.java
        )
        val singleSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(driftedMethod)) },
            listOf("/base.apk"), noInterceptorLoader, setOf(DexAssistQuery.KMP_GRPC_WIRE_ENGINE)
        )
        val ambigSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(dupA, dupB)) },
            listOf("/base.apk"), noInterceptorLoader, setOf(DexAssistQuery.KMP_GRPC_WIRE_ENGINE)
        )
        assertEquals(cachedPoint, KmpGrpcWireAdapter.locate(noInterceptorLoader, singleSession, true, null, true))
        assertNull(KmpGrpcWireAdapter.locate(noInterceptorLoader, ambigSession, true, null, true))

        // DeserializationStrategy descriptor resolution & invalid shapes.
        assertNotNull(KmpGrpcWireAdapter.inspectEntry(DeserEngine::class.java.declaredMethods.single { it.name == "dispatch" }))
        assertTrue(KmpMethodDescriptorResolver.isDescriptorType(DeserDescriptor::class.java))
        val desc = DeserDescriptor(String::class, DummyDeserStrategy)
        assertEquals("bilibili.app.interface.v1.Search/DefaultWords", KmpMethodDescriptorResolver.resolve(desc))
        assertEquals("bilibili.app.interface.v1.Search/DefaultWords", KmpMethodDescriptorResolver.resolve(desc))
        KmpMethodDescriptorResolver.clearCaches()
        assertEquals("bilibili.app.interface.v1.Search/DefaultWords", KmpMethodDescriptorResolver.resolve(desc))
        assertNull(KmpMethodDescriptorResolver.resolve(LeadingDotPkg()))
        assertNull(KmpMethodDescriptorResolver.resolve(WhitespacePkg()))
        assertNull(KmpMethodDescriptorResolver.resolve(SlashInSvc()))
        assertNull(KmpGrpcWireChannel.parseDescriptorName(OtherToStringDescriptor("a.b", "Svc", "Call")))
        assertEquals("a.b.Svc/Call", KmpGrpcWireChannel.parseDescriptorName(GetterOnlyStrippedDescriptor("a.b", "Svc", "Call")))

        val moss = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss"
        assertEquals("bilibili.main.community.reply.v1.Reply/MainList", KmpGrpcWireChannel.fullMethodName(moss, "mainList"))
        assertEquals("bilibili.main.community.reply.v1.Reply/DetailList", KmpGrpcWireChannel.fullMethodName(moss, "detailList"))
        assertEquals("bilibili.main.community.reply.v1.Reply/DialogList", KmpGrpcWireChannel.fullMethodName(moss, "dialogList"))
        assertEquals("bilibili.main.community.reply.v1.Reply/FoldList", KmpGrpcWireChannel.fullMethodName(moss, "foldList"))
        assertEquals("bilibili.main.community.reply.v1.Reply/HotspotPage", KmpGrpcWireChannel.fullMethodName(moss, "hotspotPage"))
        assertEquals("bilibili.main.community.reply.v1.Reply/ReplyInfo", KmpGrpcWireChannel.fullMethodName(moss, "replyInfo"))
        assertEquals("bilibili.main.community.reply.v1.Reply/MainList", KmpGrpcWireChannel.fullMethodName(moss, "executeMainList"))
        assertNull(KmpGrpcWireChannel.fullMethodName("BadName", "mainList"))
        assertEquals(
            "bilibili.app.interface.v1.Search/DefaultWords",
            KmpGrpcWireChannel.fullMethodName("com.bapis.bilibili.app.interfaces.v1.SearchMoss", "defaultWords")
        )
        assertEquals(
            "bilibili.app.interface.v1.Search/DefaultWords",
            KmpGrpcWireChannel.fullMethodName("com.bapis.bilibili.app.interfaces.v1.SearchMoss", "executeDefaultWords")
        )
    }

    @Test
    fun wireChannelSingleParseConfirmedReadbackRequestTransformAndMossObservation() {
        val surface = KmpGrpcWireAdapter.inspectEntry(GrpcEngine::class.java.declaredMethods.single { it.name == "a" })!!

        // Single parse across steps, no redundant encodeToByteArray, original bytes preserved for readOnly.
        run {
            CountingCodecJavaMessage.reset()
            val registrar = PlayerPortTestRegistrar()
            val errors = mutableListOf<String>()
            val environment = env(registrar = registrar, errors = errors)
            val seenFilter = mutableListOf<CountingCodecJavaMessage>()
            val seenTopology = mutableListOf<CountingCodecJavaMessage>()
            assertTrue(
                KmpGrpcWireChannel.install(
                    environment, surface, "com.bapis.bilibili.main.community.reply.v1.ReplyMoss", "mainList",
                    CountingCodecJavaMessage::class.java, "filter", "filter"
                ) { msg -> seenFilter += msg as CountingCodecJavaMessage; msg }
            )
            assertTrue(
                KmpGrpcWireChannel.install(
                    environment, surface, "com.bapis.bilibili.main.community.reply.v1.ReplyMoss", "mainList",
                    CountingCodecJavaMessage::class.java, "topology", "topology"
                ) { msg -> seenTopology += msg as CountingCodecJavaMessage; msg }
            )
            assertEquals(1, registrar.hooks.size)
            assertEquals(1, registrar.registrationAttempts.size)

            val descriptor = Descriptor("bilibili.main.community.reply.v1", "Reply", "MainList", WireDescriptor::class, DummyStrategy)
            val reqBytes = "req".toByteArray()
            val delivered = mutableListOf<ByteArray>()
            val sink = object : Handler {
                override fun onNext(bytes: ByteArray?) { if (bytes != null) delivered += bytes }
                override fun onCompleted() = Unit
                override fun onError(error: KMossException) = Unit
            }
            val engine = GrpcEngine(emptyList())
            val originalReplyBytes = "hello-nullable-map".toByteArray()
            registrar.invoke("kmp.grpc.wire.channel", receiver = engine, args = arrayOf(Service("grpc.biliapi.net"), descriptor, reqBytes, sink, null)) { callArgs ->
                (callArgs[3] as Handler).onNext(originalReplyBytes)
                null
            }
            assertTrue(errors.isEmpty())
            assertEquals(1, CountingCodecJavaMessage.parses)
            assertEquals(0, CountingCodecJavaMessage.serializes)
            assertEquals(1, seenFilter.size)
            assertSame(seenFilter.single(), seenTopology.single())
            assertSame(originalReplyBytes, delivered.single())

            val otherDescriptor = Descriptor("bilibili.app.view.v1", "View", "View", WireDescriptor::class, DummyStrategy)
            registrar.invoke("kmp.grpc.wire.channel", receiver = engine, args = arrayOf(Service("grpc.biliapi.net"), otherDescriptor, reqBytes, sink, null)) { callArgs ->
                (callArgs[3] as Handler).onNext("ignored".toByteArray())
                null
            }
            assertEquals(1, CountingCodecJavaMessage.parses)
        }

        // Confirmed readback, delayed receipts, and isolated step/host errors.
        run {
            val registrar = PlayerPortTestRegistrar()
            val errors = mutableListOf<String>()
            val environment = env(registrar = registrar, errors = errors)
            val events = mutableListOf<String>()
            assertTrue(
                KmpGrpcWireChannel.install(
                    environment, surface, "com.bapis.bilibili.main.community.reply.v1.ReplyMoss", "mainList",
                    WireJavaMessage::class.java, "purify", "purify",
                    javaRequestClass = WireJavaMessage::class.java,
                    transformRequest = { req -> WireJavaMessage((req as WireJavaMessage).body + "|no_underline") },
                    confirmedTransform = { msg -> ConfirmedReply(WireJavaMessage((msg as WireJavaMessage).body + "|purified")) { events += "purify_applied" } }
                ) { it }
            )
            assertTrue(
                KmpGrpcWireChannel.install(
                    environment, surface, "com.bapis.bilibili.main.community.reply.v1.ReplyMoss", "mainList",
                    CorruptReadbackMessage::class.java, "broken", "broken",
                    confirmedTransform = { ConfirmedReply(CorruptReadbackMessage("expected")) { events += "broken_must_not_run" } }
                ) { it }
            )
            assertTrue(
                KmpGrpcWireChannel.install(
                    environment, surface, "com.bapis.bilibili.main.community.reply.v1.ReplyMoss", "mainList",
                    WireJavaMessage::class.java, "filter", "filter",
                    confirmedTransform = { msg -> ConfirmedReply(WireJavaMessage((msg as WireJavaMessage).body + "|filtered")) { events += "filter_applied" } }
                ) { it }
            )
            val descriptor = Descriptor("bilibili.main.community.reply.v1", "Reply", "MainList", WireDescriptor::class, DummyStrategy)
            val engine = GrpcEngine(emptyList())
            var sentReq: ByteArray? = null
            var deliveredReply: ByteArray? = null
            val sink = object : Handler {
                override fun onNext(bytes: ByteArray?) { assertTrue(events.isEmpty()); deliveredReply = bytes }
                override fun onCompleted() = Unit
                override fun onError(error: KMossException) = Unit
            }
            registrar.invoke("kmp.grpc.wire.channel", receiver = engine, args = arrayOf(Service("grpc.biliapi.net"), descriptor, "req0".toByteArray(), sink, null)) { callArgs ->
                sentReq = callArgs[2] as ByteArray
                (callArgs[3] as Handler).onNext("raw".toByteArray())
                null
            }
            assertEquals("req0|no_underline", String(sentReq!!, Charsets.UTF_8))
            assertEquals("raw|purified|filtered", String(deliveredReply!!, Charsets.UTF_8))
            assertEquals(listOf("purify_applied", "filter_applied"), events)
            assertTrue("broken_wire_failed" in errors)

            events.clear()
            val hostFailure = IllegalStateException("host_boom")
            val throwingSink = object : Handler {
                override fun onNext(bytes: ByteArray?) { throw hostFailure }
                override fun onCompleted() = Unit
                override fun onError(error: KMossException) = Unit
            }
            val thrown = assertThrows(IllegalStateException::class.java) {
                registrar.invoke("kmp.grpc.wire.channel", receiver = engine, args = arrayOf(Service("grpc.biliapi.net"), descriptor, "req0".toByteArray(), throwingSink, null)) { callArgs ->
                    (callArgs[3] as Handler).onNext("raw".toByteArray())
                    null
                }
            }
            assertSame(hostFailure, thrown)
            assertTrue(events.isEmpty())
        }

        // Composes with KmpMossBoundary observing proxy.
        run {
            val registrar = PlayerPortTestRegistrar()
            val wireSeen = mutableListOf<String>()
            val boundarySeen = mutableListOf<String>()
            assertTrue(
                KmpGrpcWireChannel.install(
                    env(registrar = registrar), surface, "com.bapis.bilibili.main.community.reply.v1.ReplyMoss", "mainList",
                    WireJavaMessage::class.java, "topology", "topology"
                ) { msg -> wireSeen += (msg as WireJavaMessage).body; msg }
            )
            val descriptor = Descriptor("bilibili.main.community.reply.v1", "Reply", "MainList", WireDescriptor::class, DummyStrategy)
            val rawSink = object : Handler {
                override fun onNext(bytes: ByteArray?) = Unit
                override fun onCompleted() = Unit
                override fun onError(error: KMossException) = Unit
            }
            val observed = KmpMossBoundary.observing(Handler::class.java, rawSink) { bytes -> boundarySeen += String(bytes, Charsets.UTF_8) }
            registrar.invoke("kmp.grpc.wire.channel", receiver = GrpcEngine(emptyList()), args = arrayOf(Service("grpc.biliapi.net"), descriptor, ByteArray(0), observed, null)) { callArgs ->
                (callArgs[3] as Handler).onNext("reply-payload".toByteArray())
                null
            }
            assertEquals(listOf("reply-payload"), wireSeen)
            assertEquals(listOf("reply-payload"), boundarySeen)
        }

        // transformWithRequest + stripped descriptor.
        run {
            val registrar = PlayerPortTestRegistrar()
            assertTrue(
                KmpGrpcWireChannel.install(
                    environment = env(registrar = registrar),
                    surface = surface,
                    javaMossClassName = "com.bapis.a.b.SvcMoss",
                    rpc = "call",
                    javaReplyClass = CodecReply::class.java,
                    what = "wire-req",
                    logKey = "wave2_wire",
                    javaRequestClass = CodecReq::class.java,
                    transformRequest = { req -> CodecReq((req as CodecReq).id, "${req.tag}+rewritten") },
                    transformWithRequest = { req, reply ->
                        val r = req as? CodecReq
                        val p = reply as CodecReply
                        if (r == null) p else CodecReply("${p.marker}|id=${r.id}|tag=${r.tag}")
                    },
                    transformJava = { it }
                )
            )
            var sentReq = ByteArray(0)
            var received: ByteArray? = null
            val handler = object : Handler {
                override fun onNext(bytes: ByteArray?) { received = bytes }
                override fun onCompleted() = Unit
                override fun onError(error: KMossException) = Unit
            }
            val args = arrayOf<Any?>(Service("host"), StrippedDescriptor("a.b", "Svc", "Call"), CodecReq(42L, "req").toByteArray(), handler, null)
            registrar.invoke("kmp.grpc.wire.channel", GrpcEngine(emptyList()), args) { passed ->
                sentReq = passed[2] as ByteArray
                (passed[3] as Handler).onNext(CodecReply("raw").toByteArray())
                null
            }
            val decodedReq = CodecReq.parseFrom(sentReq)
            assertEquals(42L, decodedReq.id)
            assertEquals("req+rewritten", decodedReq.tag)
            assertEquals("raw|id=42|tag=req+rewritten", CodecReply.parseFrom(requireNotNull(received)).marker)
        }
    }

    @Test
    fun deferredBindingRawByteStepsAndSupersededByGuard() {
        val surface = KmpGrpcWireAdapter.inspectEntry(GrpcEngine::class.java.declaredMethods.single { it.name == "a" })!!

        // Deferred binding deduplicates by logKey and reuses mirror without redundant parseFrom.
        run {
            val registrar = PlayerPortTestRegistrar()
            val scoped = object : HookRegistrar by registrar {}
            val environment = env(registrar = scoped)
            val wireActive = AtomicBoolean(false)
            CountingCodecJavaMessage.reset()

            assertFalse(
                KmpGrpcWireChannel.installOrDefer(
                    environment = environment, surface = null,
                    javaMossClassName = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss", rpc = "mainList",
                    javaReplyClass = CountingCodecJavaMessage::class.java, what = "filter-1", logKey = "k_filter", wireActive = wireActive
                ) { msg -> CountingCodecJavaMessage("${(msg as CountingCodecJavaMessage).text}|step1") }
            )
            assertFalse(
                KmpGrpcWireChannel.installOrDefer(
                    environment = environment, surface = null,
                    javaMossClassName = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss", rpc = "mainList",
                    javaReplyClass = CountingCodecJavaMessage::class.java, what = "filter-1-dup", logKey = "k_filter", wireActive = wireActive
                ) { error("duplicate must be ignored") }
            )
            assertFalse(
                KmpGrpcWireChannel.installOrDefer(
                    environment = environment, surface = null,
                    javaMossClassName = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss", rpc = "mainList",
                    javaReplyClass = CountingCodecJavaMessage::class.java, what = "filter-2", logKey = "k_topology"
                ) { msg -> CountingCodecJavaMessage("${(msg as CountingCodecJavaMessage).text}|step2") }
            )
            assertFalse(wireActive.get())
            assertTrue(registrar.hooks.isEmpty())

            assertEquals(2, KmpGrpcWireChannel.bindDeferred(env(registrar = registrar), surface))
            assertEquals(0, KmpGrpcWireChannel.bindDeferred(env(registrar = registrar), surface))
            assertTrue(wireActive.get())
            assertEquals(1, registrar.hooks.size)

            val descriptor = Descriptor("bilibili.main.community.reply.v1", "Reply", "MainList", WireDescriptor::class, DummyStrategy)
            var delivered: ByteArray? = null
            val sink = object : Handler {
                override fun onNext(bytes: ByteArray?) { delivered = bytes }
                override fun onCompleted() = Unit
                override fun onError(error: KMossException) = Unit
            }
            registrar.invoke("kmp.grpc.wire.channel", receiver = GrpcEngine(emptyList()), args = arrayOf(Service("grpc.biliapi.net"), descriptor, "req".toByteArray(), sink, null)) { callArgs ->
                (callArgs[3] as Handler).onNext("orig".toByteArray())
                null
            }
            assertEquals("orig|step1|step2", String(requireNotNull(delivered), Charsets.UTF_8))
            assertEquals(1, CountingCodecJavaMessage.parses)
            assertEquals(2, CountingCodecJavaMessage.serializes)
        }

        // Raw-byte step clears stale mirror and deferred bind activates supersededBy for KotlinMoss.
        run {
            val loader = requireNotNull(javaClass.classLoader)
            val registrar = PlayerPortTestRegistrar()
            val environment = env(registrar = registrar)
            val wireActive = AtomicBoolean(false)
            CodecEchoReply.parseCalls = 0

            val members = requireNotNull(KotlinMossBridgeMembers.resolve(loader))
            val entry = requireNotNull(KotlinMossBridgeMembers.callbackEntry(FakeKotlinMossEndpoint::class.java, "mainList"))
            assertTrue(
                KotlinMossChannel.install(
                    environment = environment, loader = loader, members = members,
                    javaMossClassName = "kmossfixture.ReplyMoss", rpc = "mainList",
                    javaReplyClass = CodecEchoReply::class.java, hookId = "phase6.kmoss.mainList",
                    what = "phase6", logKey = "phase6", resolvedEntry = entry, supersededBy = wireActive
                ) { it }
            )

            val sink = object : KReplyMoss.Handler { override fun onNext(reply: Any?) = Unit }
            val strategy = DummyCodecStrategy()
            val wrappedSink = registrar.invoke("phase6.kmoss.mainList", FakeKotlinMossEndpoint(), arrayOf<Any?>("req", strategy, strategy, sink, ProtoBuf())) { callArgs -> callArgs[3] }
            assertNotSame(sink, wrappedSink)

            assertFalse(
                KmpGrpcWireChannel.installOrDefer(
                    environment = environment, surface = null,
                    javaMossClassName = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss", rpc = "mainList",
                    javaReplyClass = CodecEchoReply::class.java, what = "step1-java", logKey = "p6_java1", wireActive = wireActive
                ) { msg -> CodecEchoReply((msg as CodecEchoReply).body + "|j1") }
            )
            KmpGrpcWireChannel.deferRawBytes(
                environment = environment, rpcFullName = "bilibili.main.community.reply.v1.Reply/MainList",
                what = "step2-raw", logKey = "p6_raw",
                transformRawRequest = { bytes -> (String(bytes, Charsets.UTF_8) + "|rawReq").toByteArray(Charsets.UTF_8) }
            ) { bytes -> (String(bytes, Charsets.UTF_8) + "|rawResp").toByteArray(Charsets.UTF_8) }
            assertFalse(
                KmpGrpcWireChannel.installOrDefer(
                    environment = environment, surface = null,
                    javaMossClassName = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss", rpc = "mainList",
                    javaReplyClass = CodecEchoReply::class.java, what = "step3-java", logKey = "p6_java2"
                ) { msg -> CodecEchoReply((msg as CodecEchoReply).body + "|j2") }
            )

            assertEquals(3, KmpGrpcWireChannel.bindDeferred(environment, surface))
            assertTrue(wireActive.get())
            assertSame(sink, registrar.invoke("phase6.kmoss.mainList", FakeKotlinMossEndpoint(), arrayOf<Any?>("req", strategy, strategy, sink, ProtoBuf())) { callArgs -> callArgs[3] })

            val descriptor = Descriptor("bilibili.main.community.reply.v1", "Reply", "MainList", String::class, strategy)
            var sentRequest: ByteArray? = null
            var receivedReply: ByteArray? = null
            val wireSink = object : Handler {
                override fun onNext(bytes: ByteArray?) { receivedReply = bytes }
                override fun onCompleted() = Unit
                override fun onError(error: KMossException) = Unit
            }
            registrar.invoke("kmp.grpc.wire.channel", GrpcEngine(emptyList()), arrayOf(Service("grpc.biliapi.net"), descriptor, "req0".toByteArray(), wireSink, null)) { callArgs ->
                sentRequest = callArgs[2] as ByteArray
                (callArgs[3] as Handler).onNext("in".toByteArray())
                null
            }
            assertEquals("req0|rawReq", String(requireNotNull(sentRequest), Charsets.UTF_8))
            assertEquals("in|j1|rawResp|j2", String(requireNotNull(receivedReply), Charsets.UTF_8))
            assertEquals(2, CodecEchoReply.parseCalls)
        }
    }

    private fun registerAndDropForGc(
        surface: KmpGrpcWireAdapter.WireSurface,
        queue: ReferenceQueue<Any>
    ): List<WeakReference<Any>> {
        val pendingRegistrar = PlayerPortTestRegistrar()
        val pendingScoped = FeatureScopedRegistrar(pendingRegistrar, "comment_purify")
        val pendingLoader = TestRenamingClassLoader(parent = requireNotNull(javaClass.classLoader))
        KmpGrpcWireChannel.installOrDefer(
            environment = env(loader = pendingLoader, registrar = pendingScoped),
            surface = null,
            javaMossClassName = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss",
            rpc = "mainList",
            javaReplyClass = WireJavaMessage::class.java,
            what = "pending-gc",
            logKey = "k_pending_gc"
        ) { it }

        val activeRegistrar = PlayerPortTestRegistrar()
        val activeScoped = FeatureScopedRegistrar(activeRegistrar, "comment_filter")
        val activeLoader = TestRenamingClassLoader(parent = requireNotNull(javaClass.classLoader))
        KmpGrpcWireChannel.install(
            environment = env(loader = activeLoader, registrar = activeScoped),
            surface = surface,
            javaMossClassName = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss",
            rpc = "mainList",
            javaReplyClass = WireJavaMessage::class.java,
            what = "active-gc",
            logKey = "k_active_gc"
        ) { it }

        return listOf(
            WeakReference<Any>(pendingRegistrar, queue),
            WeakReference<Any>(pendingScoped, queue),
            WeakReference<Any>(pendingLoader, queue),
            WeakReference<Any>(activeRegistrar, queue),
            WeakReference<Any>(activeScoped, queue),
            WeakReference<Any>(activeLoader, queue)
        )
    }

    @Test
    fun rootRegistrarIsolationWeakReferenceReleaseAndUnsubscribedSkip() {
        val surface = KmpGrpcWireAdapter.inspectEntry(GrpcEngine::class.java.declaredMethods.single { it.name == "a" })!!

        // rootRegistrar unwraps FeatureScopedRegistrar, shares dispatcher, and stamps wire faultOwner.
        run {
            val registrar = PlayerPortTestRegistrar()
            val scopedPurify = FeatureScopedRegistrar(registrar, "comment_purify")
            val wrappedPurify = object : HookRegistrar by scopedPurify {}
            val scopedFilter = FeatureScopedRegistrar(registrar, "comment_filter")
            val wireActivePurify = AtomicBoolean(false)
            val wireActiveFilter = AtomicBoolean(false)

            assertFalse(
                KmpGrpcWireChannel.installOrDefer(
                    environment = env(registrar = wrappedPurify), surface = null,
                    javaMossClassName = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss", rpc = "mainList",
                    javaReplyClass = WireJavaMessage::class.java, what = "purify", logKey = "k_purify", wireActive = wireActivePurify
                ) { it }
            )
            assertTrue(
                KmpGrpcWireChannel.install(
                    environment = env(registrar = scopedFilter), surface = surface,
                    javaMossClassName = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss", rpc = "mainList",
                    javaReplyClass = WireJavaMessage::class.java, what = "filter", logKey = "k_filter", wireActive = wireActiveFilter
                ) { it }
            )
            assertEquals(1, KmpGrpcWireChannel.bindDeferred(env(registrar = wrappedPurify), surface))
            assertTrue(wireActivePurify.get())
            assertTrue(wireActiveFilter.get())
            assertEquals(1, registrar.hooks.size)
            assertEquals(KmpGrpcWireChannel.FAULT_OWNER, registrar.hooks.getValue("kmp.grpc.wire.channel").callback.faultOwner)

            val otherRegistrar = PlayerPortTestRegistrar()
            val otherWireActive = AtomicBoolean(false)
            assertTrue(
                KmpGrpcWireChannel.install(
                    environment = env(registrar = otherRegistrar), surface = surface,
                    javaMossClassName = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss", rpc = "mainList",
                    javaReplyClass = WireJavaMessage::class.java, what = "other", logKey = "k_other", wireActive = otherWireActive
                ) { it }
            )
            assertTrue(otherWireActive.get())
            assertEquals(1, otherRegistrar.hooks.size)
            assertEquals(KmpGrpcWireChannel.FAULT_OWNER, otherRegistrar.hooks.getValue("kmp.grpc.wire.channel").callback.faultOwner)
        }

        // Weak channel maps release registrars and classLoaders promptly.
        run {
            val queue = ReferenceQueue<Any>()
            val refs = registerAndDropForGc(surface, queue)
            for (attempt in 0 until 15) {
                if (refs.all { it.get() == null }) break
                System.gc()
                System.runFinalization()
                queue.remove(15)
            }
            assertTrue("Expected WeakHashMap entries to release registrars and loaders", refs.all { it.get() == null })
        }

        // Unsubscribed RPC skips chain args materialization and override completely.
        run {
            val registrar = PlayerPortTestRegistrar()
            assertTrue(
                KmpGrpcWireChannel.install(
                    environment = env(registrar = registrar), surface = surface,
                    javaMossClassName = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss", rpc = "mainList",
                    javaReplyClass = WireJavaMessage::class.java, what = "filter", logKey = "k_zero_alloc"
                ) { it }
            )
            val unsubscribed = Descriptor("bilibili.app.view.v1", "View", "View", WireDescriptor::class, DummyStrategy)
            val engine = GrpcEngine(emptyList())
            val sink = object : Handler {
                override fun onNext(bytes: ByteArray?) = Unit
                override fun onCompleted() = Unit
                override fun onError(error: KMossException) = Unit
            }
            val argsArray = arrayOf(Service("grpc.biliapi.net"), unsubscribed, "req".toByteArray(), sink, null)
            var getArgsCount = 0
            var getArgCount = 0
            var proceedNoArgsCount = 0
            var proceedWithArgsCount = 0
            val entry = registrar.hooks.getValue("kmp.grpc.wire.channel")
            entry.callback.invoke(object : io.github.libxposed.api.XposedInterface.Chain {
                override fun getExecutable() = entry.member
                override fun getThisObject() = engine
                override fun getArgs(): List<Any?> { getArgsCount++; return argsArray.toList() }
                override fun getArg(index: Int): Any? { getArgCount++; return argsArray[index] }
                override fun proceed(): Any? { proceedNoArgsCount++; return null }
                override fun proceed(args: Array<Any?>): Any? { proceedWithArgsCount++; return null }
                override fun proceedWith(thisObject: Any): Any? = null
                override fun proceedWith(thisObject: Any, args: Array<Any?>): Any? = null
            })
            assertEquals(0, getArgsCount)
            assertEquals(1, getArgCount)
            assertEquals(1, proceedNoArgsCount)
            assertEquals(0, proceedWithArgsCount)
        }
    }
}
