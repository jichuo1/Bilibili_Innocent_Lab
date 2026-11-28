package com.Bilibili_Innocent_Lab.xposedmodule.hook

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.HostAgentRuntime
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CloudKeyFreezeFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentComposePurifyBridge
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentFullTextReader
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentKotlinPurifier
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentPurifyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentRichTextReader
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentTopologyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ComposeCommentText
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DanmakuPurifyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DetailModulePurifyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallRecord
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FollowFeedFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FollowUpRecommendFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.GamePromotionFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HeadlineBadgeModelCleaner
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HomeBannerFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HomeFrameLockFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HomeVerticalDetailFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HostVideoCardStyleFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KmpGrpcWireChannel
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KmpMethodDescriptorResolver
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KotlinMossChannel
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KotlinMossReplyBridge
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KotlinMossResponseHandlerProxy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MerchandiseFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerAiSubtitleFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerCodecForceFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerCodecPreference
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerDecodeMode
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy.PrivacyFeatureRuntime
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SpecialTopicTagsModelCleaner
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SplashAutoNightFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.AudioRescueModel
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.danmakuwire.DanmakuWire
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.danmakuwire.writeBytesField
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.danmakuwire.writeVarintField
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight.AiInsightFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight.InsightHostSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.writeBackVideoRelateItems
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.ComposeChangedMask
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostMemoryPressureBridge
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopBarFxController
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.invoke
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.TargetAppStorage
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostCardMaterial
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import com.bapis.bilibili.app.playurl.v1.CodeType as LegacyCodeType
import com.bapis.bilibili.app.view.v1.ViewReply
import com.google.protobuf.Internal
import io.github.libxposed.api.XposedInterface
import kmpfixture.modern.Descriptor
import kmpfixture.modern.GrpcEngine
import kmpfixture.modern.Handler
import kmpfixture.modern.KMossException
import kmpfixture.modern.Service
import kmossfixture.KReplyMoss
import kntr.srcs.app.splash.brand.model.BrandSplashMode
import kntr.srcs.app.splash.brand.model.BrandSplashSource
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.reflect.KClass

class FeatureWiringIntegrationTest {
    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private class LocalWiringRegistrar : HookRegistrar {
        data class Entry(val member: Executable, val callback: ModernMemberHookCreator)
        val exactHooks = linkedMapOf<String, Entry>()
        val constructorHooks = linkedMapOf<String, Entry>()

        override fun first(id: String, className: String, methodName: String, block: ModernMemberHookCreator.() -> Unit) = Unit
        override fun all(id: String, className: String, methodName: String, block: ModernMemberHookCreator.() -> Unit) = Unit
        override fun adapted(
            id: String,
            point: HookPoint,
            exceptionPolicy: HookExceptionPolicy,
            block: ModernMemberHookCreator.() -> Unit
        ) = Unit

        override fun exact(
            id: String,
            owner: Class<*>,
            methodName: String,
            vararg parameterTypes: Class<*>,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            val m = owner.getDeclaredMethod(methodName, *parameterTypes)
            require(!Modifier.isAbstract(m.modifiers)) { "Cannot hook abstract executable: $m" }
            exactHooks[id] = Entry(m, ModernMemberHookCreator(m).apply(block))
        }

        override fun constructor(id: String, constructor: Constructor<*>, block: ModernMemberHookCreator.() -> Unit) {
            constructorHooks[id] = Entry(constructor, ModernMemberHookCreator(constructor).apply(block))
        }

        fun invokeExact(id: String, receiver: Any? = null, args: Array<Any?> = emptyArray(), original: (Array<Any?>) -> Any? = { null }): Any? {
            val entry = exactHooks.getValue(id)
            return invokeChain(entry.callback, entry.member, receiver, args, original)
        }

        fun invokeConstructor(id: String, instance: Any, args: Array<Any?>): Any? {
            val entry = constructorHooks.getValue(id)
            return invokeChain(entry.callback, entry.member, instance, args) { null }
        }

        private fun invokeChain(
            creator: ModernMemberHookCreator,
            member: Executable,
            receiver: Any?,
            originalArgs: Array<Any?>,
            original: (Array<Any?>) -> Any?
        ): Any? = creator.invoke(object : XposedInterface.Chain {
            override fun getExecutable() = member
            override fun getThisObject() = receiver
            override fun getArgs() = originalArgs.toList()
            override fun getArg(index: Int) = originalArgs[index]
            override fun proceed() = original(originalArgs)
            override fun proceed(args: Array<Any?>): Any? {
                if (args.size == originalArgs.size) args.copyInto(originalArgs)
                return original(args)
            }
            override fun proceedWith(thisObject: Any) = original(originalArgs)
            override fun proceedWith(thisObject: Any, args: Array<Any?>): Any? {
                if (args.size == originalArgs.size) args.copyInto(originalArgs)
                return original(args)
            }
        })
    }

    private fun env(
        loader: ClassLoader = javaClass.classLoader!!,
        registrar: HookRegistrar = PlayerPortTestRegistrar(),
        statuses: MutableList<Pair<String, String>> = mutableListOf(),
        stages: MutableList<Pair<String, FeatureRuntimeStage>> = mutableListOf(),
        logs: MutableList<String> = mutableListOf(),
        errors: MutableList<String> = mutableListOf()
    ) = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = loader,
        hookPoints = HookPointRegistry(loader),
        registrar = registrar,
        logInfo = { k, m -> logs += "$k:$m" },
        logError = { k, m -> errors += "$k:$m" },
        reportStatus = { ch, s -> statuses += ch to s },
        runtimeEvidence = { id, st, _ -> stages += id to st }
    )

    private fun wirePoint(): HookPoint = KmpGrpcWireAdapter.point(
        GrpcEngine::class.java.declaredMethods.single { it.name == "a" }
    )

    private fun wireSurface(): KmpGrpcWireAdapter.WireSurface = requireNotNull(
        KmpGrpcWireAdapter.inspectEntry(GrpcEngine::class.java.declaredMethods.single { it.name == "a" })
    )

    private fun allocateUnsafe(clazz: Class<*>): Any {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(clazz)
    }

    @Test
    fun kmpWireKotlinMossAndProtobufPurifiers() {
        val loader = javaClass.classLoader!!

        // 1. KmpMethodDescriptorResolver non-object toString & semantic field order
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

        // 2. CommentTopologyFeatureInstaller Compose/KMP decoupling from legacy RecyclerView binds
        val topoLoader = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
                "kntr.common.comment.card.model.comment.CommentModel" -> FakeComposeModel::class.java
                "com.bapis.bilibili.main.community.reply.v1.MainListReply",
                "com.bapis.bilibili.main.community.reply.v1.DetailListReply",
                "com.bapis.bilibili.main.community.reply.v1.DialogListReply" -> FakeWireReplyMsg::class.java
                else -> super.loadClass(name, resolve)
            }
        }
        val methods = mapOf(
            VersionAdapter.CommentTopologyPoints.COMMENT_ITEM_ID to HookPoint(FakeCommentItem::class.java.name, "getId", emptyList()),
            VersionAdapter.CommentTopologyPoints.REPLY_ID to HookPoint(FakeReplyInfo::class.java.name, "getId", emptyList()),
            VersionAdapter.CommentTopologyPoints.REPLY_OID to HookPoint(FakeReplyInfo::class.java.name, "getOid", emptyList()),
            VersionAdapter.CommentTopologyPoints.REPLY_TYPE to HookPoint(FakeReplyInfo::class.java.name, "getType", emptyList()),
            VersionAdapter.CommentTopologyPoints.REPLY_ROOT to HookPoint(FakeReplyInfo::class.java.name, "getRoot", emptyList()),
            VersionAdapter.CommentTopologyPoints.REPLY_PARENT to HookPoint(FakeReplyInfo::class.java.name, "getParent", emptyList()),
            VersionAdapter.CommentTopologyPoints.REPLY_DIALOG to HookPoint(FakeReplyInfo::class.java.name, "getDialog", emptyList()),
            VersionAdapter.CommentTopologyPoints.REPLY_CTIME to HookPoint(FakeReplyInfo::class.java.name, "getCtime", emptyList()),
            VersionAdapter.CommentTopologyPoints.REPLY_COUNT to HookPoint(FakeReplyInfo::class.java.name, "getCount", emptyList()),
            VersionAdapter.CommentTopologyPoints.REPLY_MID to HookPoint(FakeReplyInfo::class.java.name, "getMid", emptyList()),
            VersionAdapter.CommentTopologyPoints.REPLY_CONTENT to HookPoint(FakeReplyInfo::class.java.name, "getContent", emptyList()),
            VersionAdapter.CommentTopologyPoints.REPLY_MEMBER_V2 to HookPoint(FakeReplyInfo::class.java.name, "getMemberV2", emptyList()),
            VersionAdapter.CommentTopologyPoints.REPLY_CHILDREN to HookPoint(FakeReplyInfo::class.java.name, "getRepliesList", emptyList()),
            VersionAdapter.CommentTopologyPoints.CONTENT_MESSAGE to HookPoint(FakeContent::class.java.name, "getMessage", emptyList()),
            VersionAdapter.CommentTopologyPoints.MEMBER_V2_BASIC to HookPoint(FakeMemberV2::class.java.name, "getBasic", emptyList()),
            VersionAdapter.CommentTopologyPoints.MEMBER_BASIC_NAME to HookPoint(FakeMemberBasic::class.java.name, "getName", emptyList()),
            VersionAdapter.CommentTopologyPoints.PAGINATION_NEW_BUILDER to HookPoint(FakePagination::class.java.name, "newBuilder", emptyList()),
            VersionAdapter.CommentTopologyPoints.PAGINATION_SET_OFFSET to HookPoint(FakePagination.Builder::class.java.name, "setNextOffset", listOf(String::class.java.name)),
            VersionAdapter.CommentTopologyPoints.PAGINATION_BUILD to HookPoint(FakePagination.Builder::class.java.name, "build", emptyList()),
            VersionAdapter.CommentTopologyPoints.DETAIL_NEW_BUILDER to HookPoint(FakeDetailReq::class.java.name, "newBuilder", emptyList()),
            VersionAdapter.CommentTopologyPoints.DETAIL_SET_OID to HookPoint(FakeDetailReq.Builder::class.java.name, "setOid", listOf("long")),
            VersionAdapter.CommentTopologyPoints.DETAIL_SET_TYPE to HookPoint(FakeDetailReq.Builder::class.java.name, "setType", listOf("long")),
            VersionAdapter.CommentTopologyPoints.DETAIL_SET_MODE to HookPoint(FakeDetailReq.Builder::class.java.name, "setMode", listOf("int")),
            VersionAdapter.CommentTopologyPoints.DETAIL_SET_PAGINATION to HookPoint(FakeDetailReq.Builder::class.java.name, "setPagination", listOf(FakePagination::class.java.name)),
            VersionAdapter.CommentTopologyPoints.DETAIL_SET_ROOT to HookPoint(FakeDetailReq.Builder::class.java.name, "setRoot", listOf("long")),
            VersionAdapter.CommentTopologyPoints.DETAIL_SET_RPID to HookPoint(FakeDetailReq.Builder::class.java.name, "setRpid", listOf("long")),
            VersionAdapter.CommentTopologyPoints.DETAIL_BUILD to HookPoint(FakeDetailReq.Builder::class.java.name, "build", emptyList()),
            VersionAdapter.CommentTopologyPoints.MOSS_DETAIL to HookPoint(FakeReplyMoss::class.java.name, "detailList", listOf(FakeDetailReq::class.java.name, FakeMossHandler::class.java.name)),
            VersionAdapter.CommentTopologyPoints.DETAIL_ROOT to HookPoint(FakeDetailReply::class.java.name, "getRoot", emptyList()),
            VersionAdapter.CommentTopologyPoints.DETAIL_PAGINATION to HookPoint(FakeDetailReply::class.java.name, "getPagination", emptyList()),
            VersionAdapter.CommentTopologyPoints.PAGINATION_NEXT_OFFSET to HookPoint(FakePagination::class.java.name, "getNextOffset", emptyList())
        )
        val topologyPoints = VersionAdapter.CommentTopologyPoints(
            mapperMethods = listOf(HookPoint(FakeCommentMapper::class.java.name, "map", listOf(FakeReplyInfo::class.java.name))),
            replyMossClassName = FakeReplyMoss::class.java.name,
            methods = methods
        )
        val topoRegistrar = PlayerPortTestRegistrar()
        val topoStatuses = linkedMapOf<String, String>()
        val topoEnv = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = topoLoader,
            hookPoints = HookPointRegistry(topoLoader),
            registrar = topoRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { k, v -> topoStatuses[k] = v }
        )
        val topoResult = CommentTopologyFeatureInstaller(
            enabled = true,
            points = topologyPoints,
            lowBindPoint = null,
            highBindPoint = null,
            composeMenuPoints = null,
            kmpGrpcWireEngine = wirePoint()
        ).install(topoEnv)
        assertTrue(topoResult is FeatureInstallResult.Installed)
        val installedTopo = topoResult as FeatureInstallResult.Installed
        assertTrue(installedTopo.hookCount >= 1)
        assertFalse(installedTopo.complete)
        assertEquals("partial", topoStatuses["comment_topology_status"])
        assertTrue(topoRegistrar.hooks.containsKey("kmp.grpc.wire.channel"))

        // 3. KmpGrpcWireChannel installOrDefer + KotlinMossChannel supersededBy + DetailModulePurify
        val wireRegistrar = PlayerPortTestRegistrar()
        val wireEnv = env(loader, wireRegistrar)
        val wireActive = AtomicBoolean(false)
        val transformCalls = AtomicInteger(0)
        val transformJava: (Any) -> Any = { reply ->
            transformCalls.incrementAndGet()
            val typed = reply as P58_DirectJavaProtoReply
            if (typed.label == "ad") P58_DirectJavaProtoReply("purified") else typed
        }
        assertFalse(
            KmpGrpcWireChannel.installOrDefer(
                environment = wireEnv,
                surface = null,
                javaMossClassName = "com.bapis.bilibili.main.community.reply.v1.ReplyMoss",
                rpc = "mainList",
                javaReplyClass = P58_DirectJavaProtoReply::class.java,
                what = "direct-superseded-check",
                logKey = "p5_8_direct",
                wireActive = wireActive,
                transformJava = transformJava
            )
        )
        assertFalse(wireActive.get())
        assertFalse("kmp.grpc.wire.channel" in wireRegistrar.hooks)

        val bridge = requireNotNull(KotlinMossChannel.prepare(wireEnv, loader, "direct", "p5_8_direct"))
        val kReplyMainList = KReplyMoss::class.java.declaredMethods.single { it.name == "mainList" }
        assertTrue(
            KotlinMossChannel.install(
                environment = wireEnv,
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
            override fun onNext(reply: Any?) { kmossDelivered = reply }
        }
        wireRegistrar.invoke(
            "p5_8.kmoss.mainList",
            KReplyMoss(),
            arrayOf("req", P58_DummyStrategy, P58_DummyStrategy, kmossSink, ProtoBuf())
        ) { args -> (args[3] as KReplyMoss.Handler).onNext("ad"); args[3] }
        assertEquals("K:purified", kmossDelivered)
        assertEquals(1, transformCalls.get())

        assertEquals(1, KmpGrpcWireChannel.bindDeferred(wireEnv, wireSurface()))
        assertTrue(wireActive.get())
        assertTrue("kmp.grpc.wire.channel" in wireRegistrar.hooks)

        kmossDelivered = null
        val skippedHandler = wireRegistrar.invoke(
            "p5_8.kmoss.mainList",
            KReplyMoss(),
            arrayOf("req", P58_DummyStrategy, P58_DummyStrategy, kmossSink, ProtoBuf())
        ) { args -> (args[3] as KReplyMoss.Handler).onNext("ad"); args[3] }
        assertSame(kmossSink, skippedHandler)
        assertEquals("ad", kmossDelivered)
        assertEquals(1, transformCalls.get())

        var wireReceived: ByteArray? = null
        val wireSink = object : Handler {
            override fun onNext(bytes: ByteArray?) { wireReceived = bytes }
            override fun onCompleted() = Unit
            override fun onError(error: KMossException) = Unit
        }
        val descriptor = Descriptor("bilibili.main.community.reply.v1", "Reply", "MainList", String::class, P58_DummyStrategy)
        wireRegistrar.invoke(
            "kmp.grpc.wire.channel",
            GrpcEngine(emptyList()),
            arrayOf(Service("grpc.biliapi.net"), descriptor, ByteArray(0), wireSink, null)
        ) { args -> (args[3] as Handler).onNext(P58_DirectJavaProtoReply("ad").toByteArray()); null }
        assertEquals("purified", P58_DirectJavaProtoReply.parseFrom(requireNotNull(wireReceived)).label)
        assertEquals(2, transformCalls.get())

        val detailLoader = TestRenamingClassLoader(
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
        assertTrue(detailResult is FeatureInstallResult.Installed && detailResult.hookCount == 1)
        val detailKmossId = "detail_module_purify.kmoss.view"
        val kViewHandlerClass = detailLoader.loadClass("com.bapis.bilibili.app.view.v1.KViewMoss\$Handler")
        val rawKViewHandler = Proxy.newProxyInstance(detailLoader, arrayOf(kViewHandlerClass)) { _, _, _ -> null }
        val kViewMossInstance = detailLoader.loadClass("com.bapis.bilibili.app.view.v1.KViewMoss")
            .getDeclaredConstructor().newInstance()
        val preBindReturned = detailRegistrar.invoke(
            detailKmossId, kViewMossInstance, arrayOf("req", P58_DummyStrategy, P58_DummyStrategy, rawKViewHandler, ProtoBuf())
        ) { args -> args[3] }
        assertNotSame(rawKViewHandler, preBindReturned)

        assertEquals(1, KmpGrpcWireChannel.bindDeferred(detailEnv, wireSurface()))
        val postBindReturned = detailRegistrar.invoke(
            detailKmossId, kViewMossInstance, arrayOf("req", P58_DummyStrategy, P58_DummyStrategy, rawKViewHandler, ProtoBuf())
        ) { args -> args[3] }
        assertSame(rawKViewHandler, postBindReturned)

        var detailWireReply: ByteArray? = null
        val detailWireSink = object : Handler {
            override fun onNext(bytes: ByteArray?) { detailWireReply = bytes }
            override fun onCompleted() = Unit
            override fun onError(error: KMossException) = Unit
        }
        val viewDescriptor = Descriptor("bilibili.app.view.v1", "View", "View", String::class, P58_DummyStrategy)
        detailRegistrar.invoke(
            "kmp.grpc.wire.channel",
            GrpcEngine(emptyList()),
            arrayOf(Service("grpc.biliapi.net"), viewDescriptor, ByteArray(0), detailWireSink, null)
        ) { args -> (args[3] as Handler).onNext(ViewReply(setOf("Honor", "Label")).toByteArray()); null }
        val decodedView = ViewReply.parseFrom(requireNotNull(detailWireReply))
        assertFalse(decodedView.hasHonor())
        assertTrue(decodedView.hasLabel())
    }

    @Test
    fun danmakuPurifyRawWireAndHandlerProxyContracts() {
        val loader = javaClass.classLoader!!

        // 4. DanmakuPurify raw-bytes wire filter without Java protobuf roundtrip
        val dmBaseTemplates = mapOf(
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
        val dmBaseReplacements = mapOf(
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
        val dmWireLoader = TestRenamingClassLoader(
            loader,
            templates = dmBaseTemplates,
            replacements = dmBaseReplacements,
            hiddenNames = setOf(
                "com.bapis.bilibili.community.service.dm.v1.DMMoss",
                "com.bapis.bilibili.community.service.dm.v1.KDMMoss"
            )
        )
        val dmWireReg = PlayerPortTestRegistrar()
        val dmEvidence = mutableListOf<Pair<String, FeatureRuntimeStage>>()
        P58_DmRoundtripCounters.reset()
        val dmWireRes = DanmakuPurifyFeatureInstaller(
            weightFilterEnabled = true,
            minimumWeight = 5,
            removeVipColorful = true,
            kmpGrpcWireEngine = wirePoint()
        ).install(env(dmWireLoader, dmWireReg, stages = dmEvidence))
        assertEquals(FeatureInstallResult.Installed(4, complete = true), dmWireRes)
        assertTrue(P58_DmRoundtripCounters.parseFromCalls.get() > 0)

        P58_DmRoundtripCounters.reset()
        var dmOutBytes: ByteArray? = null
        val dmSink = object : Handler {
            override fun onNext(bytes: ByteArray?) { dmOutBytes = bytes }
            override fun onCompleted() = Unit
            override fun onError(error: KMossException) = Unit
        }
        val dmDesc = Descriptor("bilibili.community.service.dm.v1", "DM", "DmSegMobile", String::class, P58_DummyStrategy)
        dmWireReg.invoke(
            "kmp.grpc.wire.channel",
            GrpcEngine(emptyList()),
            arrayOf(Service("grpc.biliapi.net"), dmDesc, ByteArray(0), dmSink, null)
        ) { args -> (args[3] as Handler).onNext(sampleWireBytes); null }
        val decodedDirect = P58_DmWireCodec.decodeReply(requireNotNull(dmOutBytes))
        assertEquals(listOf(8, 6), decodedDirect.elems.map { it.weight })
        assertEquals(listOf(0, 12), decodedDirect.elems.map { it.colorful })
        assertTrue(decodedDirect.colorfulSrcTypes.isEmpty())
        assertEquals(77L, decodedDirect.flags)
        assertEquals(0, P58_DmRoundtripCounters.parseFromCalls.get())
        assertEquals(0, P58_DmRoundtripCounters.toByteArrayCalls.get())
        assertTrue(dmEvidence.any { it.first == DanmakuPurifyFeatureInstaller.ID && it.second == FeatureRuntimeStage.APPLIED })

        val dmDeferredLoader = TestRenamingClassLoader(
            loader,
            templates = dmBaseTemplates + mapOf(
                "com.bapis.bilibili.community.service.dm.v1.KDMMoss" to P58_FakeKDmMossTemplate::class.java,
                "com.bapis.bilibili.community.service.dm.v1.KDMMoss\$Handler" to P58_FakeKDmMossTemplate.Handler::class.java
            ),
            replacements = dmBaseReplacements + mapOf(
                P58_FakeKDmMossTemplate::class.java.name.replace('.', '/') to "com/bapis/bilibili/community/service/dm/v1/KDMMoss",
                P58_FakeKDmMossTemplate.Handler::class.java.name.replace('.', '/') to "com/bapis/bilibili/community/service/dm/v1/KDMMoss\$Handler"
            ),
            hiddenNames = setOf("com.bapis.bilibili.community.service.dm.v1.DMMoss")
        )
        val dmDefReg = PlayerPortTestRegistrar()
        val dmDefEnv = env(dmDeferredLoader, dmDefReg)
        assertEquals(
            FeatureInstallResult.Installed(4, complete = true),
            DanmakuPurifyFeatureInstaller(
                weightFilterEnabled = true,
                minimumWeight = 5,
                removeVipColorful = true,
                kmpGrpcWireEngine = null
            ).install(dmDefEnv)
        )
        assertTrue("danmaku.purify.kmoss.dmSegMobile" in dmDefReg.hooks)
        assertTrue("danmaku.purify.kmoss.dmSegCache" in dmDefReg.hooks)
        assertFalse("kmp.grpc.wire.channel" in dmDefReg.hooks)

        val kDmHandlerClass = dmDeferredLoader.loadClass("com.bapis.bilibili.community.service.dm.v1.KDMMoss\$Handler")
        val rawKDmHandler = Proxy.newProxyInstance(dmDeferredLoader, arrayOf(kDmHandlerClass)) { _, _, _ -> null }
        val kDmInstance = dmDeferredLoader.loadClass("com.bapis.bilibili.community.service.dm.v1.KDMMoss")
            .getDeclaredConstructor().newInstance()
        assertNotSame(
            rawKDmHandler,
            dmDefReg.invoke("danmaku.purify.kmoss.dmSegMobile", kDmInstance, arrayOf("req", P58_DummyStrategy, P58_DummyStrategy, rawKDmHandler, ProtoBuf())) { args -> args[3] }
        )
        assertEquals(2, KmpGrpcWireChannel.bindDeferred(dmDefEnv, wireSurface()))
        assertSame(
            rawKDmHandler,
            dmDefReg.invoke("danmaku.purify.kmoss.dmSegMobile", kDmInstance, arrayOf("req", P58_DummyStrategy, P58_DummyStrategy, rawKDmHandler, ProtoBuf())) { args -> args[3] }
        )

        P58_DmRoundtripCounters.reset()
        var dmDefOutBytes: ByteArray? = null
        val dmDefSink = object : Handler {
            override fun onNext(bytes: ByteArray?) { dmDefOutBytes = bytes }
            override fun onCompleted() = Unit
            override fun onError(error: KMossException) = Unit
        }
        dmDefReg.invoke(
            "kmp.grpc.wire.channel",
            GrpcEngine(emptyList()),
            arrayOf(Service("grpc.biliapi.net"), dmDesc, ByteArray(0), dmDefSink, null)
        ) { args -> (args[3] as Handler).onNext(sampleWireBytes); null }
        val decodedDef = P58_DmWireCodec.decodeReply(requireNotNull(dmDefOutBytes))
        assertEquals(listOf(8, 6), decodedDef.elems.map { it.weight })
        assertEquals(0, P58_DmRoundtripCounters.parseFromCalls.get())
        assertEquals(0, P58_DmRoundtripCounters.toByteArrayCalls.get())

        // 5. KotlinMossResponseHandlerProxy error containment + getDefaultInstance immutability
        val badBridge = KotlinMossReplyBridge(
            encode = { error("encode failure") },
            parseJava = { it },
            toBytes = { ByteArray(0) },
            decode = { it }
        )
        val endpointMethod = EndpointStub::class.java.declaredMethods.single()
        val handlerKey = KotlinMossResponseHandlerProxy.Key(endpointMethod, Any::class.java, badBridge, endpointMethod)
        var delivered: Any? = null
        val throwingDelegate = object : Wave4MossCallback {
            override fun onNext(reply: Any?) {
                delivered = reply
                throw IllegalStateException("host-delegate-boom")
            }
        }
        val throwingProxy = KotlinMossResponseHandlerProxy.wrap(
            Wave4MossCallback::class.java, throwingDelegate, handlerKey, badBridge,
            failed = { throw RuntimeException("failed-listener-boom") }, transform = { it }
        ) as Wave4MossCallback
        val err = assertThrows(IllegalStateException::class.java) { throwingProxy.onNext("raw-reply") }
        assertEquals("host-delegate-boom", err.message)
        assertEquals("raw-reply", delivered)

        val goodBridge = KotlinMossReplyBridge(
            encode = { if (it == "rewritten-msg") byteArrayOf(2) else byteArrayOf(1) },
            parseJava = { if (it.contentEquals(byteArrayOf(2))) "transformed-java" else "java-msg" },
            toBytes = { byteArrayOf(2) },
            decode = { "rewritten-msg" }
        )
        var okDelivered: Any? = null
        val okProxy = KotlinMossResponseHandlerProxy.wrap(
            Wave4MossCallback::class.java,
            object : Wave4MossCallback { override fun onNext(reply: Any?) { okDelivered = reply } },
            handlerKey,
            goodBridge,
            failed = { throw AssertionError(it) },
            transform = { it },
            confirmedTransform = { KotlinMossResponseHandlerProxy.ConfirmedReply("transformed-java") { throw RuntimeException("receipt-boom") } }
        ) as Wave4MossCallback
        okProxy.onNext("orig-k")
        assertEquals("rewritten-msg", okDelivered)

        // VideoRelateResponseWriteback refuses protobuf getDefaultInstance()
        val itemsField = requireNotNull(KavaMemberLookup.fieldOrNull(FakeProtoRelateReply::class.java, "items"))
        val relateDefault = FakeProtoRelateReply.getDefaultInstance()
        val defaultItemsBefore = relateDefault.items
        assertFalse(writeBackVideoRelateItems(relateDefault, itemsField, emptyList<String>()))
        assertSame(defaultItemsBefore, relateDefault.items)
        assertEquals(listOf("default"), relateDefault.items)
        val liveRelate = FakeProtoRelateReply()
        assertTrue(writeBackVideoRelateItems(liveRelate, itemsField, listOf("keep")))
        assertEquals(listOf("keep"), liveRelate.items)
        assertNotSame(defaultItemsBefore, liveRelate.items)

        // CommentKotlinPurifier keeps defaultInstance immutable across top/control/reply
        PurifyControl.edits = 0
        PurifyContent.edits = 0
        PurifyReplyItem.edits = 0
        PurifyTopReply.edits = 0
        val purifier = CommentKotlinPurifier(
            replyInfoClass = PurifyReplyItem::class.java,
            isSearchUrl = { true },
            clearEmptyPage = true,
            payloads = mapOf("Qoe" to "comments_qoe_removed")
        ) { _, _, _ -> }
        val topDefault = PurifyTopReply.getDefaultInstance()
        assertSame(topDefault, purifier.purify(topDefault))
        assertEquals(0, PurifyTopReply.edits + PurifyControl.edits + PurifyReplyItem.edits + PurifyContent.edits)

        val itemDefaultContainer = PurifyTopReply(replies = listOf(PurifyReplyItem.DEFAULT), qoe = false, control = null)
        val purifiedItemDefault = purifier.purify(itemDefaultContainer) as PurifyTopReply
        assertSame(itemDefaultContainer, purifiedItemDefault)
        assertSame(PurifyReplyItem.DEFAULT, purifiedItemDefault.getRepliesList().single())
        assertEquals(0, PurifyReplyItem.edits + PurifyContent.edits)

        val defaultContentContainer = PurifyTopReply(replies = listOf(PurifyReplyItem(PurifyContent.DEFAULT)), qoe = false, control = null)
        assertSame(defaultContentContainer, purifier.purify(defaultContentContainer))
        assertEquals(0, PurifyContent.edits + PurifyReplyItem.edits)

        val withDefaultControl = PurifyTopReply(replies = emptyList(), qoe = true, control = PurifyControl.DEFAULT)
        val purifiedTop = purifier.purify(withDefaultControl) as PurifyTopReply
        assertNotSame(withDefaultControl, purifiedTop)
        assertFalse(purifiedTop.hasQoe())
        assertEquals(1, PurifyTopReply.edits)
        assertSame(PurifyControl.DEFAULT, purifiedTop.getSubjectControl())
        assertEquals(0, PurifyControl.edits)

        val liveContainer = PurifyTopReply(replies = listOf(PurifyReplyItem(PurifyContent(mapOf("bad" to "s")))), qoe = false, control = null)
        val purifiedLive = purifier.purify(liveContainer) as PurifyTopReply
        assertNotSame(liveContainer, purifiedLive)
        assertTrue(purifiedLive.getRepliesList().single().getContent()?.getUrlsMap()?.isEmpty() == true)
        assertEquals(1, PurifyContent.edits)
        assertEquals(1, PurifyReplyItem.edits)
    }

    @Test
    fun retryableFeatureInstallersBridgesAndRuntimeGuards() {
        val baseLoader = javaClass.classLoader!!

        // 1. Subprocess skip for GamePromotion, HomeBanner, Merchandise
        val subStatuses = linkedMapOf<String, String>()
        val subRegistrar = PlayerPortTestRegistrar()
        val subEnv = HookEnvironment(
            processName = "tv.danmaku.bili:pushservice",
            classLoader = baseLoader,
            hookPoints = HookPointRegistry(baseLoader),
            registrar = subRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { k, v -> subStatuses[k] = v }
        )
        assertEquals(FeatureInstallResult.Skipped("non-main-process"), GamePromotionFeatureInstaller(true).install(subEnv))
        assertEquals("non-main-process", subStatuses["gamecard_ad_status"])
        assertEquals(FeatureInstallResult.Skipped("non-main-process"), HomeBannerFeatureInstaller(true, null) { false }.install(subEnv))
        assertEquals("non-main-process", subStatuses["banner_ad_status"])
        assertEquals(FeatureInstallResult.Skipped("non-main-process"), MerchandiseFeatureInstaller(true).install(subEnv))
        assertTrue(subRegistrar.hooks.isEmpty())

        // 2. PrivacyFeatureRuntime asAdaptRetryable completion & retry
        val posted = mutableListOf<() -> Unit>()
        val emptyPrivacyLoader = object : ClassLoader(null) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                if (name == KmpGrpcWireAdapter.INTERCEPTOR) Any::class.java else super.loadClass(name, resolve)
        }
        val privacyContext: Context = object : ContextWrapper(null) {}
        val privEnv = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = emptyPrivacyLoader,
            hookPoints = HookPointRegistry(emptyPrivacyLoader),
            registrar = PlayerPortTestRegistrar(),
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> },
            postToMain = { posted += it }
        )
        val runtime1 = PrivacyFeatureRuntime(privacyContext, noLogin = false, incognito = true, kmpGrpcWireEngine = null)
        val retryable1 = runtime1.asAdaptRetryable()
        val coord1 = FeatureInstallCoordinator(privEnv).apply { registerRetryable(retryable1) }
        assertEquals(
            FeatureInstallResult.Skipped("missing-host-structure"),
            coord1.installAll(runtime1.installers()).single { it.id == "incognito_privacy" }.result
        )
        assertTrue(retryable1.isAdaptationRetryPending)
        coord1.retryAdapted(adapted = null, ok = true)
        assertFalse(retryable1.isAdaptationRetryPending)
        assertTrue(posted.isEmpty())

        val updatedPrivacyResult = VersionAdapter.AdaptResult(
            biliVersionCode = 9210200, ts = 1000L, commentLow = null, commentHigh = null, mineEntry = null,
            pause = VersionAdapter.PausePoints(emptyList(), null, null, null),
            banner = null, homeTopBar = null, mineVip = null, blockUpdate = null, dynamicTabs = null,
            fullNumbers = null, playerPortrait = null, playerInteractiveOverlays = null, pgcAutoActivityPopup = null,
            playerStatusBar = null, homeRecommendFeed = null, videoRelate = null, homeTabs = null,
            homeComponents = null, mineComponents = null, mineAccountMine = null, storyFeed = null, bottomBar = null,
            playerQuality = null, teenagersMode = null, commentPurify = null, commentFilter = null,
            commentTopology = null, commentSection = null, splashAds = null,
            hostFingerprint = "fp", protocolFingerprint = "proto", diagnostics = emptyList(),
            kmpGrpcWireEngine = HookPoint("kmp.Engine", "invoke", emptyList())
        )
        val posted2 = mutableListOf<() -> Unit>()
        val runtime2 = PrivacyFeatureRuntime(privacyContext, noLogin = false, incognito = true, kmpGrpcWireEngine = null)
        val retryable2 = runtime2.asAdaptRetryable()
        val coord2 = FeatureInstallCoordinator(privEnv.copy(postToMain = { posted2 += it })).apply { registerRetryable(retryable2) }
        coord2.installAll(runtime2.installers())
        assertTrue(retryable2.isAdaptationRetryPending)
        coord2.retryAdapted(adapted = updatedPrivacyResult, ok = true)
        assertEquals(1, posted2.size)
        posted2.single().invoke()
        assertFalse(retryable2.isAdaptationRetryPending)

        // 3. FollowFeed & HostVideoCardStyle incremental retry without duplicate hooks
        val followLoader = TestRenamingClassLoader(
            baseLoader,
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
        val cachedFollowPoints = FollowFeedPoints("renamed.follow.DriftPainter", "paint", "renamed.follow.DriftModule", "renamed.follow.DriftItem", "renamed.follow.DriftHome", "current")
        assertFalse(FollowFeedFeatureInstaller(enabled = false, points = null).updateAdaptedPoints(cachedFollowPoints))
        assertFalse(FollowFeedFeatureInstaller(enabled = true, points = cachedFollowPoints).updateAdaptedPoints(cachedFollowPoints))
        val followRegistrar = PlayerPortTestRegistrar()
        val followEnv = env(followLoader, followRegistrar)
        val followInstaller = FollowFeedFeatureInstaller(enabled = true, points = null)
        assertEquals(FeatureInstallResult.Skipped("missing-follow-contract"), followInstaller.install(followEnv))
        assertTrue(followInstaller.updateAdaptedPoints(cachedFollowPoints))
        val followResult = followInstaller.install(followEnv)
        assertTrue(followResult is FeatureInstallResult.Installed && followResult.hookCount > 0)
        val followAttemptCount = followRegistrar.registrationAttempts.size
        assertFalse(followInstaller.updateAdaptedPoints(cachedFollowPoints))
        assertEquals(followResult, followInstaller.install(followEnv))
        assertEquals(followAttemptCount, followRegistrar.registrationAttempts.size)

        val pegasusLoader = TestRenamingClassLoader(
            baseLoader,
            templates = mapOf("com.bilibili.pegasus.PegasusFragment" to P58_DummyPegasusFragment::class.java),
            replacements = mapOf(P58_DummyPegasusFragment::class.java.name.replace('.', '/') to "com/bilibili/pegasus/PegasusFragment"),
            hiddenNames = setOf("com.bilibili.pegasus.widget.j", "com.bilibili.pegasus.widget.l")
        )
        val cardRegistrar = PlayerPortTestRegistrar()
        val sharedCards = HostVideoCardStyleFeatureInstaller.shared(
            enabled = true, radiusDp = 12,
            backgroundConfig = HostBackgroundConfig(preset = HostBackgroundPreset.MIST),
            material = HostCardMaterial.ORIGINAL, cardSurfaces = emptyMap(), followFeedPoints = null
        )
        val cardRecords = linkedMapOf<String, FeatureInstallRecord>()
        val cardEnv = env(pegasusLoader, cardRegistrar)
        val cardCoordinator = FeatureInstallCoordinator(cardEnv.copy(installationEvidence = { cardRecords[it.id] = it }))
        cardCoordinator.installAll(sharedCards)
        val initialCardRes = cardRecords.getValue(HostVideoCardStyleFeatureInstaller.ID).result as FeatureInstallResult.Installed
        val initialBgRes = cardRecords.getValue(HostVideoCardStyleFeatureInstaller.BACKGROUND_ID).result as FeatureInstallResult.Installed
        assertEquals(1, cardRegistrar.registrationAttempts.count { it.first == "${HostVideoCardStyleFeatureInstaller.ID}.bind" })
        assertEquals(0, cardRegistrar.registrationAttempts.count { it.first == "${HostVideoCardStyleFeatureInstaller.ID}.pegasus.divider" })
        val initialCounts = cardRegistrar.registrationAttempts.groupingBy { it.first }.eachCount()

        val pegasusPoint = HookPoint(
            "com.bilibili.pegasus.widget.RenamedDivider", "onDrawOver",
            listOf("android.graphics.Canvas", "androidx.recyclerview.widget.RecyclerView", "androidx.recyclerview.widget.RecyclerView\$State")
        )
        val retryTargets = HostVideoCardStyleFeatureInstaller.updateAdaptedPoints(sharedCards, mapOf("pegasus_divider" to pegasusPoint), cachedFollowPoints)
        assertEquals(2, retryTargets.size)
        cardCoordinator.installAll(retryTargets)
        val retryCardRes = cardRecords.getValue(HostVideoCardStyleFeatureInstaller.ID).result as FeatureInstallResult.Installed
        val retryBgRes = cardRecords.getValue(HostVideoCardStyleFeatureInstaller.BACKGROUND_ID).result as FeatureInstallResult.Installed
        assertEquals(initialCardRes.hookCount + 1, retryCardRes.hookCount)
        assertTrue(retryCardRes.complete && retryBgRes.complete && retryBgRes.hookCount >= initialBgRes.hookCount)
        assertEquals(1, cardRegistrar.registrationAttempts.count { it.first == "${HostVideoCardStyleFeatureInstaller.ID}.pegasus.divider" })
        for ((hookId, count) in initialCounts) {
            assertEquals("Hook $hookId must not be duplicated", count, cardRegistrar.registrationAttempts.count { it.first == hookId })
        }
        assertTrue(HostVideoCardStyleFeatureInstaller.updateAdaptedPoints(sharedCards, mapOf("pegasus_divider" to pegasusPoint), cachedFollowPoints).isEmpty())

        // 4. CommentPurify retry preserves sibling capabilities + AiInsight live kmpGrpcWireEngineProvider
        val purifyLoader = TestRenamingClassLoader(
            baseLoader,
            hiddenNames = setOf("com.bapis.bilibili.main.community.reply.v1.ReplyMoss", "com.bapis.bilibili.main.community.reply.v1.KReplyMoss")
        )
        val basePurifyReg = PlayerPortTestRegistrar()
        val adaptedAttempts = mutableListOf<String>()
        val purifyReg = object : HookRegistrar by basePurifyReg {
            override fun adapted(id: String, point: HookPoint, exceptionPolicy: HookExceptionPolicy, block: ModernMemberHookCreator.() -> Unit) {
                adaptedAttempts += id
                basePurifyReg.adapted(id, point, exceptionPolicy, block)
            }
        }
        val purifyRecords = linkedMapOf<String, FeatureInstallRecord>()
        val purifyCoord = FeatureInstallCoordinator(env(purifyLoader, purifyReg).copy(installationEvidence = { purifyRecords[it.id] = it }))
        val adaptedRef = AtomicReference<VersionAdapter.CommentPurifyPoints?>(null)
        val wireRef = AtomicReference<HookPoint?>(null)
        val purifyInstaller = CommentPurifyFeatureInstaller(
            removeSearchLinks = false, removeEmptyGuide = false, removeVoteWidgets = true,
            removeFollowButtons = false, removeQoe = false, removeOperations = false, blockQuickReply = true,
            points = null, pointsProvider = { adaptedRef.get() }, kmpGrpcWireEngineProvider = { wireRef.get() }
        )
        purifyCoord.installAll(listOf(purifyInstaller))
        assertTrue(purifyInstaller.requiresAdaptationRetry)
        assertTrue(purifyRecords[CommentPurifyFeatureInstaller.ID]?.result is FeatureInstallResult.Skipped)
        adaptedRef.set(
            VersionAdapter.CommentPurifyPoints(emptyList(), emptyList(), listOf(HookPoint(P58_FakeVoteWidget::class.java.name, "bind", emptyList())), null, null, emptyList(), emptyList())
        )
        wireRef.set(wirePoint())
        purifyCoord.installAll(listOf(purifyInstaller))
        assertTrue(purifyRecords[CommentPurifyFeatureInstaller.ID]?.result is FeatureInstallResult.Installed)
        assertTrue(purifyRecords["comments_vote_widgets_removed"]?.result is FeatureInstallResult.Installed)
        assertEquals(1, adaptedAttempts.count { it == "comment.purify.vote.0" })
        purifyCoord.installAll(listOf(purifyInstaller))
        assertEquals(1, adaptedAttempts.count { it == "comment.purify.vote.0" })

        val reqName = "com.bapis.bilibili.main.community.reply.v1.MainListReq"
        val replyName = "com.bapis.bilibili.main.community.reply.v1.MainListReply"
        val insightLoader = TestRenamingClassLoader(
            baseLoader,
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
            hiddenNames = setOf("com.bapis.bilibili.main.community.reply.v1.ReplyMoss", "com.bapis.bilibili.main.community.reply.v1.KReplyMoss")
        )
        val insightReg = PlayerPortTestRegistrar()
        val liveWire = AtomicReference<HookPoint?>(null)
        val insightInstaller = AiInsightFeatureInstaller(
            enabled = true, automaticChapters = false, source = null,
            cacheRoot = tempFolder.newFolder("insight-wire-test"),
            kmpGrpcWireEngine = null, kmpGrpcWireEngineProvider = { liveWire.get() }
        )
        val installComments = AiInsightFeatureInstaller::class.java.declaredMethods.single { it.name == "installComments" }.apply { isAccessible = true }
        val snapshot = InsightHostSnapshot()
        assertEquals(0, installComments.invoke(insightInstaller, env(insightLoader, insightReg), snapshot))
        assertFalse("kmp.grpc.wire.channel" in insightReg.hooks)
        liveWire.set(wirePoint())
        assertEquals(1, installComments.invoke(insightInstaller, env(insightLoader, insightReg), snapshot))
        assertTrue("kmp.grpc.wire.channel" in insightReg.hooks)
    }

    @Test
    fun frameInterpolationTrimAndCommentTopologyComposeSlot() {
        val baseLoader = javaClass.classLoader!!

        // 5. FrameInterpolation & HostMemoryPressureBridge trim dispatch
        val interpInstaller = FrameInterpolationFeatureInstaller(enabled = false, verificationAllowed = false, moduleApk = null)
        assertFalse(interpInstaller.releaseIfIdle())
        var moderateTrimmed = 0
        var criticalTrimmed = 0
        try {
            HostMemoryPressureBridge.resetForTests()
            HostMemoryPressureBridge.registerTrimCallbacks(
                onModerate = { KmpMethodDescriptorResolver.clearCaches(); moderateTrimmed++ },
                onCritical = { KmpMethodDescriptorResolver.clearCaches(); AudioRescueModel.releaseIfIdle(); interpInstaller.releaseIfIdle(); criticalTrimmed++ }
            )
            HostMemoryPressureBridge.dispatchModerate()
            assertEquals(1, moderateTrimmed); assertEquals(0, criticalTrimmed)
            HostMemoryPressureBridge.dispatchCritical()
            assertEquals(2, moderateTrimmed); assertEquals(1, criticalTrimmed)
        } finally {
            HostMemoryPressureBridge.resetForTests()
        }

        // 6. CommentTopology RecyclerView walk & Compose purify Follow slot (renamed & private ctor)
        val rvMethod = CommentTopologyFeatureInstaller::class.java.getDeclaredMethod("isRecyclerViewInstance", View::class.java).apply { isAccessible = true }
        val topoDummy = CommentTopologyFeatureInstaller(enabled = false, points = null, lowBindPoint = null, highBindPoint = null)
        assertFalse(rvMethod.invoke(topoDummy, null) as Boolean)
        assertFalse(rvMethod.invoke(topoDummy, allocateUnsafe(NonRvView::class.java)) as Boolean)
        assertTrue(rvMethod.invoke(topoDummy, allocateUnsafe(SubRv::class.java)) as Boolean)

        val composePkg = "kntr.common.comment.card.model.comment"
        val userName = "$composePkg.CommentUserInfoRendererModel"
        val composeLoader1 = TestRenamingClassLoader(
            baseLoader,
            templates = mapOf(
                "$composePkg.CommentModel" to PurifyCommentModel::class.java,
                userName to PurifyUserModel::class.java,
                "$userName\$Decoration" to PurifyDecoration::class.java,
                "$userName\$DecorA" to PurifyDecorA::class.java,
                "$userName\$OtherB" to PurifyOtherB::class.java,
                "$userName\$Follow" to PurifyFollow::class.java,
                "$userName\$DecorD" to PurifyDecorD::class.java
            ),
            replacements = mapOf(
                PurifyUserModel::class.java.name.replace('.', '/') to userName.replace('.', '/'),
                PurifyDecoration::class.java.name.replace('.', '/') to "$userName\$Decoration".replace('.', '/'),
                PurifyDecorA::class.java.name.replace('.', '/') to "$userName\$DecorA".replace('.', '/'),
                PurifyOtherB::class.java.name.replace('.', '/') to "$userName\$OtherB".replace('.', '/'),
                PurifyFollow::class.java.name.replace('.', '/') to "$userName\$Follow".replace('.', '/'),
                PurifyDecorD::class.java.name.replace('.', '/') to "$userName\$DecorD".replace('.', '/')
            )
        )
        val localReg1 = LocalWiringRegistrar()
        assertEquals(
            FeatureInstallResult.Installed(1, complete = true),
            CommentComposePurifyBridge(vote = false, follow = true, quickReply = false).install(env(composeLoader1, localReg1)).getValue("comments_follow_buttons_removed")
        )
        val decCls1 = composeLoader1.loadClass("$userName\$Decoration")
        val aVal = composeLoader1.loadClass("$userName\$DecorA").getDeclaredConstructor().newInstance()
        val bVal = composeLoader1.loadClass("$userName\$OtherB").getDeclaredConstructor().newInstance()
        val followVal = composeLoader1.loadClass("$userName\$Follow").getDeclaredConstructor(Long::class.javaPrimitiveType).newInstance(42L)
        val dVal = composeLoader1.loadClass("$userName\$DecorD").getDeclaredConstructor().newInstance()
        val cArgs1 = arrayOf<Any?>(aVal, bVal, followVal, dVal)
        localReg1.invokeConstructor("comment.compose.follow.${decCls1.name}", decCls1.constructors.single().newInstance(aVal, bVal, null, dVal), cArgs1)
        assertSame(aVal, cArgs1[0]); assertSame(bVal, cArgs1[1]); assertNull(cArgs1[2]); assertSame(dVal, cArgs1[3])

        val composeLoader2 = TestRenamingClassLoader(
            baseLoader,
            templates = mapOf(
                "$composePkg.CommentModel" to ComposeCommentStub::class.java,
                userName to ComposeUserStub::class.java,
                "$userName\$Deco" to ComposeDecoPrivateStub::class.java,
                "$userName\$Follow" to ComposeFollowStub::class.java,
                "$userName\$A" to ComposeSlotA::class.java,
                "$userName\$C" to ComposeSlotC::class.java,
                "$userName\$D" to ComposeSlotD::class.java
            ),
            replacements = mapOf(
                ComposeCommentStub::class.java.name.replace('.', '/') to "$composePkg/CommentModel",
                ComposeUserStub::class.java.name.replace('.', '/') to userName.replace('.', '/'),
                ComposeDecoPrivateStub::class.java.name.replace('.', '/') to "$userName\$Deco".replace('.', '/'),
                ComposeFollowStub::class.java.name.replace('.', '/') to "$userName\$Follow".replace('.', '/'),
                ComposeSlotA::class.java.name.replace('.', '/') to "$userName\$A".replace('.', '/'),
                ComposeSlotC::class.java.name.replace('.', '/') to "$userName\$C".replace('.', '/'),
                ComposeSlotD::class.java.name.replace('.', '/') to "$userName\$D".replace('.', '/')
            )
        )
        val localReg2 = LocalWiringRegistrar()
        val privStages = mutableListOf<Pair<String, FeatureRuntimeStage>>()
        val privRes = CommentComposePurifyBridge(vote = false, follow = true, quickReply = false)
            .install(env(composeLoader2, localReg2, stages = privStages))["comments_follow_buttons_removed"] as? FeatureInstallResult.Installed
        assertTrue(privRes != null && privRes.complete)
        val decCls2 = composeLoader2.loadClass("$userName\$Deco")
        val f2 = composeLoader2.loadClass("$userName\$Follow").getDeclaredConstructor(Long::class.javaPrimitiveType).newInstance(99L)
        val ctor2 = decCls2.declaredConstructors.single().apply { isAccessible = true }
        val cArgs2 = arrayOf<Any?>(null, f2, null, null)
        localReg2.invokeConstructor("comment.compose.follow.$userName\$Deco", ctor2.newInstance(null, null, null, null), cArgs2)
        assertNull(cArgs2[1])
        assertTrue(privStages.any { it.second == FeatureRuntimeStage.APPLIED })

        // 7. CloudKeyFreeze, HomeFrameLock, PlayerAiSubtitle
        val ckLoader = TestRenamingClassLoader(
            baseLoader,
            templates = mapOf(
                "kntr.base.config.KConfig" to KConfigStub::class.java,
                "com.bilibili.lib.dd.GDeviceDecision" to GddContract::class.java,
                "com.bilibili.lib.dd.DeviceDecision" to DdFacadeTemplate::class.java,
                "FlatDdImpl" to FlattenedDdImpl::class.java,
                "kntr.base.dd.IDeviceDecisionKt" to KotlinDd3Arg::class.java
            ),
            replacements = mapOf(
                KConfigStub::class.java.name.replace('.', '/') to "kntr/base/config/KConfig",
                GddContract::class.java.name.replace('.', '/') to "com/bilibili/lib/dd/GDeviceDecision",
                DdFacadeTemplate::class.java.name.replace('.', '/') to "com/bilibili/lib/dd/DeviceDecision",
                FlattenedDdImpl::class.java.name.replace('.', '/') to "FlatDdImpl",
                KotlinDd3Arg::class.java.name.replace('.', '/') to "kntr/base/dd/IDeviceDecisionKt"
            )
        )
        val ckReg = LocalWiringRegistrar()
        val ckStatuses = mutableListOf<Pair<String, String>>()
        assertTrue(CloudKeyFreezeFeatureInstaller(true, "flag.a=true", false).install(env(ckLoader, ckReg, ckStatuses)) is FeatureInstallResult.Installed)
        val ckStatus = ckStatuses.single().second
        assertTrue(ckStatus.contains("kconfig") && ckStatus.contains("dd-impl") && ckStatus.contains("kotlin-dd"))
        assertEquals(true, ckReg.invokeExact(ckReg.exactHooks.keys.first { it.startsWith("cloud_key_freeze.kconfig.") && ckReg.exactHooks[it]!!.member.name == "ff" }, args = arrayOf("flag.a", false)) { false })
        assertEquals(true, ckReg.invokeExact(ckReg.exactHooks.keys.first { it.startsWith("cloud_key_freeze.kotlin-dd.") && ckReg.exactHooks[it]!!.member.name == "getBool" }, args = arrayOf(null, "flag.a", false)) { false })

        val noKConfigLoader = TestRenamingClassLoader(
            baseLoader,
            mapOf("com.bilibili.lib.dd.DeviceDecision" to DdFacadeTemplate::class.java),
            mapOf(DdFacadeTemplate::class.java.name.replace('.', '/') to "com/bilibili/lib/dd/DeviceDecision"),
            hiddenNames = setOf("kntr.base.config.KConfig")
        )
        assertEquals(FeatureInstallResult.Skipped("missing-config-boundary"), HomeFrameLockFeatureInstaller(true).install(env(noKConfigLoader, LocalWiringRegistrar())))
        val bothConfigLoader = TestRenamingClassLoader(
            baseLoader,
            mapOf("kntr.base.config.KConfig" to KConfigStub::class.java, "com.bilibili.lib.dd.DeviceDecision" to DdFacadeTemplate::class.java),
            mapOf(KConfigStub::class.java.name.replace('.', '/') to "kntr/base/config/KConfig", DdFacadeTemplate::class.java.name.replace('.', '/') to "com/bilibili/lib/dd/DeviceDecision")
        )
        val lockReg = LocalWiringRegistrar()
        assertEquals(FeatureInstallResult.Installed(1), HomeFrameLockFeatureInstaller(true).install(env(bothConfigLoader, lockReg)))
        assertEquals(false, lockReg.invokeExact("home_frame_lock.flag", args = arrayOf(HomeFrameLockFeatureInstaller.FRAME_FLAG_KEY, false)) { true })

        val subAiLoader = TestRenamingClassLoader(
            baseLoader,
            mapOf(
                "com.bilibili.lib.dd.DeviceDecision" to Dd3Arg::class.java,
                "kntr.base.config.KConfig" to KConfigStub::class.java,
                "kntr.base.dd.IDeviceDecisionKt" to KotlinDd3Arg::class.java,
                "tv.danmaku.biliplayerv2.service.Video\$DanmakuResolveParams" to DanmakuParams::class.java
            ),
            mapOf(
                Dd3Arg::class.java.name.replace('.', '/') to "com/bilibili/lib/dd/DeviceDecision",
                KConfigStub::class.java.name.replace('.', '/') to "kntr/base/config/KConfig",
                KotlinDd3Arg::class.java.name.replace('.', '/') to "kntr/base/dd/IDeviceDecisionKt",
                DanmakuParams::class.java.name.replace('.', '/') to "tv/danmaku/biliplayerv2/service/Video\$DanmakuResolveParams"
            )
        )
        val subAiReg = LocalWiringRegistrar()
        assertEquals(FeatureInstallResult.Installed(2, complete = true), PlayerAiSubtitleFeatureInstaller(true).install(env(subAiLoader, subAiReg)))
        assertTrue("player.subtitle.ai.language.3" in subAiReg.exactHooks && "player.subtitle.ai.kconfig" in subAiReg.exactHooks && "player.subtitle.ai.kotlin_dd.3" in subAiReg.exactHooks)
        assertEquals(true, subAiReg.invokeExact("player.subtitle.ai.kotlin_dd.3", args = arrayOf(null, PlayerAiSubtitleFeatureInstaller.LANGUAGE_FLAG, false)) { false })
    }

    @Test
    fun detailPresentationCleanersHomeVerticalSplashAndProxy() {
        val baseLoader = javaClass.classLoader!!

        // 8. DetailPresentation cleaners, HomeVerticalDetail KBoolValue 1-arg, FollowUpRecommend requestBodyEntry
        val headlineCleaner = requireNotNull(HeadlineBadgeModelCleaner.resolve(ReorderedHeadlineData::class.java))
        val cleanedHeadline = headlineCleaner.removeHotSearchBadge(ReorderedHeadlineData(ObfLabel9(x1 = "bilibili://search?from=apphotword_search_huangtiao"), "新闻")) as ReorderedHeadlineData
        assertEquals("新闻", cleanedHeadline.fTitle); assertNull(cleanedHeadline.fLabel)
        assertNull(HeadlineBadgeModelCleaner.resolve(ExtraHeadlineData::class.java))

        val tagsCleaner = requireNotNull(SpecialTopicTagsModelCleaner.resolve(ReorderedTagsData::class.java))
        val cleanedTags = tagsCleaner.removeSpecialTopicCells(ReorderedTagsData(true, listOf(ReorderedTagCell("https://m.bilibili.com/topic-detail?topic_id=1")))) as ReorderedTagsData
        assertTrue(cleanedTags.fCells.isEmpty()); assertFalse(cleanedTags.fRefresh)
        assertNull(SpecialTopicTagsModelCleaner.resolve(ExtraTagsData::class.java))

        val kBoolLoader = TestRenamingClassLoader(
            baseLoader,
            mapOf("com.bapis.bilibili.app.distribution.setting.play.KPlayConfig" to KPlayConfigTemplate::class.java, "com.bapis.bilibili.app.distribution.KBoolValue" to KBoolValue1Arg::class.java),
            mapOf(KPlayConfigTemplate::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/distribution/setting/play/KPlayConfig", KBoolValue1Arg::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/distribution/KBoolValue"),
            hiddenPrefixes = setOf("com.bapis.bilibili.app.distribution.BoolValue", "com.bapis.bilibili.app.distribution.setting.play.PlayConfig")
        )
        val vReg = LocalWiringRegistrar()
        val vMethod = HomeVerticalDetailFeatureInstaller::class.java.getDeclaredMethod("installPlayConfigStorySuppression", HookEnvironment::class.java).apply { isAccessible = true }
        assertNotNull(vMethod.invoke(HomeVerticalDetailFeatureInstaller(true, null), env(kBoolLoader, vReg)))
        val kBoolRes = vReg.invokeExact("home.vertical.k_play_config.getLandscapeAutoStory")
        val kBoolCls = kBoolLoader.loadClass("com.bapis.bilibili.app.distribution.KBoolValue")
        assertTrue(kBoolCls.isInstance(kBoolRes) && kBoolCls.getMethod("getValue").invoke(kBoolRes) == false)

        assertNotNull(FollowUpRecommendFeatureInstaller.requestBodyEntry(baseLoader, FollowBody3::class.java, FollowRepo::class.java))
        assertNotNull(FollowUpRecommendFeatureInstaller.requestBodyEntry(baseLoader, FollowBody4::class.java, FollowRepo::class.java))

        // 9. SplashAutoNight, Comment text structural readers, PlayerCodecForce readback guard, HostTopBarFxController proxy
        val splashLoader = TestRenamingClassLoader(
            baseLoader,
            templates = mapOf(
                "androidx.compose.ui.Modifier" to SplashModifier::class.java,
                "androidx.compose.ui.Modifier\$Companion" to SplashModifier.Companion::class.java,
                "androidx.compose.ui.graphics.Shape" to SplashShape::class.java,
                "androidx.compose.ui.graphics.SplashRectShape" to SplashRectShape::class.java,
                "androidx.compose.ui.graphics.RectangleShapeKt" to SplashRectShapeKt::class.java,
                "androidx.compose.ui.graphics.ColorKt" to SplashColorKt::class.java,
                "androidx.compose.foundation.SplashBgModifier" to SplashBgModifier::class.java,
                "androidx.compose.foundation.BackgroundKt" to SplashBackgroundKt::class.java,
                "androidx.compose.runtime.CompositionLocal" to SplashCompLocal::class.java,
                "androidx.compose.runtime.SplashCompLocalImpl" to SplashCompLocalImpl::class.java,
                "androidx.compose.ui.platform.AndroidCompositionLocals_androidKt" to SplashAndroidLocalsKt::class.java,
                "androidx.compose.runtime.Composer" to SplashComposer::class.java,
                "androidx.compose.runtime.SplashComposerImpl" to SplashComposerImpl::class.java,
                "kntr.srcs.app.splash.brand.startup.SplashModel9" to SplashModel9::class.java,
                "kntr.srcs.app.splash.brand.startup.BrandSplashPageKt" to SplashPageKt::class.java
            ),
            replacements = mapOf(
                SplashModifier.Companion::class.java.name.replace('.', '/') to "androidx/compose/ui/Modifier\$Companion",
                SplashModifier::class.java.name.replace('.', '/') to "androidx/compose/ui/Modifier",
                SplashShape::class.java.name.replace('.', '/') to "androidx/compose/ui/graphics/Shape",
                SplashRectShape::class.java.name.replace('.', '/') to "androidx/compose/ui/graphics/SplashRectShape",
                SplashRectShapeKt::class.java.name.replace('.', '/') to "androidx/compose/ui/graphics/RectangleShapeKt",
                SplashColorKt::class.java.name.replace('.', '/') to "androidx/compose/ui/graphics/ColorKt",
                SplashBgModifier::class.java.name.replace('.', '/') to "androidx/compose/foundation/SplashBgModifier",
                SplashBackgroundKt::class.java.name.replace('.', '/') to "androidx/compose/foundation/BackgroundKt",
                SplashCompLocal::class.java.name.replace('.', '/') to "androidx/compose/runtime/CompositionLocal",
                SplashCompLocalImpl::class.java.name.replace('.', '/') to "androidx/compose/runtime/SplashCompLocalImpl",
                SplashAndroidLocalsKt::class.java.name.replace('.', '/') to "androidx/compose/ui/platform/AndroidCompositionLocals_androidKt",
                SplashComposer::class.java.name.replace('.', '/') to "androidx/compose/runtime/Composer",
                SplashComposerImpl::class.java.name.replace('.', '/') to "androidx/compose/runtime/SplashComposerImpl",
                SplashModel9::class.java.name.replace('.', '/') to "kntr/srcs/app/splash/brand/startup/SplashModel9",
                SplashPageKt::class.java.name.replace('.', '/') to "kntr/srcs/app/splash/brand/startup/BrandSplashPageKt"
            ),
            hiddenNames = setOf("tv.danmaku.bili.ui.splash.brand.ui.BaseBrandSplashFragment")
        )
        val splashReg = LocalWiringRegistrar()
        assertEquals(FeatureInstallResult.Installed(1, complete = true), SplashAutoNightFeatureInstaller(true).install(env(splashLoader, splashReg)))
        val cfg = (allocateUnsafe(Configuration::class.java) as Configuration).apply { uiMode = Configuration.UI_MODE_NIGHT_YES }
        val resObj = (allocateUnsafe(SplashFakeResources::class.java) as SplashFakeResources).also {
            SplashFakeResources::class.java.getDeclaredField("cfg").apply { isAccessible = true }.set(it, cfg)
        }
        val fakeView = (allocateUnsafe(SplashFakeView::class.java) as SplashFakeView).also {
            SplashFakeView::class.java.getDeclaredField("res").apply { isAccessible = true }.set(it, resObj)
        }
        val splashModel = splashLoader.loadClass("kntr.srcs.app.splash.brand.startup.SplashModel9").constructors.single()
            .newInstance(BrandSplashSource.OFFICIAL, 1L, "", "", "", "", "", false, BrandSplashMode.HALF)
        val onExit: () -> Unit = {}
        val modCompanion = splashLoader.loadClass("androidx.compose.ui.Modifier").getField("Companion").get(null)
        val composer = splashLoader.loadClass("androidx.compose.runtime.SplashComposerImpl").getConstructor(View::class.java).newInstance(fakeView)
        val initFlags = 1 or (0b111 shl 7)
        val sArgs = arrayOf<Any?>(splashModel, onExit, modCompanion, "url", composer, initFlags)
        splashReg.invokeExact("splash.auto_night.kntr_page", args = sArgs)
        assertSame(splashModel, sArgs[0]); assertSame(onExit, sArgs[1])
        val bgMod = sArgs[2]!!
        assertEquals("androidx.compose.foundation.SplashBgModifier", bgMod.javaClass.name)
        assertEquals((Color.BLACK.toLong() and 0xffffffffL) shl 32, bgMod.javaClass.getField("color").getLong(bgMod))
        assertEquals(ComposeChangedMask.markUncertain(initFlags, 2), sArgs[5])

        val fullTextReader = CommentFullTextReader()
        assertTrue(fullTextReader.isNicknameSpan(RenamedNickSpan()))
        assertFalse(fullTextReader.isNicknameSpan(allocateUnsafe(ViewLikeSpan::class.java)))
        val likableLoader = TestRenamingClassLoader(
            baseLoader,
            mapOf("com.bilibili.app.comment3.ui.nextwidget.NextLikableTextView" to RenamedNextLikable::class.java),
            mapOf(RenamedNextLikable::class.java.name.replace('.', '/') to "com/bilibili/app/comment3/ui/nextwidget/NextLikableTextView")
        )
        val likable = likableLoader.loadClass("com.bilibili.app.comment3.ui.nextwidget.NextLikableTextView")
            .getConstructor(CharSequence::class.java, CharSequence::class.java, CharSequence::class.java).newInstance("full body", "short", "tail")
        assertEquals("full body", fullTextReader.read(likable))
        assertEquals("abc", CommentRichTextReader.resolve(ObfRichField::class.java)!!.read(ObfRichField("abc")))
        assertEquals("xyz", CommentRichTextReader.resolve(ObfRichGetter::class.java)!!.read(ObfRichGetter("xyz")))
        assertEquals("译文", requireNotNull(ComposeCommentText.resolve(TwoRichComposeModel::class.java)).read(TwoRichComposeModel(1L, 2L, 3L, Lang.TRANSLATION, ObfRichField("原文"), ObfRichField("译文"))))

        val mossName = "com.bapis.bilibili.app.playurl.v1.PlayURLMoss"
        val reqNameCodec = "com.bapis.bilibili.app.playurl.v1.PlayViewReq"
        val codecLoader = TestRenamingClassLoader(
            baseLoader,
            templates = mapOf(
                mossName to Wave4BadPlayURLMoss::class.java,
                reqNameCodec to Wave4BadPlayViewReq::class.java,
                "$reqNameCodec\$Builder" to Wave4BadPlayViewReq.Builder::class.java,
                "$reqNameCodec\$Companion" to Wave4BadPlayViewReq.Companion::class.java
            ),
            replacements = mapOf(
                Wave4BadPlayURLMoss::class.java.name.replace('.', '/') to mossName.replace('.', '/'),
                Wave4BadPlayViewReq::class.java.name.replace('.', '/') to reqNameCodec.replace('.', '/')
            ),
            hiddenNames = setOf(
                "com.bapis.bilibili.app.playerunite.v1.PlayerMoss",
                "com.bapis.bilibili.app.playerunite.v1.KPlayerMoss",
                "com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReq",
                "com.bapis.bilibili.app.playerunite.v1.KPlayViewUniteReq",
                "com.bapis.bilibili.app.playurl.v1.KPlayURLMoss",
                "com.bapis.bilibili.app.playurl.v1.KPlayViewReq",
                "tv.danmaku.ijk.media.player.services.IjkMediaPlayerItemClient"
            )
        )
        val codecReg = LocalWiringRegistrar()
        val codecStages = mutableListOf<Pair<String, FeatureRuntimeStage>>()
        val codecRes = PlayerCodecForceFeatureInstaller(PlayerCodecPreference.H265.value, PlayerDecodeMode.FOLLOW_HOST.value).install(env(codecLoader, codecReg, stages = codecStages))
        assertEquals(FeatureInstallResult.Installed(1), codecRes)
        val badReq = codecLoader.loadClass(reqNameCodec).getDeclaredConstructor().newInstance()
        val codecArgs = arrayOf<Any?>(badReq)
        codecReg.invokeExact("player_codec_force.request.PlayViewReq", codecLoader.loadClass(mossName).getDeclaredConstructor().newInstance(), codecArgs)
        assertSame(badReq, codecArgs[0])
        assertFalse(codecStages.any { it.second == FeatureRuntimeStage.APPLIED })

        var pageCbCalls = 0
        val pageProxy = Proxy.newProxyInstance(
            TestPageListener::class.java.classLoader,
            arrayOf(TestPageListener::class.java),
            HostTopBarFxController.createPageChangeProxyHandler { pageCbCalls++; error("simulated callback exception") }
        ) as TestPageListener
        val otherProxy = Proxy.newProxyInstance(
            TestPageListener::class.java.classLoader,
            arrayOf(TestPageListener::class.java),
            HostTopBarFxController.createPageChangeProxyHandler {}
        )
        assertTrue(pageProxy == pageProxy); assertFalse(pageProxy == otherProxy)
        assertEquals(System.identityHashCode(pageProxy), pageProxy.hashCode())
        assertEquals("HostTopBarPageChangeListener", pageProxy.toString())
        assertFalse(pageProxy.onPrimitiveCheck()); assertEquals(0, pageProxy.onIntCheck())
        pageProxy.onPageSelected(1)
        assertEquals(1, pageCbCalls)
    }

    @Test
    fun hostAgentUidValidationAndTargetAppStorageSegmentBoundary() {
        assertFalse(HostAgentRuntime.isValidModuleUid(-1, 10_000))
        assertFalse(HostAgentRuntime.isValidModuleUid(9_999, 10_000))
        assertFalse(HostAgentRuntime.isValidModuleUid(10_251, 10_251))
        assertTrue(HostAgentRuntime.isValidModuleUid(10_251, 10_250))

        val field = HostAgentRuntime::class.java.getDeclaredField("injectedModuleUid").apply { isAccessible = true }
        val prevProvider = HostAgentRuntime.currentUidProvider
        val prevUid = field.getInt(HostAgentRuntime)
        try {
            HostAgentRuntime.currentUidProvider = { 10_000 }
            HostAgentRuntime.setModuleUid(9_999)
            assertEquals(prevUid, field.getInt(HostAgentRuntime))
            HostAgentRuntime.setModuleUid(10_000)
            assertEquals(prevUid, field.getInt(HostAgentRuntime))
            HostAgentRuntime.setModuleUid(10_251)
            assertEquals(10_251, field.getInt(HostAgentRuntime))
        } finally {
            HostAgentRuntime.currentUidProvider = prevProvider
            field.setInt(HostAgentRuntime, prevUid)
        }

        val dir = tempFolder.newFolder("target-cache")
        FakeCacheDirContext.fakeCacheDir = dir
        val ctx = allocateUnsafe(FakeCacheDirContext::class.java) as Context
        val resolved = TargetAppStorage.cacheFile("innocent_lab_adapt.json", ctx)
        assertEquals(File(dir, "innocent_lab_adapt.json"), resolved)
        for (bad in listOf("", " ", "a/b.json", "a\\b.json", ".", "..")) {
            assertThrows(IllegalArgumentException::class.java) {
                TargetAppStorage.cacheFile(bad, ctx)
            }
        }
    }

    private class ObfToStringDescriptor(
        val packageName: String,
        val serviceName: String,
        val methodName: String
    ) {
        @Suppress("unused")
        val k: KClass<*> = Any::class
        @Suppress("unused")
        val s: SerializationStrategy<Any> = P58_DummyStrategy
        override fun toString(): String = "ObfDesc(compact)"
    }

    private class MainListReq

    private class ReorderedKClassDescriptor {
        @Suppress("unused") @JvmField val a: String = "MainList"
        @Suppress("unused") @JvmField val b: String = "bilibili.main.community.reply.v1"
        @Suppress("unused") @JvmField val c: String = "Reply"
        @Suppress("unused") @JvmField val req: KClass<*> = MainListReq::class
        @Suppress("unused") @JvmField val strat: SerializationStrategy<Any> = P58_DummyStrategy
        override fun toString(): String = "minified-desc"
    }

    private class ReorderedServiceSuffixDescriptor {
        @Suppress("unused") @JvmField val x: String = "DefaultWords"
        @Suppress("unused") @JvmField val y: String = "SearchService"
        @Suppress("unused") @JvmField val z: String = "bilibili.app.interface.v1"
        @Suppress("unused") @JvmField val req: KClass<*> = Any::class
        @Suppress("unused") @JvmField val strat: SerializationStrategy<Any> = P58_DummyStrategy
        override fun toString(): String = "obf.S@1a"
    }

    private class PojoCustomToString(
        @Suppress("unused") val packageName: String,
        @Suppress("unused") val serviceName: String,
        @Suppress("unused") val methodName: String
    ) {
        override fun toString(): String = "NotADescriptor()"
    }

    class FakeReplyInfo {
        fun getId(): Long = 100L; fun getOid(): Long = 200L; fun getType(): Long = 1L
        fun getRoot(): Long = 0L; fun getParent(): Long = 0L; fun getDialog(): Long = 0L
        fun getCtime(): Long = 1L; fun getCount(): Long = 1L; fun getMid(): Long = 1L
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
    class FakeReplyMoss { @Suppress("unused") fun detailList(@Suppress("UNUSED_PARAMETER") req: FakeDetailReq, @Suppress("UNUSED_PARAMETER") handler: FakeMossHandler) = Unit }
    class FakeDetailReply { fun getRoot(): FakeReplyInfo = FakeReplyInfo(); fun getPagination(): FakePagination = FakePagination() }
    object FakeCommentMapper { @JvmStatic fun map(@Suppress("UNUSED_PARAMETER") info: FakeReplyInfo): FakeCommentItem = FakeCommentItem() }
    class FakeComposeModel
    class FakeWireReplyMsg {
        fun toByteArray(): ByteArray = ByteArray(0)
        companion object { @JvmStatic fun parseFrom(@Suppress("UNUSED_PARAMETER") bytes: ByteArray): FakeWireReplyMsg = FakeWireReplyMsg() }
    }

    private class FakeCacheDirContext : ContextWrapper(null) {
        override fun getCacheDir(): File = fakeCacheDir
        companion object { lateinit var fakeCacheDir: File }
    }

    private class NonRvView(ctx: Context?) : View(ctx)
    private class SubRv(ctx: Context?) : RecyclerView(ctx!!)

    class PurifyDecorA
    class PurifyOtherB { override fun toString(): String = "OtherB(x=1)" }
    class PurifyFollow(@JvmField val mid: Long) { override fun toString(): String = "Follow(mid=$mid)" }
    class PurifyDecorD
    class PurifyDecoration(@JvmField val a: PurifyDecorA?, @JvmField val b: PurifyOtherB?, @JvmField val c: PurifyFollow?, @JvmField val d: PurifyDecorD?)
    class PurifyUserModel(@JvmField val decoration: PurifyDecoration?)
    class PurifyCommentModel

    class ComposeCommentStub
    class ComposeUserStub(val d: ComposeDecoPrivateStub?)
    class ComposeSlotA
    class ComposeSlotC
    class ComposeSlotD
    class ComposeFollowStub(val mid: Long) { override fun toString() = "Follow(mid=$mid)" }
    class ComposeDecoPrivateStub private constructor(val a: ComposeSlotA?, val renamedFollow: ComposeFollowStub?, val c: ComposeSlotC?, val d: ComposeSlotD?)

    class KConfigStub { fun ff(key: String, default: Boolean): Boolean = default; fun config(key: String, default: String): String = default }
    interface GddContract
    class FlattenedDdImpl : GddContract { fun getBoolean(key: String, default: Boolean, extra: Any?): Boolean = default }
    class DdFacadeTemplate(@JvmField val impl: FlattenedDdImpl = FlattenedDdImpl()) { fun getBoolean(key: String, default: Boolean): Boolean = default }
    object KotlinDd3Arg { @JvmStatic fun getBool(receiver: Any?, key: String, default: Boolean): Boolean = default; @JvmStatic fun getString(receiver: Any?, key: String, default: String?): String? = default }
    class Dd3Arg { fun getBoolean(key: String, default: Boolean, extra: Any?): Boolean = default }
    class DanmakuParams { fun getRequestSubtitle(): Boolean = false; fun getFrom(): String = "ugc"; fun getAvid(): Long = 10L; fun getCid(): Long = 20L }

    class ObfLabel9(val x0: Long = 0L, val x1: String, val x2: Int = 0, val x3: String = "", val x4: Map<String, String> = emptyMap(), val x5: String = "", val x6: Long = 0L, val x7: String = "", val x8: String = "")
    class ReorderedHeadlineData(val fLabel: ObfLabel9?, val fTitle: String)
    class ExtraHeadlineData(val title: String, val label: ObfLabel9?, val extra: String)
    class ReorderedTagCell(val uri: String)
    class ReorderedTagsData(val fRefresh: Boolean, val fCells: List<ReorderedTagCell>)
    class ExtraTagsData(val cells: List<ReorderedTagCell>, val refresh: Boolean, val extra: Int)

    class KBoolValue1Arg(@JvmField val value: Boolean) { fun getValue(): Boolean = value }
    class KPlayConfigTemplate { fun getLandscapeAutoStory(): KBoolValue1Arg = KBoolValue1Arg(true); fun getShouldAutoStory(): KBoolValue1Arg = KBoolValue1Arg(true) }
    class FollowRepo
    class FollowBody3(val repo: FollowRepo, val tag: Int, val cont: Continuation<*>) : Function2<Any?, Any?, Any?> {
        override fun invoke(p1: Any?, p2: Any?): Any? = null; fun invokeSuspend(o: Any?): Any? = o
    }
    class FollowBody4(val repo: FollowRepo, val a: Int, val b: String, val cont: Continuation<*>) : Function2<Any?, Any?, Any?> {
        override fun invoke(p1: Any?, p2: Any?): Any? = null; fun invokeSuspend(o: Any?): Any? = o
    }

    class SplashFakeResources(private val cfg: Configuration) : Resources(null, null, null) { override fun getConfiguration(): Configuration = cfg }
    class SplashFakeView(private val res: Resources) : View(null) { override fun getResources(): Resources = res }
    interface SplashShape
    class SplashRectShape : SplashShape
    object SplashRectShapeKt { private val shape = SplashRectShape(); @JvmStatic fun getRectangleShape(): SplashShape = shape }
    object SplashColorKt { @JvmStatic fun Color(argb: Int): Long = (argb.toLong() and 0xffffffffL) shl 32 }
    interface SplashModifier { companion object : SplashModifier }
    class SplashBgModifier(@JvmField val base: SplashModifier, @JvmField val color: Long) : SplashModifier
    object SplashBackgroundKt { @JvmStatic fun `background-bw27NRU`(mod: SplashModifier, color: Long, shape: SplashShape): SplashModifier = SplashBgModifier(mod, color) }
    interface SplashCompLocal
    class SplashCompLocalImpl : SplashCompLocal
    object SplashAndroidLocalsKt { private val local = SplashCompLocalImpl(); @JvmStatic fun getLocalView(): SplashCompLocal = local }
    interface SplashComposer { fun consume(local: SplashCompLocal): Any? }
    class SplashComposerImpl(@JvmField val view: View) : SplashComposer { override fun consume(local: SplashCompLocal): Any? = view }
    class SplashModel9(val s: BrandSplashSource, val id: Long, val u1: String, val u2: String, val u3: String, val u4: String, val u5: String, val show: Boolean, val m: BrandSplashMode)
    object SplashPageKt {
        @JvmStatic fun BrandSplashPage(model: SplashModel9, onExit: () -> Unit, modifier: SplashModifier, url: String, composer: SplashComposer, changed: Int) = Unit
    }

    class RenamedNickSpan { fun getClickable(): Boolean = true; fun onClick(@Suppress("UNUSED_PARAMETER") v: View?) = Unit }
    class ViewLikeSpan(@Suppress("UNUSED_PARAMETER") c: Context?) : View(c) { fun getClickable(): Boolean = true; fun onClick(@Suppress("UNUSED_PARAMETER") v: View?) = Unit }
    class RenamedNextLikable(@JvmField val f0: CharSequence, @JvmField val f1: CharSequence, @JvmField val f2: CharSequence) { fun getLikableTextMaxLines(): Int = 4 }
    class ObfRichField(@JvmField val x: String)
    class ObfRichGetter(private val x: String) { fun g(): String = x }
    enum class Lang { ORIGIN, TRANSLATING, TRANSLATION }
    class TwoRichComposeModel(@JvmField val id: Long, @JvmField val commentId: Long, @JvmField val rootId: Long, @JvmField val state: Lang, @JvmField val o: ObfRichField, @JvmField val t: ObfRichField?)

    private interface TestPageListener {
        fun onPageSelected(position: Int)
        fun onPrimitiveCheck(): Boolean
        fun onIntCheck(): Int
    }

    private class EndpointStub {
        @Suppress("UNUSED_PARAMETER")
        fun rpc(req: Any, ser: SerializationStrategy<Any>, dec: DeserializationStrategy<Any>, h: Wave4MossCallback, p: ProtoBuf) = Unit
    }

    private class ProtoListFake(initial: List<String>, private var writable: Boolean = false) :
        AbstractMutableList<String>(), Internal.ProtobufList<String> {
        private val data = initial.toMutableList()
        override val size: Int get() = data.size
        override fun get(index: Int): String = data[index]
        override fun add(index: Int, element: String) { check(writable); data.add(index, element) }
        override fun removeAt(index: Int): String { check(writable); return data.removeAt(index) }
        override fun set(index: Int, element: String): String { check(writable); return data.set(index, element) }
        override fun isModifiable(): Boolean = writable
        override fun makeImmutable() { writable = false }
        override fun mutableCopyWithCapacity(capacity: Int): Internal.ProtobufList<String> = ProtoListFake(data, writable = true)
    }

    private open class FakeProtoRelateReply(initial: List<String> = listOf("ad", "keep")) {
        @JvmField var memoizedHashCode = 0
        private var memoizedSize = -1
        @JvmField var items: Internal.ProtobufList<String> = ProtoListFake(initial)
        fun getMemoizedSerializedSize(): Int = memoizedSize
        fun setMemoizedSerializedSize(value: Int) { memoizedSize = value }
        companion object {
            val DEFAULT = FakeProtoRelateReply(listOf("default"))
            @JvmStatic fun getDefaultInstance(): FakeProtoRelateReply = DEFAULT
        }
    }

    private class PurifyControl(val empty: Boolean) {
        fun hasEmptyPage() = empty
        class Builder(src: PurifyControl) {
            var empty = src.empty
            fun clearEmptyPage(): Builder { empty = false; edits++; return this }
            fun build() = PurifyControl(empty)
        }
        companion object {
            var edits = 0
            @JvmStatic val DEFAULT = PurifyControl(true)
            @JvmStatic fun getDefaultInstance() = DEFAULT
            @JvmStatic fun newBuilder(src: PurifyControl) = Builder(src)
        }
    }

    private class PurifyContent(val urls: Map<String, String>) {
        fun getUrlsMap(): Map<String, String> = urls
        class Builder(src: PurifyContent) {
            var urls = src.urls.toMutableMap()
            fun clearUrls(): Builder { urls.clear(); edits++; return this }
            fun putAllUrls(map: Map<*, *>): Builder { @Suppress("UNCHECKED_CAST") urls.putAll(map as Map<String, String>); return this }
            fun build() = PurifyContent(urls)
        }
        companion object {
            var edits = 0
            @JvmStatic val DEFAULT = PurifyContent(mapOf("search" to "bilibili://search?keyword=test"))
            @JvmStatic fun getDefaultInstance() = DEFAULT
            @JvmStatic fun newBuilder(src: PurifyContent) = Builder(src)
        }
    }

    private class PurifyReplyItem(private val rawContent: PurifyContent?) {
        fun getContent() = rawContent
        fun getRepliesList(): List<Any> = emptyList()
        fun hasUpSelection() = false
        class Builder(src: PurifyReplyItem) {
            private var rawContent = src.rawContent
            fun setContent(value: PurifyContent): Builder { rawContent = value; edits++; return this }
            fun build() = PurifyReplyItem(rawContent)
        }
        companion object {
            var edits = 0
            @JvmStatic val DEFAULT = PurifyReplyItem(PurifyContent(mapOf("bad" to "s")))
            @JvmStatic fun getDefaultInstance() = DEFAULT
            @JvmStatic fun newBuilder(src: PurifyReplyItem) = Builder(src)
        }
    }

    private class PurifyTopReply(val replies: List<PurifyReplyItem> = emptyList(), val qoe: Boolean = true, val control: PurifyControl? = PurifyControl(true)) {
        fun getRepliesList(): List<PurifyReplyItem> = replies
        fun hasQoe() = qoe
        fun hasSubjectControl() = control != null
        fun getSubjectControl() = control
        class Builder(src: PurifyTopReply) {
            var items = src.replies.toMutableList(); var qoe = src.qoe; var control = src.control
            fun clearReplies(): Builder { items.clear(); edits++; return this }
            fun addAllReplies(values: Iterable<PurifyReplyItem>): Builder { items.addAll(values); return this }
            fun clearQoe(): Builder { qoe = false; edits++; return this }
            fun setSubjectControl(v: PurifyControl): Builder { control = v; edits++; return this }
            fun build() = PurifyTopReply(items, qoe, control)
        }
        companion object {
            var edits = 0
            @JvmStatic val DEFAULT = PurifyTopReply(listOf(PurifyReplyItem.DEFAULT), qoe = true, control = PurifyControl.DEFAULT)
            @JvmStatic fun getDefaultInstance() = DEFAULT
            @JvmStatic fun newBuilder(src: PurifyTopReply) = Builder(src)
        }
    }
}

internal object P58_DummyStrategy : SerializationStrategy<Any>, DeserializationStrategy<Any>

internal class P58_DirectJavaProtoReply(@JvmField val label: String) {
    fun toByteArray(): ByteArray = label.toByteArray(Charsets.UTF_8)
    companion object {
        private val DEFAULT = P58_DirectJavaProtoReply("")
        @JvmStatic fun getDefaultInstance(): P58_DirectJavaProtoReply = DEFAULT
        @JvmStatic fun parseFrom(bytes: ByteArray): P58_DirectJavaProtoReply =
            P58_DirectJavaProtoReply(String(bytes, Charsets.UTF_8).removePrefix("K:"))
    }
}

internal class P58_FakeKViewMossTemplate {
    interface Handler { fun onNext(reply: Any?) }
    @Suppress("UNUSED_PARAMETER")
    fun view(request: Any, serializer: SerializationStrategy<Any>, decoder: DeserializationStrategy<Any>, handler: Handler, protoBuf: ProtoBuf) = Unit
}

internal data class P58_SampleElem(val weight: Int, val colorful: Int, val content: String)
internal data class P58_SampleReply(val elems: List<P58_SampleElem>, val colorfulSrcTypes: List<Int>, val flags: Long)

internal object P58_DmWireCodec {
    @JvmStatic fun encodeElem(weight: Int, colorful: Int, content: String): ByteArray {
        val out = DanmakuWire.ByteArrayOutput(32)
        if (weight != 0) writeVarintField(out, 8, weight.toLong())
        if (content.isNotEmpty()) writeBytesField(out, 10, content.toByteArray(Charsets.UTF_8))
        if (colorful != 0) writeVarintField(out, 11, colorful.toLong())
        return out.toByteArray()
    }
    @JvmStatic fun encodeColorful(type: Int): ByteArray {
        val out = DanmakuWire.ByteArrayOutput(16)
        if (type != 0) writeVarintField(out, 2, type.toLong())
        return out.toByteArray()
    }
    @JvmStatic fun encodeReply(elems: List<P58_SampleElem>, colorfulSrcTypes: List<Int>, flags: Long): ByteArray {
        val out = DanmakuWire.ByteArrayOutput(64)
        for (elem in elems) writeBytesField(out, 1, encodeElem(elem.weight, elem.colorful, elem.content))
        for (src in colorfulSrcTypes) writeBytesField(out, 4, encodeColorful(src))
        if (flags != 0L) writeVarintField(out, 15, flags)
        return out.toByteArray()
    }
    private fun readVarint(bytes: ByteArray, cursor: IntArray): Long {
        var pos = cursor[0]; var v = 0L; var shift = 0
        while (true) {
            val b = bytes[pos++].toLong() and 0xff
            v = v or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0L) break
            shift += 7
        }
        cursor[0] = pos; return v
    }
    @JvmStatic fun decodeReply(bytes: ByteArray): P58_SampleReply {
        val elems = mutableListOf<P58_SampleElem>(); val srcs = mutableListOf<Int>(); var flags = 0L; val cursor = intArrayOf(0)
        while (cursor[0] < bytes.size) {
            val tag = readVarint(bytes, cursor); val field = (tag ushr 3).toInt()
            when (val wt = (tag and 7).toInt()) {
                0 -> { val value = readVarint(bytes, cursor); if (field == 15) flags = value }
                2 -> {
                    val len = readVarint(bytes, cursor).toInt()
                    val payload = bytes.copyOfRange(cursor[0], cursor[0] + len); cursor[0] += len
                    when (field) { 1 -> elems += decodeElem(payload); 4 -> srcs += decodeColorful(payload) }
                }
                else -> error("unsupported wireType=$wt")
            }
        }
        return P58_SampleReply(elems, srcs, flags)
    }
    private fun decodeElem(payload: ByteArray): P58_SampleElem {
        var weight = 0; var colorful = 0; var content = ""; val cursor = intArrayOf(0)
        while (cursor[0] < payload.size) {
            val tag = readVarint(payload, cursor); val field = (tag ushr 3).toInt()
            when (val wt = (tag and 7).toInt()) {
                0 -> { val value = readVarint(payload, cursor).toInt(); when (field) { 8 -> weight = value; 11 -> colorful = value } }
                2 -> { val len = readVarint(payload, cursor).toInt(); if (field == 10) content = String(payload, cursor[0], len, Charsets.UTF_8); cursor[0] += len }
                else -> error("unsupported elem wireType=$wt")
            }
        }
        return P58_SampleElem(weight, colorful, content)
    }
    private fun decodeColorful(payload: ByteArray): Int {
        var type = 0; val cursor = intArrayOf(0)
        while (cursor[0] < payload.size) {
            val tag = readVarint(payload, cursor); val field = (tag ushr 3).toInt()
            when (val wt = (tag and 7).toInt()) {
                0 -> { val value = readVarint(payload, cursor).toInt(); if (field == 2) type = value }
                2 -> cursor[0] += readVarint(payload, cursor).toInt()
                else -> error("unsupported colorful wireType=$wt")
            }
        }
        return type
    }
}

internal object P58_DmRoundtripCounters {
    @JvmField val parseFromCalls = AtomicInteger(0)
    @JvmField val toByteArrayCalls = AtomicInteger(0)
    fun reset() { parseFromCalls.set(0); toByteArrayCalls.set(0) }
}

internal class P58_TrackableDmElem(private val weight: Int, private val colorful: Int, private val content: String) {
    constructor() : this(0, 0, "")
    fun getWeight(): Int = weight; fun getColorfulValue(): Int = colorful; fun getContent(): String = content
    fun toByteArray(): ByteArray = P58_DmWireCodec.encodeElem(weight, colorful, content)
    class Builder(original: P58_TrackableDmElem) {
        private var weight = original.weight; private var colorful = original.colorful; private var content = original.content
        fun setWeight(value: Int) = apply { weight = value }
        fun setColorfulValue(value: Int) = apply { colorful = value }
        fun setContent(value: String) = apply { content = value }
        fun build() = P58_TrackableDmElem(weight, colorful, content)
    }
    companion object {
        private val DEFAULT = P58_TrackableDmElem()
        @JvmStatic fun getDefaultInstance(): P58_TrackableDmElem = DEFAULT
        @JvmStatic fun newBuilder(original: P58_TrackableDmElem) = Builder(original)
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
        @JvmStatic fun getDefaultInstance(): P58_TrackableDmColorful = DEFAULT
        @JvmStatic fun newBuilder(original: P58_TrackableDmColorful) = Builder(original)
    }
}

internal object P58_TrackableDmColorfulType { @JvmField val VipGradualColor_VALUE: Int = 60001 }

internal class P58_TrackableDmReply(val elems: List<P58_TrackableDmElem>, val colorful: List<P58_TrackableDmColorful>, val flags: Long) {
    constructor() : this(emptyList(), emptyList(), 0L)
    fun getElemsList(): List<P58_TrackableDmElem> = elems
    fun getColorfulSrcList(): List<P58_TrackableDmColorful> = colorful
    fun toByteArray(): ByteArray {
        P58_DmRoundtripCounters.toByteArrayCalls.incrementAndGet()
        return P58_DmWireCodec.encodeReply(elems.map { P58_SampleElem(it.getWeight(), it.getColorfulValue(), it.getContent()) }, colorful.map { it.getTypeValue() }, flags)
    }
    class Builder(original: P58_TrackableDmReply) {
        private var elems = original.elems; private var colorful = original.colorful; private val flags = original.flags
        fun clearElems() = apply { elems = emptyList() }
        fun addAllElems(values: Iterable<P58_TrackableDmElem>) = apply { elems = values.toList() }
        fun clearColorfulSrc() = apply { colorful = emptyList() }
        fun addAllColorfulSrc(values: Iterable<P58_TrackableDmColorful>) = apply { colorful = values.toList() }
        fun build() = P58_TrackableDmReply(elems, colorful, flags)
    }
    companion object {
        private val DEFAULT = P58_TrackableDmReply()
        @JvmStatic fun getDefaultInstance(): P58_TrackableDmReply = DEFAULT
        @JvmStatic fun newBuilder(original: P58_TrackableDmReply) = Builder(original)
        @JvmStatic fun parseFrom(bytes: ByteArray): P58_TrackableDmReply {
            P58_DmRoundtripCounters.parseFromCalls.incrementAndGet()
            val decoded = P58_DmWireCodec.decodeReply(bytes)
            return P58_TrackableDmReply(decoded.elems.map { P58_TrackableDmElem(it.weight, it.colorful, it.content) }, decoded.colorfulSrcTypes.map { P58_TrackableDmColorful(it) }, decoded.flags)
        }
    }
}

internal class P58_FakeKDmMossTemplate {
    interface Handler { fun onNext(reply: Any?) }
    @Suppress("UNUSED_PARAMETER")
    fun dmSegMobile(request: Any, serializer: SerializationStrategy<Any>, decoder: DeserializationStrategy<Any>, handler: Handler, protoBuf: ProtoBuf) = Unit
    @Suppress("UNUSED_PARAMETER")
    fun dmSegCache(request: Any, serializer: SerializationStrategy<Any>, decoder: DeserializationStrategy<Any>, handler: Handler, protoBuf: ProtoBuf) = Unit
}

internal class P58_FollowDriftItem {
    @JvmField var n: List<Any> = emptyList()
    @JvmField var j: P58_FollowDriftItem? = null
    fun a(): Long = 1L; fun b(): List<Any> = emptyList(); fun h(): Boolean = false
}
internal class P58_FollowDriftModule {
    @JvmField var b: P58_FollowDriftItem? = null
    fun M(): P58_FollowDriftItem = P58_FollowDriftItem(); fun O(): Boolean = false
}
internal open class P58_FollowDriftPainter : androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
    @Suppress("UNUSED_PARAMETER")
    fun paint(c: Canvas, rv: androidx.recyclerview.widget.RecyclerView, v: View, m: P58_FollowDriftModule) = Unit
}
internal class P58_FollowDriftHome : P58_FollowDriftPainter()
internal class P58_FollowMainFragment {
    fun onViewCreated(view: View, savedInstanceState: Bundle?) = Unit
    fun onDestroyView() = Unit; fun onPause() = Unit; fun onResume() = Unit; fun getView(): View? = null
}
internal class P58_DummyPegasusFragment
internal class P58_FakeVoteWidget { fun bind() = Unit }
internal class P58_InsightMainListReq {
    fun getOid(): Long = 1L; fun getType(): Long = 1L; fun toByteArray(): ByteArray = ByteArray(0)
    companion object { @JvmStatic fun parseFrom(@Suppress("UNUSED_PARAMETER") bytes: ByteArray): P58_InsightMainListReq = P58_InsightMainListReq() }
}
internal class P58_InsightMainListReply {
    fun getRepliesList(): List<Any> = emptyList(); fun getTopRepliesList(): List<Any> = emptyList(); fun toByteArray(): ByteArray = ByteArray(0)
    companion object { @JvmStatic fun parseFrom(@Suppress("UNUSED_PARAMETER") bytes: ByteArray): P58_InsightMainListReply = P58_InsightMainListReply() }
}

internal class Wave4BadPlayViewReq(private val prefer: LegacyCodeType = LegacyCodeType.CODE_UNKNOWN, private val fnval: Int = 2064) {
    fun getPreferCodecType() = prefer; fun getFnval() = fnval
    class Builder(private val src: Wave4BadPlayViewReq) {
        fun setPreferCodecType(@Suppress("UNUSED_PARAMETER") value: LegacyCodeType): Builder = this
        fun setFnval(@Suppress("UNUSED_PARAMETER") value: Int): Builder = this
        fun build() = Wave4BadPlayViewReq(src.prefer, src.fnval)
    }
    companion object { @JvmStatic fun newBuilder(src: Wave4BadPlayViewReq) = Builder(src) }
}
internal class Wave4BadPlayURLMoss {
    @Suppress("UNUSED_PARAMETER")
    fun executePlayView(req: Wave4BadPlayViewReq): Any = req
}
internal interface Wave4MossCallback { fun onNext(reply: Any?) }
