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
}
