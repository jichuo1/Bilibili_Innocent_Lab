package com.Bilibili_Innocent_Lab.xposedmodule.hook

import android.content.Context
import android.content.ContextWrapper
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.CommentTopologyPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistAttemptGuard
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexMeguvizAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexMeguvizGraphPlans
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexSourceFingerprint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.TieredDexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.config.SnapshotHookConfigSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentTopologyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.GamePromotionFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HomeBannerFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KmpMethodDescriptorResolver
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MerchandiseFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.PrivacyFeatureRuntime
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookIds
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ReflectAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.invoke
import io.github.libxposed.api.XposedInterface
import kotlinx.serialization.SerializationStrategy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Executable
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.reflect.KClass

class StageBAdaptAndKmpTest {

    // -------------------------------------------------------------------------
    // 1. Subprocess routing matrix & main-process installer guards
    // -------------------------------------------------------------------------

    @Test
    fun `startup adapter subprocess profile matrix separates main ijk and background processes`() {
        val emptyConfig = SnapshotHookConfigSource(emptyMap(), 1L)
        for (proc in listOf(
            "tv.danmaku.bili",
            "tv.danmaku.bili:ijkservice",
            "tv.danmaku.bili:web",
            "tv.danmaku.bili:download",
            "tv.danmaku.bili:pushservice",
            "tv.danmaku.bili:unknown"
        )) {
            assertTrue(
                "excludedForProcess(..., nPatchRuntime=false) contract must stay empty",
                StartupAdapterPlan.excludedForProcess(emptyConfig, proc, false).isEmpty()
            )
        }

        val main = StartupAdapterPlan.processProfile("tv.danmaku.bili")
        assertEquals(StartupAdapterPlan.ProcessRole.MAIN, main.role)
        assertTrue(main.isMainProcess)
        assertTrue(main.runVersionAdapter)
        assertTrue(main.installMainProcessUiAndFeed)
        assertTrue(main.installIncognitoPrivacy)
        assertTrue(main.installPlayerAvAndCodecs)
        assertTrue(main.installComponentLibraryBlock)

        val ijk = StartupAdapterPlan.processProfile("tv.danmaku.bili:ijkservice")
        assertEquals(StartupAdapterPlan.ProcessRole.IJK_SERVICE, ijk.role)
        assertFalse(ijk.isMainProcess)
        assertFalse(ijk.runVersionAdapter)
        assertFalse(ijk.installMainProcessUiAndFeed)
        assertTrue(ijk.installIncognitoPrivacy)
        assertTrue(ijk.installPlayerAvAndCodecs)
        assertTrue(ijk.installComponentLibraryBlock)

        for (proc in listOf(
            "tv.danmaku.bili:web" to StartupAdapterPlan.ProcessRole.WEB,
            "tv.danmaku.bili:download" to StartupAdapterPlan.ProcessRole.DOWNLOAD,
            "tv.danmaku.bili:pushservice" to StartupAdapterPlan.ProcessRole.BACKGROUND_SERVICE,
            "tv.danmaku.bili:channel" to StartupAdapterPlan.ProcessRole.BACKGROUND_SERVICE,
            "tv.danmaku.bili:daemon" to StartupAdapterPlan.ProcessRole.BACKGROUND_SERVICE,
            "tv.danmaku.bili:other" to StartupAdapterPlan.ProcessRole.UNKNOWN_SUBPROCESS
        )) {
            val profile = StartupAdapterPlan.processProfile(proc.first)
            assertEquals(proc.second, profile.role)
            assertFalse(profile.isMainProcess)
            assertFalse(profile.runVersionAdapter)
            assertFalse(profile.installMainProcessUiAndFeed)
            assertTrue(profile.installIncognitoPrivacy)
            assertFalse(profile.installPlayerAvAndCodecs)
            assertTrue(profile.installComponentLibraryBlock)
        }
    }

    @Test
    fun `game promotion home banner and merchandise installers skip non-main processes`() {
        val statuses = linkedMapOf<String, String>()
        val registrar = PlayerPortTestRegistrar()
        val env = HookEnvironment(
            processName = "tv.danmaku.bili:pushservice",
            classLoader = javaClass.classLoader,
            hookPoints = HookPointRegistry(javaClass.classLoader),
            registrar = registrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { k, v -> statuses[k] = v }
        )
        assertEquals(FeatureInstallResult.Skipped("non-main-process"), GamePromotionFeatureInstaller(true).install(env))
        assertEquals("non-main-process", statuses["gamecard_ad_status"])

        assertEquals(
            FeatureInstallResult.Skipped("non-main-process"),
            HomeBannerFeatureInstaller(true, null) { false }.install(env)
        )
        assertEquals("non-main-process", statuses["banner_ad_status"])

        assertEquals(FeatureInstallResult.Skipped("non-main-process"), MerchandiseFeatureInstaller(true).install(env))
        assertTrue(registrar.hooks.isEmpty())
    }

    // -------------------------------------------------------------------------
    // 2. ModernHookIds cross-loader isolation, argsMutated, Array descriptors, Bridge preference
    // -------------------------------------------------------------------------

    class SameNameTarget {
        @Suppress("unused")
        fun ping(value: String): String = value
    }

    @Test
    fun `modern hook ids isolate identical class names across distinct class loaders`() {
        val resource = SameNameTarget::class.java.name.replace('.', '/') + ".class"
        val classBytes = requireNotNull(javaClass.classLoader!!.getResourceAsStream(resource)).use { it.readBytes() }
        val loaderA = object : ClassLoader(null) {
            fun define(): Class<*> = defineClass(SameNameTarget::class.java.name, classBytes, 0, classBytes.size)
        }
        val loaderB = object : ClassLoader(null) {
            fun define(): Class<*> = defineClass(SameNameTarget::class.java.name, classBytes, 0, classBytes.size)
        }
        val methodA = loaderA.define().getDeclaredMethod("ping", String::class.java)
        val methodB = loaderB.define().getDeclaredMethod("ping", String::class.java)
        assertNotEquals(loaderA, loaderB)
        assertNotEquals(ModernHookIds.of("same", methodA).value(), ModernHookIds.of("same", methodB).value())
    }

    private class TrackingChain(
        private val target: Executable,
        private val receiver: Any?,
        private val incoming: Array<Any?>,
        private val impl: (Array<Any?>) -> Any?
    ) : XposedInterface.Chain {
        var proceedNoArgsCalls = 0
        var proceedWithArgsCalls = 0
        var lastPassedArgs: Array<Any?>? = null
        override fun getExecutable(): Executable = target
        override fun getThisObject(): Any? = receiver
        override fun getArgs(): List<Any?> = incoming.toList()
        override fun getArg(index: Int): Any? = incoming[index]
        override fun proceed(): Any? {
            proceedNoArgsCalls++
            lastPassedArgs = incoming
            return impl(incoming)
        }
        override fun proceed(args: Array<Any?>): Any? {
            proceedWithArgsCalls++
            lastPassedArgs = args
            return impl(args)
        }
        override fun proceedWith(thisObject: Any): Any? = proceed()
        override fun proceedWith(thisObject: Any, args: Array<Any?>): Any? = proceed(args)
    }

    class ReadAndWriteArgsTarget {
        fun combine(a: String, b: String): String = "$a:$b"
    }

    @Test
    fun `modern hook param avoids chain proceed array copy for read-only args access and forwards mutated args`() {
        val method = ReadAndWriteArgsTarget::class.java.getDeclaredMethod("combine", String::class.java, String::class.java)
        val receiver = ReadAndWriteArgsTarget()

        var readFirst: Any? = null
        val readOnlyHook = ModernMemberHookCreator(method).apply {
            before { readFirst = args[0] }
        }
        val readChain = TrackingChain(method, receiver, arrayOf("x", "y")) { a -> receiver.combine(a[0] as String, a[1] as String) }
        assertEquals("x:y", readOnlyHook.invoke(readChain))
        assertEquals("x", readFirst)
        assertEquals(1, readChain.proceedNoArgsCalls)
        assertEquals(0, readChain.proceedWithArgsCalls)

        val mutatedViaArray = ModernMemberHookCreator(method).apply {
            before { args[0] = "z" }
        }
        val mutateArrayChain = TrackingChain(method, receiver, arrayOf("x", "y")) { a -> receiver.combine(a[0] as String, a[1] as String) }
        assertEquals("z:y", mutatedViaArray.invoke(mutateArrayChain))
        assertEquals(0, mutateArrayChain.proceedNoArgsCalls)
        assertEquals(1, mutateArrayChain.proceedWithArgsCalls)
        assertArrayEquals(arrayOf("x", "y"), mutateArrayChain.args.toTypedArray())
        assertArrayEquals(arrayOf("z", "y"), mutateArrayChain.lastPassedArgs)

        val mutatedViaSetArg = ModernMemberHookCreator(method).apply {
            before { setArg(1, "w") }
        }
        val mutateSetArgChain = TrackingChain(method, receiver, arrayOf("x", "y")) { a -> receiver.combine(a[0] as String, a[1] as String) }
        assertEquals("x:w", mutatedViaSetArg.invoke(mutateSetArgChain))
        assertEquals(0, mutateSetArgChain.proceedNoArgsCalls)
        assertEquals(1, mutateSetArgChain.proceedWithArgsCalls)
    }

    @Test
    fun `hook point registry resolves jvm array descriptors`() {
        val registry = HookPointRegistry(javaClass.classLoader)
        val classes = registry.resolveParameterClasses(
            listOf("[Ljava.lang.String;", "[I", "[[B", "int")
        )
        assertNotNull(classes)
        assertArrayEquals(
            arrayOf(Array<String>::class.java, IntArray::class.java, Array<ByteArray>::class.java, Int::class.javaPrimitiveType),
            classes!!
        )
    }

    interface GenericBase<T> {
        fun produce(input: T): Any
    }

    open class ConcreteCovariant : GenericBase<String> {
        override fun produce(input: String): String = input.uppercase()
    }

    @Test
    fun `reflect access prefers non-bridge concrete method over synthetic bridge method`() {
        val instance = ConcreteCovariant()
        val chosen = ReflectAccess.findCompatibleMethod(ConcreteCovariant::class.java, "produce", arrayOf("hi"))
        assertNotNull(chosen)
        assertFalse(chosen!!.isBridge)
        assertFalse(chosen.isSynthetic)
        assertEquals(String::class.java, chosen.returnType)
        assertEquals("HI", chosen.invoke(instance, "hi"))
        assertEquals("HI", ReflectAccess.callMethod(instance, "produce", "hi"))
    }

    // -------------------------------------------------------------------------
    // 3. KmpMethodDescriptorResolver semantic classification & custom toString
    // -------------------------------------------------------------------------

    private object DummyStrategy : SerializationStrategy<Any>
    private class MainListReq

    private class ObfToStringDescriptor(
        val packageName: String,
        val serviceName: String,
        val methodName: String
    ) {
        @Suppress("unused")
        val k: KClass<*> = Any::class
        @Suppress("unused")
        val s: SerializationStrategy<Any> = DummyStrategy
        override fun toString(): String = "ObfDesc(compact)"
    }

    private class ReorderedKClassDescriptor {
        @Suppress("unused") @JvmField val a: String = "MainList"
        @Suppress("unused") @JvmField val b: String = "bilibili.main.community.reply.v1"
        @Suppress("unused") @JvmField val c: String = "Reply"
        @Suppress("unused") @JvmField val req: KClass<*> = MainListReq::class
        @Suppress("unused") @JvmField val strat: SerializationStrategy<Any> = DummyStrategy
        override fun toString(): String = "minified-desc"
    }

    private class ReorderedServiceSuffixDescriptor {
        @Suppress("unused") @JvmField val x: String = "DefaultWords"
        @Suppress("unused") @JvmField val y: String = "SearchService"
        @Suppress("unused") @JvmField val z: String = "bilibili.app.interface.v1"
        @Suppress("unused") @JvmField val req: KClass<*> = Any::class
        @Suppress("unused") @JvmField val strat: SerializationStrategy<Any> = DummyStrategy
        override fun toString(): String = "obf.S@1a"
    }

    private class PojoCustomToString(
        @Suppress("unused") val packageName: String,
        @Suppress("unused") val serviceName: String,
        @Suppress("unused") val methodName: String
    ) {
        override fun toString(): String = "NotADescriptor()"
    }

    @Test
    fun `kmp method descriptor resolver handles non-object toString and semantic field order`() {
        KmpMethodDescriptorResolver.clearCaches()
        assertEquals(
            "bilibili.app.view.v1.View/PlayView",
            KmpMethodDescriptorResolver.resolve(ObfToStringDescriptor("bilibili.app.view.v1", "View", "PlayView"))
        )
        assertEquals(
            "bilibili.main.community.reply.v1.Reply/MainList",
            KmpMethodDescriptorResolver.resolve(ReorderedKClassDescriptor())
        )
        assertEquals(
            "bilibili.app.interface.v1.SearchService/DefaultWords",
            KmpMethodDescriptorResolver.resolve(ReorderedServiceSuffixDescriptor())
        )
        assertNull(KmpMethodDescriptorResolver.resolve(PojoCustomToString("a.b", "Svc", "Call")))
    }

    // -------------------------------------------------------------------------
    // 4. CommentTopologyFeatureInstaller Compose/KMP decoupling from RecyclerView binds
    // -------------------------------------------------------------------------

    class FakeReplyInfo {
        fun getId(): Long = 100L
        fun getOid(): Long = 200L
        fun getType(): Long = 1L
        fun getRoot(): Long = 0L
        fun getParent(): Long = 0L
        fun getDialog(): Long = 0L
        fun getCtime(): Long = 1L
        fun getCount(): Long = 1L
        fun getMid(): Long = 1L
        fun getContent(): FakeContent = FakeContent()
        fun getMemberV2(): FakeMemberV2 = FakeMemberV2()
        fun getRepliesList(): List<Any> = emptyList()
    }

    class FakeContent { fun getMessage(): String = "hi" }
    class FakeMemberV2 { fun getBasic(): FakeMemberBasic = FakeMemberBasic() }
    class FakeMemberBasic { fun getName(): String = "up" }
    class FakeCommentItem { fun getId(): Long = 100L }
    class FakePagination {
        class Builder { fun setNextOffset(@Suppress("UNUSED_PARAMETER") o: String): Builder = this; fun build(): FakePagination = FakePagination() }
        companion object { @JvmStatic fun newBuilder(): Builder = Builder() }
        fun getNextOffset(): String = ""
    }
    class FakeDetailReq {
        class Builder {
            fun setOid(@Suppress("UNUSED_PARAMETER") v: Long): Builder = this
            fun setType(@Suppress("UNUSED_PARAMETER") v: Long): Builder = this
            fun setMode(@Suppress("UNUSED_PARAMETER") v: Int): Builder = this
            fun setPagination(@Suppress("UNUSED_PARAMETER") p: FakePagination): Builder = this
            fun setRoot(@Suppress("UNUSED_PARAMETER") v: Long): Builder = this
            fun setRpid(@Suppress("UNUSED_PARAMETER") v: Long): Builder = this
            fun build(): FakeDetailReq = FakeDetailReq()
        }
        companion object { @JvmStatic fun newBuilder(): Builder = Builder() }
    }
    interface FakeMossHandler { fun onNext(v: Any?) }
    class FakeReplyMoss {
        @Suppress("unused")
        fun detailList(@Suppress("UNUSED_PARAMETER") req: FakeDetailReq, @Suppress("UNUSED_PARAMETER") handler: FakeMossHandler) = Unit
    }
    class FakeDetailReply {
        fun getRoot(): FakeReplyInfo = FakeReplyInfo()
        fun getPagination(): FakePagination = FakePagination()
    }
    object FakeCommentMapper {
        @JvmStatic
        fun map(@Suppress("UNUSED_PARAMETER") info: FakeReplyInfo): FakeCommentItem = FakeCommentItem()
    }
    class FakeComposeModel
    class FakeWireReplyMsg {
        fun toByteArray(): ByteArray = ByteArray(0)
        companion object { @JvmStatic fun parseFrom(@Suppress("UNUSED_PARAMETER") bytes: ByteArray): FakeWireReplyMsg = FakeWireReplyMsg() }
    }

    @Test
    fun `comment topology installs compose and kmp hooks even when legacy recycler binds are absent`() {
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
                "kntr.common.comment.card.model.comment.CommentModel" -> FakeComposeModel::class.java
                "com.bapis.bilibili.main.community.reply.v1.MainListReply",
                "com.bapis.bilibili.main.community.reply.v1.DetailListReply",
                "com.bapis.bilibili.main.community.reply.v1.DialogListReply" -> FakeWireReplyMsg::class.java
                else -> super.loadClass(name, resolve)
            }
        }
        val methods = mapOf(
            CommentTopologyPoints.COMMENT_ITEM_ID to HookPoint(FakeCommentItem::class.java.name, "getId", emptyList()),
            CommentTopologyPoints.REPLY_ID to HookPoint(FakeReplyInfo::class.java.name, "getId", emptyList()),
            CommentTopologyPoints.REPLY_OID to HookPoint(FakeReplyInfo::class.java.name, "getOid", emptyList()),
            CommentTopologyPoints.REPLY_TYPE to HookPoint(FakeReplyInfo::class.java.name, "getType", emptyList()),
            CommentTopologyPoints.REPLY_ROOT to HookPoint(FakeReplyInfo::class.java.name, "getRoot", emptyList()),
            CommentTopologyPoints.REPLY_PARENT to HookPoint(FakeReplyInfo::class.java.name, "getParent", emptyList()),
            CommentTopologyPoints.REPLY_DIALOG to HookPoint(FakeReplyInfo::class.java.name, "getDialog", emptyList()),
            CommentTopologyPoints.REPLY_CTIME to HookPoint(FakeReplyInfo::class.java.name, "getCtime", emptyList()),
            CommentTopologyPoints.REPLY_COUNT to HookPoint(FakeReplyInfo::class.java.name, "getCount", emptyList()),
            CommentTopologyPoints.REPLY_MID to HookPoint(FakeReplyInfo::class.java.name, "getMid", emptyList()),
            CommentTopologyPoints.REPLY_CONTENT to HookPoint(FakeReplyInfo::class.java.name, "getContent", emptyList()),
            CommentTopologyPoints.REPLY_MEMBER_V2 to HookPoint(FakeReplyInfo::class.java.name, "getMemberV2", emptyList()),
            CommentTopologyPoints.REPLY_CHILDREN to HookPoint(FakeReplyInfo::class.java.name, "getRepliesList", emptyList()),
            CommentTopologyPoints.CONTENT_MESSAGE to HookPoint(FakeContent::class.java.name, "getMessage", emptyList()),
            CommentTopologyPoints.MEMBER_V2_BASIC to HookPoint(FakeMemberV2::class.java.name, "getBasic", emptyList()),
            CommentTopologyPoints.MEMBER_BASIC_NAME to HookPoint(FakeMemberBasic::class.java.name, "getName", emptyList()),
            CommentTopologyPoints.PAGINATION_NEW_BUILDER to HookPoint(FakePagination::class.java.name, "newBuilder", emptyList()),
            CommentTopologyPoints.PAGINATION_SET_OFFSET to HookPoint(FakePagination.Builder::class.java.name, "setNextOffset", listOf(String::class.java.name)),
            CommentTopologyPoints.PAGINATION_BUILD to HookPoint(FakePagination.Builder::class.java.name, "build", emptyList()),
            CommentTopologyPoints.DETAIL_NEW_BUILDER to HookPoint(FakeDetailReq::class.java.name, "newBuilder", emptyList()),
            CommentTopologyPoints.DETAIL_SET_OID to HookPoint(FakeDetailReq.Builder::class.java.name, "setOid", listOf("long")),
            CommentTopologyPoints.DETAIL_SET_TYPE to HookPoint(FakeDetailReq.Builder::class.java.name, "setType", listOf("long")),
            CommentTopologyPoints.DETAIL_SET_MODE to HookPoint(FakeDetailReq.Builder::class.java.name, "setMode", listOf("int")),
            CommentTopologyPoints.DETAIL_SET_PAGINATION to HookPoint(FakeDetailReq.Builder::class.java.name, "setPagination", listOf(FakePagination::class.java.name)),
            CommentTopologyPoints.DETAIL_SET_ROOT to HookPoint(FakeDetailReq.Builder::class.java.name, "setRoot", listOf("long")),
            CommentTopologyPoints.DETAIL_SET_RPID to HookPoint(FakeDetailReq.Builder::class.java.name, "setRpid", listOf("long")),
            CommentTopologyPoints.DETAIL_BUILD to HookPoint(FakeDetailReq.Builder::class.java.name, "build", emptyList()),
            CommentTopologyPoints.MOSS_DETAIL to HookPoint(FakeReplyMoss::class.java.name, "detailList", listOf(FakeDetailReq::class.java.name, FakeMossHandler::class.java.name)),
            CommentTopologyPoints.DETAIL_ROOT to HookPoint(FakeDetailReply::class.java.name, "getRoot", emptyList()),
            CommentTopologyPoints.DETAIL_PAGINATION to HookPoint(FakeDetailReply::class.java.name, "getPagination", emptyList()),
            CommentTopologyPoints.PAGINATION_NEXT_OFFSET to HookPoint(FakePagination::class.java.name, "getNextOffset", emptyList())
        )
        val topologyPoints = CommentTopologyPoints(
            mapperMethods = listOf(HookPoint(FakeCommentMapper::class.java.name, "map", listOf(FakeReplyInfo::class.java.name))),
            replyMossClassName = FakeReplyMoss::class.java.name,
            methods = methods
        )
        val wireEntry = kmpfixture.modern.GrpcEngine::class.java.declaredMethods.single { it.name == "a" }
        val wirePoint = KmpGrpcWireAdapter.point(wireEntry)
        val registrar = PlayerPortTestRegistrar()
        val statuses = linkedMapOf<String, String>()
        val env = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = loader,
            hookPoints = HookPointRegistry(loader),
            registrar = registrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { k, v -> statuses[k] = v }
        )
        val installer = CommentTopologyFeatureInstaller(
            enabled = true,
            points = topologyPoints,
            lowBindPoint = null,
            highBindPoint = null,
            composeMenuPoints = null,
            kmpGrpcWireEngine = wirePoint
        )
        val result = installer.install(env)
        assertTrue("Compose/KMP hooks must install even when legacy binds are missing: $result", result is FeatureInstallResult.Installed)
        val installed = result as FeatureInstallResult.Installed
        assertTrue(installed.hookCount >= 1)
        assertFalse(installed.complete)
        assertEquals("partial", statuses["comment_topology_status"])
        assertTrue(registrar.hooks.containsKey("kmp.grpc.wire.channel"))
    }

    // -------------------------------------------------------------------------
    // 5. DexMeguviz graph fallbackOnly, AttemptGuard tiered retry, SourceFingerprint, Privacy retry
    // -------------------------------------------------------------------------

    @Test
    fun `graph plans for paused countdown and home tab resource prioritize tier1 fallbackOnly`() {
        val pausedPlans = DexMeguvizGraphPlans.plans(DexAssistQuery.PAUSED_AD_COUNTDOWN)
        assertTrue("PAUSED_AD_COUNTDOWN should have plans", pausedPlans.isNotEmpty())
        assertTrue("PAUSED_AD_COUNTDOWN plans should all mark fallbackOnly=true", pausedPlans.all { it.fallbackOnly })
        assertFalse(DexMeguvizGraphPlans.requiresArchiveSnapshot(setOf(DexAssistQuery.PAUSED_AD_COUNTDOWN)))

        val homeTabPlans = DexMeguvizGraphPlans.plans(DexAssistQuery.HOME_TAB_RESOURCE)
        assertEquals(2, homeTabPlans.size)
        assertTrue("HOME_TAB_RESOURCE heavy entity plan should mark fallbackOnly=true", homeTabPlans[0].fallbackOnly)
        assertFalse("HOME_TAB_RESOURCE light model MethodUses plan remains fallbackOnly=false", homeTabPlans[1].fallbackOnly)
    }

    @Test
    fun `dex assist attempt guard supports tiered maxAttempts=2 and tier1-only retry or low memory`() {
        val dir = Files.createTempDirectory("bil-tiered-guard").toFile()
        try {
            val marker = File(dir, "pending")
            val recordedAllowTier2 = mutableListOf<Boolean>()
            val crashOnceEngine = object : TieredDexAssistEngine {
                override fun resolve(request: com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistRequest) =
                    DexAssistResult.Candidates(emptyList())

                override fun resolveAll(
                    queries: Set<DexAssistQuery>,
                    codePaths: List<String>,
                    classLoader: ClassLoader
                ) = resolveAll(queries, codePaths, classLoader, allowTier2 = true)

                override fun resolveAll(
                    queries: Set<DexAssistQuery>,
                    codePaths: List<String>,
                    classLoader: ClassLoader,
                    allowTier2: Boolean
                ): Map<DexAssistQuery, DexAssistResult> {
                    recordedAllowTier2 += allowTier2
                    return queries.associateWith { DexAssistResult.Candidates(emptyList()) }
                }
            }

            // Simulate first attempt killed mid-pass (marker + attempts=1 left behind).
            marker.writeText("fp|v1")
            File(dir, "pending.attempts").writeText("fp|v1\n1")

            val guardRetry = DexAssistAttemptGuard(
                marker = marker,
                key = "fp|v1",
                delegate = crashOnceEngine,
                maxAttempts = DexAssistAttemptGuard.TIERED_MAX_ATTEMPTS
            )
            val retryResult = guardRetry.resolveAll(setOf(DexAssistQuery.BLOCK_UPDATE), listOf("/base.apk"), javaClass.classLoader!!)
            assertEquals(listOf(false), recordedAllowTier2)
            assertTrue(retryResult.getValue(DexAssistQuery.BLOCK_UPDATE) is DexAssistResult.Candidates)
            assertFalse(marker.exists())

            // Simulate second unfinished attempt (attempts=2 >= maxAttempts) -> blacklisted!
            marker.writeText("fp|v1")
            File(dir, "pending.attempts").writeText("fp|v1\n2")
            val blocked = guardRetry.resolveAll(setOf(DexAssistQuery.BLOCK_UPDATE), listOf("/base.apk"), javaClass.classLoader!!)
            assertEquals(
                DexAssistResult.Unavailable(DexAssistResult.Reason.PREVIOUS_ATTEMPT_UNFINISHED),
                blocked.getValue(DexAssistQuery.BLOCK_UPDATE)
            )
            marker.delete()
            File(dir, "pending.attempts").delete()

            // Fresh pass under low memory -> skips Tier-2 cleanly.
            val lowMemGuard = DexAssistAttemptGuard(
                marker = marker,
                key = "fp|v1",
                delegate = crashOnceEngine,
                maxAttempts = DexAssistAttemptGuard.TIERED_MAX_ATTEMPTS,
                isLowMemory = { true }
            )
            lowMemGuard.resolveAll(setOf(DexAssistQuery.BLOCK_UPDATE), listOf("/base.apk"), javaClass.classLoader!!)
            assertEquals(listOf(false, false), recordedAllowTier2)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `dex source fingerprint filters non-code resource locale and density splits before max archives 64`() {
        val dir = Files.createTempDirectory("bil-split-filter").toFile()
        try {
            fun writeZip(file: File, entryName: String) {
                ZipOutputStream(file.outputStream()).use { out ->
                    out.putNextEntry(ZipEntry(entryName))
                    out.write(byteArrayOf(1, 2, 3))
                    out.closeEntry()
                }
            }
            val base = File(dir, "base.apk").also { writeZip(it, "classes.dex") }
            val splits = (1..70).map { idx ->
                val name = when {
                    idx % 3 == 0 -> "split_config.xxhdpi_$idx.apk"
                    idx % 2 == 0 -> "split_config.zh_$idx.apk"
                    else -> "split_resources_$idx.apk"
                }
                File(dir, name).also { writeZip(it, "resources.arsc") }
            }
            val allPaths = listOf(base.absolutePath) + splits.map { it.absolutePath }
            val splitNames = listOf<String?>(null) + splits.mapIndexed { i, _ ->
                if (i % 2 == 0) "config.en" else "config.xxhdpi"
            }
            val filteredResult = DexSourceFingerprint.inspectCodePathsWithSplitNames(allPaths, splitNames)
            assertNotNull("Non-code locale/density/resource splits should not trigger >64 rejection", filteredResult)
            assertEquals(listOf(base.canonicalPath), filteredResult!!.codePaths)

            // Actual >64 code APKs still fail closed.
            val codeSplits = (1..65).map { idx -> File(dir, "code_$idx.apk").also { writeZip(it, "classes.dex") } }
            assertNull(DexSourceFingerprint.inspectCodePaths(codeSplits.map { it.absolutePath }))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `dex meguviz native missing falls back to UNAVAILABLE without crashing host`() {
        val result = DexMeguvizAssistEngine.resolveAll(
            setOf(DexAssistQuery.BLOCK_UPDATE),
            listOf("/nonexistent/base.apk"),
            javaClass.classLoader!!
        )
        assertEquals(
            DexAssistResult.Unavailable(DexAssistResult.Reason.NATIVE_UNAVAILABLE),
            result.getValue(DexAssistQuery.BLOCK_UPDATE)
        )
    }

    @Test
    fun `privacy feature runtime adapted retry updates points and completes pending retry safely`() {
        val posted = mutableListOf<() -> Unit>()
        val registrar = PlayerPortTestRegistrar()
        val statuses = linkedMapOf<String, String>()
        val emptyLoader = object : ClassLoader(null) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                if (name == KmpGrpcWireAdapter.INTERCEPTOR) Any::class.java
                else super.loadClass(name, resolve)
        }
        val context: Context = object : ContextWrapper(null) {}
        val env = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = emptyLoader,
            hookPoints = HookPointRegistry(emptyLoader),
            registrar = registrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { k, v -> statuses[k] = v },
            postToMain = { posted += it }
        )
        val runtime = PrivacyFeatureRuntime(
            context = context,
            noLogin = false,
            incognito = true,
            kmpGrpcWireEngine = null
        )
        val coordinator = FeatureInstallCoordinator(env)
        val retryable = runtime.asAdaptRetryable()
        coordinator.registerRetryable(retryable)
        val initial = coordinator.installAll(runtime.installers()).single { it.id == "incognito_privacy" }.result
        assertEquals(FeatureInstallResult.Skipped("missing-host-structure"), initial)
        assertTrue(retryable.isAdaptationRetryPending)

        // When adaptation completes with no privacy points, retry stops without looping.
        coordinator.retryAdapted(adapted = null, ok = true)
        assertFalse(retryable.isAdaptationRetryPending)
        assertTrue(posted.isEmpty())

        // Positive adapted update wiring: updates adapted points cleanly when retry is pending.
        val wirePoint = HookPoint("kmp.Engine", "invoke", emptyList())
        val updatedResult = VersionAdapter.AdaptResult(
            biliVersionCode = 9210200,
            ts = 1000L,
            commentLow = null,
            commentHigh = null,
            mineEntry = null,
            pause = VersionAdapter.PausePoints(emptyList(), null, null, null),
            banner = null,
            homeTopBar = null,
            mineVip = null,
            blockUpdate = null,
            dynamicTabs = null,
            fullNumbers = null,
            playerPortrait = null,
            playerInteractiveOverlays = null,
            pgcAutoActivityPopup = null,
            playerStatusBar = null,
            homeRecommendFeed = null,
            videoRelate = null,
            homeTabs = null,
            homeComponents = null,
            mineComponents = null,
            mineAccountMine = null,
            storyFeed = null,
            bottomBar = null,
            playerQuality = null,
            teenagersMode = null,
            commentPurify = null,
            commentFilter = null,
            commentTopology = null,
            commentSection = null,
            splashAds = null,
            hostFingerprint = "fp",
            protocolFingerprint = "proto",
            diagnostics = emptyList(),
            kmpGrpcWireEngine = wirePoint
        )
        val posted2 = mutableListOf<() -> Unit>()
        val runtime2 = PrivacyFeatureRuntime(
            context = context,
            noLogin = false,
            incognito = true,
            kmpGrpcWireEngine = null
        )
        val retryable2 = runtime2.asAdaptRetryable()
        val coordinator2 = FeatureInstallCoordinator(env.copy(postToMain = { posted2 += it }))
        coordinator2.registerRetryable(retryable2)
        coordinator2.installAll(runtime2.installers())
        assertTrue(retryable2.isAdaptationRetryPending)
        coordinator2.retryAdapted(adapted = updatedResult, ok = true)
        assertEquals(1, posted2.size)
        posted2.single().invoke()
        assertFalse(retryable2.isAdaptationRetryPending)
    }
}
