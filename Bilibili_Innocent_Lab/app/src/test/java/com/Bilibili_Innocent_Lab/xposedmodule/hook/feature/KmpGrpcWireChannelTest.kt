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
import kmpfixture.modern.Descriptor
import kmpfixture.modern.GrpcEngine
import kmpfixture.modern.Handler
import kmpfixture.modern.Service
import kotlinx.serialization.SerializationStrategy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
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

            fun reset() {
                parses = 0
                serializes = 0
            }

            @JvmStatic
            fun parseFrom(bytes: ByteArray): CountingCodecJavaMessage {
                parses++
                return CountingCodecJavaMessage(String(bytes, Charsets.UTF_8))
            }
        }
    }

    private fun loaderWithout(hiddenPrefixes: Set<String>): ClassLoader = object : ClassLoader(javaClass.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (hiddenPrefixes.any(name::startsWith)) throw ClassNotFoundException(name)
            return super.loadClass(name, resolve)
        }
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
    fun `KmpGrpcWireAdapter resolves without MossInterceptor and handles drifted cache and ambiguity`() {
        val noInterceptorLoader = loaderWithout(setOf("kntr.base.moss."))
        val directModern = KmpGrpcWireAdapter.inspectEntry(
            GrpcEngine::class.java.declaredMethods.single { it.name == "a" }
        )!!
        assertEquals(1, directModern.descriptorIndex)
        assertEquals(2, directModern.requestBytesIndex)
        assertEquals(3, directModern.handlerIndex)

        // Cached point resolves a drifted engine class even when fixed GrpcEngine/MossInterceptor classes are absent.
        val driftedMethod = DriftedWireEngine::class.java.getDeclaredMethod(
            "dispatch",
            Any::class.java,
            WireDescriptor::class.java,
            ByteArray::class.java,
            WireHandlerContract::class.java
        )
        val cachedPoint = KmpGrpcWireAdapter.point(driftedMethod)
        val resolvedFromCache = KmpGrpcWireAdapter.resolve(noInterceptorLoader, cachedPoint)
        assertNotNull(resolvedFromCache)
        assertEquals(driftedMethod, resolvedFromCache!!.engineEntry)

        // Rejects invalid cache shape (wrong param count).
        assertNull(
            KmpGrpcWireAdapter.resolve(
                noInterceptorLoader,
                VersionAdapter.HookPoint(DriftedWireEngine::class.java.name, "dispatch", listOf("int"))
            )
        )

        // Ambiguous DEX candidates fail closed.
        val dupA = DuplicateWireEngine::class.java.getDeclaredMethod(
            "first", Any::class.java, WireDescriptor::class.java, ByteArray::class.java, WireHandlerContract::class.java
        )
        val dupB = DuplicateWireEngine::class.java.getDeclaredMethod(
            "second", Any::class.java, WireDescriptor::class.java, ByteArray::class.java, WireHandlerContract::class.java
        )
        val singleSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(driftedMethod)) },
            listOf("/base.apk"),
            noInterceptorLoader,
            setOf(DexAssistQuery.KMP_GRPC_WIRE_ENGINE)
        )
        val ambigSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(dupA, dupB)) },
            listOf("/base.apk"),
            noInterceptorLoader,
            setOf(DexAssistQuery.KMP_GRPC_WIRE_ENGINE)
        )
        assertEquals(cachedPoint, KmpGrpcWireAdapter.locate(noInterceptorLoader, singleSession, true, null, true))
        assertNull(KmpGrpcWireAdapter.locate(noInterceptorLoader, ambigSession, true, null, true))
    }

    @Test
    fun `fullMethodName maps Bapis Moss RPCs consistently with KMethodDescriptor`() {
        val moss = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss"
        assertEquals("bilibili.main.community.reply.v1.Reply/MainList", KmpGrpcWireChannel.fullMethodName(moss, "mainList"))
        assertEquals("bilibili.main.community.reply.v1.Reply/DetailList", KmpGrpcWireChannel.fullMethodName(moss, "detailList"))
        assertEquals("bilibili.main.community.reply.v1.Reply/DialogList", KmpGrpcWireChannel.fullMethodName(moss, "dialogList"))
        assertEquals("bilibili.main.community.reply.v1.Reply/FoldList", KmpGrpcWireChannel.fullMethodName(moss, "foldList"))
        assertEquals("bilibili.main.community.reply.v1.Reply/HotspotPage", KmpGrpcWireChannel.fullMethodName(moss, "hotspotPage"))
        assertEquals("bilibili.main.community.reply.v1.Reply/ReplyInfo", KmpGrpcWireChannel.fullMethodName(moss, "replyInfo"))
        assertEquals("bilibili.main.community.reply.v1.Reply/MainList", KmpGrpcWireChannel.fullMethodName(moss, "executeMainList"))
        assertNull(KmpGrpcWireChannel.fullMethodName("BadName", "mainList"))
    }

    @Test
    fun `wire channel shares single parseAcross steps, avoids encodeToByteArray, and preserves original bytes for readOnly`() {
        CountingCodecJavaMessage.reset()
        val registrar = PlayerPortTestRegistrar()
        val errors = mutableListOf<String>()
        val environment = env(registrar = registrar, errors = errors)
        val surface = KmpGrpcWireAdapter.inspectEntry(
            GrpcEngine::class.java.declaredMethods.single { it.name == "a" }
        )!!

        val seenFilter = mutableListOf<CountingCodecJavaMessage>()
        val seenTopology = mutableListOf<CountingCodecJavaMessage>()

        // Register three steps for MainList on the same GrpcEngine.a.
        assertTrue(
            KmpGrpcWireChannel.install(
                environment, surface,
                "com.bapis.bilibili.main.community.reply.v1.ReplyMoss", "mainList",
                CountingCodecJavaMessage::class.java, "filter", "filter"
            ) { msg ->
                seenFilter += msg as CountingCodecJavaMessage
                msg // unchanged
            }
        )
        assertTrue(
            KmpGrpcWireChannel.install(
                environment, surface,
                "com.bapis.bilibili.main.community.reply.v1.ReplyMoss", "mainList",
                CountingCodecJavaMessage::class.java, "topology", "topology"
            ) { msg ->
                seenTopology += msg as CountingCodecJavaMessage
                msg // unchanged
            }
        )
        // Must share ONE hook on GrpcEngine.a.
        assertEquals(1, registrar.hooks.size)
        assertEquals(1, registrar.registrationAttempts.size)

        val descriptor = Descriptor("bilibili.main.community.reply.v1", "Reply", "MainList", WireDescriptor::class, DummyStrategy)
        val reqBytes = "req".toByteArray()
        val delivered = mutableListOf<ByteArray>()
        val sink = object : Handler {
            override fun onNext(bytes: ByteArray?) { if (bytes != null) delivered += bytes }
            override fun onCompleted() = Unit
            override fun onError(error: kmpfixture.modern.KMossException) = Unit
        }
        val engine = GrpcEngine(emptyList())
        val originalReplyBytes = "hello-nullable-map".toByteArray()
        registrar.invoke(
            "kmp.grpc.wire.channel",
            receiver = engine,
            args = arrayOf(Service("grpc.biliapi.net"), descriptor, reqBytes, sink, null)
        ) { callArgs ->
            (callArgs[3] as Handler).onNext(originalReplyBytes)
            null
        }

        assertTrue(errors.isEmpty())
        assertEquals(1, CountingCodecJavaMessage.parses)
        assertEquals(0, CountingCodecJavaMessage.serializes)
        assertEquals(1, seenFilter.size)
        assertSame(seenFilter.single(), seenTopology.single())
        assertSame(originalReplyBytes, delivered.single())

        // Non-subscribed RPC bypasses parsing completely.
        val otherDescriptor = Descriptor("bilibili.app.view.v1", "View", "View", WireDescriptor::class, DummyStrategy)
        registrar.invoke(
            "kmp.grpc.wire.channel",
            receiver = engine,
            args = arrayOf(Service("grpc.biliapi.net"), otherDescriptor, reqBytes, sink, null)
        ) { callArgs ->
            (callArgs[3] as Handler).onNext("ignored".toByteArray())
            null
        }
        assertEquals(1, CountingCodecJavaMessage.parses)
    }

    @Test
    fun `wire channel confirms readback, delays receipts until after host delivery, and isolates step or host errors`() {
        val registrar = PlayerPortTestRegistrar()
        val errors = mutableListOf<String>()
        val environment = env(registrar = registrar, errors = errors)
        val surface = KmpGrpcWireAdapter.inspectEntry(
            GrpcEngine::class.java.declaredMethods.single { it.name == "a" }
        )!!
        val events = mutableListOf<String>()

        // Request transform + Purify confirmed transform + corrupt step + Filter confirmed transform.
        assertTrue(
            KmpGrpcWireChannel.install(
                environment, surface,
                "com.bapis.bilibili.main.community.reply.v1.ReplyMoss", "mainList",
                WireJavaMessage::class.java, "purify", "purify",
                javaRequestClass = WireJavaMessage::class.java,
                transformRequest = { req -> WireJavaMessage((req as WireJavaMessage).body + "|no_underline") },
                confirmedTransform = { msg ->
                    val current = msg as WireJavaMessage
                    ConfirmedReply(WireJavaMessage(current.body + "|purified")) { events += "purify_applied" }
                }
            ) { it }
        )
        assertTrue(
            KmpGrpcWireChannel.install(
                environment, surface,
                "com.bapis.bilibili.main.community.reply.v1.ReplyMoss", "mainList",
                CorruptReadbackMessage::class.java, "broken", "broken",
                confirmedTransform = { _ ->
                    ConfirmedReply(CorruptReadbackMessage("expected")) { events += "broken_must_not_run" }
                }
            ) { it }
        )
        assertTrue(
            KmpGrpcWireChannel.install(
                environment, surface,
                "com.bapis.bilibili.main.community.reply.v1.ReplyMoss", "mainList",
                WireJavaMessage::class.java, "filter", "filter",
                confirmedTransform = { msg ->
                    val current = msg as WireJavaMessage
                    ConfirmedReply(WireJavaMessage(current.body + "|filtered")) { events += "filter_applied" }
                }
            ) { it }
        )

        val descriptor = Descriptor("bilibili.main.community.reply.v1", "Reply", "MainList", WireDescriptor::class, DummyStrategy)
        val engine = GrpcEngine(emptyList())
        var sentReq: ByteArray? = null
        var deliveredReply: ByteArray? = null
        val sink = object : Handler {
            override fun onNext(bytes: ByteArray?) {
                assertTrue("Receipts must fire only AFTER host handler returns", events.isEmpty())
                deliveredReply = bytes
            }
            override fun onCompleted() = Unit
            override fun onError(error: kmpfixture.modern.KMossException) = Unit
        }
        registrar.invoke(
            "kmp.grpc.wire.channel",
            receiver = engine,
            args = arrayOf(Service("grpc.biliapi.net"), descriptor, "req0".toByteArray(), sink, null)
        ) { callArgs ->
            sentReq = callArgs[2] as ByteArray
            (callArgs[3] as Handler).onNext("raw".toByteArray())
            null
        }

        assertEquals("req0|no_underline", String(sentReq!!, Charsets.UTF_8))
        assertEquals("raw|purified|filtered", String(deliveredReply!!, Charsets.UTF_8))
        assertEquals(listOf("purify_applied", "filter_applied"), events)
        assertTrue("broken_wire_failed" in errors)

        // Host exception aborts receipts and keeps the exact exception type.
        events.clear()
        val hostFailure = IllegalStateException("host_boom")
        val throwingSink = object : Handler {
            override fun onNext(bytes: ByteArray?) { throw hostFailure }
            override fun onCompleted() = Unit
            override fun onError(error: kmpfixture.modern.KMossException) = Unit
        }
        val thrown = assertThrows(IllegalStateException::class.java) {
            registrar.invoke(
                "kmp.grpc.wire.channel",
                receiver = engine,
                args = arrayOf(Service("grpc.biliapi.net"), descriptor, "req0".toByteArray(), throwingSink, null)
            ) { callArgs ->
                (callArgs[3] as Handler).onNext("raw".toByteArray())
                null
            }
        }
        assertSame(hostFailure, thrown)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `wire channel composes cleanly with KmpMossBoundary observing proxy`() {
        val registrar = PlayerPortTestRegistrar()
        val environment = env(registrar = registrar)
        val surface = KmpGrpcWireAdapter.inspectEntry(
            GrpcEngine::class.java.declaredMethods.single { it.name == "a" }
        )!!
        val wireSeen = mutableListOf<String>()
        val boundarySeen = mutableListOf<String>()
        assertTrue(
            KmpGrpcWireChannel.install(
                environment, surface,
                "com.bapis.bilibili.main.community.reply.v1.ReplyMoss", "mainList",
                WireJavaMessage::class.java, "topology", "topology"
            ) { msg ->
                wireSeen += (msg as WireJavaMessage).body
                msg
            }
        )
        val descriptor = Descriptor("bilibili.main.community.reply.v1", "Reply", "MainList", WireDescriptor::class, DummyStrategy)
        val rawSink = object : Handler {
            override fun onNext(bytes: ByteArray?) = Unit
            override fun onCompleted() = Unit
            override fun onError(error: kmpfixture.modern.KMossException) = Unit
        }
        val observed = KmpMossBoundary.observing(Handler::class.java, rawSink) { bytes ->
            boundarySeen += String(bytes, Charsets.UTF_8)
        }
        registrar.invoke(
            "kmp.grpc.wire.channel",
            receiver = GrpcEngine(emptyList()),
            args = arrayOf(Service("grpc.biliapi.net"), descriptor, ByteArray(0), observed, null)
        ) { callArgs ->
            (callArgs[3] as Handler).onNext("reply-payload".toByteArray())
            null
        }
        assertEquals(listOf("reply-payload"), wireSeen)
        assertEquals(listOf("reply-payload"), boundarySeen)
    }

    @Test
    fun `interfaces package segment maps to interface in fullMethodName`() {
        assertEquals(
            "bilibili.app.interface.v1.Search/DefaultWords",
            KmpGrpcWireChannel.fullMethodName("com.bapis.bilibili.app.interfaces.v1.SearchMoss", "defaultWords")
        )
        assertEquals(
            "bilibili.app.interface.v1.Search/DefaultWords",
            KmpGrpcWireChannel.fullMethodName("com.bapis.bilibili.app.interface.v1.SearchMoss", "executeDefaultWords")
        )
    }

    @Test
    fun `deferred binding unwraps wrapper dedups by logKey and avoids redundant parse`() {
        val registrar = PlayerPortTestRegistrar()
        val scoped = object : HookRegistrar by registrar {}
        val environment = env(registrar = scoped)
        val wireActive = AtomicBoolean(false)
        CountingCodecJavaMessage.reset()

        assertFalse(
            KmpGrpcWireChannel.installOrDefer(
                environment = environment,
                surface = null,
                javaMossClassName = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss",
                rpc = "mainList",
                javaReplyClass = CountingCodecJavaMessage::class.java,
                what = "filter-1",
                logKey = "k_filter",
                wireActive = wireActive
            ) { msg ->
                val cur = msg as CountingCodecJavaMessage
                CountingCodecJavaMessage("${cur.text}|step1")
            }
        )
        // Duplicate (logKey, rpcFullName) must be deduplicated cleanly.
        assertFalse(
            KmpGrpcWireChannel.installOrDefer(
                environment = environment,
                surface = null,
                javaMossClassName = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss",
                rpc = "mainList",
                javaReplyClass = CountingCodecJavaMessage::class.java,
                what = "filter-1-dup",
                logKey = "k_filter",
                wireActive = wireActive
            ) { error("duplicate must be ignored") }
        )
        // Second distinct logKey chains on same RPC and must reuse non-confirmed mirror without redundant parseFrom.
        assertFalse(
            KmpGrpcWireChannel.installOrDefer(
                environment = environment,
                surface = null,
                javaMossClassName = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss",
                rpc = "mainList",
                javaReplyClass = CountingCodecJavaMessage::class.java,
                what = "filter-2",
                logKey = "k_topology"
            ) { msg ->
                val cur = msg as CountingCodecJavaMessage
                CountingCodecJavaMessage("${cur.text}|step2")
            }
        )
        assertFalse(wireActive.get())
        assertTrue(registrar.hooks.isEmpty())

        val surface = KmpGrpcWireAdapter.inspectEntry(
            GrpcEngine::class.java.declaredMethods.single { it.name == "a" }
        )!!
        assertEquals(2, KmpGrpcWireChannel.bindDeferred(env(registrar = registrar), surface))
        assertEquals(0, KmpGrpcWireChannel.bindDeferred(env(registrar = registrar), surface))
        assertTrue(wireActive.get())
        assertEquals(1, registrar.hooks.size)

        val descriptor = Descriptor("bilibili.main.community.reply.v1", "Reply", "MainList", WireDescriptor::class, DummyStrategy)
        var delivered: ByteArray? = null
        val sink = object : Handler {
            override fun onNext(bytes: ByteArray?) { delivered = bytes }
            override fun onCompleted() = Unit
            override fun onError(error: kmpfixture.modern.KMossException) = Unit
        }
        registrar.invoke(
            "kmp.grpc.wire.channel",
            receiver = GrpcEngine(emptyList()),
            args = arrayOf(Service("grpc.biliapi.net"), descriptor, "req".toByteArray(), sink, null)
        ) { callArgs ->
            (callArgs[3] as Handler).onNext("orig".toByteArray())
            null
        }
        assertEquals("orig|step1|step2", String(requireNotNull(delivered), Charsets.UTF_8))
        // Step1 parses once (1); Step2 reuses step1's updated mirror (0 parseFrom)!
        assertEquals(1, CountingCodecJavaMessage.parses)
        assertEquals(2, CountingCodecJavaMessage.serializes)
    }

    @Test
    fun `rootRegistrar unwraps FeatureScopedRegistrar shares dispatcher and stamps wire faultOwner`() {
        val registrar = PlayerPortTestRegistrar()
        val scopedPurify = FeatureScopedRegistrar(registrar, "comment_purify")
        val wrappedPurify = object : HookRegistrar by scopedPurify {}
        val scopedFilter = FeatureScopedRegistrar(registrar, "comment_filter")
        val wireActivePurify = AtomicBoolean(false)
        val wireActiveFilter = AtomicBoolean(false)

        assertFalse(
            KmpGrpcWireChannel.installOrDefer(
                environment = env(registrar = wrappedPurify),
                surface = null,
                javaMossClassName = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss",
                rpc = "mainList",
                javaReplyClass = WireJavaMessage::class.java,
                what = "purify",
                logKey = "k_purify",
                wireActive = wireActivePurify
            ) { it }
        )

        val surface = KmpGrpcWireAdapter.inspectEntry(
            GrpcEngine::class.java.declaredMethods.single { it.name == "a" }
        )!!
        assertTrue(
            KmpGrpcWireChannel.install(
                environment = env(registrar = scopedFilter),
                surface = surface,
                javaMossClassName = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss",
                rpc = "mainList",
                javaReplyClass = WireJavaMessage::class.java,
                what = "filter",
                logKey = "k_filter",
                wireActive = wireActiveFilter
            ) { it }
        )
        assertEquals(1, KmpGrpcWireChannel.bindDeferred(env(registrar = wrappedPurify), surface))
        assertTrue(wireActivePurify.get())
        assertTrue(wireActiveFilter.get())
        assertEquals(1, registrar.hooks.size)
        assertEquals(
            KmpGrpcWireChannel.FAULT_OWNER,
            registrar.hooks.getValue("kmp.grpc.wire.channel").callback.faultOwner
        )

        // Separate PlayerPortTestRegistrar instance stays isolated by identity via rootRegistrar.
        val otherRegistrar = PlayerPortTestRegistrar()
        val otherWireActive = AtomicBoolean(false)
        assertTrue(
            KmpGrpcWireChannel.install(
                environment = env(registrar = otherRegistrar),
                surface = surface,
                javaMossClassName = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss",
                rpc = "mainList",
                javaReplyClass = WireJavaMessage::class.java,
                what = "other",
                logKey = "k_other",
                wireActive = otherWireActive
            ) { it }
        )
        assertTrue(otherWireActive.get())
        assertEquals(1, otherRegistrar.hooks.size)
        assertEquals(
            KmpGrpcWireChannel.FAULT_OWNER,
            otherRegistrar.hooks.getValue("kmp.grpc.wire.channel").callback.faultOwner
        )
    }

    private fun registerAndDropForGc(
        surface: KmpGrpcWireAdapter.WireSurface
    ): List<WeakReference<*>> {
        val pendingRegistrar = PlayerPortTestRegistrar()
        val pendingScoped = FeatureScopedRegistrar(pendingRegistrar, "comment_purify")
        val pendingLoader = loaderWithout(emptySet())
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
        val activeLoader = loaderWithout(emptySet())
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
            WeakReference(pendingRegistrar),
            WeakReference(pendingScoped),
            WeakReference(pendingLoader),
            WeakReference(activeRegistrar),
            WeakReference(activeScoped),
            WeakReference(activeLoader)
        )
    }

    @Test
    fun `weak channel maps do not retain registrar or classLoader via registrations`() {
        val surface = KmpGrpcWireAdapter.inspectEntry(
            GrpcEngine::class.java.declaredMethods.single { it.name == "a" }
        )!!
        val refs = registerAndDropForGc(surface)
        for (attempt in 0 until 20) {
            System.gc()
            System.runFinalization()
            if (refs.all { it.get() == null }) break
            Thread.sleep(15)
        }
        assertTrue("Expected WeakHashMap entries to release registrars and loaders", refs.all { it.get() == null })
    }

    @Test
    fun `unsubscribed RPC skips chain args materialization and override completely`() {
        val registrar = PlayerPortTestRegistrar()
        val environment = env(registrar = registrar)
        val surface = KmpGrpcWireAdapter.inspectEntry(
            GrpcEngine::class.java.declaredMethods.single { it.name == "a" }
        )!!
        assertTrue(
            KmpGrpcWireChannel.install(
                environment = environment,
                surface = surface,
                javaMossClassName = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss",
                rpc = "mainList",
                javaReplyClass = WireJavaMessage::class.java,
                what = "filter",
                logKey = "k_zero_alloc"
            ) { it }
        )

        val unsubscribed = Descriptor("bilibili.app.view.v1", "View", "View", WireDescriptor::class, DummyStrategy)
        val engine = GrpcEngine(emptyList())
        val sink = object : Handler {
            override fun onNext(bytes: ByteArray?) = Unit
            override fun onCompleted() = Unit
            override fun onError(error: kmpfixture.modern.KMossException) = Unit
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
            override fun getArgs(): List<Any?> {
                getArgsCount++
                return argsArray.toList()
            }
            override fun getArg(index: Int): Any? {
                getArgCount++
                return argsArray[index]
            }
            override fun proceed(): Any? {
                proceedNoArgsCount++
                return null
            }
            override fun proceed(args: Array<Any?>): Any? {
                proceedWithArgsCount++
                return null
            }
            override fun proceedWith(thisObject: Any): Any? = null
            override fun proceedWith(thisObject: Any, args: Array<Any?>): Any? = null
        })
        assertEquals(0, getArgsCount)
        assertEquals(1, getArgCount)
        assertEquals(1, proceedNoArgsCount)
        assertEquals(0, proceedWithArgsCount)
    }
}
