package com.Bilibili_Innocent_Lab.xposedmodule.hook

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.HostAgentRuntime
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentComposeMenuAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentReplyButtonAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.DetailRelateGameAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HomeTabResourceAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedMenuAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedSessionLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.WeChatShareCompatAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexGraphPlan
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexMeguvizAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexMeguvizQueries
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CloudKeyFreezeFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentComposePurifyBridge
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentFullTextReader
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentRichTextReader
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentTopologyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ComposeCommentText
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FollowUpRecommendFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HeadlineBadgeModelCleaner
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HomeFrameLockFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HomeVerticalDetailFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerAiSubtitleFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SpecialTopicTagsModelCleaner
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SplashAutoNightFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.ComposeChangedMask
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.invoke
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.TargetAppStorage
import io.github.libxposed.api.XposedInterface
import kotlin.coroutines.Continuation
import kntr.srcs.app.splash.brand.model.BrandSplashMode
import kntr.srcs.app.splash.brand.model.BrandSplashSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.nio.file.Files

class Phase5To8DexAndFeaturesTest {

    private class LocalTestRegistrar : HookRegistrar {
        data class Entry(val member: Executable, val callback: ModernMemberHookCreator)
        val exactHooks = linkedMapOf<String, Entry>()
        val constructorHooks = linkedMapOf<String, Entry>()

        override fun first(id: String, className: String, methodName: String, block: ModernMemberHookCreator.() -> Unit) = Unit
        override fun all(id: String, className: String, methodName: String, block: ModernMemberHookCreator.() -> Unit) = Unit
        override fun adapted(
            id: String,
            point: VersionAdapter.HookPoint,
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
            exactHooks[id] = Entry(m, ModernMemberHookCreator(m).apply(block))
        }

        override fun constructor(id: String, constructor: Constructor<*>, block: ModernMemberHookCreator.() -> Unit) {
            constructorHooks[id] = Entry(constructor, ModernMemberHookCreator(constructor).apply(block))
        }

        fun invokeExact(
            id: String,
            receiver: Any? = null,
            args: Array<Any?> = emptyArray(),
            original: (Array<Any?>) -> Any? = { null }
        ): Any? {
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

    private class RenamingClassLoader(
        parent: ClassLoader,
        private val templates: Map<String, Class<*>>,
        private val replacements: Map<String, String> = emptyMap(),
        private val hiddenNames: Set<String> = emptySet(),
        private val hiddenPrefixes: Set<String> = emptySet()
    ) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            if (name in hiddenNames || hiddenPrefixes.any(name::startsWith)) {
                throw ClassNotFoundException(name)
            }
            val template = templates[name] ?: return@synchronized super.loadClass(name, resolve)
            val loaded = findLoadedClass(name) ?: run {
                val raw = requireNotNull(template.getResourceAsStream("/${template.name.replace('.', '/')}.class"))
                    .use { it.readBytes() }
                val map = replacements + (template.name.replace('.', '/') to name.replace('.', '/'))
                val bytes = rewriteConstantPool(raw, map)
                defineClass(name, bytes, 0, bytes.size)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }

        private fun rewriteConstantPool(raw: ByteArray, map: Map<String, String>): ByteArray {
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
                    map.entries.sortedByDescending { it.key.length }.forEach { (k, v) ->
                        value = value.replace(k, v)
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

    private fun env(
        loader: ClassLoader = javaClass.classLoader!!,
        registrar: HookRegistrar = LocalTestRegistrar(),
        statuses: MutableList<Pair<String, String>> = mutableListOf(),
        stages: MutableList<Pair<String, FeatureRuntimeStage>> = mutableListOf()
    ) = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = loader,
        hookPoints = HookPointRegistry(loader),
        registrar = registrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { ch, s -> statuses += ch to s },
        runtimeEvidence = { id, st, _ -> stages += id to st }
    )

    private fun allocateUnsafe(clazz: Class<*>): Any {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(clazz)
    }

    // -------------------------------------------------------------------------
    // 5B-2 / 7B: HostAgentRuntime UID boundary & TargetAppStorage cacheFile
    // -------------------------------------------------------------------------

    fun `host agent runtime validates module uid strictly`() {
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
    }

    private class FakeCacheDirContext : ContextWrapper(null) {
        override fun getCacheDir(): File = fakeCacheDir
        companion object {
            lateinit var fakeCacheDir: File
        }
    }

    fun `target app storage prefers context cache dir and validates single file segment`() {
        val dir = Files.createTempDirectory("bil-target-cache").toFile()
        try {
            FakeCacheDirContext.fakeCacheDir = dir
            val ctx = allocateUnsafe(FakeCacheDirContext::class.java) as Context
            val resolved = TargetAppStorage.cacheFile("innocent_lab_adapt.json", ctx)
            assertEquals(File(dir, "innocent_lab_adapt.json"), resolved)
            for (bad in listOf("", " ", "a/b.json", "a\\b.json", ".", "..")) {
                assertThrows(IllegalArgumentException::class.java) {
                    TargetAppStorage.cacheFile(bad, ctx)
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // 5D-1 / 5D-3 / 5D-5: CommentTopology RV check, Compose Follow slot, KeelPlayer
    // -------------------------------------------------------------------------

    private class NonRvView(ctx: Context?) : View(ctx)
    private class SubRv(ctx: Context?) : RecyclerView(ctx!!)

    fun `comment topology detects RecyclerView via bounded superclass walk without direct cast import`() {
        val method = CommentTopologyFeatureInstaller::class.java
            .getDeclaredMethod("isRecyclerViewInstance", View::class.java)
            .apply { isAccessible = true }
        val installer = CommentTopologyFeatureInstaller(
            enabled = false,
            points = null,
            lowBindPoint = null,
            highBindPoint = null
        )
        assertFalse(method.invoke(installer, null) as Boolean)
        assertFalse(method.invoke(installer, allocateUnsafe(NonRvView::class.java)) as Boolean)
        assertTrue(method.invoke(installer, allocateUnsafe(SubRv::class.java)) as Boolean)
    }

    class PurifyDecorA
    class PurifyOtherB {
        override fun toString(): String = "OtherB(x=1)"
    }
    class PurifyFollow(@JvmField val mid: Long) {
        override fun toString(): String = "Follow(mid=$mid)"
    }
    class PurifyDecorD
    class PurifyDecoration(
        @JvmField val a: PurifyDecorA?,
        @JvmField val b: PurifyOtherB?,
        @JvmField val c: PurifyFollow?,
        @JvmField val d: PurifyDecorD?
    )
    class PurifyUserModel(@JvmField val decoration: PurifyDecoration?)
    class PurifyCommentModel

    fun `comment compose purify clears renamed follow slot in a-bOther-cFollow-d without b shadowing Follow`() {
        val base = PurifyUserModel::class.java.name.replace('.', '/')
        val templates = linkedMapOf<String, Class<*>>(
            "kntr.common.comment.card.model.comment.CommentModel" to PurifyCommentModel::class.java,
            "kntr.common.comment.card.model.comment.CommentUserInfoRendererModel" to PurifyUserModel::class.java,
            "kntr.common.comment.card.model.comment.CommentUserInfoRendererModel\$Decoration" to PurifyDecoration::class.java,
            "kntr.common.comment.card.model.comment.CommentUserInfoRendererModel\$DecorA" to PurifyDecorA::class.java,
            "kntr.common.comment.card.model.comment.CommentUserInfoRendererModel\$OtherB" to PurifyOtherB::class.java,
            "kntr.common.comment.card.model.comment.CommentUserInfoRendererModel\$Follow" to PurifyFollow::class.java,
            "kntr.common.comment.card.model.comment.CommentUserInfoRendererModel\$DecorD" to PurifyDecorD::class.java
        )
        val replacements = linkedMapOf(
            base to "kntr/common/comment/card/model/comment/CommentUserInfoRendererModel",
            PurifyDecoration::class.java.name.replace('.', '/') to "kntr/common/comment/card/model/comment/CommentUserInfoRendererModel\$Decoration",
            PurifyDecorA::class.java.name.replace('.', '/') to "kntr/common/comment/card/model/comment/CommentUserInfoRendererModel\$DecorA",
            PurifyOtherB::class.java.name.replace('.', '/') to "kntr/common/comment/card/model/comment/CommentUserInfoRendererModel\$OtherB",
            PurifyFollow::class.java.name.replace('.', '/') to "kntr/common/comment/card/model/comment/CommentUserInfoRendererModel\$Follow",
            PurifyDecorD::class.java.name.replace('.', '/') to "kntr/common/comment/card/model/comment/CommentUserInfoRendererModel\$DecorD"
        )
        val loader = RenamingClassLoader(javaClass.classLoader!!, templates, replacements)
        val registrar = LocalTestRegistrar()
        val bridge = CommentComposePurifyBridge(vote = false, follow = true, quickReply = false)
        val result = bridge.install(env(loader, registrar)).getValue("comments_follow_buttons_removed")
        assertEquals(FeatureInstallResult.Installed(1, complete = true), result)

        val decClass = loader.loadClass("kntr.common.comment.card.model.comment.CommentUserInfoRendererModel\$Decoration")
        val hookId = "comment.compose.follow.${decClass.name}"
        val aVal = loader.loadClass("kntr.common.comment.card.model.comment.CommentUserInfoRendererModel\$DecorA")
            .getDeclaredConstructor().newInstance()
        val bVal = loader.loadClass("kntr.common.comment.card.model.comment.CommentUserInfoRendererModel\$OtherB")
            .getDeclaredConstructor().newInstance()
        val followVal = loader.loadClass("kntr.common.comment.card.model.comment.CommentUserInfoRendererModel\$Follow")
            .getDeclaredConstructor(Long::class.javaPrimitiveType).newInstance(42L)
        val dVal = loader.loadClass("kntr.common.comment.card.model.comment.CommentUserInfoRendererModel\$DecorD")
            .getDeclaredConstructor().newInstance()
        val args = arrayOf<Any?>(aVal, bVal, followVal, dVal)
        val instance = decClass.constructors.single().newInstance(aVal, bVal, null, dVal)
        registrar.invokeConstructor(hookId, instance, args)
        assertSame(aVal, args[0])
        assertSame(bVal, args[1])
        assertNull("renamed c(Follow) slot must be cleared to null", args[2])
        assertSame(dVal, args[3])
    }

    // Minimal host shape templates for theseus2.keel.player and flattened KeelPlayer checks.
    class KeelManagerTemplate
    class KeelPlayerTemplate(@JvmField val mgr: KeelManagerTemplate = KeelManagerTemplate())
    class WindowDelegateTemplate
    class FunctionSvcTemplate
    class KeelSpeedWidgetTemplate(@JvmField val keel: KeelPlayerTemplate = KeelPlayerTemplate())
    class SpeedMediaResourceTemplate
    open class SpeedRecyclerAdapterTemplate
    class SpeedOptionTemplate(@JvmField val s: Float, @JvmField val a: Boolean)
    object SpeedFactoryTemplate {
        @JvmField val defaults = listOf(0.5f, 1.0f, 1.5f, 2.0f)
        @JvmStatic fun create(r: SpeedMediaResourceTemplate?, q: Int?, f: Boolean, m: Int): List<SpeedOptionTemplate> = emptyList()
    }
    class SpeedAdapterTemplate(@Suppress("UNUSED_PARAMETER") theme: Int) : SpeedRecyclerAdapterTemplate(), View.OnClickListener {
        class Item { @JvmField var a = 1.0f; @JvmField var b = false; @JvmField var c = true }
        @JvmField val list: List<Item> = emptyList()
        fun pop(f: Float, l: List<SpeedOptionTemplate>) = Unit
        override fun onClick(v: View?) = Unit
    }
    class CompactOwnerTemplate(
        @JvmField val fs: FunctionSvcTemplate,
        @JvmField val kp: KeelPlayerTemplate,
        @JvmField val wd: WindowDelegateTemplate
    ) {
        fun getFunctionWidgetService(): FunctionSvcTemplate = fs
        fun getKeelPlayer(): KeelPlayerTemplate = kp
        fun getWindowSizeDelegate(): WindowDelegateTemplate = wd
        companion object {
            @JvmStatic fun open(o: CompactOwnerTemplate) = Unit
        }
    }

    fun `player speed locator accepts theseus2 keel player and flattened owner`() {
        fun buildLoader(keelName: String): ClassLoader {
            val templates = linkedMapOf<String, Class<*>>(
                PlayerSpeedLocator.SPEED_MANAGER to KeelManagerTemplate::class.java,
                keelName to KeelPlayerTemplate::class.java,
                PlayerSpeedLocator.THESEUS_SPEED_WIDGET to KeelSpeedWidgetTemplate::class.java,
                PlayerSpeedLocator.MEDIA_RESOURCE to SpeedMediaResourceTemplate::class.java,
                PlayerSpeedLocator.RECYCLER_ADAPTER to SpeedRecyclerAdapterTemplate::class.java,
                "tv.danmaku.biliplayerv2.service.AbsFunctionWidgetService" to FunctionSvcTemplate::class.java,
                "${PlayerSpeedLocator.SPEED_UTILS_PKG}.a" to SpeedFactoryTemplate::class.java,
                "${PlayerSpeedLocator.SPEED_UTILS_PKG}.b" to SpeedOptionTemplate::class.java,
                "${PlayerSpeedLocator.SPEED_WIDGET_PKG}.f" to SpeedAdapterTemplate::class.java,
                "${PlayerSpeedLocator.SPEED_WIDGET_PKG}.f\$Item" to SpeedAdapterTemplate.Item::class.java,
                "${PlayerSpeedLocator.THESEUS_SPEED_PKG}.a" to CompactOwnerTemplate::class.java,
                "fkWindowDelegate" to WindowDelegateTemplate::class.java
            )
            val replacements = linkedMapOf(
                KeelManagerTemplate::class.java.name.replace('.', '/') to PlayerSpeedLocator.SPEED_MANAGER.replace('.', '/'),
                KeelPlayerTemplate::class.java.name.replace('.', '/') to keelName.replace('.', '/'),
                KeelSpeedWidgetTemplate::class.java.name.replace('.', '/') to PlayerSpeedLocator.THESEUS_SPEED_WIDGET.replace('.', '/'),
                SpeedMediaResourceTemplate::class.java.name.replace('.', '/') to PlayerSpeedLocator.MEDIA_RESOURCE.replace('.', '/'),
                SpeedRecyclerAdapterTemplate::class.java.name.replace('.', '/') to PlayerSpeedLocator.RECYCLER_ADAPTER.replace('.', '/'),
                FunctionSvcTemplate::class.java.name.replace('.', '/') to "tv/danmaku/biliplayerv2/service/AbsFunctionWidgetService",
                SpeedFactoryTemplate::class.java.name.replace('.', '/') to "${PlayerSpeedLocator.SPEED_UTILS_PKG.replace('.', '/')}/a",
                SpeedOptionTemplate::class.java.name.replace('.', '/') to "${PlayerSpeedLocator.SPEED_UTILS_PKG.replace('.', '/')}/b",
                SpeedAdapterTemplate::class.java.name.replace('.', '/') to "${PlayerSpeedLocator.SPEED_WIDGET_PKG.replace('.', '/')}/f",
                SpeedAdapterTemplate.Item::class.java.name.replace('.', '/') to "${PlayerSpeedLocator.SPEED_WIDGET_PKG.replace('.', '/')}/f\$Item",
                CompactOwnerTemplate::class.java.name.replace('.', '/') to "${PlayerSpeedLocator.THESEUS_SPEED_PKG.replace('.', '/')}/a",
                WindowDelegateTemplate::class.java.name.replace('.', '/') to "fkWindowDelegate"
            )
            return RenamingClassLoader(javaClass.classLoader!!, templates, replacements)
        }
        assertNotNull(PlayerSpeedLocator.speedMenu(buildLoader("com.bilibili.ship.theseus2.keel.player.m"))?.compactRoute)
        assertTrue(PlayerSpeedMenuAdapter.flattenedOwner("fk"))
        assertNotNull(PlayerSpeedLocator.speedMenu(buildLoader("fk"))?.compactRoute)
    }

    // -------------------------------------------------------------------------
    // 6B / 8A: DEX GraphPlans fallbackOnly & Group-A plans
    // -------------------------------------------------------------------------

    fun `dex graph plans mark Group-A sourceInformation and compose fallbacks as fallbackOnly and reject empty paths`() {
        val groupAQueries = listOf(
            DexAssistQuery.HOME_CHROME_TOP,
            DexAssistQuery.HOME_CHROME_TOP_LAYOUT,
            DexAssistQuery.HOME_CHROME_TOP_BACKDROP,
            DexAssistQuery.HOME_CHROME_TOP_RIGHT_ACTIONS,
            DexAssistQuery.HOME_CHROME_TOP_CONTENT,
            DexAssistQuery.HOME_CHROME_BOTTOM,
            DexAssistQuery.HOME_CHROME_BOTTOM_CONTENT,
            DexAssistQuery.HOME_CHROME_BOTTOM_ITEM,
            DexAssistQuery.BRAND_SPLASH_KNTR_DRAW
        )
        for (q in groupAQueries) {
            val plans = DexMeguvizQueries.graphPlans(q)
            assertTrue("$q should have fallbackOnly plans", plans.isNotEmpty() && plans.all { it.fallbackOnly })
            plans.forEach { JSONObject(it.toQueryIrJson()) }
        }
        val pagePlans = DexMeguvizQueries.graphPlans(DexAssistQuery.BRAND_SPLASH_KNTR_PAGE)
        assertEquals(2, pagePlans.size)
        assertTrue(pagePlans[0].fallbackOnly)
        assertTrue(pagePlans[1].fallbackOnly)

        val composePlans = DexMeguvizQueries.graphPlans(DexAssistQuery.COMMENT_COMPOSE_MORE_MENU)
        assertEquals(3, composePlans.size)
        assertTrue(composePlans[0].fallbackOnly)
        assertTrue(composePlans[1].fallbackOnly)
        assertTrue(composePlans[2].fallbackOnly)

        val followUpJson = DexMeguvizQueries.graphPlans(DexAssistQuery.FOLLOW_UP_RECOMMEND_BODY)
            .joinToString("\n") { raw ->
                Regex("\"u16\":\\[([0-9,]+)]").replace(raw.toQueryIrJson()) { m ->
                    val text = m.groupValues[1].split(',').joinToString("") { it.toInt().toChar().toString() }
                    "\"value\":\"$text\""
                }
            }
        assertTrue(followUpJson.contains("\"min\":2") && followUpJson.contains("\"max\":4"))
        assertTrue(followUpJson.contains("Lkotlin/coroutines/Continuation;)V"))

        assertThrows(IllegalArgumentException::class.java) {
            DexMeguvizAssistEngine.withArchiveSnapshot(emptyList()) {}
        }
        assertEquals(
            DexAssistResult.Reason.TOO_MANY_MATCHES to null,
            DexMeguvizAssistEngine.applyGraphRejection(null, null, DexAssistResult.Reason.TOO_MANY_MATCHES)
        )
    }

    // -------------------------------------------------------------------------
    // 8B & 8C: Feature installers & bridges
    // -------------------------------------------------------------------------

    class KConfigStub {
        fun ff(key: String, default: Boolean): Boolean = default
        fun config(key: String, default: String): String = default
    }
    interface GddContract
    class FlattenedDdImpl : GddContract {
        fun getBoolean(key: String, default: Boolean, extra: Any?): Boolean = default
    }
    class DdFacadeTemplate(@JvmField val impl: FlattenedDdImpl = FlattenedDdImpl()) {
        fun getBoolean(key: String, default: Boolean): Boolean = default
    }
    object KotlinDd3Arg {
        @JvmStatic fun getBool(receiver: Any?, key: String, default: Boolean): Boolean = default
        @JvmStatic fun getString(receiver: Any?, key: String, default: String?): String? = default
    }

    fun `cloud key freeze supports optional KConfig, 3-arg Kotlin DD, and field-discovered flattened GDeviceDecision`() {
        val templates = linkedMapOf<String, Class<*>>(
            "kntr.base.config.KConfig" to KConfigStub::class.java,
            "com.bilibili.lib.dd.GDeviceDecision" to GddContract::class.java,
            "com.bilibili.lib.dd.DeviceDecision" to DdFacadeTemplate::class.java,
            "FlatDdImpl" to FlattenedDdImpl::class.java,
            "kntr.base.dd.IDeviceDecisionKt" to KotlinDd3Arg::class.java
        )
        val replacements = linkedMapOf(
            KConfigStub::class.java.name.replace('.', '/') to "kntr/base/config/KConfig",
            GddContract::class.java.name.replace('.', '/') to "com/bilibili/lib/dd/GDeviceDecision",
            DdFacadeTemplate::class.java.name.replace('.', '/') to "com/bilibili/lib/dd/DeviceDecision",
            FlattenedDdImpl::class.java.name.replace('.', '/') to "FlatDdImpl",
            KotlinDd3Arg::class.java.name.replace('.', '/') to "kntr/base/dd/IDeviceDecisionKt"
        )
        val loader = RenamingClassLoader(javaClass.classLoader!!, templates, replacements)
        val registrar = LocalTestRegistrar()
        val statuses = mutableListOf<Pair<String, String>>()
        val result = CloudKeyFreezeFeatureInstaller(true, "flag.a=true", false)
            .install(env(loader, registrar, statuses))
        assertTrue(result is FeatureInstallResult.Installed)
        val status = statuses.single().second
        assertTrue(status, status.contains("kconfig"))
        assertTrue(status, status.contains("dd-impl"))
        assertTrue(status, status.contains("kotlin-dd"))
        val kcHook = registrar.exactHooks.entries.first {
            it.key.startsWith("cloud_key_freeze.kconfig.") && it.value.member.name == "ff"
        }.key
        assertEquals(true, registrar.invokeExact(kcHook, args = arrayOf("flag.a", false)) { false })
        val k3Hook = registrar.exactHooks.entries.first {
            it.key.startsWith("cloud_key_freeze.kotlin-dd.") && it.value.member.name == "getBool"
        }.key
        assertEquals(true, registrar.invokeExact(k3Hook, args = arrayOf(null, "flag.a", false)) { false })
    }

    fun `home frame lock preserves strict KConfig fail-closed boundary even when DeviceDecision exists`() {
        val loaderNoKConfig = RenamingClassLoader(
            javaClass.classLoader!!,
            mapOf("com.bilibili.lib.dd.DeviceDecision" to DdFacadeTemplate::class.java),
            mapOf(DdFacadeTemplate::class.java.name.replace('.', '/') to "com/bilibili/lib/dd/DeviceDecision"),
            hiddenNames = setOf("kntr.base.config.KConfig")
        )
        assertEquals(
            FeatureInstallResult.Skipped("missing-config-boundary"),
            HomeFrameLockFeatureInstaller(true).install(env(loaderNoKConfig))
        )

        val loaderBoth = RenamingClassLoader(
            javaClass.classLoader!!,
            mapOf(
                "kntr.base.config.KConfig" to KConfigStub::class.java,
                "com.bilibili.lib.dd.DeviceDecision" to DdFacadeTemplate::class.java
            ),
            mapOf(
                KConfigStub::class.java.name.replace('.', '/') to "kntr/base/config/KConfig",
                DdFacadeTemplate::class.java.name.replace('.', '/') to "com/bilibili/lib/dd/DeviceDecision"
            )
        )
        val registrar = LocalTestRegistrar()
        val installed = HomeFrameLockFeatureInstaller(true).install(env(loaderBoth, registrar))
        assertEquals(FeatureInstallResult.Installed(1), installed)
        assertEquals(setOf("home_frame_lock.flag"), registrar.exactHooks.keys)
        assertEquals(false, registrar.invokeExact("home_frame_lock.flag", args = arrayOf(HomeFrameLockFeatureInstaller.FRAME_FLAG_KEY, false)) { true })
    }

    class Dd3Arg {
        fun getBoolean(key: String, default: Boolean, extra: Any?): Boolean = default
    }
    class DanmakuParams {
        fun getRequestSubtitle(): Boolean = false
        fun getFrom(): String = "ugc"
        fun getAvid(): Long = 10L
        fun getCid(): Long = 20L
    }

    fun `player ai subtitle supports 3-arg DD, KConfig, and IDeviceDecisionKt while keeping expected 2`() {
        val loader = RenamingClassLoader(
            javaClass.classLoader!!,
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
        val registrar = LocalTestRegistrar()
        val res = PlayerAiSubtitleFeatureInstaller(true).install(env(loader, registrar))
        assertEquals(FeatureInstallResult.Installed(2, complete = true), res)
        assertTrue("player.subtitle.ai.language.3" in registrar.exactHooks)
        assertTrue("player.subtitle.ai.kconfig" in registrar.exactHooks)
        assertTrue("player.subtitle.ai.kotlin_dd.3" in registrar.exactHooks)
        assertEquals(
            true,
            registrar.invokeExact("player.subtitle.ai.kotlin_dd.3", args = arrayOf(null, PlayerAiSubtitleFeatureInstaller.LANGUAGE_FLAG, false)) { false }
        )
    }

    class ObfLabel9(
        val x0: Long = 0L,
        val x1: String,
        val x2: Int = 0,
        val x3: String = "",
        val x4: Map<String, String> = emptyMap(),
        val x5: String = "",
        val x6: Long = 0L,
        val x7: String = "",
        val x8: String = ""
    )
    class ReorderedHeadlineData(val fLabel: ObfLabel9?, val fTitle: String)
    class ExtraHeadlineData(val title: String, val label: ObfLabel9?, val extra: String)
    class ReorderedTagCell(val uri: String)
    class ReorderedTagsData(val fRefresh: Boolean, val fCells: List<ReorderedTagCell>)
    class ExtraTagsData(val cells: List<ReorderedTagCell>, val refresh: Boolean, val extra: Int)

    fun `detail presentation purifiers accept reordered obfuscated 2-field models and fail closed on extra fields`() {
        val headlineCleaner = requireNotNull(HeadlineBadgeModelCleaner.resolve(ReorderedHeadlineData::class.java))
        val badHeadline = ReorderedHeadlineData(ObfLabel9(x1 = "bilibili://search?from=apphotword_search_huangtiao"), "新闻")
        val cleanedHeadline = headlineCleaner.removeHotSearchBadge(badHeadline) as ReorderedHeadlineData
        assertEquals("新闻", cleanedHeadline.fTitle)
        assertNull(cleanedHeadline.fLabel)
        assertNull(HeadlineBadgeModelCleaner.resolve(ExtraHeadlineData::class.java))

        val tagsCleaner = requireNotNull(SpecialTopicTagsModelCleaner.resolve(ReorderedTagsData::class.java))
        val badTags = ReorderedTagsData(true, listOf(ReorderedTagCell("https://m.bilibili.com/topic-detail?topic_id=1")))
        val cleanedTags = tagsCleaner.removeSpecialTopicCells(badTags) as ReorderedTagsData
        assertTrue(cleanedTags.fCells.isEmpty())
        assertFalse(cleanedTags.fRefresh)
        assertNull(SpecialTopicTagsModelCleaner.resolve(ExtraTagsData::class.java))
    }

    class KBoolValue1Arg(@JvmField val value: Boolean) {
        fun getValue(): Boolean = value
    }
    class KPlayConfigTemplate {
        fun getLandscapeAutoStory(): KBoolValue1Arg = KBoolValue1Arg(true)
        fun getShouldAutoStory(): KBoolValue1Arg = KBoolValue1Arg(true)
    }

    fun `home vertical detail falls back to 1-arg Boolean constructor on KBoolValue`() {
        val loader = RenamingClassLoader(
            javaClass.classLoader!!,
            mapOf(
                "com.bapis.bilibili.app.distribution.setting.play.KPlayConfig" to KPlayConfigTemplate::class.java,
                "com.bapis.bilibili.app.distribution.KBoolValue" to KBoolValue1Arg::class.java
            ),
            mapOf(
                KPlayConfigTemplate::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/distribution/setting/play/KPlayConfig",
                KBoolValue1Arg::class.java.name.replace('.', '/') to "com/bapis/bilibili/app/distribution/KBoolValue"
            ),
            hiddenPrefixes = setOf(
                "com.bapis.bilibili.app.distribution.BoolValue",
                "com.bapis.bilibili.app.distribution.setting.play.PlayConfig"
            )
        )
        val registrar = LocalTestRegistrar()
        val installer = HomeVerticalDetailFeatureInstaller(true, null)
        val m = HomeVerticalDetailFeatureInstaller::class.java
            .getDeclaredMethod("installPlayConfigStorySuppression", HookEnvironment::class.java)
            .apply { isAccessible = true }
        val layer = m.invoke(installer, env(loader, registrar))!!
        assertEquals(2, registrar.exactHooks.size)
        val hookRes = registrar.invokeExact("home.vertical.k_play_config.getLandscapeAutoStory")
        val boolClass = loader.loadClass("com.bapis.bilibili.app.distribution.KBoolValue")
        assertTrue(boolClass.isInstance(hookRes))
        assertEquals(false, boolClass.getMethod("getValue").invoke(hookRes))
        assertNotNull(layer)
    }

    class FollowRepo
    class FollowBody3(val repo: FollowRepo, val tag: Int, val cont: Continuation<*>) : Function2<Any?, Any?, Any?> {
        override fun invoke(p1: Any?, p2: Any?): Any? = null
        fun invokeSuspend(o: Any?): Any? = o
    }
    class FollowBody4(val repo: FollowRepo, val a: Int, val b: String, val cont: Continuation<*>) : Function2<Any?, Any?, Any?> {
        override fun invoke(p1: Any?, p2: Any?): Any? = null
        fun invokeSuspend(o: Any?): Any? = o
    }

    fun `follow up recommend requestBodyEntry matches 2 to 4 parameter coroutine body constructors`() {
        val loader = javaClass.classLoader!!
        assertNotNull(FollowUpRecommendFeatureInstaller.requestBodyEntry(loader, FollowBody3::class.java, FollowRepo::class.java))
        assertNotNull(FollowUpRecommendFeatureInstaller.requestBodyEntry(loader, FollowBody4::class.java, FollowRepo::class.java))
    }

    class SplashFakeResources(private val cfg: Configuration) : Resources(null, null, null) {
        override fun getConfiguration(): Configuration = cfg
    }
    class SplashFakeView(private val res: Resources) : View(null) {
        override fun getResources(): Resources = res
    }
    interface SplashShape
    class SplashRectShape : SplashShape
    object SplashRectShapeKt {
        private val shape = SplashRectShape()
        @JvmStatic fun getRectangleShape(): SplashShape = shape
    }
    object SplashColorKt {
        @JvmStatic fun Color(argb: Int): Long = (argb.toLong() and 0xffffffffL) shl 32
    }
    interface SplashModifier {
        companion object : SplashModifier
    }
    class SplashBgModifier(@JvmField val base: SplashModifier, @JvmField val color: Long) : SplashModifier
    object SplashBackgroundKt {
        @JvmStatic fun `background-bw27NRU`(mod: SplashModifier, color: Long, shape: SplashShape): SplashModifier =
            SplashBgModifier(mod, color)
    }
    interface SplashCompLocal
    class SplashCompLocalImpl : SplashCompLocal
    object SplashAndroidLocalsKt {
        private val local = SplashCompLocalImpl()
        @JvmStatic fun getLocalView(): SplashCompLocal = local
    }
    interface SplashComposer {
        fun consume(local: SplashCompLocal): Any?
    }
    class SplashComposerImpl(@JvmField val view: View) : SplashComposer {
        override fun consume(local: SplashCompLocal): Any? = view
    }
    class SplashModel9(
        val s: BrandSplashSource, val id: Long,
        val u1: String, val u2: String, val u3: String, val u4: String, val u5: String,
        val show: Boolean, val m: BrandSplashMode
    )
    object SplashPageKt {
        @JvmStatic fun BrandSplashPage(
            model: SplashModel9,
            onExit: () -> Unit,
            modifier: SplashModifier,
            url: String,
            composer: SplashComposer,
            changed: Int
        ) = Unit
    }

    fun `splash auto night installs on kntr BrandSplashPage modifying only Modifier and changed mask`() {
        val templates = linkedMapOf<String, Class<*>>(
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
        )
        val replacements = linkedMapOf(
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
        )
        val loader = RenamingClassLoader(
            javaClass.classLoader!!,
            templates,
            replacements,
            hiddenNames = setOf("tv.danmaku.bili.ui.splash.brand.ui.BaseBrandSplashFragment")
        )
        val registrar = LocalTestRegistrar()
        val result = SplashAutoNightFeatureInstaller(true).install(env(loader, registrar))
        assertEquals(FeatureInstallResult.Installed(1, complete = true), result)

        val cfg = allocateUnsafe(Configuration::class.java) as Configuration
        cfg.uiMode = Configuration.UI_MODE_NIGHT_YES
        val resObj = allocateUnsafe(SplashFakeResources::class.java) as SplashFakeResources
        SplashFakeResources::class.java.getDeclaredField("cfg").apply { isAccessible = true }.set(resObj, cfg)
        val fakeView = allocateUnsafe(SplashFakeView::class.java) as SplashFakeView
        SplashFakeView::class.java.getDeclaredField("res").apply { isAccessible = true }.set(fakeView, resObj)

        val modelClass = loader.loadClass("kntr.srcs.app.splash.brand.startup.SplashModel9")
        val model = modelClass.constructors.single().newInstance(
            BrandSplashSource.OFFICIAL, 1L, "", "", "", "", "", false, BrandSplashMode.HALF
        )
        val onExit: () -> Unit = {}
        val modClass = loader.loadClass("androidx.compose.ui.Modifier")
        val modCompanion = modClass.getField("Companion").get(null)
        val composer = loader.loadClass("androidx.compose.runtime.SplashComposerImpl")
            .getConstructor(View::class.java).newInstance(fakeView)
        val initialFlags = 1 or (0b111 shl 7)
        val args = arrayOf<Any?>(model, onExit, modCompanion, "url", composer, initialFlags)
        registrar.invokeExact("splash.auto_night.kntr_page", args = args)

        assertSame(model, args[0])
        assertSame(onExit, args[1])
        val bgMod = args[2]!!
        assertEquals("androidx.compose.foundation.SplashBgModifier", bgMod.javaClass.name)
        val appliedColor = bgMod.javaClass.getField("color").getLong(bgMod)
        assertEquals((Color.BLACK.toLong() and 0xffffffffL) shl 32, appliedColor)
        assertEquals(ComposeChangedMask.markUncertain(initialFlags, 2), args[5])
    }

    class RenamedNickSpan {
        fun getClickable(): Boolean = true
        fun onClick(@Suppress("UNUSED_PARAMETER") v: View?) = Unit
    }
    class ViewLikeSpan(@Suppress("UNUSED_PARAMETER") c: Context?) : View(c) {
        fun getClickable(): Boolean = true
        fun onClick(@Suppress("UNUSED_PARAMETER") v: View?) = Unit
    }
    class RenamedNextLikable(
        @JvmField val f0: CharSequence,
        @JvmField val f1: CharSequence,
        @JvmField val f2: CharSequence
    ) {
        fun getLikableTextMaxLines(): Int = 4
    }
    class ObfRichField(@JvmField val x: String)
    class ObfRichGetter(private val x: String) {
        fun g(): String = x
    }
    enum class Lang { ORIGIN, TRANSLATING, TRANSLATION }
    class TwoRichComposeModel(
        @JvmField val id: Long,
        @JvmField val commentId: Long,
        @JvmField val rootId: Long,
        @JvmField val state: Lang,
        @JvmField val o: ObfRichField,
        @JvmField val t: ObfRichField?
    )

    fun `comment bridges fall back structurally for nickname span, likable fields, rich text, and compose 2-field text`() {
        val reader = CommentFullTextReader()
        assertTrue(reader.isNicknameSpan(RenamedNickSpan()))
        assertFalse(reader.isNicknameSpan(allocateUnsafe(ViewLikeSpan::class.java)))

        val loader = RenamingClassLoader(
            javaClass.classLoader!!,
            mapOf("com.bilibili.app.comment3.ui.nextwidget.NextLikableTextView" to RenamedNextLikable::class.java),
            mapOf(RenamedNextLikable::class.java.name.replace('.', '/') to "com/bilibili/app/comment3/ui/nextwidget/NextLikableTextView")
        )
        val viewInstance = loader.loadClass("com.bilibili.app.comment3.ui.nextwidget.NextLikableTextView")
            .getConstructor(CharSequence::class.java, CharSequence::class.java, CharSequence::class.java)
            .newInstance("full body", "short", "tail")
        assertEquals("full body", reader.read(viewInstance))

        assertEquals("abc", CommentRichTextReader.resolve(ObfRichField::class.java)!!.read(ObfRichField("abc")))
        assertEquals("xyz", CommentRichTextReader.resolve(ObfRichGetter::class.java)!!.read(ObfRichGetter("xyz")))

        val composeText = requireNotNull(ComposeCommentText.resolve(TwoRichComposeModel::class.java))
        assertEquals(
            "译文",
            composeText.read(TwoRichComposeModel(1L, 2L, 3L, Lang.TRANSLATION, ObfRichField("原文"), ObfRichField("译文")))
        )
    }

    class ObfHomeTabWithGettersAndScheme(
        @JvmField var z: String = "",
        @JvmField var w: String = "",
        @JvmField var a: String = "bilibili://main/home",
        @JvmField var m: String = ""
    ) {
        fun getId(): String = z
        fun getTitle(): String = w
        fun getReportId(): String = m
    }

    class ObfHomeTabPlainAlpha(
        @JvmField var b: String = "",
        @JvmField var a: String = "",
        @JvmField var d: String = "",
        @JvmField var c: String = ""
    )

    fun `lazy refreshCache short circuits warm cache first skips probes and home tab infers flattened roles`() {
        var probes = 0
        fun failOnLambda(): Boolean {
            probes++
            return true
        }
        assertFalse(PlayerSpeedSessionLocator.refreshCache(false, ::failOnLambda, ::failOnLambda, ::failOnLambda, ::failOnLambda))
        assertFalse(PlayerSpeedLocator.calculatorRefreshCache(false, ::failOnLambda, ::failOnLambda, ::failOnLambda, ::failOnLambda))
        assertFalse(WeChatShareCompatAccess.refreshCache(false, ::failOnLambda, ::failOnLambda, ::failOnLambda, ::failOnLambda))
        assertEquals("disabled features must not evaluate refreshCache lambdas", 0, probes)

        var directChecked = 0
        assertFalse(
            WeChatShareCompatAccess.refreshCache(
                enabled = true,
                applicable = { true },
                directFound = { directChecked++; false },
                cachedFound = { true },
                attempted = { false }
            )
        )
        assertEquals("valid cached hit must short-circuit directFound lambda", 0, directChecked)

        val loaderLookups = mutableListOf<String>()
        val probeLoader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                loaderLookups += name
                return super.loadClass(name, resolve)
            }
        }
        assertNull(DetailRelateGameAdapter.resolve(probeLoader, "evil.pkg.Comp"))
        assertTrue(CommentReplyButtonAdapter.resolvePlans(probeLoader, emptyMap()).isEmpty())
        assertTrue(loaderLookups.any { it.startsWith("com.bilibili.app.comment3.") })
        assertFalse(CommentComposeMenuAdapter.resolve(probeLoader, null).isComplete())

        val getterMapped = requireNotNull(HomeTabResourceAdapter.resolveFieldMapping(ObfHomeTabWithGettersAndScheme::class.java))
        assertEquals("z", getterMapped.idField)
        assertEquals("w", getterMapped.titleField)
        assertEquals("a", getterMapped.uriField)
        assertEquals("m", getterMapped.reporterIdField)

        val alphaFallback = requireNotNull(HomeTabResourceAdapter.resolveFieldMapping(ObfHomeTabPlainAlpha::class.java))
        assertEquals("a", alphaFallback.idField)
        assertEquals("b", alphaFallback.titleField)
        assertEquals("c", alphaFallback.uriField)
        assertEquals("d", alphaFallback.reporterIdField)
    }

    @Test
    fun verifyAllCases() {
        fun runIsolated(block: (Phase5To8DexAndFeaturesTest) -> Unit) {
            val s = Phase5To8DexAndFeaturesTest()
            block(s)
        }
        runIsolated { it.`host agent runtime validates module uid strictly`() }
        runIsolated { it.`target app storage prefers context cache dir and validates single file segment`() }
        runIsolated { it.`comment topology detects RecyclerView via bounded superclass walk without direct cast import`() }
        runIsolated { it.`comment compose purify clears renamed follow slot in a-bOther-cFollow-d without b shadowing Follow`() }
        runIsolated { it.`player speed locator accepts theseus2 keel player and flattened owner`() }
        runIsolated { it.`dex graph plans mark Group-A sourceInformation and compose fallbacks as fallbackOnly and reject empty paths`() }
        runIsolated { it.`cloud key freeze supports optional KConfig, 3-arg Kotlin DD, and field-discovered flattened GDeviceDecision`() }
        runIsolated { it.`home frame lock preserves strict KConfig fail-closed boundary even when DeviceDecision exists`() }
        runIsolated { it.`player ai subtitle supports 3-arg DD, KConfig, and IDeviceDecisionKt while keeping expected 2`() }
        runIsolated { it.`detail presentation purifiers accept reordered obfuscated 2-field models and fail closed on extra fields`() }
        runIsolated { it.`home vertical detail falls back to 1-arg Boolean constructor on KBoolValue`() }
        runIsolated { it.`follow up recommend requestBodyEntry matches 2 to 4 parameter coroutine body constructors`() }
        runIsolated { it.`splash auto night installs on kntr BrandSplashPage modifying only Modifier and changed mask`() }
        runIsolated { it.`comment bridges fall back structurally for nickname span, likable fields, rich text, and compose 2-field text`() }
        runIsolated { it.`lazy refreshCache short circuits warm cache first skips probes and home tab infers flattened roles`() }
    }
}
