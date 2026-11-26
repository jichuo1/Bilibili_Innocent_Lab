package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MossRpcChannelTest {

    data class FakeReq(val id: String)

    data class FakeReply(val label: String) {
        fun toByteArray(): ByteArray = label.toByteArray(Charsets.UTF_8)

        companion object {
            @JvmStatic
            fun parseFrom(bytes: ByteArray): FakeReply =
                FakeReply(String(bytes, Charsets.UTF_8).removePrefix("K:"))
        }
    }

    interface FakeMossHandler {
        fun onNext(reply: Any?)
        fun onError(t: Throwable)
        fun onCompleted()
    }

    class FakeJavaMoss {
        fun executeFetch(@Suppress("UNUSED_PARAMETER") req: FakeReq): FakeReply = FakeReply("ad")
        fun fetch(@Suppress("UNUSED_PARAMETER") req: FakeReq, handler: FakeMossHandler) {
            handler.onNext(FakeReply("ad"))
        }
    }

    interface FakeWireHandler {
        fun onNext(bytes: ByteArray)
    }

    class FakeDescriptor(
        @Suppress("unused") val packageName: String,
        @Suppress("unused") val serviceName: String,
        @Suppress("unused") val methodName: String
    ) {
        override fun toString(): String =
            "MethodDescriptor(packageName=$packageName, serviceName=$serviceName, methodName=$methodName, extra=0)"
    }

    class FakeGrpcEngine {
        @Suppress("UNUSED_PARAMETER")
        fun invokeRpc(svc: Any, desc: FakeDescriptor, req: ByteArray, handler: FakeWireHandler) = Unit
    }

    class FakeKotlinMoss {
        @Suppress("UNUSED_PARAMETER")
        fun fetch(
            req: Any,
            reqSer: kotlinx.serialization.SerializationStrategy<Any>,
            repSer: kotlinx.serialization.DeserializationStrategy<Any>,
            handler: FakeMossHandler,
            proto: ProtoBuf
        ) = Unit
    }

    private object WireLabelSerializer : SerializationStrategy<Any>, DeserializationStrategy<Any>

    private fun env(registrar: PlayerPortTestRegistrar) = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = javaClass.classLoader,
        hookPoints = HookPointRegistry(javaClass.classLoader),
        registrar = registrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { _, _ -> }
    )

    fun `mountJavaSync transforms reply only on non throwing changes`() {
        val registrar = PlayerPortTestRegistrar()
        val environment = env(registrar)
        val mounted = MossRpcChannel.mountJavaSync(
            environment = environment,
            mossClass = FakeJavaMoss::class.java,
            syncMethod = "executeFetch",
            requestClass = FakeReq::class.java,
            hookId = "test.moss.sync.executeFetch",
            logKey = "test_sync",
            what = "TestSync"
        ) { _, reply ->
            val typed = reply as FakeReply
            if (typed.label == "ad") FakeReply("clean") else typed
        }
        assertTrue(mounted)

        val unchanged = FakeReply("ok")
        val kept = registrar.invoke("test.moss.sync.executeFetch", FakeJavaMoss(), arrayOf(FakeReq("1"))) { unchanged }
        assertSame(unchanged, kept)

        val replaced = registrar.invoke("test.moss.sync.executeFetch", FakeJavaMoss(), arrayOf(FakeReq("2"))) {
            FakeReply("ad")
        } as FakeReply
        assertEquals("clean", replaced.label)

        val throwAttempt = runCatching {
            registrar.invoke("test.moss.sync.executeFetch", FakeJavaMoss(), arrayOf(FakeReq("3"))) {
                error("host-boom")
            }
        }
        assertTrue(throwAttempt.isFailure)
    }

    fun `mountJavaAsync wraps handler arg and transforms response`() {
        val registrar = PlayerPortTestRegistrar()
        val environment = env(registrar)
        val mounted = MossRpcChannel.mountJavaAsync(
            environment = environment,
            mossClass = FakeJavaMoss::class.java,
            asyncMethod = "fetch",
            requestClass = FakeReq::class.java,
            handlerClass = FakeMossHandler::class.java,
            hookId = "test.moss.async.fetch",
            logKey = "test_async",
            what = "TestAsync"
        ) { req, reply ->
            val id = (req as FakeReq).id
            val typed = reply as FakeReply
            FakeReply("${typed.label}->$id")
        }
        assertTrue(mounted)

        var delivered: FakeReply? = null
        val handler = object : FakeMossHandler {
            override fun onNext(reply: Any?) { delivered = reply as? FakeReply }
            override fun onError(t: Throwable) = Unit
            override fun onCompleted() = Unit
        }
        registrar.invoke("test.moss.async.fetch", FakeJavaMoss(), arrayOf(FakeReq("42"), handler)) { args ->
            (args[1] as FakeMossHandler).onNext(FakeReply("raw"))
            null
        }
        assertEquals(FakeReply("raw->42"), delivered)
    }

    fun `mountKmpWireOrKotlin skips KotlinMoss when wire surface mounts directly and prevents double execution on deferred wire`() {
        val registrar = PlayerPortTestRegistrar()
        val environment = env(registrar)
        val surface = KmpGrpcWireAdapter.WireSurface(
            engine = FakeGrpcEngine::class.java,
            engineEntry = FakeGrpcEngine::class.java.declaredMethods.single { it.name == "invokeRpc" },
            descriptorIndex = 1,
            requestBytesIndex = 2,
            handlerIndex = 3
        )
        val wireActiveDirect = AtomicBoolean(false)
        val directResult = MossRpcChannel.mountKmpWireOrKotlin(
            environment = environment,
            loader = javaClass.classLoader!!,
            javaMossClassName = "fake.pkg.MockMoss",
            rpc = "fetch",
            javaReplyClass = FakeReply::class.java,
            what = "TestWireDirect",
            logKey = "test_wire_direct",
            kotlinHookId = "test.wire.kmoss",
            wireSurface = surface,
            wireActive = wireActiveDirect,
            allowKotlinFallback = true
        ) { it }
        assertTrue(directResult.kmpWireInstalled)
        assertFalse(directResult.kotlinMossInstalled)
        assertFalse("test.wire.kmoss" in registrar.hooks)

        // Deferred case: wireSurface is null -> queues deferred wire + installs KotlinMoss sharing wireActive.
        val registrarDeferred = PlayerPortTestRegistrar()
        val deferredEnv = env(registrarDeferred)
        val wireActiveDeferred = AtomicBoolean(false)
        val transformCalls = AtomicInteger(0)
        val transformJava: (Any) -> Any = { reply ->
            transformCalls.incrementAndGet()
            val typed = reply as FakeReply
            if (typed.label == "ad") FakeReply("clean") else typed
        }
        val kEntry = FakeKotlinMoss::class.java.declaredMethods.single { it.name == "fetch" }
        val deferredResult = MossRpcChannel.mountKmpWireOrKotlin(
            environment = deferredEnv,
            loader = javaClass.classLoader!!,
            javaMossClassName = "fake.pkg.DeferredMockMoss",
            rpc = "fetch",
            javaReplyClass = FakeReply::class.java,
            what = "TestDeferred",
            logKey = "test_deferred",
            kotlinHookId = "test.deferred.kmoss.fetch",
            wireSurface = null,
            deferWireIfUnavailable = true,
            wireActive = wireActiveDeferred,
            resolvedKotlinEntry = kEntry,
            allowKotlinFallback = true,
            transformJava = transformJava
        )
        assertFalse(deferredResult.kmpWireInstalled)
        assertTrue(deferredResult.kotlinMossInstalled)

        var delivered: Any? = null
        val kHandler = object : FakeMossHandler {
            override fun onNext(reply: Any?) { delivered = reply }
            override fun onError(t: Throwable) = Unit
            override fun onCompleted() = Unit
        }
        registrarDeferred.invoke(
            "test.deferred.kmoss.fetch",
            FakeKotlinMoss(),
            arrayOf("req", WireLabelSerializer, WireLabelSerializer, kHandler, ProtoBuf.Default)
        ) { args ->
            (args[3] as FakeMossHandler).onNext("ad")
            null
        }
        assertEquals("K:clean", delivered)
        assertEquals(1, transformCalls.get())

        // Activate deferred wire binding & fire a wire response -> flips wireActiveDeferred = true.
        assertEquals(1, KmpGrpcWireChannel.bindDeferred(deferredEnv, surface))
        var wireDelivered: ByteArray? = null
        val wireHandler = object : FakeWireHandler {
            override fun onNext(bytes: ByteArray) { wireDelivered = bytes }
        }
        registrarDeferred.invoke(
            "kmp.grpc.wire.channel",
            FakeGrpcEngine(),
            arrayOf(Any(), FakeDescriptor("fake.pkg", "DeferredMock", "Fetch"), ByteArray(0), wireHandler)
        ) { args ->
            (args[3] as FakeWireHandler).onNext(FakeReply("ad").toByteArray())
            null
        }
        assertTrue(wireActiveDeferred.get())
        assertEquals("clean", FakeReply.parseFrom(wireDelivered!!).label)
        assertEquals(2, transformCalls.get())

        // After wire becomes active, KotlinMoss callback skips transformJava cleanly (no double execution).
        delivered = null
        registrarDeferred.invoke(
            "test.deferred.kmoss.fetch",
            FakeKotlinMoss(),
            arrayOf("req", WireLabelSerializer, WireLabelSerializer, kHandler, ProtoBuf.Default)
        ) { args ->
            (args[3] as FakeMossHandler).onNext("ad")
            null
        }
        assertEquals("ad", delivered)
        assertEquals(2, transformCalls.get())
    }

    fun `mountAll composes Java sync Java async and KMP wire channels cleanly`() {
        val registrar = PlayerPortTestRegistrar()
        val environment = env(registrar)
        val surface = KmpGrpcWireAdapter.WireSurface(
            engine = FakeGrpcEngine::class.java,
            engineEntry = FakeGrpcEngine::class.java.declaredMethods.single { it.name == "invokeRpc" },
            descriptorIndex = 1,
            requestBytesIndex = 2,
            handlerIndex = 3
        )
        val outcome = MossRpcChannel.mountAll(
            environment = environment,
            loader = javaClass.classLoader!!,
            hookIdPrefix = "test.all",
            javaMossClassName = "fake.pkg.AllMockMoss",
            rpc = "fetch",
            javaReplyClass = FakeReply::class.java,
            what = "TestAll",
            logKey = "test_all",
            syncMethod = "executeFetch",
            javaMossClass = FakeJavaMoss::class.java,
            javaRequestClass = FakeReq::class.java,
            handlerClass = FakeMossHandler::class.java,
            wireSurface = surface
        ) { _, reply -> reply }

        assertTrue(outcome.javaSyncInstalled)
        assertTrue(outcome.javaAsyncInstalled)
        assertTrue(outcome.kmpWireInstalled)
        assertFalse(outcome.kotlinMossInstalled)
        assertEquals(3, outcome.totalInstalledCount)
    }

    @Test
    fun verifyAllCases() {
        fun runIsolated(block: (MossRpcChannelTest) -> Unit) {
            val s = MossRpcChannelTest()
            block(s)
        }
        runIsolated { s -> s.`mountJavaSync transforms reply only on non throwing changes`() }
        runIsolated { s -> s.`mountJavaAsync wraps handler arg and transforms response`() }
        runIsolated { s -> s.`mountKmpWireOrKotlin skips KotlinMoss when wire surface mounts directly and prevents double execution on deferred wire`() }
        runIsolated { s -> s.`mountAll composes Java sync Java async and KMP wire channels cleanly`() }
    }
}
