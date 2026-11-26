package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PrivacyChannelAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight.InsightRpc
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.GuestHostFavoriteDialog
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.IgnetHttpBoundary
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.IgnetHttpSanitizer
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.IncognitoNetworkHooks
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.IncognitoSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.IncognitoTelemetryHooks
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.KmpHeaderSanitizer
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.KmpMossInterceptor
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.KmpMossSurface
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.PrivacyNetworkPolicy
import com.bapis.bilibili.app.interfaces.v1.DefaultWordsReply
import com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReply
import com.bapis.bilibili.app.resource.v1.ListReply
import com.bapis.bilibili.app.resource.v1.ModuleReply
import com.bapis.bilibili.app.resource.v1.PoolReply
import com.bapis.bilibili.app.view.v1.VideoGuide
import com.bapis.bilibili.app.view.v1.ViewProgressReply
import com.bapis.bilibili.app.view.v1.ViewReply as LegacyViewReply
import com.bapis.bilibili.app.viewunite.common.Owner
import com.bapis.bilibili.app.viewunite.common.Vip
import com.bapis.bilibili.app.viewunite.v1.ViewReply as UnitedViewReply
import com.bapis.bilibili.community.service.dm.v1.DanmakuElem
import com.bapis.bilibili.community.service.dm.v1.DmSegMobileReply
import com.bapis.bilibili.playershared.ArcConf
import com.bapis.bilibili.playershared.PlayArcConf
import com.bapis.bilibili.polymer.app.search.v1.Item
import com.bapis.bilibili.polymer.app.search.v1.SearchAllResponse
import com.bapis.bilibili.polymer.app.search.v1.SearchAv
import com.bilibili.lib.ighttp.IgHttpRequest
import com.bilibili.lib.moss.api.MossResponseHandler
import kmpfixture.modern.Descriptor
import kmpfixture.modern.GrpcEngine
import kmpfixture.modern.Handler
import kmpfixture.modern.KMossException
import kmpfixture.modern.MossInterceptor
import kmpfixture.modern.Service
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

class Wave2PrivacyAndKmpWireTest {

    private fun env(
        loader: ClassLoader = javaClass.classLoader!!,
        registrar: HookRegistrar = PlayerPortTestRegistrar(),
        statuses: MutableMap<String, String> = linkedMapOf(),
        stages: MutableList<Pair<String, FeatureRuntimeStage>> = mutableListOf()
    ) = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = loader,
        hookPoints = HookPointRegistry(loader),
        registrar = registrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { k, v -> statuses[k] = v },
        runtimeEvidence = { id, stage, _ -> stages += id to stage }
    )

    private fun kmpOnlyLoader(vararg hiddenJavaClasses: String): ClassLoader {
        val hidden = hiddenJavaClasses.toSet()
        return object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name in hidden) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
    }

    private fun wireEnginePoint(): VersionAdapter.HookPoint {
        val method = GrpcEngine::class.java.getDeclaredMethod(
            "a",
            Service::class.java,
            Descriptor::class.java,
            ByteArray::class.java,
            Handler::class.java,
            Any::class.java
        )
        return KmpGrpcWireAdapter.point(method)
    }

    private object DummyStrategy : SerializationStrategy<Any>

    private fun modernNames() = KmpMossSurface.Names(
        engine = GrpcEngine::class.java.name,
        interceptor = MossInterceptor::class.java.name,
        exception = KMossException::class.java.name
    )

    class StrippedDescriptor(
        pkg: String,
        svc: String,
        mtd: String
    ) : Descriptor(pkg, svc, mtd, Any::class, DummyStrategy) {
        override fun toString(): String = "kmp.Descriptor@${Integer.toHexString(System.identityHashCode(this))}"
    }

    class GetterOnlyStrippedDescriptor(
        private val pkg: String,
        private val svc: String,
        private val mtd: String
    ) {
        @Suppress("unused")
        internal val reqClass = Any::class
        @Suppress("unused")
        internal val reqSerialization: SerializationStrategy<*> = DummyStrategy
        fun getPackageName(): String = pkg
        fun getServiceName(): String = svc
        fun getMethodName(): String = mtd
        override fun toString(): String = "ObfDesc@${Integer.toHexString(System.identityHashCode(this))}"
    }

    class OtherToStringDescriptor(
        val packageName: String,
        val serviceName: String,
        val methodName: String
    ) {
        override fun toString(): String = "SomethingElse()"
    }

    fun `privacy policy blocks new write and tracking rpcs while keeping read-only ones`() {
        for (method in listOf(
            "Reply", "Ping", "Vote", "Cancel",
            "CreateFolder", "RemoveItem", "EditTag", "SaveDraft", "UploadLog",
            "SubmitForm", "PostAction", "TrackEvent", "LogMetric", "SyncState",
            "UnfollowUser", "UnlikeVideo", "UnfavFolder"
        )) {
            assertTrue("Should block $method", PrivacyNetworkPolicy.blockRpc("bilibili.app.test.v1.Service/$method"))
        }
        for (allowed in listOf("ReplyList", "StatInfo", "MainList", "View", "ViewProgress", "PlayViewUnite")) {
            assertFalse("Should allow $allowed", PrivacyNetworkPolicy.blockRpc("bilibili.app.test.v1.Service/$allowed"))
        }
    }

    fun `privacy header sanitizers strip fawkes-req-bin and seal buvid3 and buvid4`() {
        val session = IncognitoSession { "00000000-0000-0000-0000-0000000000aa" }
        val identity = session.enter()

        val kmpSanitizer = KmpHeaderSanitizer({ b, _ -> b }, { b, _ -> b })
        val ascii = linkedMapOf<Any?, Any?>(
            "X-Bili-Fawkes-Req-Bin" to "leak",
            "Buvid3" to "real-3",
            "Buvid4" to "real-4",
            "User-Agent" to "ua"
        )
        val binary = linkedMapOf<Any?, Any?>("x-bili-fawkes-req-bin" to byteArrayOf(1, 2))
        kmpSanitizer.sanitize(listOf(ascii, binary), identity)
        assertFalse("X-Bili-Fawkes-Req-Bin" in ascii)
        assertFalse("x-bili-fawkes-req-bin" in binary)
        assertEquals(identity.buvid, ascii["Buvid3"])
        assertEquals(identity.buvid, ascii["Buvid4"])
        assertEquals("ua", ascii["User-Agent"])

        val ignetHeaders = linkedMapOf(
            "X-Bili-Fawkes-Req-Bin" to "leak",
            "X-Bili-Metadata-Bin" to "real-meta",
            "X-Bili-Device-Bin" to "real-dev",
            "Buvid3" to "real-3",
            "Buvid4" to "real-4"
        )
        IgnetHttpSanitizer.headers(ignetHeaders, identity)
        assertFalse("X-Bili-Fawkes-Req-Bin" in ignetHeaders)
        assertFalse("X-Bili-Metadata-Bin" in ignetHeaders)
        assertFalse("X-Bili-Device-Bin" in ignetHeaders)
        assertEquals(identity.buvid, ignetHeaders["Buvid3"])
        assertEquals(identity.buvid, ignetHeaders["Buvid4"])

        val sanitizedParams = PrivacyNetworkPolicy.parameters(
            mapOf("buvid3" to "real-3", "buvid4" to "real-4", "access_key" to "secret", "aid" to "1"),
            identity
        )
        assertEquals(identity.buvid, sanitizedParams["buvid3"])
        assertEquals(identity.buvid, sanitizedParams["buvid4"])
        assertFalse("access_key" in sanitizedParams)
        assertEquals("1", sanitizedParams["aid"])
    }

    fun `ignet form json body and stream provider sanitize payloads lazily and fail closed on overflow`() {
        val identity = IncognitoSession { "11111111-1111-1111-1111-111111111111" }.enter()
        val sign: (Map<String, String>) -> String = { params ->
            params.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" } + "&sign=sealed"
        }

        val cleanBytes = "a=1&b=2".toByteArray()
        assertSame(cleanBytes, IgnetHttpSanitizer.formBody(cleanBytes, "application/x-www-form-urlencoded", identity, sign))

        val cleanedForm = String(
            IgnetHttpSanitizer.formBody(
                "access_key=tok&buvid=real&dedeuserid=9&spmid=trace&a=1&sign=old".toByteArray(),
                "application/x-www-form-urlencoded; charset=utf-8",
                identity,
                sign
            )
        )
        assertFalse(cleanedForm.contains("tok"))
        assertFalse(cleanedForm.contains("dedeuserid="))
        assertFalse(cleanedForm.contains("spmid="))
        assertTrue(cleanedForm.contains("buvid=${identity.buvid}"))
        assertTrue(cleanedForm.contains("sign=sealed"))

        val cleanedJson = String(
            IgnetHttpSanitizer.formBody(
                """{"access_key":"tok","items":[{"buvid":"real","spmid":"trace","k":"v"}]}""".toByteArray(),
                "application/json",
                identity,
                sign
            )
        )
        assertFalse(cleanedJson.contains("access_key"))
        assertFalse(cleanedJson.contains("spmid"))
        assertTrue(cleanedJson.contains(identity.buvid))
        assertTrue(cleanedJson.contains(""""k":"v""""))

        var reads = 0
        val chunks = ArrayDeque(listOf("access_key=tok&".toByteArray(), "buvid=real&ok=1".toByteArray(), ByteArray(0)))
        val provider = IgHttpRequest.StreamDataProvider { chunks.removeFirst().also { reads++ } }
        val wrapped = IgnetHttpSanitizer.wrapStreamProvider(
            javaClass.classLoader!!,
            IgHttpRequest.StreamDataProvider::class.java,
            provider,
            IgHttpRequest.StreamDataProvider::class.java.getMethod("onProduceData", Int::class.javaPrimitiveType),
            "application/x-www-form-urlencoded",
            identity,
            sign
        ) as IgHttpRequest.StreamDataProvider
        assertEquals(0, reads)
        val first = wrapped.onProduceData(12)
        val rest = wrapped.onProduceData(128)
        assertEquals(0, wrapped.onProduceData(16).size)
        val total = String(first + rest)
        assertFalse(total.contains("access_key"))
        assertTrue(total.contains("buvid=${identity.buvid}"))
        assertTrue(total.contains("ok=1"))

        val huge = IgHttpRequest.StreamDataProvider { ByteArray(4096) }
        val capped = IgnetHttpSanitizer.wrapStreamProvider(
            javaClass.classLoader!!,
            IgHttpRequest.StreamDataProvider::class.java,
            huge,
            IgHttpRequest.StreamDataProvider::class.java.getMethod("onProduceData", Int::class.javaPrimitiveType),
            "application/x-www-form-urlencoded",
            identity,
            sign,
            maxBytes = 5000
        ) as IgHttpRequest.StreamDataProvider
        val thrown = assertThrows(Throwable::class.java) { capped.onProduceData(4096) }
        val cause = (thrown as? java.lang.reflect.UndeclaredThrowableException)?.undeclaredThrowable ?: thrown
        assertTrue("Expected IOException fail-closed, got $cause", cause is IOException)
    }

    fun `fail-closed privacy gates reject missing boundaries and unready favorite dialog`() {
        val session = IncognitoSession().apply { enter() }

        // 1. IgnetHttpBoundary hides IgHttpRequest while IgHttpResponse still visible -> prepare returns null
        val noIgnetReqLoader = kmpOnlyLoader(PrivacyChannelAdapter.IGNET_REQUEST)
        assertNull(IgnetHttpBoundary(session) { true }.prepare(env(noIgnetReqLoader)))

        // 2. KmpMossSurface: MossInterceptor present without GrpcEngine -> Unresolved("engine")
        val noEngineLoader = kmpOnlyLoader(GrpcEngine::class.java.name)
        val engineFail = KmpMossSurface.resolve(noEngineLoader, modernNames())
        assertEquals(KmpMossSurface.Result.Unresolved("engine"), engineFail)

        // 3. KmpMossSurface resolves cleanly via cachedEngine when GrpcEngine kept under explicit point
        val resolvedWithCached = KmpMossSurface.resolve(javaClass.classLoader!!, modernNames(), cachedEngine = wireEnginePoint())
        assertTrue(resolvedWithCached is KmpMossSurface.Result.Resolved)

        // 4. KmpMossInterceptor stripped toString() fallback vs "SomethingElse()"
        val surface = (resolvedWithCached as KmpMossSurface.Result.Resolved).surface
        val interceptor = KmpMossInterceptor(
            surface,
            javaClass.classLoader!!,
            session,
            { true },
            KmpHeaderSanitizer({ b, _ -> b }, { b, _ -> b }),
            { false },
            { null },
            object : KmpMossInterceptor.Events {
                override fun replied(local: Boolean, rpc: String?) = Unit
                override fun sanitized(names: Set<String>) = Unit
                override fun passed(rpc: String) = Unit
                override fun failed(reason: Throwable) = Unit
            }
        )
        assertEquals(
            "pkg.Service/View",
            interceptor.fullMethodName(StrippedDescriptor("pkg", "Service", "View"))
        )
        assertEquals(
            "pkg.Service/View",
            interceptor.fullMethodName(GetterOnlyStrippedDescriptor("pkg", "Service", "View"))
        )
        assertNull(interceptor.fullMethodName(OtherToStringDescriptor("pkg", "Service", "View")))

        // 5. IncognitoTelemetryHooks: KConfig visible without KNeuron -> prepare returns null
        val noKNeuronLoader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
                IncognitoTelemetryHooks.KMP_NEURON -> throw ClassNotFoundException(name)
                "kntr.base.config.KConfig" -> Service::class.java
                else -> super.loadClass(name, resolve)
            }
        }
        assertNull(IncognitoTelemetryHooks(session).prepare(env(noKNeuronLoader)))

        // 6. GuestHostFavoriteDialog: gated off when localBoundaryReady == false
        val statuses = linkedMapOf<String, String>()
        assertFalse(GuestHostFavoriteDialog { false }.install(env(statuses = statuses), javaClass.classLoader!!))
        assertEquals("skipped-no-local-boundary", statuses["guest_video_favorite_host_dialog"])

        // 7. PrivacyChannelAdapter validation & allowlist rules
        val loader = javaClass.classLoader!!
        assertNotNull(PrivacyChannelAdapter.directIgnet(loader))
        assertNotNull(PrivacyChannelAdapter.directKNeuron(loader))
        assertNull(PrivacyChannelAdapter.resolveIgHttpRequest(noIgnetReqLoader, VersionAdapter.HookPoint("com.evil.Spy", "execute", emptyList())))
        assertNull(PrivacyChannelAdapter.resolveIgHttpRequest(noIgnetReqLoader, VersionAdapter.HookPoint(PrivacyChannelAdapter.IGNET_REQUEST, "execute", listOf("int"))))
        assertNull(PrivacyChannelAdapter.resolveKNeuron(noKNeuronLoader, "com.bilibili.lib.neuron.api.Neurons"))
        assertTrue(PrivacyChannelAdapter.allowedIgnetOwnerName("com.bilibili.lib.ighttp.RelocatedReq"))
        assertFalse(PrivacyChannelAdapter.allowedIgnetOwnerName("com.evil.RelocatedReq"))
        assertTrue(PrivacyChannelAdapter.allowedKNeuronOwnerName("kntr.base.neuron.Relocated"))
        assertFalse(PrivacyChannelAdapter.allowedKNeuronOwnerName("com.bilibili.lib.neuron.api.Neurons"))
    }

    class CodecReq(val id: Long, val tag: String = "orig") {
        fun toByteArray(): ByteArray = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { s -> s.writeLong(id); s.writeUTF(tag) }
        }.toByteArray()
        companion object {
            @JvmStatic fun parseFrom(bytes: ByteArray): CodecReq =
                DataInputStream(ByteArrayInputStream(bytes)).use { CodecReq(it.readLong(), it.readUTF()) }
        }
    }

    class CodecReply(val marker: String) {
        fun toByteArray(): ByteArray = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { s -> s.writeUTF(marker) }
        }.toByteArray()
        companion object {
            @JvmStatic fun parseFrom(bytes: ByteArray): CodecReply =
                DataInputStream(ByteArrayInputStream(bytes)).use { CodecReply(it.readUTF()) }
        }
    }

    private fun invokeWire(
        registrar: PlayerPortTestRegistrar,
        pkg: String,
        svc: String,
        mtd: String,
        reqBytes: ByteArray,
        replyBytes: ByteArray
    ): Pair<ByteArray, ByteArray?> {
        var sentReq = reqBytes
        var received: ByteArray? = null
        val handler = object : Handler {
            override fun onNext(bytes: ByteArray?) { received = bytes }
            override fun onCompleted() = Unit
            override fun onError(error: KMossException) = Unit
        }
        val descriptor = StrippedDescriptor(pkg, svc, mtd)
        val args = arrayOf<Any?>(Service("host"), descriptor, reqBytes, handler, null)
        registrar.invoke("kmp.grpc.wire.channel", GrpcEngine(emptyList()), args) { passed ->
            sentReq = passed[2] as ByteArray
            (passed[3] as Handler).onNext(replyBytes)
            null
        }
        return sentReq to received
    }

    fun `KmpGrpcWireChannel supports transformWithRequest and stripped descriptors`() {
        val registrar = PlayerPortTestRegistrar()
        val environment = env(registrar = registrar)
        val surface = requireNotNull(KmpGrpcWireAdapter.resolve(javaClass.classLoader!!, wireEnginePoint()))

        assertNull(KmpGrpcWireChannel.parseDescriptorName(OtherToStringDescriptor("a.b", "Svc", "Call")))
        assertEquals(
            "a.b.Svc/Call",
            KmpGrpcWireChannel.parseDescriptorName(GetterOnlyStrippedDescriptor("a.b", "Svc", "Call"))
        )

        assertTrue(
            KmpGrpcWireChannel.install(
                environment = environment,
                surface = surface,
                javaMossClassName = "com.bapis.a.b.SvcMoss",
                rpc = "call",
                javaReplyClass = CodecReply::class.java,
                what = "wire-req",
                logKey = "wave2_wire",
                javaRequestClass = CodecReq::class.java,
                transformRequest = { req ->
                    val r = req as CodecReq
                    CodecReq(r.id, "${r.tag}+rewritten")
                },
                transformWithRequest = { req, reply ->
                    val r = req as? CodecReq
                    val p = reply as CodecReply
                    if (r == null) p else CodecReply("${p.marker}|id=${r.id}|tag=${r.tag}")
                },
                transformJava = { it }
            )
        )

        val (outReq, outReply) = invokeWire(
            registrar,
            "a.b",
            "Svc",
            "Call",
            CodecReq(42L, "req").toByteArray(),
            CodecReply("raw").toByteArray()
        )
        val decodedReq = CodecReq.parseFrom(outReq)
        assertEquals(42L, decodedReq.id)
        assertEquals("req+rewritten", decodedReq.tag)
        assertEquals("raw|id=42|tag=req+rewritten", CodecReply.parseFrom(requireNotNull(outReply)).marker)
    }

    fun `InsightRpc install and observe run through Wire-First`() {
        val registrar = PlayerPortTestRegistrar()
        val environment = env(registrar = registrar)
        val wire = wireEnginePoint()
        val observed = mutableListOf<String>()

        val installCount = InsightRpc.install(
            env = environment,
            mossName = "com.bapis.test.v1.InsightMoss",
            rpc = "analyze",
            requestClass = CodecReq::class.java,
            replyClass = CodecReply::class.java,
            transformRequest = { req -> CodecReq((req as CodecReq).id + 1, "insight") },
            kmpGrpcWireEngine = wire
        ) { req, reply ->
            CodecReply("${(reply as CodecReply).marker}:bound=${(req as CodecReq).id}")
        }
        assertEquals(1, installCount)

        val (sentReq, gotReply) = invokeWire(
            registrar,
            "test.v1",
            "Insight",
            "Analyze",
            CodecReq(10L, "orig").toByteArray(),
            CodecReply("base").toByteArray()
        )
        assertEquals(11L, CodecReq.parseFrom(sentReq).id)
        assertEquals("base:bound=11", CodecReply.parseFrom(requireNotNull(gotReply)).marker)

        val observeCount = InsightRpc.observe(
            env = environment,
            mossName = "com.bapis.test.v1.WatchMoss",
            rpc = "ping",
            requestClass = CodecReq::class.java,
            replyClass = CodecReply::class.java,
            hookIdPrefix = "wave2.observe",
            what = "obs",
            kmpGrpcWireEngine = wire
        ) { reply ->
            observed += (reply as CodecReply).marker
        }
        assertEquals(1, observeCount)
        invokeWire(
            registrar,
            "test.v1",
            "Watch",
            "Ping",
            ByteArray(0),
            CodecReply("seen").toByteArray()
        )
        assertEquals(listOf("seen"), observed)
    }

    class Wave2EndPageReq
    class Wave2EndPageReply(val cards: List<String>) {
        fun getRelatesList(): List<String> = cards
        fun getRelatesCount(): Int = cards.size
        fun toByteArray(): ByteArray = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { s ->
                s.writeInt(cards.size)
                cards.forEach(s::writeUTF)
            }
        }.toByteArray()
        class Builder(source: Wave2EndPageReply) {
            private var cards = source.cards
            fun clearRelates() = apply { cards = emptyList() }
            fun build() = Wave2EndPageReply(cards)
        }
        companion object {
            private val DEFAULT = Wave2EndPageReply(emptyList())
            @JvmStatic fun getDefaultInstance() = DEFAULT
            @JvmStatic fun newBuilder(source: Wave2EndPageReply) = Builder(source)
            @JvmStatic fun parseFrom(bytes: ByteArray): Wave2EndPageReply =
                DataInputStream(ByteArrayInputStream(bytes)).use { s ->
                    Wave2EndPageReply(List(s.readInt()) { s.readUTF() })
                }
        }
    }

    class Wave2PauseReq
    class Wave2PauseReply(val ads: String?, val bar: String?, val unrelated: String = "keep") {
        fun hasAds() = ads != null
        fun hasBar() = bar != null
        fun toByteArray(): ByteArray = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { s ->
                s.writeBoolean(ads != null); if (ads != null) s.writeUTF(ads)
                s.writeBoolean(bar != null); if (bar != null) s.writeUTF(bar)
                s.writeUTF(unrelated)
            }
        }.toByteArray()
        class Builder(private val source: Wave2PauseReply) {
            private var ads = source.ads
            fun clearAds() = apply { ads = null }
            fun build() = Wave2PauseReply(ads, source.bar, source.unrelated)
        }
        companion object {
            private val DEFAULT = Wave2PauseReply(null, null)
            @JvmStatic fun getDefaultInstance() = DEFAULT
            @JvmStatic fun newBuilder(source: Wave2PauseReply) = Builder(source)
            @JvmStatic fun parseFrom(bytes: ByteArray): Wave2PauseReply =
                DataInputStream(ByteArrayInputStream(bytes)).use { s ->
                    val ads = if (s.readBoolean()) s.readUTF() else null
                    val bar = if (s.readBoolean()) s.readUTF() else null
                    Wave2PauseReply(ads, bar, s.readUTF())
                }
        }
    }

    fun `HomeTopBar PlayerCapability PauseResponseAdapter and EndPage work end-to-end via Wire-First`() {
        val wire = wireEnginePoint()

        // 1. HomeTopBarFeatureInstaller (DefaultWords)
        val topReg = PlayerPortTestRegistrar()
        HomeTopBarFeatureInstaller(
            hideGameMenu = false,
            hideSearchDefaultWord = true,
            points = null,
            kmpGrpcWireEngine = wire
        ).install(env(registrar = topReg))
        val (_, defaultWordOut) = invokeWire(
            topReg,
            "bilibili.app.interface.v1",
            "Search",
            "DefaultWords",
            ByteArray(0),
            DefaultWordsReply("hot show", "hot word", "hot val", "route").toByteArray()
        )
        val cleanedWords = DefaultWordsReply.parseFrom(requireNotNull(defaultWordOut))
        assertEquals("", cleanedWords.getShow())
        assertEquals("", cleanedWords.getWord())
        assertEquals("", cleanedWords.getValue())

        // 2. PlayerCapabilityFeatureInstaller (PlayViewUnite KMP-only)
        val capReg = PlayerPortTestRegistrar()
        val capLoader = kmpOnlyLoader(
            "com.bapis.bilibili.app.playerunite.v1.PlayerMoss",
            "com.bapis.bilibili.app.playurl.v1.PlayURLMoss"
        )
        val capResult = PlayerCapabilityFeatureInstaller(
            PlayerCapabilityOptions(background = true, smallWindow = false, cast = false),
            kmpGrpcWireEngine = wire
        ).install(env(loader = capLoader, registrar = capReg))
        assertTrue(capResult is FeatureInstallResult.Installed)
        val lockedUnite = PlayViewUniteReply(
            PlayArcConf(mapOf(PlayerCapability.BACKGROUND.wireId to ArcConf(blocked = true, allowed = false))),
            video = true
        )
        val (_, capOut) = invokeWire(
            capReg,
            "bilibili.app.playerunite.v1",
            "Player",
            "PlayViewUnite",
            ByteArray(0),
            lockedUnite.toByteArray()
        )
        val unlockedUnite = PlayViewUniteReply.parseFrom(requireNotNull(capOut))
        val unlockedBg = unlockedUnite.getPlayArcConf().getArcConfsMap()[PlayerCapability.BACKGROUND.wireId]!!
        assertFalse(unlockedBg.getDisabled())
        assertTrue(unlockedBg.getIsSupport())
        assertTrue(lockedUnite.getPlayArcConf().getArcConfsMap()[PlayerCapability.BACKGROUND.wireId]!!.getDisabled())

        // 3. PauseResponseAdapter Wire-First
        val pauseReg = PlayerPortTestRegistrar()
        val pauseStatuses = linkedMapOf<String, String>()
        val pausePoints = PauseResponseAdapter.resolve(Wave2PauseReply::class.java, Wave2PauseReq::class.java, null, null)
        val pauseCov = PauseResponseAdapter.install(
            env(registrar = pauseReg, statuses = pauseStatuses),
            pausePoints,
            kmpGrpcWireEngine = wire
        )
        assertEquals(PauseResponseAdapter.Coverage(1, 1, kotlinInstalled = true), pauseCov)
        val (_, pauseOut) = invokeWire(
            pauseReg,
            "bilibili.app.viewunite.v1",
            "View",
            "PlayPause",
            ByteArray(0),
            Wave2PauseReply("ad-payload", "bar-keep", "meta").toByteArray()
        )
        val pauseDecoded = Wave2PauseReply.parseFrom(requireNotNull(pauseOut))
        assertFalse(pauseDecoded.hasAds())
        assertEquals("bar-keep", pauseDecoded.bar)
        assertEquals("meta", pauseDecoded.unrelated)

        // 4. PlayerEndPageRecommendFeatureInstaller KMP-only Wire-First
        val endReg = PlayerPortTestRegistrar()
        val endStatuses = linkedMapOf<String, String>()
        val endAccess = PlayerEndPageRecommendLocator.resolve(Wave2EndPageReply::class.java, null, null, null)
        val endResult = PlayerEndPageRecommendFeatureInstaller(
            enabled = true,
            kmpGrpcWireEngine = wire,
            resolve = { endAccess }
        ).install(env(registrar = endReg, statuses = endStatuses))
        assertTrue(endResult is FeatureInstallResult.Installed)
        val (_, endOut) = invokeWire(
            endReg,
            "bilibili.app.viewunite.v1",
            "View",
            "ViewEndPage",
            ByteArray(0),
            Wave2EndPageReply(listOf("card-1", "card-2")).toByteArray()
        )
        assertTrue(Wave2EndPageReply.parseFrom(requireNotNull(endOut)).getRelatesList().isEmpty())
    }

    fun `migrated KMP-only installers clean payloads over Wire-First without Java Moss`() {
        val wire = wireEnginePoint()

        // 1. DanmakuPurifyFeatureInstaller
        val dmReg = PlayerPortTestRegistrar()
        val dmLoader = kmpOnlyLoader("com.bapis.bilibili.community.service.dm.v1.DMMoss")
        val dmRes = DanmakuPurifyFeatureInstaller(
            weightFilterEnabled = true,
            minimumWeight = 5,
            removeVipColorful = false,
            kmpGrpcWireEngine = wire
        ).install(env(loader = dmLoader, registrar = dmReg))
        assertEquals(FeatureInstallResult.Installed(3, complete = true), dmRes)
        val dmPayload = DmSegMobileReply(elems = listOf(DanmakuElem(2), DanmakuElem(8)))
        val (_, dmOut) = invokeWire(
            dmReg,
            "bilibili.community.service.dm.v1",
            "DM",
            "DmSegMobile",
            ByteArray(0),
            dmPayload.toByteArray()
        )
        assertEquals(listOf(8), DmSegMobileReply.parseFrom(requireNotNull(dmOut)).getElemsList().map { it.getWeight() })

        // 2. DetailModulePurifyFeatureInstaller
        val modReg = PlayerPortTestRegistrar()
        val modLoader = kmpOnlyLoader("com.bapis.bilibili.app.view.v1.ViewMoss")
        val modRes = DetailModulePurifyFeatureInstaller(
            enabledKeys = setOf(DetailModulePurifyPolicy.targets.first().preferenceKey),
            kmpGrpcWireEngine = wire
        ).install(env(loader = modLoader, registrar = modReg))
        assertEquals(FeatureInstallResult.Installed(1, complete = false), modRes)
        val (_, modOut) = invokeWire(
            modReg,
            "bilibili.app.view.v1",
            "View",
            "View",
            ByteArray(0),
            LegacyViewReply(setOf("Honor", "Label")).toByteArray()
        )
        val cleanedLegacyView = LegacyViewReply.parseFrom(requireNotNull(modOut))
        assertFalse(cleanedLegacyView.hasHonor())
        assertTrue(cleanedLegacyView.hasLabel())

        // 3. DetailUnitedModulePurifyFeatureInstaller (owner.vip)
        val unitedReg = PlayerPortTestRegistrar()
        val unitedLoader = kmpOnlyLoader("com.bapis.bilibili.app.viewunite.v1.ViewMoss")
        val unitedRes = DetailUnitedModulePurifyFeatureInstaller(
            enabledKeys = setOf(DetailUnitedModulePurifyPolicy.UpVipLabel.PREFERENCE_KEY),
            kmpGrpcWireEngine = wire
        ).install(env(loader = unitedLoader, registrar = unitedReg))
        assertEquals(FeatureInstallResult.Installed(1, complete = false), unitedRes)
        val (_, unitedOut) = invokeWire(
            unitedReg,
            "bilibili.app.viewunite.v1",
            "View",
            "View",
            ByteArray(0),
            UnitedViewReply(null, Owner("up-name", Vip("vip"))).toByteArray()
        )
        val cleanedUnited = UnitedViewReply.parseFrom(requireNotNull(unitedOut))
        assertFalse(cleanedUnited.getOwner().hasVip())
        assertEquals("up-name", cleanedUnited.getOwner().getName())

        // 4. BlockComponentLibraryFeatureInstaller
        val libReg = PlayerPortTestRegistrar()
        val libLoader = kmpOnlyLoader("com.bapis.bilibili.app.resource.v1.ModuleMoss")
        val libRes = BlockComponentLibraryFeatureInstaller(
            enabled = true,
            targetKeywords = setOf("基础组件库"),
            kmpGrpcWireEngine = wire
        ).install(env(loader = libLoader, registrar = libReg))
        assertEquals(FeatureInstallResult.Installed(1, complete = true), libRes)
        val (_, libOut) = invokeWire(
            libReg,
            "bilibili.app.resource.v1",
            "Module",
            "List",
            ByteArray(0),
            ListReply(
                listOf(
                    PoolReply("video", listOf(ModuleReply("keep"))),
                    PoolReply("基础组件库", listOf(ModuleReply("drop-me")))
                )
            ).toByteArray()
        )
        val cleanedLib = ListReply.parseFrom(requireNotNull(libOut))
        assertEquals(1, cleanedLib.getPoolsList()[0].getModulesList().size)
        assertTrue(cleanedLib.getPoolsList()[1].getModulesList().isEmpty())

        // 5. SearchPurifyFeatureInstaller (SearchAll wire filter)
        val searchReg = PlayerPortTestRegistrar()
        SearchPurifyFeatureInstaller(
            removeCommercial = true,
            keywordFilterEnabled = false,
            rawKeywords = "",
            authorFilterEnabled = false,
            rawAuthorRules = "",
            kmpGrpcWireEngine = wire
        ).install(env(registrar = searchReg))
        val (_, searchOut) = invokeWire(
            searchReg,
            "bilibili.polymer.app.search.v1",
            "Search",
            "SearchAll",
            ByteArray(0),
            SearchAllResponse(listOf(Item(true, SearchAv("ad")), Item(false, SearchAv("normal")))).toByteArray()
        )
        val cleanedSearch = SearchAllResponse.parseFrom(requireNotNull(searchOut))
        assertEquals(listOf("normal"), cleanedSearch.getItemList().map { it.getAv().getTitle() })

        // 6. PlayerInteractiveOverlayFeatureInstaller Wire-First + 4-param legacy fallback check
        val interReg = PlayerPortTestRegistrar()
        val overlayPoints = requireNotNull(VersionAdapter.locatePlayerInteractiveOverlays(javaClass.classLoader!!))
            .copy(mossExecutes = emptyList(), mossAsync = emptyList())
        val interRes = PlayerInteractiveOverlayFeatureInstaller(
            enabled = true,
            points = overlayPoints,
            kmpGrpcWireEngine = wire
        ).install(env(registrar = interReg))
        assertTrue(interRes is FeatureInstallResult.Installed)
        val (_, guideOut) = invokeWire(
            interReg,
            "bilibili.app.view.v1",
            "View",
            "ViewProgress",
            ByteArray(0),
            ViewProgressReply(VideoGuide(setOf("clearAttention", "clearCardsSecond"))).toByteArray()
        )
        assertTrue(ViewProgressReply.parseFrom(requireNotNull(guideOut)).getVideoGuide().fields.isEmpty())

        assertNotNull(
            KotlinMossBridgeMembers.legacyCallbackEntry(LegacyKotlinInteractiveMoss::class.java, "viewProgress")
        )
    }

    class LegacyKotlinInteractiveMoss {
        @Suppress("UNUSED_PARAMETER")
        fun viewProgress(
            req: Any,
            ser: SerializationStrategy<*>,
            deser: DeserializationStrategy<*>,
            handler: MossResponseHandler
        ) = Unit
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (Wave2PrivacyAndKmpWireTest) -> Unit) {
            block(Wave2PrivacyAndKmpWireTest())
        }
        runIsolated { it.`privacy policy blocks new write and tracking rpcs while keeping read-only ones`() }
        runIsolated { it.`privacy header sanitizers strip fawkes-req-bin and seal buvid3 and buvid4`() }
        runIsolated { it.`ignet form json body and stream provider sanitize payloads lazily and fail closed on overflow`() }
        runIsolated { it.`fail-closed privacy gates reject missing boundaries and unready favorite dialog`() }
        runIsolated { it.`KmpGrpcWireChannel supports transformWithRequest and stripped descriptors`() }
        runIsolated { it.`InsightRpc install and observe run through Wire-First`() }
        runIsolated { it.`HomeTopBar PlayerCapability PauseResponseAdapter and EndPage work end-to-end via Wire-First`() }
        runIsolated { it.`migrated KMP-only installers clean payloads over Wire-First without Java Moss`() }
    }
}
