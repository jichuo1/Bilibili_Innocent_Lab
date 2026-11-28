package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import com.bilibili.lib.moss.api.MossResponseHandler
import kmpfixture.modern.Descriptor
import kmpfixture.modern.GrpcEngine
import kmpfixture.modern.Handler
import kmpfixture.modern.KMossException
import kmpfixture.modern.Service
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class PauseResponseInstallerTest {
    private val stages = mutableListOf<FeatureRuntimeStage>()
    private val statuses = linkedMapOf<String, String>()
    private fun environment(registrar: HookRegistrar) = HookEnvironment(
        "tv.danmaku.bili", javaClass.classLoader, HookPointRegistry(javaClass.classLoader), registrar,
        { _, _ -> }, { _, _ -> }, { key, value -> statuses[key] = value },
        runtimeEvidence = { _, stage, _ -> stages += stage }
    )
    private fun points(java: Class<*> = JavaPauseMoss::class.java, kotlin: Class<*>? = KotlinPauseMoss::class.java) =
        PauseResponseAdapter.resolve(PauseReply::class.java, PauseReq::class.java, java, kotlin)

    fun `4-param legacy KViewMoss resolves and registers via default ProtoBuf`() {
        val registrar = PlayerPortTestRegistrar()
        val legacyPoints = points(kotlin = LegacyKotlinPauseMoss::class.java)
        assertNotNull(legacyPoints.kotlin)
        assertEquals(4, legacyPoints.kotlin!!.parameterCount)
        val coverage = PauseResponseAdapter.install(environment(registrar), legacyPoints)
        assertEquals(PauseResponseAdapter.Coverage(3, 3, kotlinInstalled = true), coverage)
        assertTrue("paused.response.kmoss.play_pause" in registrar.hooks)
    }

    fun `sync async and Kotlin responses each have their own registration`() {
        val registrar = PlayerPortTestRegistrar()
        val coverage = PauseResponseAdapter.install(environment(registrar), points())
        assertEquals(PauseResponseAdapter.Coverage(3, 3, kotlinInstalled = true), coverage)
        assertTrue(coverage.complete)
        assertEquals(setOf("paused.response.execute_play_pause", "paused.response.play_pause",
            "paused.response.kmoss.play_pause"), registrar.hooks.keys)
    }

    fun `absent methods ambiguous callbacks and registration failures remain in the denominator`() {
        val missing = points(MissingAsyncPauseMoss::class.java, AmbiguousKotlinPauseMoss::class.java)
        assertNull(missing.async)
        assertNull(missing.kotlin)
        val partial = PauseResponseAdapter.install(environment(PlayerPortTestRegistrar()), missing)
        assertEquals(PauseResponseAdapter.Coverage(1, 3), partial)
        assertFalse(partial.complete)
        val failed = PauseResponseAdapter.install(environment(PlayerPortTestRegistrar("paused.response.play_pause")), points())
        assertEquals(PauseResponseAdapter.Coverage(2, 3, kotlinInstalled = true), failed)
        assertFalse(failed.complete)
        val kotlinFailed = PauseResponseAdapter.install(
            environment(PlayerPortTestRegistrar("paused.response.kmoss.play_pause")), points())
        assertEquals(PauseResponseAdapter.Coverage(2, 3), kotlinFailed)
    }

    fun `request routes require the audited host ABI and the registered Kotlin boundary`() {
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                error("Unexpected host lookup without route evidence")
        }
        val families = setOf("ad-api", "theseus")
        assertTrue(PauseRequestCoverage.covered(loader, 9190400, families, kotlinInstalled = false).isEmpty())
        assertTrue(PauseRequestCoverage.covered(loader, 9190300, setOf("other"), kotlinInstalled = true).isEmpty())
        val emptyLoader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = throw ClassNotFoundException(name)
        }
        assertTrue(PauseRequestCoverage.covered(emptyLoader, 9190300, families, kotlinInstalled = true).isEmpty())
        assertTrue(PauseRequestCoverage.covered(javaClass.classLoader!!, 9190400, families, kotlinInstalled = true).isEmpty())
        // 9.20/9.21 构建与 9.19 共用 b#c 请求入口（远端 dexq 验真）；无宿主 ABI 证据时同样不得宣称覆盖。
        for (version in listOf(9200100, 9200200, 9200300, 9210100, 9210200, 9210300)) {
            assertTrue(PauseRequestCoverage.covered(loader, version, families, kotlinInstalled = false).isEmpty())
            assertTrue(PauseRequestCoverage.covered(javaClass.classLoader!!, version, families, kotlinInstalled = true).isEmpty())
        }
    }

    fun `request entry owner is pinned per verified build and unknown builds stay closed`() {
        val ad = "kntr.app.ad.biz.videodetail.pausedpage."
        for (version in listOf(9180100, 9180200, 9180300))
            assertEquals(ad + "a", PauseRequestCoverage.apiOwnerName(version))
        for (version in listOf(9190200, 9190400, 9200100, 9200200, 9200300, 9210100, 9210200, 9210300))
            assertEquals(ad + "b", PauseRequestCoverage.apiOwnerName(version))
        assertNull(PauseRequestCoverage.apiOwnerName(9190300))
        assertNull(PauseRequestCoverage.apiOwnerName(9210400))
        assertNull(PauseRequestCoverage.apiOwnerName(null))
    }

    fun `future 921+ builds resolve unique 16-param AdPausedPageApi structurally and fail closed on ambiguity or missing ABI`() {
        val pkg = "kntr.app.ad.biz.videodetail.pausedpage."
        val mossPkg = "${PauseResponseAdapter.PACKAGE}."
        fun buildLoader(
            extraAmbiguous: Boolean = false,
            hideReceiveLambda: Boolean = false
        ): ClassLoader {
            val baseTemplates = mapOf(
                "${pkg}KTabType" to TemplateKTabType::class.java,
                "${pkg}d" to TemplateApiOwner::class.java,
                "${pkg}AdPausedPageApi\$requestPausedPage\$awaitResponse\$2\$1" to TemplateLambda::class.java,
                "${pkg}AdPausedPageApi\$receivePausedPageResult\$2" to TemplateLambda::class.java,
                "${pkg}AdPausedPageApi\$requestPausedPage\$1" to TemplateCont::class.java,
                "${mossPkg}KTabType" to TemplateKTabType::class.java,
                "${mossPkg}KPlayPauseReq" to TemplateKPlayPauseReq::class.java,
                "${mossPkg}KPlayPauseReply" to TemplateKPlayPauseReply::class.java,
                "${mossPkg}KPauseBar" to TemplateKPauseBar::class.java,
                "${mossPkg}KPlayPauseReply\$IData" to TemplateKIData::class.java,
                PauseResponseAdapter.KOTLIN_MOSS to TemplateKViewMoss::class.java
            ).let { map ->
                val withAmbiguity = if (extraAmbiguous) map + ("${pkg}e" to TemplateApiOwner::class.java) else map
                if (hideReceiveLambda) withAmbiguity - "${pkg}AdPausedPageApi\$receivePausedPageResult\$2" else withAmbiguity
            }
            val replacements = mapOf(
                TemplateBaseLambda::class.java.name.replace('.', '/') to "kotlin/coroutines/jvm/internal/SuspendLambda",
                TemplateBaseCont::class.java.name.replace('.', '/') to "kotlin/coroutines/jvm/internal/ContinuationImpl",
                TemplateBaseFunction2::class.java.name.replace('.', '/') to "kotlin/jvm/functions/Function2",
                TemplateBaseContinuation::class.java.name.replace('.', '/') to "kotlin/coroutines/Continuation",
                TemplateKTabType::class.java.name.replace('.', '/') to "${mossPkg}KTabType".replace('.', '/'),
                TemplateKPlayPauseReq::class.java.name.replace('.', '/') to "${mossPkg}KPlayPauseReq".replace('.', '/'),
                TemplateKPlayPauseReply::class.java.name.replace('.', '/') to "${mossPkg}KPlayPauseReply".replace('.', '/'),
                TemplateKPauseBar::class.java.name.replace('.', '/') to "${mossPkg}KPauseBar".replace('.', '/'),
                TemplateKIData::class.java.name.replace('.', '/') to "${mossPkg}KPlayPauseReply\$IData".replace('.', '/')
            )
            val hidden = if (hideReceiveLambda) setOf("${pkg}AdPausedPageApi\$receivePausedPageResult\$2") else emptySet()
            return TestRenamingClassLoader(
                parent = javaClass.classLoader!!,
                templates = baseTemplates,
                replacements = replacements,
                hiddenNames = hidden
            )
        }

        val valid = buildLoader()
        assertTrue(PauseRequestCoverage.hasVerifiedApiEntry(valid, 9210000))
        assertEquals(setOf("ad-api"), PauseRequestCoverage.covered(valid, 9210000, setOf("ad-api"), kotlinInstalled = true))

        val ambiguous = buildLoader(extraAmbiguous = true)
        assertFalse(PauseRequestCoverage.hasVerifiedApiEntry(ambiguous, 9210000))
        assertTrue(PauseRequestCoverage.covered(ambiguous, 9210000, setOf("ad-api"), kotlinInstalled = true).isEmpty())

        val incomplete = buildLoader(hideReceiveLambda = true)
        assertTrue(PauseRequestCoverage.covered(incomplete, 9210000, setOf("ad-api"), kotlinInstalled = true).isEmpty())
    }

    fun `sync cleans only ads and copies bar extra fields and Java unknown fields`() {
        val registrar = PlayerPortTestRegistrar()
        PauseResponseAdapter.install(environment(registrar), points())
        val original = PauseReply("ad", "pause bar", 7, "unrelated", byteArrayOf(1, 4, 9))
        val updated = registrar.invoke("paused.response.execute_play_pause") { original } as PauseReply
        assertNotSame(original, updated)
        assertFalse(updated.hasAds())
        assertTrue(original.hasAds())
        assertEquals(original.bar, updated.bar)
        assertEquals(original.kind, updated.kind)
        assertEquals(original.other, updated.other)
        assertArrayEquals(original.unknownFields, updated.unknownFields)
        assertTrue(FeatureRuntimeStage.APPLIED in stages)
        stages.clear()
        val onlyBar = PauseReply(null, "pause bar")
        assertSame(onlyBar, registrar.invoke("paused.response.execute_play_pause") { onlyBar })
        assertFalse(FeatureRuntimeStage.APPLIED in stages)
        val default = PauseReply.getDefaultInstance()
        assertSame(default, registrar.invoke("paused.response.execute_play_pause") { default })
    }

    fun `async keeps callback errors completion and delegate exceptions unchanged`() {
        val registrar = PlayerPortTestRegistrar()
        PauseResponseAdapter.install(environment(registrar), points())
        val replies = mutableListOf<Any?>()
        val errors = mutableListOf<Throwable>()
        var completed = 0
        val handler = object : MossResponseHandler {
            override fun onNext(reply: Any?) { replies += reply }
            override fun onError(error: Throwable) { errors += error }
            override fun onCompleted() { completed++ }
        }
        val original = PauseReply("ad", "bar")
        val error = IllegalStateException("host callback")
        val arguments = arrayOf<Any?>(PauseReq(), handler)
        registrar.invoke("paused.response.play_pause", args = arguments) { args ->
            (args[1] as MossResponseHandler).apply { onNext(original); onError(error); onCompleted() }
        }
        assertSame(handler, arguments[1])
        assertFalse((replies.single() as PauseReply).hasAds())
        assertEquals("bar", (replies.single() as PauseReply).bar)
        assertEquals(listOf(error), errors)
        assertEquals(1, completed)
        val throwing = object : MossResponseHandler by handler {
            override fun onNext(reply: Any?) { throw error }
        }
        val thrown = assertThrows(IllegalStateException::class.java) {
            registrar.invoke("paused.response.play_pause", args = arrayOf(PauseReq(), throwing)) { args ->
                (args[1] as MossResponseHandler).onNext(original)
            }
        }
        assertSame(error, thrown)
        assertSame(error, assertThrows(IllegalStateException::class.java) {
            registrar.invoke("paused.response.execute_play_pause") { throw error }
        })
    }

    fun `Kotlin bridge rewrites only a successful copy and preserves a bar only reply identity`() {
        val registrar = PlayerPortTestRegistrar()
        PauseResponseAdapter.install(environment(registrar), points())
        val replies = mutableListOf<Any?>()
        val handler = collecting(replies)
        val proto = PauseProtoBuf()
        val original = KotlinPause(PauseReply("ad", "bar", 5, "keep", byteArrayOf(8, 2)))
        fun emit(reply: KotlinPause) = registrar.invoke("paused.response.kmoss.play_pause",
            args = arrayOf(PauseReq(), Strategy, Strategy, handler, proto)) { args ->
            (args[3] as MossResponseHandler).onNext(reply)
        }
        emit(original)
        val updated = replies.last() as KotlinPause
        assertNotSame(original, updated)
        assertFalse(updated.message.hasAds())
        assertTrue(original.message.hasAds())
        assertEquals("bar", updated.message.bar)
        assertEquals(5, updated.message.kind)
        assertEquals("keep", updated.message.other)
        // 夹具在线格式中保存该字段；不据此许诺任意 Kotlin 序列化器保留未知字段。
        assertArrayEquals(original.message.unknownFields, updated.message.unknownFields)
        assertTrue(FeatureRuntimeStage.APPLIED in stages)
        stages.clear()
        val onlyBar = KotlinPause(PauseReply(null, "bar"))
        emit(onlyBar)
        assertSame(onlyBar, replies.last())
        assertFalse(FeatureRuntimeStage.APPLIED in stages)
    }

    fun `failed Kotlin write or changed bar readback passes the original and never claims applied`() {
        val registrar = PlayerPortTestRegistrar()
        val env = environment(registrar)
        val cleaner = requireNotNull(PausePayloadCleaner.resolve(PauseReply::class.java))
        val original = KotlinPause(PauseReply("ad", "bar"))
        val bridge = KotlinMossReplyBridge(
            encode = { (it as KotlinPause).message.toByteArray() },
            parseJava = PauseReply::parseFrom,
            toBytes = { (it as PauseReply).toByteArray() },
            decode = { error("decode failed") }
        )
        assertSame(original, PauseResponseAdapter.transformKotlin(bridge, cleaner, original, env))
        assertTrue(FeatureRuntimeStage.ERROR in stages)
        assertFalse(FeatureRuntimeStage.APPLIED in stages)
        stages.clear()
        val corrupt = KotlinMossReplyBridge(
            encode = { (it as KotlinPause).message.toByteArray() },
            parseJava = PauseReply::parseFrom,
            toBytes = { (it as PauseReply).toByteArray() },
            decode = { KotlinPause(PauseReply(null, "changed bar")) }
        )
        assertSame(original, PauseResponseAdapter.transformKotlin(corrupt, cleaner, original, env))
        assertTrue(FeatureRuntimeStage.ERROR in stages)
        assertFalse(FeatureRuntimeStage.APPLIED in stages)
    }

    fun `identified requests remain untouched and an incomplete response layer cannot turn green`() {
        val registrar = PlayerPortTestRegistrar()
        val requests = VersionAdapter.PausePoints(listOf(VersionAdapter.HookPoint("shared.pause.Request", "invokeSuspend")), null, null, null)
        val result = PausedAdFeatureInstaller(true, requests).install(environment(registrar)) as FeatureInstallResult.Installed
        assertFalse(result.complete)
        assertTrue(statuses.getValue("adskip_status").startsWith("partial:"))
        assertEquals("partial:1/2", statuses["adskip_response_status"])
        assertTrue(registrar.hooks.keys.none { it.startsWith("paused.request") || it == "paused.legacy_callback" })
    }

    fun `9190300 and 9180000 builds also resolve unique 16-param AdPausedPageApi structurally and fail closed on ambiguity`() {
        fun buildLoader(extraAmbiguous: Boolean = false): ClassLoader {
            val baseTemplates = linkedMapOf<String, Class<*>>(
                "kotlin.coroutines.jvm.internal.SuspendLambda" to TemplateBaseLambda::class.java,
                "kotlin.coroutines.jvm.internal.ContinuationImpl" to TemplateBaseCont::class.java,
                "kotlin.jvm.functions.Function2" to TemplateBaseFunction2::class.java,
                "kotlin.coroutines.Continuation" to TemplateBaseContinuation::class.java,
                "com.bapis.bilibili.app.viewunite.v1.KTabType" to TemplateKTabType::class.java,
                "com.bapis.bilibili.app.viewunite.v1.KPlayPauseReq" to TemplateKPlayPauseReq::class.java,
                "com.bapis.bilibili.app.viewunite.v1.KPauseBar" to TemplateKPauseBar::class.java,
                "com.bapis.bilibili.app.viewunite.v1.KPlayPauseReply\$IData" to TemplateKIData::class.java,
                "com.bapis.bilibili.app.viewunite.v1.KPlayPauseReply" to TemplateKPlayPauseReply::class.java,
                "com.bapis.bilibili.app.viewunite.v1.KViewMoss" to TemplateKViewMoss::class.java,
                "kntr.app.ad.biz.videodetail.pausedpage.AdPausedPageApi\$requestPausedPage\$awaitResponse\$2\$1" to TemplateLambda::class.java,
                "kntr.app.ad.biz.videodetail.pausedpage.AdPausedPageApi\$receivePausedPageResult\$2" to TemplateLambda::class.java,
                "kntr.app.ad.biz.videodetail.pausedpage.AdPausedPageApi\$requestPausedPage\$1" to TemplateCont::class.java,
                "kntr.app.ad.biz.videodetail.pausedpage.z" to TemplateApiOwner::class.java
            )
            if (extraAmbiguous) {
                baseTemplates["kntr.app.ad.biz.videodetail.pausedpage.y"] = TemplateApiOwner::class.java
            }
            val replacements = mapOf(
                TemplateBaseLambda::class.java.name.replace('.', '/') to "kotlin/coroutines/jvm/internal/SuspendLambda",
                TemplateBaseCont::class.java.name.replace('.', '/') to "kotlin/coroutines/jvm/internal/ContinuationImpl",
                TemplateBaseFunction2::class.java.name.replace('.', '/') to "kotlin/jvm/functions/Function2",
                TemplateBaseContinuation::class.java.name.replace('.', '/') to "kotlin/coroutines/Continuation",
                TemplateKTabType::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/viewunite/v1/KTabType",
                TemplateKPlayPauseReq::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/viewunite/v1/KPlayPauseReq",
                TemplateKPauseBar::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/viewunite/v1/KPauseBar",
                TemplateKIData::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/viewunite/v1/KPlayPauseReply\$IData",
                TemplateKPlayPauseReply::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/viewunite/v1/KPlayPauseReply",
                TemplateKViewMoss::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/viewunite/v1/KViewMoss"
            )
            return TestRenamingClassLoader(javaClass.classLoader!!, templates = baseTemplates, replacements = replacements)
        }
        val adFamilies = setOf("ad-api")
        val uniqueLoader = buildLoader(extraAmbiguous = false)
        assertEquals(adFamilies, PauseRequestCoverage.covered(uniqueLoader, 9190300, adFamilies, kotlinInstalled = true))
        assertEquals(adFamilies, PauseRequestCoverage.covered(uniqueLoader, 9180000, adFamilies, kotlinInstalled = true))
        val ambiguousLoader = buildLoader(extraAmbiguous = true)
        assertTrue(PauseRequestCoverage.covered(ambiguousLoader, 9190300, adFamilies, kotlinInstalled = true).isEmpty())
    }

    fun `paused ad installer retries kmp grpc wire idempotently without re-registering java hooks`() {
        val templates = mapOf(
            "com.bapis.bilibili.app.viewunite.v1.PlayPauseReq" to PauseReq::class.java,
            "com.bapis.bilibili.app.viewunite.v1.PlayPauseReply" to PauseReply::class.java,
            "com.bapis.bilibili.app.viewunite.v1.PlayPauseReply\$Builder" to PauseReply.Builder::class.java,
            "com.bapis.bilibili.app.viewunite.v1.PlayPauseReply\$Companion" to PauseReply.Companion::class.java,
            "com.bapis.bilibili.app.viewunite.v1.ViewMoss" to JavaPauseMoss::class.java,
            "com.bapis.bilibili.app.viewunite.v1.KViewMoss" to MissingAsyncPauseMoss::class.java,
            "kntr.base.moss.MossInterceptor" to MissingAsyncPauseMoss::class.java
        )
        val replacements = mapOf(
            PauseReq::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/viewunite/v1/PlayPauseReq",
            PauseReply::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/viewunite/v1/PlayPauseReply",
            PauseReply.Builder::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/viewunite/v1/PlayPauseReply\$Builder",
            PauseReply.Companion::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/viewunite/v1/PlayPauseReply\$Companion",
            JavaPauseMoss::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/viewunite/v1/ViewMoss"
        )
        val wireLoader = TestRenamingClassLoader(javaClass.classLoader!!, templates = templates, replacements = replacements)
        val registrar = PlayerPortTestRegistrar()
        val env = HookEnvironment("tv.danmaku.bili", wireLoader, HookPointRegistry(wireLoader), registrar,
            { _, _ -> }, { _, _ -> }, { k, v -> statuses[k] = v },
            runtimeEvidence = { _, stage, _ -> stages += stage })
        var adaptedWirePoint: VersionAdapter.HookPoint? = null
        val installer = PausedAdFeatureInstaller(
            enabled = true,
            points = null,
            hostVersionCode = 9210100,
            kmpGrpcWireEngineProvider = { adaptedWirePoint }
        )
        val first = installer.install(env) as FeatureInstallResult.Installed
        assertEquals(2, first.hookCount)
        assertFalse(first.complete)
        assertTrue(installer.requiresAdaptationRetry)
        assertEquals("partial:2/3", statuses["adskip_response_status"])
        assertEquals(2, registrar.registrationAttempts.size)

        val wireMethod = kmpfixture.modern.GrpcEngine::class.java.declaredMethods.single { it.name == "a" }
        adaptedWirePoint = VersionAdapter.HookPoint(
            wireMethod.declaringClass.name,
            wireMethod.name,
            wireMethod.parameterTypes.map(Class<*>::getName)
        )
        val retry = installer.install(env) as FeatureInstallResult.Installed
        assertEquals(3, retry.hookCount)
        assertTrue(retry.complete)
        assertFalse(installer.requiresAdaptationRetry)
        assertEquals(3, registrar.registrationAttempts.size)
        assertTrue("kmp.grpc.wire.channel" in registrar.hooks.keys)
        assertEquals("success", statuses["adskip_response_status"])
    }

    private fun collecting(replies: MutableList<Any?>) = object : MossResponseHandler {
        override fun onNext(reply: Any?) { replies += reply }
        override fun onError(error: Throwable) = Unit
        override fun onCompleted() = Unit
    }

    class PauseReq
    class PauseReply(val ads: String?, val bar: String?, val kind: Int = 0, val other: String = "",
        val unknownFields: ByteArray = byteArrayOf()) {
        fun hasAds() = ads != null
        fun hasBar() = bar != null
        fun toByteArray(): ByteArray = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use {
                it.writeBoolean(ads != null); if (ads != null) it.writeUTF(ads)
                it.writeBoolean(bar != null); if (bar != null) it.writeUTF(bar)
                it.writeInt(kind); it.writeUTF(other); it.writeInt(unknownFields.size); it.write(unknownFields)
            }
        }.toByteArray()
        class Builder(private val original: PauseReply) {
            private var ads = original.ads
            fun clearAds(): Builder { ads = null; return this }
            fun build() = PauseReply(ads, original.bar, original.kind, original.other, original.unknownFields.copyOf())
        }
        companion object {
            private val DEFAULT = PauseReply(null, null)
            @JvmStatic fun getDefaultInstance() = DEFAULT
            @JvmStatic fun newBuilder(original: PauseReply) = Builder(original)
            @JvmStatic fun parseFrom(bytes: ByteArray): PauseReply = DataInputStream(ByteArrayInputStream(bytes)).use {
                val ads = if (it.readBoolean()) it.readUTF() else null
                val bar = if (it.readBoolean()) it.readUTF() else null
                val kind = it.readInt(); val other = it.readUTF()
                val unknown = ByteArray(it.readInt()); it.readFully(unknown)
                PauseReply(ads, bar, kind, other, unknown)
            }
        }
    }
    class JavaPauseMoss {
        fun executePlayPause(request: PauseReq): PauseReply = PauseReply.getDefaultInstance()
        fun playPause(request: PauseReq, handler: MossResponseHandler) = Unit
    }
    class MissingAsyncPauseMoss {
        fun executePlayPause(request: PauseReq): PauseReply = PauseReply.getDefaultInstance()
    }
    class KotlinPauseMoss {
        fun playPause(request: Any, serializer: SerializationStrategy<*>, deserializer: DeserializationStrategy<*>,
            handler: MossResponseHandler, protoBuf: ProtoBuf) = Unit
    }
    class LegacyKotlinPauseMoss {
        fun playPause(request: Any, serializer: SerializationStrategy<*>, deserializer: DeserializationStrategy<*>,
            handler: MossResponseHandler) = Unit
    }
    interface OtherHandler { fun onNext(reply: Any?) }
    class AmbiguousKotlinPauseMoss {
        fun playPause(request: Any, serializer: SerializationStrategy<*>, deserializer: DeserializationStrategy<*>,
            handler: MossResponseHandler, protoBuf: ProtoBuf) = Unit
        fun playPause(request: Any, serializer: SerializationStrategy<*>, deserializer: DeserializationStrategy<*>,
            handler: OtherHandler, protoBuf: ProtoBuf) = Unit
    }
    class KotlinPause(val message: PauseReply)
    object Strategy : SerializationStrategy<Any>, DeserializationStrategy<Any>
    class PauseProtoBuf : ProtoBuf() {
        override fun encodeToByteArray(serializer: SerializationStrategy<*>, value: Any): ByteArray =
            (value as KotlinPause).message.toByteArray()
        override fun decodeFromByteArray(deserializer: DeserializationStrategy<*>, bytes: ByteArray): Any =
            KotlinPause(PauseReply.parseFrom(bytes))
    }

    open class TemplateBaseLambda
    open class TemplateBaseCont
    interface TemplateBaseFunction2
    interface TemplateBaseContinuation
    class TemplateKTabType
    class TemplateKPlayPauseReq
    class TemplateKPauseBar
    interface TemplateKIData
    class TemplateKPlayPauseReply {
        fun getBar(): TemplateKPauseBar = TemplateKPauseBar()
        fun getData(): TemplateKIData = object : TemplateKIData {}
    }
    class TemplateKViewMoss {
        fun playPause(req: TemplateKPlayPauseReq, cont: TemplateBaseContinuation): Any = Unit
    }
    class TemplateLambda : TemplateBaseLambda() {
        fun invokeSuspend(arg: Any?): Any? = arg
    }
    class TemplateCont : TemplateBaseCont() {
        fun invokeSuspend(arg: Any?): Any? = arg
    }
    class TemplateApiOwner {
        @Suppress("UNUSED_PARAMETER")
        fun f(
            a1: Long, a2: Long, a3: TemplateKTabType, a4: String, a5: Int,
            a6: Long, a7: Long, a8: Int, a9: Int, a10: Boolean,
            a11: List<*>, a12: Int, a13: String, a14: String,
            a15: TemplateBaseFunction2, a16: TemplateBaseCont
        ): Any = Unit
    }

    class TemplateIntraClassAmbiguousApiOwner {
        @Suppress("UNUSED_PARAMETER")
        fun f(
            a1: Long, a2: Long, a3: TemplateKTabType, a4: String, a5: Int,
            a6: Long, a7: Long, a8: Int, a9: Int, a10: Boolean,
            a11: List<*>, a12: Int, a13: String, a14: String,
            a15: TemplateBaseFunction2, a16: TemplateBaseCont
        ): Any = Unit

        @Suppress("UNUSED_PARAMETER")
        fun g(
            a1: Long, a2: Long, a3: TemplateKTabType, a4: String, a5: Int,
            a6: Long, a7: Long, a8: Int, a9: Int, a10: Boolean,
            a11: List<*>, a12: Int, a13: String, a14: String,
            a15: TemplateBaseFunction2, a16: TemplateBaseCont
        ): Any = Unit
    }

    fun `intra-class 2-method 16-param ambiguity fails closed even when another class has a single entry`() {
        val templates = linkedMapOf<String, Class<*>>(
            "kotlin.coroutines.jvm.internal.SuspendLambda" to TemplateBaseLambda::class.java,
            "kotlin.coroutines.jvm.internal.ContinuationImpl" to TemplateBaseCont::class.java,
            "kotlin.jvm.functions.Function2" to TemplateBaseFunction2::class.java,
            "kotlin.coroutines.Continuation" to TemplateBaseContinuation::class.java,
            "com.bapis.bilibili.app.viewunite.v1.KTabType" to TemplateKTabType::class.java,
            "com.bapis.bilibili.app.viewunite.v1.KPlayPauseReq" to TemplateKPlayPauseReq::class.java,
            "com.bapis.bilibili.app.viewunite.v1.KPauseBar" to TemplateKPauseBar::class.java,
            "com.bapis.bilibili.app.viewunite.v1.KPlayPauseReply\$IData" to TemplateKIData::class.java,
            "com.bapis.bilibili.app.viewunite.v1.KPlayPauseReply" to TemplateKPlayPauseReply::class.java,
            "com.bapis.bilibili.app.viewunite.v1.KViewMoss" to TemplateKViewMoss::class.java,
            "kntr.app.ad.biz.videodetail.pausedpage.AdPausedPageApi\$requestPausedPage\$awaitResponse\$2\$1" to TemplateLambda::class.java,
            "kntr.app.ad.biz.videodetail.pausedpage.AdPausedPageApi\$receivePausedPageResult\$2" to TemplateLambda::class.java,
            "kntr.app.ad.biz.videodetail.pausedpage.AdPausedPageApi\$requestPausedPage\$1" to TemplateCont::class.java,
            "kntr.app.ad.biz.videodetail.pausedpage.a" to TemplateIntraClassAmbiguousApiOwner::class.java,
            "kntr.app.ad.biz.videodetail.pausedpage.z" to TemplateApiOwner::class.java
        )
        val replacements = mapOf(
            TemplateBaseLambda::class.java.name.replace('.', '/') to "kotlin/coroutines/jvm/internal/SuspendLambda",
            TemplateBaseCont::class.java.name.replace('.', '/') to "kotlin/coroutines/jvm/internal/ContinuationImpl",
            TemplateBaseFunction2::class.java.name.replace('.', '/') to "kotlin/jvm/functions/Function2",
            TemplateBaseContinuation::class.java.name.replace('.', '/') to "kotlin/coroutines/Continuation",
            TemplateKTabType::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/viewunite/v1/KTabType",
            TemplateKPlayPauseReq::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/viewunite/v1/KPlayPauseReq",
            TemplateKPauseBar::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/viewunite/v1/KPauseBar",
            TemplateKIData::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/viewunite/v1/KPlayPauseReply\$IData",
            TemplateKPlayPauseReply::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/viewunite/v1/KPlayPauseReply",
            TemplateKViewMoss::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/viewunite/v1/KViewMoss"
        )
        val loader = TestRenamingClassLoader(javaClass.classLoader!!, templates = templates, replacements = replacements)
        assertFalse(PauseRequestCoverage.hasVerifiedApiEntry(loader, hostVersionCode = 9210100))
        assertTrue(PauseRequestCoverage.covered(loader, 9210100, setOf("ad-api"), kotlinInstalled = true).isEmpty())
    }

    private class WireStrippedDescriptor(
        pkg: String,
        svc: String,
        mtd: String
    ) : Descriptor(pkg, svc, mtd, Any::class, Strategy) {
        override fun toString(): String = "kmp.Descriptor@${Integer.toHexString(System.identityHashCode(this))}"
    }

    fun `wire-first cleans pause response without Java or Kotlin Moss`() {
        val pauseReg = PlayerPortTestRegistrar()
        val pausePoints = PauseResponseAdapter.resolve(PauseReply::class.java, PauseReq::class.java, null, null)
        val wireMethod = GrpcEngine::class.java.getDeclaredMethod(
            "a",
            Service::class.java,
            Descriptor::class.java,
            ByteArray::class.java,
            Handler::class.java,
            Any::class.java
        )
        val pauseCov = PauseResponseAdapter.install(
            environment(pauseReg),
            pausePoints,
            kmpGrpcWireEngine = KmpGrpcWireAdapter.point(wireMethod)
        )
        assertEquals(PauseResponseAdapter.Coverage(1, 1, kotlinInstalled = true), pauseCov)
        var pauseOut: ByteArray? = null
        val handler = object : Handler {
            override fun onNext(bytes: ByteArray?) { pauseOut = bytes }
            override fun onCompleted() = Unit
            override fun onError(error: KMossException) = Unit
        }
        val descriptor = WireStrippedDescriptor("bilibili.app.viewunite.v1", "View", "PlayPause")
        pauseReg.invoke("kmp.grpc.wire.channel", GrpcEngine(emptyList()), arrayOf(Service("host"), descriptor, ByteArray(0), handler, null)) { passed ->
            (passed[3] as Handler).onNext(PauseReply("ad-payload", "bar-keep", 0, "meta").toByteArray())
            null
        }
        val pauseDecoded = PauseReply.parseFrom(requireNotNull(pauseOut))
        assertFalse(pauseDecoded.hasAds())
        assertEquals("bar-keep", pauseDecoded.bar)
        assertEquals("meta", pauseDecoded.other)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (PauseResponseInstallerTest) -> Unit) {
            val s = PauseResponseInstallerTest()
            block(s)
        }
        runIsolated { s -> s.`4-param legacy KViewMoss resolves and registers via default ProtoBuf`() }
        runIsolated { s -> s.`sync async and Kotlin responses each have their own registration`() }
        runIsolated { s -> s.`absent methods ambiguous callbacks and registration failures remain in the denominator`() }
        runIsolated { s -> s.`request routes require the audited host ABI and the registered Kotlin boundary`() }
        runIsolated { s -> s.`request entry owner is pinned per verified build and unknown builds stay closed`() }
        runIsolated { s -> s.`future 921+ builds resolve unique 16-param AdPausedPageApi structurally and fail closed on ambiguity or missing ABI`() }
        runIsolated { s -> s.`sync cleans only ads and copies bar extra fields and Java unknown fields`() }
        runIsolated { s -> s.`async keeps callback errors completion and delegate exceptions unchanged`() }
        runIsolated { s -> s.`Kotlin bridge rewrites only a successful copy and preserves a bar only reply identity`() }
        runIsolated { s -> s.`failed Kotlin write or changed bar readback passes the original and never claims applied`() }
        runIsolated { s -> s.`identified requests remain untouched and an incomplete response layer cannot turn green`() }
        runIsolated { s -> s.`9190300 and 9180000 builds also resolve unique 16-param AdPausedPageApi structurally and fail closed on ambiguity`() }
        runIsolated { s -> s.`paused ad installer retries kmp grpc wire idempotently without re-registering java hooks`() }
        runIsolated { s -> s.`intra-class 2-method 16-param ambiguity fails closed even when another class has a single entry`() }
        runIsolated { s -> s.`wire-first cleans pause response without Java or Kotlin Moss`() }
    }
}
