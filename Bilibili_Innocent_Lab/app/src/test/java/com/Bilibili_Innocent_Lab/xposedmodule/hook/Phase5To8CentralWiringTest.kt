package com.Bilibili_Innocent_Lab.xposedmodule.hook

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptCallback
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HostIdentity
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.StartupCacheSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptAssistGate
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlan
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.BrandSplashKntrAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedSessionLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PrivacyChannelAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexSourceFingerprint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentPurifyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DanmakuPurifyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailModulePurifyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallRecord
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FollowFeedFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HostVideoCardStyleFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KmpGrpcWireChannel
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KotlinMossChannel
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.danmakuwire.DanmakuWire
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.danmakuwire.writeBytesField
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.danmakuwire.writeVarintField
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight.AiInsightFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight.InsightHostSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostCardMaterial
import com.bapis.bilibili.app.view.v1.ViewReply
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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class Phase5To8CentralWiringTest {

    private fun env(
        customLoader: ClassLoader = javaClass.classLoader!!,
        registrar: com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar = PlayerPortTestRegistrar(),
        evidence: MutableList<Pair<String, FeatureRuntimeStage>> = mutableListOf(),
        logs: MutableList<String> = mutableListOf(),
        errors: MutableList<String> = mutableListOf()
    ) = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = customLoader,
        hookPoints = HookPointRegistry(customLoader),
        registrar = registrar,
        logInfo = { k, m -> logs += "$k:$m" },
        logError = { k, m -> errors += "$k:$m" },
        reportStatus = { k, v -> logs += "status:$k=$v" },
        runtimeEvidence = { id, stage, _ -> evidence += id to stage }
    )

    private fun wirePoint(): HookPoint = KmpGrpcWireAdapter.point(
        GrpcEngine::class.java.declaredMethods.single { it.name == "a" }
    )

    private fun wireSurface(): KmpGrpcWireAdapter.WireSurface = requireNotNull(
        KmpGrpcWireAdapter.inspectEntry(GrpcEngine::class.java.declaredMethods.single { it.name == "a" })
    )

    private class RenamingClassLoader(
        parent: ClassLoader,
        private val templates: Map<String, Class<*>> = emptyMap(),
        private val replacements: Map<String, String> = emptyMap(),
        private val exactReplacements: Map<String, String> = emptyMap(),
        private val hiddenNames: Set<String> = emptySet(),
        private val hiddenPrefixes: Set<String> = emptySet()
    ) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            if (name in hiddenNames || hiddenPrefixes.any(name::startsWith)) {
                throw ClassNotFoundException(name)
            }
            val template = templates[name] ?: return@synchronized super.loadClass(name, resolve)
            val loaded = findLoadedClass(name) ?: run {
                val raw = requireNotNull(
                    template.getResourceAsStream("/${template.name.replace('.', '/')}.class")
                ).use { it.readBytes() }
                val map = replacements + (template.name.replace('.', '/') to name.replace('.', '/'))
                val bytes = rewriteConstantPool(raw, map, exactReplacements)
                defineClass(name, bytes, 0, bytes.size)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }

        private fun rewriteConstantPool(
            raw: ByteArray,
            map: Map<String, String>,
            exactMap: Map<String, String>
        ): ByteArray {
            val input = DataInputStream(ByteArrayInputStream(raw))
            val outBuffer = ByteArrayOutputStream()
            val output = DataOutputStream(outBuffer)
            output.writeInt(input.readInt())
            output.writeShort(input.readUnsignedShort())
            output.writeShort(input.readUnsignedShort())
            val count = input.readUnsignedShort()
            output.writeShort(count)
            var index = 1
            while (index < count) {
                val tag = input.readUnsignedByte()
                output.writeByte(tag)
                if (tag == 1) {
                    var value = input.readUTF()
                    val exact = exactMap[value]
                    if (exact != null) {
                        value = exact
                    } else {
                        map.entries.sortedByDescending { it.key.length }.forEach { (k, v) ->
                            value = value.replace(k, v)
                        }
                    }
                    output.writeUTF(value)
                } else {
                    val length = when (tag) {
                        3, 4, 9, 10, 11, 12, 17, 18 -> 4
                        5, 6 -> 8
                        7, 8, 16, 19, 20 -> 2
                        15 -> 3
                        else -> error("Unsupported CP tag: $tag")
                    }
                    repeat(length) { output.writeByte(input.readUnsignedByte()) }
                    if (tag == 5 || tag == 6) index++
                }
                index++
            }
            output.write(input.readBytes())
            output.flush()
            return outBuffer.toByteArray()
        }
    }

    // -------------------------------------------------------------------------
    // 1. KmpGrpcWireChannel installOrDefer + KotlinMossChannel supersededBy
    // -------------------------------------------------------------------------

    @Test
    fun `KmpGrpcWireChannel installOrDefer and KotlinMossChannel supersededBy prevent double execution after bindDeferred`() {
        val loader = javaClass.classLoader!!
        val registrar = PlayerPortTestRegistrar()
        val environment = env(loader, registrar)

        val wireActive = AtomicBoolean(false)
        val transformCalls = AtomicInteger(0)
        val transformJava: (Any) -> Any = { reply ->
            transformCalls.incrementAndGet()
            val typed = reply as P58_DirectJavaProtoReply
            if (typed.label == "ad") P58_DirectJavaProtoReply("purified") else typed
        }

        val immediateInstalled = KmpGrpcWireChannel.installOrDefer(
            environment = environment,
            surface = null,
            javaMossClassName = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss",
            rpc = "mainList",
            javaReplyClass = P58_DirectJavaProtoReply::class.java,
            what = "direct-superseded-check",
            logKey = "p5_8_direct",
            wireActive = wireActive,
            transformJava = transformJava
        )
        assertFalse(immediateInstalled)
        assertFalse(wireActive.get())
        assertFalse("kmp.grpc.wire.channel" in registrar.hooks)

        val bridge = requireNotNull(KotlinMossChannel.prepare(environment, loader, "direct", "p5_8_direct"))
        val kReplyMainList = KReplyMoss::class.java.declaredMethods.single { it.name == "mainList" }
        assertTrue(
            KotlinMossChannel.install(
                environment = environment,
                loader = loader,
                members = bridge,
                javaMossClassName = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss",
                rpc = "mainList",
                javaReplyClass = P58_DirectJavaProtoReply::class.java,
                hookId = "p5_8.kmoss.mainList",
                what = "direct-superseded-check",
                logKey = "p5_8_direct",
                resolvedEntry = kReplyMainList,
                supersededBy = wireActive,
                transformJava = transformJava
            )
        )

        var kmossDelivered: Any? = null
        val kmossSink = object : KReplyMoss.Handler {
            override fun onNext(reply: Any?) {
                kmossDelivered = reply
            }
        }
        registrar.invoke(
            "p5_8.kmoss.mainList",
            KReplyMoss(),
            arrayOf("req", P58_DummyStrategy, P58_DummyStrategy, kmossSink, ProtoBuf())
        ) { args ->
            (args[3] as KReplyMoss.Handler).onNext("ad")
            args[3]
        }
        assertEquals("K:purified", kmossDelivered)
        assertEquals(1, transformCalls.get())

        assertEquals(1, KmpGrpcWireChannel.bindDeferred(environment, wireSurface()))
        assertTrue(wireActive.get())
        assertTrue("kmp.grpc.wire.channel" in registrar.hooks)

        // Post-bind: KotlinMoss callback skips wrapping and never invokes transformJava again.
        kmossDelivered = null
        val skippedHandler = registrar.invoke(
            "p5_8.kmoss.mainList",
            KReplyMoss(),
            arrayOf("req", P58_DummyStrategy, P58_DummyStrategy, kmossSink, ProtoBuf())
        ) { args ->
            (args[3] as KReplyMoss.Handler).onNext("ad")
            args[3]
        }
        assertSame(kmossSink, skippedHandler)
        assertEquals("ad", kmossDelivered)
        assertEquals(1, transformCalls.get())

        // Wire channel handles the RPC once.
        var wireReceived: ByteArray? = null
        val wireSink = object : Handler {
            override fun onNext(bytes: ByteArray?) {
                wireReceived = bytes
            }
            override fun onCompleted() = Unit
            override fun onError(error: KMossException) = Unit
        }
        val descriptor = Descriptor("bilibili.main.community.reply.v1", "Reply", "MainList", String::class, P58_DummyStrategy)
        registrar.invoke(
            "kmp.grpc.wire.channel",
            GrpcEngine(emptyList()),
            arrayOf(Service("grpc.biliapi.net"), descriptor, ByteArray(0), wireSink, null)
        ) { args ->
            (args[3] as Handler).onNext(P58_DirectJavaProtoReply("ad").toByteArray())
            null
        }
        assertEquals("purified", P58_DirectJavaProtoReply.parseFrom(requireNotNull(wireReceived)).label)
        assertEquals(2, transformCalls.get())

        // Real consumer wiring: DetailModulePurifyFeatureInstaller defers Wire & yields KotlinMoss on bindDeferred.
        val detailLoader = RenamingClassLoader(
            loader,
            templates = mapOf(
                "com.bapis.bilibili.app.view.v1.KViewMoss" to P58_FakeKViewMossTemplate::class.java,
                "com.bapis.bilibili.app.view.v1.KViewMoss\$Handler" to P58_FakeKViewMossTemplate.Handler::class.java
            ),
            replacements = mapOf(
                P58_FakeKViewMossTemplate::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/view/v1/KViewMoss",
                P58_FakeKViewMossTemplate.Handler::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/view/v1/KViewMoss\$Handler"
            ),
            hiddenNames = setOf("com.bapis.bilibili.app.view.v1.ViewMoss")
        )
        val detailRegistrar = PlayerPortTestRegistrar()
        val detailEnv = env(detailLoader, detailRegistrar)
        val detailResult = DetailModulePurifyFeatureInstaller(
            enabledKeys = setOf(FeaturePreferences.REMOVE_DETAIL_HONOR),
            kmpGrpcWireEngine = null
        ).install(detailEnv)
        assertTrue(
            "Expected 1 KMP hook installed: $detailResult",
            detailResult is FeatureInstallResult.Installed && detailResult.hookCount == 1
        )
        val detailKmossId = "detail_module_purify.kmoss.view"
        assertTrue(detailKmossId in detailRegistrar.hooks)
        assertFalse("kmp.grpc.wire.channel" in detailRegistrar.hooks)

        val kViewHandlerClass = detailLoader.loadClass("com.bapis.bilibili.app.view.v1.KViewMoss\$Handler")
        val rawKViewHandler = java.lang.reflect.Proxy.newProxyInstance(
            detailLoader,
            arrayOf(kViewHandlerClass)
        ) { _, _, _ -> null }
        val kViewMossInstance = detailLoader.loadClass("com.bapis.bilibili.app.view.v1.KViewMoss")
            .getDeclaredConstructor().newInstance()
        val preBindReturned = detailRegistrar.invoke(
            detailKmossId,
            kViewMossInstance,
            arrayOf("req", P58_DummyStrategy, P58_DummyStrategy, rawKViewHandler, ProtoBuf())
        ) { args -> args[3] }
        assertNotSame(rawKViewHandler, preBindReturned)

        assertEquals(1, KmpGrpcWireChannel.bindDeferred(detailEnv, wireSurface()))
        val postBindReturned = detailRegistrar.invoke(
            detailKmossId,
            kViewMossInstance,
            arrayOf("req", P58_DummyStrategy, P58_DummyStrategy, rawKViewHandler, ProtoBuf())
        ) { args -> args[3] }
        assertSame(rawKViewHandler, postBindReturned)

        var detailWireReply: ByteArray? = null
        val detailWireSink = object : Handler {
            override fun onNext(bytes: ByteArray?) {
                detailWireReply = bytes
            }
            override fun onCompleted() = Unit
            override fun onError(error: KMossException) = Unit
        }
        val viewDescriptor = Descriptor("bilibili.app.view.v1", "View", "View", String::class, P58_DummyStrategy)
        detailRegistrar.invoke(
            "kmp.grpc.wire.channel",
            GrpcEngine(emptyList()),
            arrayOf(Service("grpc.biliapi.net"), viewDescriptor, ByteArray(0), detailWireSink, null)
        ) { args ->
            (args[3] as Handler).onNext(ViewReply(setOf("Honor", "Label")).toByteArray())
            null
        }
        val decodedView = ViewReply.parseFrom(requireNotNull(detailWireReply))
        assertFalse(decodedView.hasHonor())
        assertTrue(decodedView.hasLabel())
    }

    // -------------------------------------------------------------------------
    // 2. DanmakuPurifyFeatureInstaller raw-bytes wire filter without Java protobuf roundtrip
    // -------------------------------------------------------------------------

    @Test
    fun `DanmakuPurifyFeatureInstaller raw-bytes installRawBytes and deferRawBytes filter bytes via purifyWireBytes without Java protobuf roundtrip`() {
        val baseTemplates = mapOf(
            "com.bapis.bilibili.community.service.dm.v1.DmSegMobileReply" to P58_TrackableDmReply::class.java,
            "com.bapis.bilibili.community.service.dm.v1.DmSegMobileReply\$Builder" to P58_TrackableDmReply.Builder::class.java,
            "com.bapis.bilibili.community.service.dm.v1.DmSegMobileReply\$Companion" to P58_TrackableDmReply.Companion::class.java,
            "com.bapis.bilibili.community.service.dm.v1.DanmakuElem" to P58_TrackableDmElem::class.java,
            "com.bapis.bilibili.community.service.dm.v1.DanmakuElem\$Builder" to P58_TrackableDmElem.Builder::class.java,
            "com.bapis.bilibili.community.service.dm.v1.DanmakuElem\$Companion" to P58_TrackableDmElem.Companion::class.java,
            "com.bapis.bilibili.community.service.dm.v1.DmColorful" to P58_TrackableDmColorful::class.java,
            "com.bapis.bilibili.community.service.dm.v1.DmColorful\$Builder" to P58_TrackableDmColorful.Builder::class.java,
            "com.bapis.bilibili.community.service.dm.v1.DmColorful\$Companion" to P58_TrackableDmColorful.Companion::class.java,
            "com.bapis.bilibili.community.service.dm.v1.DmColorfulType" to P58_TrackableDmColorfulType::class.java
        )
        val baseReplacements = mapOf(
            P58_TrackableDmReply.Builder::class.java.name.replace('.', '/') to "com/bapis/bilibili/community/service/dm/v1/DmSegMobileReply\$Builder",
            P58_TrackableDmReply.Companion::class.java.name.replace('.', '/') to "com/bapis/bilibili/community/service/dm/v1/DmSegMobileReply\$Companion",
            P58_TrackableDmReply::class.java.name.replace('.', '/') to "com/bapis/bilibili/community/service/dm/v1/DmSegMobileReply",
            P58_TrackableDmElem.Builder::class.java.name.replace('.', '/') to "com/bapis/bilibili/community/service/dm/v1/DanmakuElem\$Builder",
            P58_TrackableDmElem.Companion::class.java.name.replace('.', '/') to "com/bapis/bilibili/community/service/dm/v1/DanmakuElem\$Companion",
            P58_TrackableDmElem::class.java.name.replace('.', '/') to "com/bapis/bilibili/community/service/dm/v1/DanmakuElem",
            P58_TrackableDmColorful.Builder::class.java.name.replace('.', '/') to "com/bapis/bilibili/community/service/dm/v1/DmColorful\$Builder",
            P58_TrackableDmColorful.Companion::class.java.name.replace('.', '/') to "com/bapis/bilibili/community/service/dm/v1/DmColorful\$Companion",
            P58_TrackableDmColorful::class.java.name.replace('.', '/') to "com/bapis/bilibili/community/service/dm/v1/DmColorful",
            P58_TrackableDmColorfulType::class.java.name.replace('.', '/') to "com/bapis/bilibili/community/service/dm/v1/DmColorfulType"
        )

        val sampleWireBytes = P58_DmWireCodec.encodeReply(
            elems = listOf(
                P58_SampleElem(weight = 2, colorful = 0, content = "low"),
                P58_SampleElem(weight = 8, colorful = 60001, content = "vip"),
                P58_SampleElem(weight = 6, colorful = 12, content = "normal")
            ),
            colorfulSrcTypes = listOf(60001),
            flags = 77L
        )

        // Case A: direct installRawBytes with wire engine resolved upfront.
        val wireOnlyLoader = RenamingClassLoader(
            javaClass.classLoader!!,
            templates = baseTemplates,
            replacements = baseReplacements,
            hiddenNames = setOf(
                "com.bapis.bilibili.community.service.dm.v1.DMMoss",
                "com.bapis.bilibili.community.service.dm.v1.KDMMoss"
            )
        )
        val wireRegistrar = PlayerPortTestRegistrar()
        val wireEvidence = mutableListOf<Pair<String, FeatureRuntimeStage>>()
        val wireLogs = mutableListOf<String>()
        val wireErrors = mutableListOf<String>()
        P58_DmRoundtripCounters.reset()
        val wireResult = DanmakuPurifyFeatureInstaller(
            weightFilterEnabled = true,
            minimumWeight = 5,
            removeVipColorful = true,
            kmpGrpcWireEngine = wirePoint()
        ).install(env(wireOnlyLoader, wireRegistrar, wireEvidence, wireLogs, wireErrors))
        assertEquals(FeatureInstallResult.Installed(4, complete = true), wireResult)
        assertTrue("Expected DanmakuWirePlan install-time probe to run; errors=$wireErrors", P58_DmRoundtripCounters.parseFromCalls.get() > 0)
        assertTrue("kmp.grpc.wire.channel" in wireRegistrar.hooks)

        P58_DmRoundtripCounters.reset()
        var directOutBytes: ByteArray? = null
        val directSink = object : Handler {
            override fun onNext(bytes: ByteArray?) {
                directOutBytes = bytes
            }
            override fun onCompleted() = Unit
            override fun onError(error: KMossException) = Unit
        }
        val dmDescriptor = Descriptor("bilibili.community.service.dm.v1", "DM", "DmSegMobile", String::class, P58_DummyStrategy)
        wireRegistrar.invoke(
            "kmp.grpc.wire.channel",
            GrpcEngine(emptyList()),
            arrayOf(Service("grpc.biliapi.net"), dmDescriptor, ByteArray(0), directSink, null)
        ) { args ->
            (args[3] as Handler).onNext(sampleWireBytes)
            null
        }
        val decodedDirect = P58_DmWireCodec.decodeReply(requireNotNull(directOutBytes))
        assertEquals(
            "Direct wire filter mismatch; logs=$wireLogs errors=$wireErrors evidence=$wireEvidence",
            listOf(8, 6),
            decodedDirect.elems.map { it.weight }
        )
        assertEquals(listOf(0, 12), decodedDirect.elems.map { it.colorful })
        assertTrue(decodedDirect.colorfulSrcTypes.isEmpty())
        assertEquals(77L, decodedDirect.flags)
        assertEquals(0, P58_DmRoundtripCounters.parseFromCalls.get())
        assertEquals(0, P58_DmRoundtripCounters.toByteArrayCalls.get())
        assertTrue(wireEvidence.any { it.first == DanmakuPurifyFeatureInstaller.ID && it.second == FeatureRuntimeStage.APPLIED })

        // Case B: deferred raw-bytes registration (kmpGrpcWireEngine = null) + KotlinMoss fallback yielding after bindDeferred.
        val deferredLoader = RenamingClassLoader(
            javaClass.classLoader!!,
            templates = baseTemplates + mapOf(
                "com.bapis.bilibili.community.service.dm.v1.KDMMoss" to P58_FakeKDmMossTemplate::class.java,
                "com.bapis.bilibili.community.service.dm.v1.KDMMoss\$Handler" to P58_FakeKDmMossTemplate.Handler::class.java
            ),
            replacements = baseReplacements + mapOf(
                P58_FakeKDmMossTemplate::class.java.name.replace('.', '/') to "com/bapis/bilibili/community/service/dm/v1/KDMMoss",
                P58_FakeKDmMossTemplate.Handler::class.java.name.replace('.', '/') to "com/bapis/bilibili/community/service/dm/v1/KDMMoss\$Handler"
            ),
            hiddenNames = setOf("com.bapis.bilibili.community.service.dm.v1.DMMoss")
        )
        val deferredRegistrar = PlayerPortTestRegistrar()
        val deferredLogs = mutableListOf<String>()
        val deferredErrors = mutableListOf<String>()
        val deferredEnv = env(deferredLoader, deferredRegistrar, logs = deferredLogs, errors = deferredErrors)
        val deferredResult = DanmakuPurifyFeatureInstaller(
            weightFilterEnabled = true,
            minimumWeight = 5,
            removeVipColorful = true,
            kmpGrpcWireEngine = null
        ).install(deferredEnv)
        assertEquals(FeatureInstallResult.Installed(4, complete = true), deferredResult)
        assertTrue("danmaku.purify.kmoss.dmSegMobile" in deferredRegistrar.hooks)
        assertTrue("danmaku.purify.kmoss.dmSegCache" in deferredRegistrar.hooks)
        assertFalse("kmp.grpc.wire.channel" in deferredRegistrar.hooks)

        val kDmHandlerClass = deferredLoader.loadClass("com.bapis.bilibili.community.service.dm.v1.KDMMoss\$Handler")
        val rawKDmHandler = java.lang.reflect.Proxy.newProxyInstance(
            deferredLoader,
            arrayOf(kDmHandlerClass)
        ) { _, _, _ -> null }
        val kDmInstance = deferredLoader.loadClass("com.bapis.bilibili.community.service.dm.v1.KDMMoss")
            .getDeclaredConstructor().newInstance()
        val preBindProxy = deferredRegistrar.invoke(
            "danmaku.purify.kmoss.dmSegMobile",
            kDmInstance,
            arrayOf("req", P58_DummyStrategy, P58_DummyStrategy, rawKDmHandler, ProtoBuf())
        ) { args -> args[3] }
        assertNotSame(rawKDmHandler, preBindProxy)

        assertEquals(2, KmpGrpcWireChannel.bindDeferred(deferredEnv, wireSurface()))
        assertTrue("kmp.grpc.wire.channel" in deferredRegistrar.hooks)

        val postBindHandler = deferredRegistrar.invoke(
            "danmaku.purify.kmoss.dmSegMobile",
            kDmInstance,
            arrayOf("req", P58_DummyStrategy, P58_DummyStrategy, rawKDmHandler, ProtoBuf())
        ) { args -> args[3] }
        assertSame(rawKDmHandler, postBindHandler)

        P58_DmRoundtripCounters.reset()
        var deferredOutBytes: ByteArray? = null
        val deferredSink = object : Handler {
            override fun onNext(bytes: ByteArray?) {
                deferredOutBytes = bytes
            }
            override fun onCompleted() = Unit
            override fun onError(error: KMossException) = Unit
        }
        deferredRegistrar.invoke(
            "kmp.grpc.wire.channel",
            GrpcEngine(emptyList()),
            arrayOf(Service("grpc.biliapi.net"), dmDescriptor, ByteArray(0), deferredSink, null)
        ) { args ->
            (args[3] as Handler).onNext(sampleWireBytes)
            null
        }
        val decodedDeferred = P58_DmWireCodec.decodeReply(requireNotNull(deferredOutBytes))
        assertEquals(
            "Deferred wire filter mismatch; logs=$deferredLogs errors=$deferredErrors",
            listOf(8, 6),
            decodedDeferred.elems.map { it.weight }
        )
        assertEquals(listOf(0, 12), decodedDeferred.elems.map { it.colorful })
        assertTrue(decodedDeferred.colorfulSrcTypes.isEmpty())
        assertEquals(77L, decodedDeferred.flags)
        assertEquals(0, P58_DmRoundtripCounters.parseFromCalls.get())
        assertEquals(0, P58_DmRoundtripCounters.toByteArrayCalls.get())
    }

    // -------------------------------------------------------------------------
    // 3. FollowFeedFeatureInstaller & HostVideoCardStyleFeatureInstaller retry
    // -------------------------------------------------------------------------

    @Test
    fun `FollowFeedFeatureInstaller updateAdaptedPoints and HostVideoCardStyleFeatureInstaller updateAdaptedPoints perform non-duplicating incremental retry`() {
        val followLoader = RenamingClassLoader(
            javaClass.classLoader!!,
            templates = mapOf(
                "renamed.follow.DriftPainter" to P58_FollowDriftPainter::class.java,
                "renamed.follow.DriftHome" to P58_FollowDriftHome::class.java,
                "renamed.follow.DriftModule" to P58_FollowDriftModule::class.java,
                "renamed.follow.DriftItem" to P58_FollowDriftItem::class.java,
                FollowFeedAdapter.FRAGMENT to P58_FollowMainFragment::class.java
            ),
            replacements = mapOf(
                P58_FollowDriftPainter::class.java.name.replace('.', '/') to "renamed/follow/DriftPainter",
                P58_FollowDriftHome::class.java.name.replace('.', '/') to "renamed/follow/DriftHome",
                P58_FollowDriftModule::class.java.name.replace('.', '/') to "renamed/follow/DriftModule",
                P58_FollowDriftItem::class.java.name.replace('.', '/') to "renamed/follow/DriftItem",
                P58_FollowMainFragment::class.java.name.replace('.', '/') to FollowFeedAdapter.FRAGMENT.replace('.', '/')
            ),
            hiddenNames = setOf(
                "rU.d", "sU.d", "ey80", "mU.d", "gv1.c", "iU.d",
                "com.bilibili.bplus.followinglist.quick.consume.VideoQuickConsumeFragment"
            )
        )
        val cachedFollowPoints = FollowFeedPoints(
            painterOwnerClass = "renamed.follow.DriftPainter",
            painterMethod = "paint",
            moduleClass = "renamed.follow.DriftModule",
            itemClass = "renamed.follow.DriftItem",
            homeDecorationClass = "renamed.follow.DriftHome",
            modelFamily = "current"
        )
        assertFalse(FollowFeedFeatureInstaller(enabled = false, points = null).updateAdaptedPoints(cachedFollowPoints))
        assertFalse(FollowFeedFeatureInstaller(enabled = true, points = cachedFollowPoints).updateAdaptedPoints(cachedFollowPoints))

        val followRegistrar = PlayerPortTestRegistrar()
        val followEnv = env(followLoader, followRegistrar)
        val followInstaller = FollowFeedFeatureInstaller(enabled = true, points = null)
        assertEquals(FeatureInstallResult.Skipped("missing-follow-contract"), followInstaller.install(followEnv))
        assertTrue(followInstaller.updateAdaptedPoints(cachedFollowPoints))

        val followResult = followInstaller.install(followEnv)
        assertTrue(followResult is FeatureInstallResult.Installed && followResult.hookCount > 0)
        val initialFollowAttemptCount = followRegistrar.registrationAttempts.size
        assertTrue(initialFollowAttemptCount > 0)

        assertFalse(followInstaller.updateAdaptedPoints(cachedFollowPoints))
        assertEquals(followResult, followInstaller.install(followEnv))
        assertEquals(initialFollowAttemptCount, followRegistrar.registrationAttempts.size)

        // HostVideoCardStyleFeatureInstaller incremental divider retry without duplicating .bind/.measure.
        val pegasusLoader = RenamingClassLoader(
            javaClass.classLoader!!,
            templates = mapOf("com.bilibili.pegasus.PegasusFragment" to P58_DummyPegasusFragment::class.java),
            replacements = mapOf(P58_DummyPegasusFragment::class.java.name.replace('.', '/') to "com/bilibili/pegasus/PegasusFragment"),
            hiddenNames = setOf("com.bilibili.pegasus.widget.j", "com.bilibili.pegasus.widget.l")
        )
        val cardRegistrar = PlayerPortTestRegistrar()
        val cardEnv = env(pegasusLoader, cardRegistrar)
        val shared = HostVideoCardStyleFeatureInstaller.shared(
            enabled = true,
            radiusDp = 12,
            backgroundConfig = HostBackgroundConfig(preset = HostBackgroundPreset.MIST),
            material = HostCardMaterial.ORIGINAL,
            cardSurfaces = emptyMap(),
            followFeedPoints = null
        )
        val recordedByFeature = linkedMapOf<String, FeatureInstallRecord>()
        val cardCoordinator = FeatureInstallCoordinator(
            cardEnv.copy(installationEvidence = { record -> recordedByFeature[record.id] = record })
        )
        cardCoordinator.installAll(shared)
        val initialCardResult = recordedByFeature.getValue(HostVideoCardStyleFeatureInstaller.ID).result as FeatureInstallResult.Installed
        val initialBgResult = recordedByFeature.getValue(HostVideoCardStyleFeatureInstaller.BACKGROUND_ID).result as FeatureInstallResult.Installed
        assertEquals(1, cardRegistrar.registrationAttempts.count { it.first == "${HostVideoCardStyleFeatureInstaller.ID}.bind" })
        assertEquals(0, cardRegistrar.registrationAttempts.count { it.first == "${HostVideoCardStyleFeatureInstaller.ID}.pegasus.divider" })
        val initialBindMeasureCounts = cardRegistrar.registrationAttempts.groupingBy { it.first }.eachCount()

        val pegasusPoint = HookPoint(
            "com.bilibili.pegasus.widget.RenamedDivider",
            "onDrawOver",
            listOf(
                "android.graphics.Canvas",
                "androidx.recyclerview.widget.RecyclerView",
                "androidx.recyclerview.widget.RecyclerView\$State"
            )
        )
        val retryTargets = HostVideoCardStyleFeatureInstaller.updateAdaptedPoints(
            shared,
            mapOf("pegasus_divider" to pegasusPoint),
            newFollowPoints = cachedFollowPoints
        )
        assertEquals(2, retryTargets.size)

        cardCoordinator.installAll(retryTargets)
        val retryCardResult = recordedByFeature.getValue(HostVideoCardStyleFeatureInstaller.ID).result as FeatureInstallResult.Installed
        val retryBgResult = recordedByFeature.getValue(HostVideoCardStyleFeatureInstaller.BACKGROUND_ID).result as FeatureInstallResult.Installed
        assertEquals(initialCardResult.hookCount + 1, retryCardResult.hookCount)
        assertTrue(retryCardResult.complete)
        assertTrue(retryBgResult.hookCount >= initialBgResult.hookCount)
        assertTrue(retryBgResult.complete)
        assertEquals(1, cardRegistrar.registrationAttempts.count { it.first == "${HostVideoCardStyleFeatureInstaller.ID}.pegasus.divider" })
        for ((hookId, count) in initialBindMeasureCounts) {
            assertEquals("Hook $hookId must not be duplicated", count, cardRegistrar.registrationAttempts.count { it.first == hookId })
        }

        assertTrue(
            HostVideoCardStyleFeatureInstaller.updateAdaptedPoints(
                shared,
                mapOf("pegasus_divider" to pegasusPoint),
                newFollowPoints = cachedFollowPoints
            ).isEmpty()
        )
        assertEquals(retryCardResult, shared[0].install(cardEnv))
        assertEquals(retryBgResult, shared[1].install(cardEnv))
    }

    @Test
    fun `CommentPurify retry installs newly adapted points without clobbering sibling capabilities`() {
        val loader = RenamingClassLoader(
            javaClass.classLoader!!,
            hiddenNames = setOf(
                "com.bapis.bilibili.main.community.reply.v1.ReplyMoss",
                "com.bapis.bilibili.main.community.reply.v1.KReplyMoss"
            )
        )
        val baseRegistrar = PlayerPortTestRegistrar()
        val adaptedAttempts = mutableListOf<String>()
        val registrar = object : com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar by baseRegistrar {
            override fun adapted(
                id: String,
                point: HookPoint,
                exceptionPolicy: com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy,
                block: com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator.() -> Unit
            ) {
                adaptedAttempts += id
                baseRegistrar.adapted(id, point, exceptionPolicy, block)
            }
        }
        val records = linkedMapOf<String, FeatureInstallRecord>()
        val coordinator = FeatureInstallCoordinator(
            env(loader, registrar).copy(installationEvidence = { records[it.id] = it })
        )
        val adaptedRef = AtomicReference<VersionAdapter.CommentPurifyPoints?>(null)
        val wireRef = AtomicReference<HookPoint?>(null)
        val installer = CommentPurifyFeatureInstaller(
            removeSearchLinks = false,
            removeEmptyGuide = false,
            removeVoteWidgets = true,
            removeFollowButtons = false,
            removeQoe = false,
            removeOperations = false,
            blockQuickReply = true,
            points = null,
            pointsProvider = { adaptedRef.get() },
            kmpGrpcWireEngineProvider = { wireRef.get() }
        )

        coordinator.installAll(listOf(installer))
        assertTrue(installer.requiresAdaptationRetry)
        assertTrue(records[CommentPurifyFeatureInstaller.ID]?.result is FeatureInstallResult.Skipped)

        adaptedRef.set(
            VersionAdapter.CommentPurifyPoints(
                urlMapGetters = emptyList(),
                emptyPageGetters = emptyList(),
                voteWidgetMethods = listOf(HookPoint(P58_FakeVoteWidget::class.java.name, "bind", emptyList())),
                follow = null,
                qoe = null,
                operations = emptyList(),
                quickReplyDialogMethods = emptyList()
            )
        )
        wireRef.set(wirePoint())
        coordinator.installAll(listOf(installer))
        val parentAfterPoints = records[CommentPurifyFeatureInstaller.ID]?.result
        assertTrue("Expected parent installed on retry: $parentAfterPoints", parentAfterPoints is FeatureInstallResult.Installed)
        val voteCap = records["comments_vote_widgets_removed"]?.result
        assertTrue("Expected vote capability installed on retry: $voteCap", voteCap is FeatureInstallResult.Installed)
        assertEquals(1, adaptedAttempts.count { it == "comment.purify.vote.0" })

        // Subsequent retry when baseInstalled is true must preserve parent Installed and sibling capability state.
        coordinator.installAll(listOf(installer))
        assertTrue(records[CommentPurifyFeatureInstaller.ID]?.result is FeatureInstallResult.Installed)
        assertTrue(records["comments_vote_widgets_removed"]?.result is FeatureInstallResult.Installed)
        assertEquals(1, adaptedAttempts.count { it == "comment.purify.vote.0" })
    }

    @Test
    fun `AiInsightFeatureInstaller reads live kmpGrpcWireEngineProvider dynamically`() {
        val reqName = "com.bapis.bilibili.main.community.reply.v1.MainListReq"
        val replyName = "com.bapis.bilibili.main.community.reply.v1.MainListReply"
        val loader = RenamingClassLoader(
            javaClass.classLoader!!,
            templates = mapOf(
                reqName to P58_InsightMainListReq::class.java,
                "$reqName\$Companion" to P58_InsightMainListReq.Companion::class.java,
                replyName to P58_InsightMainListReply::class.java,
                "$replyName\$Companion" to P58_InsightMainListReply.Companion::class.java
            ),
            replacements = mapOf(
                P58_InsightMainListReq::class.java.name.replace('.', '/') to reqName.replace('.', '/'),
                P58_InsightMainListReply::class.java.name.replace('.', '/') to replyName.replace('.', '/')
            ),
            hiddenNames = setOf(
                "com.bapis.bilibili.main.community.reply.v1.ReplyMoss",
                "com.bapis.bilibili.main.community.reply.v1.KReplyMoss"
            )
        )
        val registrar = PlayerPortTestRegistrar()
        val environment = env(loader, registrar)
        val liveWire = AtomicReference<HookPoint?>(null)
        val installer = AiInsightFeatureInstaller(
            enabled = true,
            automaticChapters = false,
            source = null,
            cacheRoot = Files.createTempDirectory("bil-insight-wire-test").toFile().apply { deleteOnExit() },
            kmpGrpcWireEngine = null,
            kmpGrpcWireEngineProvider = { liveWire.get() }
        )
        val installComments = AiInsightFeatureInstaller::class.java.declaredMethods
            .single { it.name == "installComments" }
            .apply { isAccessible = true }
        val snapshot = InsightHostSnapshot()

        assertEquals(0, installComments.invoke(installer, environment, snapshot))
        assertFalse("kmp.grpc.wire.channel" in registrar.hooks)

        liveWire.set(wirePoint())
        assertEquals(1, installComments.invoke(installer, environment, snapshot))
        assertTrue("kmp.grpc.wire.channel" in registrar.hooks)
    }

    // -------------------------------------------------------------------------
    // 4. VersionAdapter guestBrowsingEnabled, splashAutoNightEnabled, versionCodeCompat, failedDexReason
    // -------------------------------------------------------------------------

    private fun createFakePackageManager(hostPackageInfo: PackageInfo): PackageManager {
        val className = "test.pm.FakePackageManager"
        val pmLoader = RenamingClassLoader(
            javaClass.classLoader!!,
            templates = mapOf(className to P58_FakePackageManagerTemplate::class.java),
            replacements = mapOf(
                P58_FakePackageManagerTemplate::class.java.name.replace('.', '/') to className.replace('.', '/')
            ),
            exactReplacements = mapOf("java/lang/Object" to "android/content/pm/PackageManager")
        )
        return pmLoader.loadClass(className)
            .getDeclaredConstructor(PackageInfo::class.java)
            .newInstance(hostPackageInfo) as PackageManager
    }

    private fun fakeHostContext(apkFile: File, cacheDir: File, versionCode: Int, versionName: String): Context {
        @Suppress("DEPRECATION")
        val pkgInfo = PackageInfo().apply {
            packageName = "tv.danmaku.bili"
            this.versionCode = versionCode
            this.versionName = versionName
            applicationInfo = ApplicationInfo().apply {
                packageName = "tv.danmaku.bili"
                sourceDir = apkFile.absolutePath
            }
        }
        val pm = createFakePackageManager(pkgInfo)
        return object : ContextWrapper(null) {
            override fun getPackageManager(): PackageManager = pm
            override fun getCacheDir(): File = cacheDir
        }
    }

    private fun invokeVersionAdapterAdapt(
        context: Context,
        loader: ClassLoader,
        followFeedStyleEnabled: Boolean,
        playerSpeedSessionEnabled: Boolean,
        guestBrowsingEnabled: Boolean,
        dexSource: DexSourceFingerprint.Result?,
        vc: Int,
        hostFingerprint: String,
        splashAutoNightEnabled: Boolean
    ): AdaptResult {
        val adaptMethod = VersionAdapter::class.java.declaredMethods.single { it.name == "adapt" }
            .apply { isAccessible = true }
        val types = adaptMethod.parameterTypes
        assertEquals(FollowFeedPoints::class.java, types[12])
        assertEquals(HookPoint::class.java, types[67])
        assertEquals(String::class.java, types[72])
        assertEquals(Boolean::class.javaPrimitiveType, types[112])

        val assistHistoryClass = types.single { it.simpleName == "AssistHistory" }
        val emptyHistory = assistHistoryClass.getDeclaredField("EMPTY").apply { isAccessible = true }.get(null)

        val args = arrayOfNulls<Any>(types.size)
        for (i in types.indices) {
            args[i] = when (types[i]) {
                Boolean::class.javaPrimitiveType -> false
                Set::class.java -> emptySet<Any>()
                Map::class.java -> emptyMap<Any, Any>()
                AdaptPlan::class.java -> AdaptPlan.full()
                assistHistoryClass -> emptyHistory
                else -> null
            }
        }
        args[0] = context
        args[1] = loader
        args[11] = followFeedStyleEnabled
        args[20] = playerSpeedSessionEnabled
        args[71] = guestBrowsingEnabled
        args[109] = dexSource
        args[110] = vc
        args[111] = hostFingerprint
        args[112] = splashAutoNightEnabled
        return adaptMethod.invoke(VersionAdapter, *args) as AdaptResult
    }

    @Test
    fun `VersionAdapter honors guestBrowsingEnabled for privacyIgnetPlanned, splashAutoNightEnabled for brandKntrPage, versionCodeCompat in readHostIdentity, and failedDexReason propagation`() {
        val tmpDir = Files.createTempDirectory("bil-phase5to8-wiring").toFile()
        try {
            val baseApk = File(tmpDir, "base.apk").apply { writeBytes(ByteArray(64)) }
            val cacheDir = File(tmpDir, "cache").apply { mkdirs() }
            val context = fakeHostContext(baseApk, cacheDir, versionCode = 9210200, versionName = "9.21.0")

            val readIdentity = VersionAdapter::class.java.declaredMethods
                .single { it.name == "readHostIdentity" }
                .apply { isAccessible = true }
            val identity = readIdentity.invoke(VersionAdapter, context) as HostIdentity
            assertEquals(9210200, identity.versionCode)
            assertTrue(identity.fingerprint.startsWith("tv.danmaku.bili|9210200|9.21.0|64|"))

            val adapterLoader = RenamingClassLoader(
                javaClass.classLoader!!,
                templates = mapOf(
                    PrivacyChannelAdapter.IGNET_ENGINE to P58_IgnetEngineStub::class.java,
                    "kntr.srcs.app.splash.brand.model.BrandSplashSource" to P58_SplashSourceStub::class.java,
                    PlayerSpeedSessionLocator.PARAMS to P58_SpeedParamsStub::class.java,
                    PlayerSpeedLocator.SPEED_MANAGER to P58_SpeedManagerStub::class.java
                ),
                replacements = mapOf(
                    P58_IgnetEngineStub::class.java.name.replace('.', '/') to PrivacyChannelAdapter.IGNET_ENGINE.replace('.', '/'),
                    P58_SplashSourceStub::class.java.name.replace('.', '/') to "kntr/srcs/app/splash/brand/model/BrandSplashSource",
                    P58_SpeedParamsStub::class.java.name.replace('.', '/') to PlayerSpeedSessionLocator.PARAMS.replace('.', '/'),
                    P58_SpeedManagerStub::class.java.name.replace('.', '/') to PlayerSpeedLocator.SPEED_MANAGER.replace('.', '/')
                ),
                hiddenNames = setOf(
                    PrivacyChannelAdapter.IGNET_REQUEST,
                    PlayerSpeedSessionLocator.RUN,
                    "kntr.srcs.app.splash.brand.startup.BrandSplashPageKt",
                    "rU.d", "sU.d", "ey80", "mU.d", "gv1.c", "iU.d"
                )
            )
            val dexSource = DexSourceFingerprint.Result(
                value = "dex-v1:1:" + "a".repeat(64),
                codePaths = listOf(baseApk.absolutePath),
                dexEntryCount = 1
            )

            // 1) Disabled baseline: neither guest nor splashAutoNight enabled -> feature-disabled.
            val baseline = invokeVersionAdapterAdapt(
                context = context,
                loader = adapterLoader,
                followFeedStyleEnabled = false,
                playerSpeedSessionEnabled = false,
                guestBrowsingEnabled = false,
                dexSource = dexSource,
                vc = 9210200,
                hostFingerprint = identity.fingerprint,
                splashAutoNightEnabled = false
            )
            val baseIgnetDiag = baseline.diagnostics.single { it.id == PrivacyChannelAdapter.DIAGNOSTIC_IGNET }
            val basePageDiag = baseline.diagnostics.single { it.id == BrandSplashKntrAdapter.DIAGNOSTIC_PAGE }
            assertEquals(AdaptState.NOT_APPLICABLE, baseIgnetDiag.state)
            assertEquals("feature-disabled", baseIgnetDiag.detail)
            assertEquals(AdaptState.NOT_APPLICABLE, basePageDiag.state)
            assertEquals("feature-disabled", basePageDiag.detail)

            // 2) Enabled: guestBrowsingEnabled plans privacyIgnetHttp, splashAutoNightEnabled plans brandKntrPage,
            // and failedDexReason propagates native_unavailable into speedSessionDiagnostic & followDiagnostic (never conclusive missing).
            val adapted = invokeVersionAdapterAdapt(
                context = context,
                loader = adapterLoader,
                followFeedStyleEnabled = true,
                playerSpeedSessionEnabled = true,
                guestBrowsingEnabled = true,
                dexSource = dexSource,
                vc = 9210200,
                hostFingerprint = identity.fingerprint,
                splashAutoNightEnabled = true
            )
            val ignetDiag = adapted.diagnostics.single { it.id == PrivacyChannelAdapter.DIAGNOSTIC_IGNET }
            val pageDiag = adapted.diagnostics.single { it.id == BrandSplashKntrAdapter.DIAGNOSTIC_PAGE }
            val speedDiag = adapted.diagnostics.single { it.id == PlayerSpeedSessionLocator.DIAGNOSTIC }
            val followDiag = adapted.diagnostics.single { it.id == FollowFeedAdapter.DIAGNOSTIC }

            assertEquals(AdaptState.MISSING, ignetDiag.state)
            assertEquals("native_unavailable", ignetDiag.detail)
            assertEquals(AdaptState.MISSING, pageDiag.state)
            assertEquals("native_unavailable", pageDiag.detail)

            assertEquals(AdaptState.MISSING, speedDiag.state)
            assertEquals("native_unavailable", speedDiag.detail)
            assertFalse(AdaptAssistGate.isConclusiveMissing(speedDiag))

            assertEquals(AdaptState.MISSING, followDiag.state)
            assertEquals("native_unavailable", followDiag.detail)
            assertFalse(AdaptAssistGate.isConclusiveMissing(followDiag))

            // 3) Cache fast-path (ensureAdapted): cached baseline (null Ignet & KntrPage) is reused when both disabled,
            // and triggers refresh when guestBrowsingEnabled = true or splashAutoNightEnabled = true.
            val runningField = VersionAdapter::class.java.getDeclaredField("adaptationRunning").apply { isAccessible = true }
            val runningAtomic = runningField.get(VersionAdapter) as AtomicBoolean
            try {
                runningAtomic.set(false)
                val baselineSnap = StartupCacheSnapshot(identity, 0L, baseline)
                val reuseStarted = AtomicBoolean(false)
                VersionAdapter.ensureAdapted(
                    context = context,
                    classLoader = adapterLoader,
                    resetTimestamp = 0L,
                    callback = object : AdaptCallback {
                        override fun onAdaptStarted() { reuseStarted.set(true) }
                        override fun onAdaptFinished(ok: Boolean) = Unit
                    },
                    startupCache = baselineSnap,
                    guestBrowsingEnabled = false,
                    splashAutoNightEnabled = false
                )
                assertFalse("Baseline cache should hit fast-path when guest & splashAutoNight disabled", reuseStarted.get())

                suspendOrAwaitAdapt(runningAtomic) { latch, cb ->
                    VersionAdapter.ensureAdapted(
                        context = context,
                        classLoader = adapterLoader,
                        resetTimestamp = 0L,
                        callback = cb,
                        startupCache = baselineSnap,
                        guestBrowsingEnabled = true,
                        splashAutoNightEnabled = false
                    )
                    assertTrue(latch.await(5, TimeUnit.SECONDS))
                }

                suspendOrAwaitAdapt(runningAtomic) { latch, cb ->
                    VersionAdapter.ensureAdapted(
                        context = context,
                        classLoader = adapterLoader,
                        resetTimestamp = 0L,
                        callback = cb,
                        startupCache = baselineSnap,
                        guestBrowsingEnabled = false,
                        splashAutoNightEnabled = true
                    )
                    assertTrue(latch.await(5, TimeUnit.SECONDS))
                }
            } finally {
                runningAtomic.set(false)
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    private inline fun suspendOrAwaitAdapt(
        runningAtomic: AtomicBoolean,
        invoke: (CountDownLatch, AdaptCallback) -> Unit
    ) {
        runningAtomic.set(false)
        val started = AtomicBoolean(false)
        val finished = CountDownLatch(1)
        val cb = object : AdaptCallback {
            override fun onAdaptStarted() {
                started.set(true)
            }

            override fun onAdaptFinished(ok: Boolean) {
                finished.countDown()
            }
        }
        invoke(finished, cb)
        assertTrue("Expected ensureAdapted to trigger adapt via requiresFeatureRefresh", started.get())
    }
}

internal object P58_DummyStrategy : SerializationStrategy<Any>, DeserializationStrategy<Any>

internal class P58_DirectJavaProtoReply(@JvmField val label: String) {
    fun toByteArray(): ByteArray = label.toByteArray(Charsets.UTF_8)

    companion object {
        private val DEFAULT = P58_DirectJavaProtoReply("")

        @JvmStatic
        fun getDefaultInstance(): P58_DirectJavaProtoReply = DEFAULT

        @JvmStatic
        fun parseFrom(bytes: ByteArray): P58_DirectJavaProtoReply =
            P58_DirectJavaProtoReply(String(bytes, Charsets.UTF_8).removePrefix("K:"))
    }
}

internal class P58_FakeKViewMossTemplate {
    interface Handler {
        fun onNext(reply: Any?)
    }

    @Suppress("UNUSED_PARAMETER")
    fun view(
        request: Any,
        serializer: SerializationStrategy<Any>,
        decoder: DeserializationStrategy<Any>,
        handler: Handler,
        protoBuf: ProtoBuf
    ) = Unit
}

internal data class P58_SampleElem(val weight: Int, val colorful: Int, val content: String)
internal data class P58_SampleReply(val elems: List<P58_SampleElem>, val colorfulSrcTypes: List<Int>, val flags: Long)

internal object P58_DmWireCodec {
    @JvmStatic
    fun encodeElem(weight: Int, colorful: Int, content: String): ByteArray {
        val out = DanmakuWire.ByteArrayOutput(32)
        if (weight != 0) writeVarintField(out, 8, weight.toLong())
        if (content.isNotEmpty()) writeBytesField(out, 10, content.toByteArray(Charsets.UTF_8))
        if (colorful != 0) writeVarintField(out, 11, colorful.toLong())
        return out.toByteArray()
    }

    @JvmStatic
    fun encodeColorful(type: Int): ByteArray {
        val out = DanmakuWire.ByteArrayOutput(16)
        if (type != 0) writeVarintField(out, 2, type.toLong())
        return out.toByteArray()
    }

    @JvmStatic
    fun encodeReply(elems: List<P58_SampleElem>, colorfulSrcTypes: List<Int>, flags: Long): ByteArray {
        val out = DanmakuWire.ByteArrayOutput(64)
        for (elem in elems) {
            writeBytesField(out, 1, encodeElem(elem.weight, elem.colorful, elem.content))
        }
        for (src in colorfulSrcTypes) {
            writeBytesField(out, 4, encodeColorful(src))
        }
        if (flags != 0L) writeVarintField(out, 15, flags)
        return out.toByteArray()
    }

    private fun readVarint(bytes: ByteArray, cursor: IntArray): Long {
        var pos = cursor[0]
        var v = 0L
        var shift = 0
        while (true) {
            val b = bytes[pos++].toLong() and 0xff
            v = v or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0L) break
            shift += 7
        }
        cursor[0] = pos
        return v
    }

    @JvmStatic
    fun decodeReply(bytes: ByteArray): P58_SampleReply {
        val elems = mutableListOf<P58_SampleElem>()
        val srcs = mutableListOf<Int>()
        var flags = 0L
        val cursor = intArrayOf(0)
        while (cursor[0] < bytes.size) {
            val tag = readVarint(bytes, cursor)
            val field = (tag ushr 3).toInt()
            when (val wt = (tag and 7).toInt()) {
                0 -> {
                    val value = readVarint(bytes, cursor)
                    if (field == 15) flags = value
                }
                2 -> {
                    val len = readVarint(bytes, cursor).toInt()
                    val payload = bytes.copyOfRange(cursor[0], cursor[0] + len)
                    cursor[0] += len
                    when (field) {
                        1 -> elems += decodeElem(payload)
                        4 -> srcs += decodeColorful(payload)
                    }
                }
                else -> error("unsupported wireType=$wt")
            }
        }
        return P58_SampleReply(elems, srcs, flags)
    }

    private fun decodeElem(payload: ByteArray): P58_SampleElem {
        var weight = 0
        var colorful = 0
        var content = ""
        val cursor = intArrayOf(0)
        while (cursor[0] < payload.size) {
            val tag = readVarint(payload, cursor)
            val field = (tag ushr 3).toInt()
            when (val wt = (tag and 7).toInt()) {
                0 -> {
                    val value = readVarint(payload, cursor).toInt()
                    when (field) {
                        8 -> weight = value
                        11 -> colorful = value
                    }
                }
                2 -> {
                    val len = readVarint(payload, cursor).toInt()
                    if (field == 10) content = String(payload, cursor[0], len, Charsets.UTF_8)
                    cursor[0] += len
                }
                else -> error("unsupported elem wireType=$wt")
            }
        }
        return P58_SampleElem(weight, colorful, content)
    }

    private fun decodeColorful(payload: ByteArray): Int {
        var type = 0
        val cursor = intArrayOf(0)
        while (cursor[0] < payload.size) {
            val tag = readVarint(payload, cursor)
            val field = (tag ushr 3).toInt()
            when (val wt = (tag and 7).toInt()) {
                0 -> {
                    val value = readVarint(payload, cursor).toInt()
                    if (field == 2) type = value
                }
                2 -> {
                    val len = readVarint(payload, cursor).toInt()
                    cursor[0] += len
                }
                else -> error("unsupported colorful wireType=$wt")
            }
        }
        return type
    }
}

internal object P58_DmRoundtripCounters {
    @JvmField
    val parseFromCalls = AtomicInteger(0)

    @JvmField
    val toByteArrayCalls = AtomicInteger(0)

    fun reset() {
        parseFromCalls.set(0)
        toByteArrayCalls.set(0)
    }
}

internal class P58_TrackableDmElem(
    private val weight: Int,
    private val colorful: Int,
    private val content: String
) {
    constructor() : this(0, 0, "")

    fun getWeight(): Int = weight
    fun getColorfulValue(): Int = colorful
    fun getContent(): String = content
    fun toByteArray(): ByteArray = P58_DmWireCodec.encodeElem(weight, colorful, content)

    class Builder(original: P58_TrackableDmElem) {
        private var weight = original.weight
        private var colorful = original.colorful
        private var content = original.content
        fun setWeight(value: Int) = apply { weight = value }
        fun setColorfulValue(value: Int) = apply { colorful = value }
        fun setContent(value: String) = apply { content = value }
        fun build() = P58_TrackableDmElem(weight, colorful, content)
    }

    companion object {
        private val DEFAULT = P58_TrackableDmElem()

        @JvmStatic
        fun getDefaultInstance(): P58_TrackableDmElem = DEFAULT

        @JvmStatic
        fun newBuilder(original: P58_TrackableDmElem) = Builder(original)
    }
}

internal class P58_TrackableDmColorful(private val type: Int) {
    constructor() : this(0)

    fun getTypeValue(): Int = type
    fun toByteArray(): ByteArray = P58_DmWireCodec.encodeColorful(type)

    class Builder(original: P58_TrackableDmColorful) {
        private var type = original.type
        fun setTypeValue(value: Int) = apply { type = value }
        fun build() = P58_TrackableDmColorful(type)
    }

    companion object {
        private val DEFAULT = P58_TrackableDmColorful()

        @JvmStatic
        fun getDefaultInstance(): P58_TrackableDmColorful = DEFAULT

        @JvmStatic
        fun newBuilder(original: P58_TrackableDmColorful) = Builder(original)
    }
}

internal object P58_TrackableDmColorfulType {
    @JvmField
    val VipGradualColor_VALUE: Int = 60001
}

internal class P58_TrackableDmReply(
    val elems: List<P58_TrackableDmElem>,
    val colorful: List<P58_TrackableDmColorful>,
    val flags: Long
) {
    constructor() : this(emptyList(), emptyList(), 0L)

    fun getElemsList(): List<P58_TrackableDmElem> = elems
    fun getColorfulSrcList(): List<P58_TrackableDmColorful> = colorful
    fun toByteArray(): ByteArray {
        P58_DmRoundtripCounters.toByteArrayCalls.incrementAndGet()
        return P58_DmWireCodec.encodeReply(
            elems.map { P58_SampleElem(it.getWeight(), it.getColorfulValue(), it.getContent()) },
            colorful.map { it.getTypeValue() },
            flags
        )
    }

    class Builder(original: P58_TrackableDmReply) {
        private var elems = original.elems
        private var colorful = original.colorful
        private val flags = original.flags
        fun clearElems() = apply { elems = emptyList() }
        fun addAllElems(values: Iterable<P58_TrackableDmElem>) = apply { elems = values.toList() }
        fun clearColorfulSrc() = apply { colorful = emptyList() }
        fun addAllColorfulSrc(values: Iterable<P58_TrackableDmColorful>) = apply { colorful = values.toList() }
        fun build() = P58_TrackableDmReply(elems, colorful, flags)
    }

    companion object {
        private val DEFAULT = P58_TrackableDmReply()

        @JvmStatic
        fun getDefaultInstance(): P58_TrackableDmReply = DEFAULT

        @JvmStatic
        fun newBuilder(original: P58_TrackableDmReply) = Builder(original)

        @JvmStatic
        fun parseFrom(bytes: ByteArray): P58_TrackableDmReply {
            P58_DmRoundtripCounters.parseFromCalls.incrementAndGet()
            val decoded = P58_DmWireCodec.decodeReply(bytes)
            return P58_TrackableDmReply(
                decoded.elems.map { P58_TrackableDmElem(it.weight, it.colorful, it.content) },
                decoded.colorfulSrcTypes.map { P58_TrackableDmColorful(it) },
                decoded.flags
            )
        }
    }
}

internal class P58_FakeKDmMossTemplate {
    interface Handler {
        fun onNext(reply: Any?)
    }

    @Suppress("UNUSED_PARAMETER")
    fun dmSegMobile(
        request: Any,
        serializer: SerializationStrategy<Any>,
        decoder: DeserializationStrategy<Any>,
        handler: Handler,
        protoBuf: ProtoBuf
    ) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun dmSegCache(
        request: Any,
        serializer: SerializationStrategy<Any>,
        decoder: DeserializationStrategy<Any>,
        handler: Handler,
        protoBuf: ProtoBuf
    ) = Unit
}

internal class P58_FollowDriftItem {
    @JvmField
    var n: List<Any> = emptyList()

    @JvmField
    var j: P58_FollowDriftItem? = null

    fun a(): Long = 1L
    fun b(): List<Any> = emptyList()
    fun h(): Boolean = false
}

internal class P58_FollowDriftModule {
    @JvmField
    var b: P58_FollowDriftItem? = null

    fun M(): P58_FollowDriftItem = P58_FollowDriftItem()
    fun O(): Boolean = false
}

internal open class P58_FollowDriftPainter : androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
    @Suppress("UNUSED_PARAMETER")
    fun paint(c: Canvas, rv: androidx.recyclerview.widget.RecyclerView, v: View, m: P58_FollowDriftModule) = Unit
}

internal class P58_FollowDriftHome : P58_FollowDriftPainter()

internal class P58_FollowMainFragment {
    fun onViewCreated(view: View, savedInstanceState: Bundle?) = Unit
    fun onDestroyView() = Unit
    fun onPause() = Unit
    fun onResume() = Unit
    fun getView(): View? = null
}

internal class P58_DummyPegasusFragment
internal class P58_FakeVoteWidget {
    fun bind() = Unit
}
internal class P58_IgnetEngineStub
internal class P58_SplashSourceStub
internal class P58_SpeedParamsStub
internal class P58_SpeedManagerStub

internal class P58_FakePackageManagerTemplate(private val hostPackageInfo: PackageInfo) {
    fun getPackageInfo(packageName: String, flags: Int): PackageInfo {
        if (packageName == hostPackageInfo.packageName) return hostPackageInfo
        throw PackageManager.NameNotFoundException(packageName)
    }
}

internal class P58_InsightMainListReq {
    fun getOid(): Long = 1L
    fun getType(): Long = 1L
    fun toByteArray(): ByteArray = ByteArray(0)

    companion object {
        @JvmStatic
        fun parseFrom(@Suppress("UNUSED_PARAMETER") bytes: ByteArray): P58_InsightMainListReq =
            P58_InsightMainListReq()
    }
}

internal class P58_InsightMainListReply {
    fun getRepliesList(): List<Any> = emptyList()
    fun getTopRepliesList(): List<Any> = emptyList()
    fun toByteArray(): ByteArray = ByteArray(0)

    companion object {
        @JvmStatic
        fun parseFrom(@Suppress("UNUSED_PARAMETER") bytes: ByteArray): P58_InsightMainListReply =
            P58_InsightMainListReply()
    }
}
