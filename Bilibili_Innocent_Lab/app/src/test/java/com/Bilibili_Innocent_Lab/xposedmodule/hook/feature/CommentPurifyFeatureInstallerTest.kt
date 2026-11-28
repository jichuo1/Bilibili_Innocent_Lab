package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.invoke
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import io.github.libxposed.api.XposedInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Constructor
import java.lang.reflect.Executable

class CommentPurifyFeatureInstallerTest {

    private data class UrlFixture(
        private val appUrl: String,
        private val displayText: String
    )

    /**
     * 判据是「跳搜索」这个类别，不是某个 `from` 值。
     *
     * 真值取自 2026-09-15 的 `Reply/MainList` 抓包：
     * `Content.urls{}.value.app_url_schema = bilibili://search?from=appcommentline_search…`。
     */
    fun `the search jump criterion matches the captured comment link by category`() {
        assertTrue(CommentPurifyFeatureInstaller.isSearchJumpUri(
            "bilibili://search?from=appcommentline_search&search_from_source=appcommentline_search" +
                "&direct_return=true&keyword=%E6%A2%81%E5%BF%97%E8%B6%85&seid=2798521577071992304"))
        // 同类别的其它 from 也必须命中——不能退化成"一个词一个开关"。
        assertTrue(CommentPurifyFeatureInstaller.isSearchJumpUri("bilibili://search?from=whatever"))
        assertTrue(CommentPurifyFeatureInstaller.isSearchJumpUri("bilibili://search/result?keyword=x"))
    }

    fun `the search jump criterion does not swallow neighbouring hosts or the web url`() {
        // 前缀写法会把这一条误判成命中，类别判据不会。
        assertFalse(CommentPurifyFeatureInstaller.isSearchJumpUri("bilibili://searchxyz?keyword=x"))
        assertFalse(CommentPurifyFeatureInstaller.isSearchJumpUri("bilibili://video/117246567387357"))
        // pc_url(13) 是无 scheme 的网页地址，不归这条判据管（移动端不走它跳转）。
        assertFalse(CommentPurifyFeatureInstaller.isSearchJumpUri(
            "//search.bilibili.com/all?from_source=webcommentline_search&keyword=x"))
        assertFalse(CommentPurifyFeatureInstaller.isSearchJumpUri(null))
        assertFalse(CommentPurifyFeatureInstaller.isSearchJumpUri(""))
    }

    fun `blocks only implicit comment quick reply origins`() {
        assertTrue(CommentPurifyFeatureInstaller.shouldBlockQuickReply(true, "CARD"))
        assertTrue(CommentPurifyFeatureInstaller.shouldBlockQuickReply(true, "TEXT"))
        assertTrue(CommentPurifyFeatureInstaller.shouldBlockQuickReply(true, ""))
        assertFalse(CommentPurifyFeatureInstaller.shouldBlockQuickReply(false, "CARD"))
        assertFalse(CommentPurifyFeatureInstaller.shouldBlockQuickReply(true, "REPLY_BUTTON"))
        assertFalse(CommentPurifyFeatureInstaller.shouldBlockQuickReply(true, "MORE_MENU"))
        assertFalse(CommentPurifyFeatureInstaller.shouldBlockQuickReply(true, "PIC_BAR"))
    }

    fun `keeps original map when no search destination exists`() {
        val source = linkedMapOf("topic" to "bilibili://topic/1")

        val filtered = CommentPurifyFeatureInstaller.withoutSearchUrls(source) {
            it.startsWith("bilibili://search")
        }

        assertSame(source, filtered)
    }

    fun `removes only search entries and returns immutable ordered copy`() {
        val source = linkedMapOf(
            "search" to "bilibili://search?keyword=test",
            "video" to "bilibili://video/BV1",
            "web" to "https://example.com"
        )

        val filtered = CommentPurifyFeatureInstaller.withoutSearchUrls(source) {
            it.startsWith("bilibili://search")
        }

        assertEquals(listOf("video", "web"), filtered.keys.toList())
        assertFalse(filtered.containsKey("search"))
        assertEquals(3, source.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (filtered as MutableMap<String, String>)["new"] = "value"
        }
    }

    fun `detects search uri from cached private text fields only`() {
        val installer = CommentPurifyFeatureInstaller(
            removeSearchLinks = true,
            removeEmptyGuide = false,
            removeVoteWidgets = false,
            removeFollowButtons = false,
            removeQoe = false,
            removeOperations = false,
            points = null
        )

        assertTrue(installer.isSearchUrlValue(UrlFixture("bilibili://search?q=test", "test")))
        assertFalse(installer.isSearchUrlValue(UrlFixture("bilibili://video/BV1", "search")))
        assertFalse(installer.isSearchUrlValue(null))
    }

    fun `locates the exact quick reply collector boundary`() {
        val points = requireNotNull(VersionAdapter.locateCommentPurify(requireNotNull(javaClass.classLoader)))

        assertEquals(1, points.quickReplyDialogMethods.size)
        assertEquals("emit", points.quickReplyDialogMethods.single().methodName)
        assertEquals(
            listOf(
                "com.bilibili.app.comment3.data.state.PublishDialogIntent",
                "kotlin.coroutines.Continuation"
            ),
            points.quickReplyDialogMethods.single().paramClassNames
        )
    }

    fun `resolves empty page defaults once and registers both protobuf versions`() {
        val loader = requireNotNull(javaClass.classLoader)
        val points = requireNotNull(VersionAdapter.locateCommentPurify(loader))
        val statuses = linkedMapOf<String, String>()
        val environment = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = loader,
            hookPoints = HookPointRegistry(loader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { channel, status -> statuses[channel] = status }
        )

        val result = CommentPurifyFeatureInstaller(
            removeSearchLinks = false,
            removeEmptyGuide = true,
            removeVoteWidgets = false,
            removeFollowButtons = false,
            removeQoe = false,
            removeOperations = false,
            points = points
        ).install(environment)

        assertEquals(FeatureInstallResult.Installed(2), result)
        assertEquals("success", statuses["comment_purify_status"])
    }

    fun `registers only structurally adapted vote widget binders`() {
        val loader = requireNotNull(javaClass.classLoader)
        val points = requireNotNull(VersionAdapter.locateCommentPurify(loader))
        val statuses = linkedMapOf<String, String>()
        val environment = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = loader,
            hookPoints = HookPointRegistry(loader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { channel, status -> statuses[channel] = status }
        )

        val result = CommentPurifyFeatureInstaller(
            removeSearchLinks = false,
            removeEmptyGuide = false,
            removeVoteWidgets = true,
            removeFollowButtons = false,
            removeQoe = false,
            removeOperations = false,
            points = points
        ).install(environment)

        assertEquals(FeatureInstallResult.Installed(3), result)
        assertEquals("success", statuses["comment_purify_status"])
    }

    fun `registers complete follow visibility state and header bind points`() {
        val loader = requireNotNull(javaClass.classLoader)
        val points = requireNotNull(VersionAdapter.locateCommentPurify(loader))
        val statuses = linkedMapOf<String, String>()
        val environment = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = loader,
            hookPoints = HookPointRegistry(loader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { channel, status -> statuses[channel] = status }
        )

        val result = CommentPurifyFeatureInstaller(
            removeSearchLinks = false,
            removeEmptyGuide = false,
            removeVoteWidgets = false,
            removeFollowButtons = true,
            removeQoe = false,
            removeOperations = false,
            points = points
        ).install(environment)

        assertEquals(FeatureInstallResult.Installed(5), result)
        assertEquals("success", statuses["comment_purify_status"])
    }

    fun `registers qoe public read boundaries without main list mutation`() {
        val loader = requireNotNull(javaClass.classLoader)
        val points = requireNotNull(VersionAdapter.locateCommentPurify(loader))
        val statuses = linkedMapOf<String, String>()
        val environment = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = loader,
            hookPoints = HookPointRegistry(loader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { channel, status -> statuses[channel] = status }
        )

        val result = CommentPurifyFeatureInstaller(
            removeSearchLinks = false,
            removeEmptyGuide = false,
            removeVoteWidgets = false,
            removeFollowButtons = false,
            removeQoe = true,
            removeOperations = false,
            points = points
        ).install(environment)

        assertEquals(FeatureInstallResult.Installed(2), result)
        assertEquals("success", statuses["comment_purify_status"])
    }

    fun `registers both operation versions without mutating main list reply`() {
        val loader = requireNotNull(javaClass.classLoader)
        val points = requireNotNull(VersionAdapter.locateCommentPurify(loader))
        val statuses = linkedMapOf<String, String>()
        val environment = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = loader,
            hookPoints = HookPointRegistry(loader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { channel, status -> statuses[channel] = status }
        )

        val result = CommentPurifyFeatureInstaller(
            removeSearchLinks = false,
            removeEmptyGuide = false,
            removeVoteWidgets = false,
            removeFollowButtons = false,
            removeQoe = false,
            removeOperations = true,
            points = points
        ).install(environment)

        assertEquals(FeatureInstallResult.Installed(4), result)
        assertEquals("success", statuses["comment_purify_status"])
    }

    private class ComposeRecordingRegistrar : HookRegistrar {
        data class Entry(val member: Executable?, val callback: ModernMemberHookCreator)
        val exactHooks = linkedMapOf<String, Entry>()
        val adaptedHooks = linkedMapOf<String, ModernMemberHookCreator>()
        val constructorHooks = linkedMapOf<String, Entry>()
        val attempts = mutableListOf<String>()

        override fun first(id: String, className: String, methodName: String, block: ModernMemberHookCreator.() -> Unit) {
            attempts += id
        }

        override fun all(id: String, className: String, methodName: String, block: ModernMemberHookCreator.() -> Unit) {
            attempts += id
        }

        override fun exact(
            id: String,
            owner: Class<*>,
            methodName: String,
            vararg parameterTypes: Class<*>,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            attempts += id
            val m = owner.getDeclaredMethod(methodName, *parameterTypes)
            exactHooks[id] = Entry(m, ModernMemberHookCreator(m).apply(block))
        }

        override fun adapted(
            id: String,
            point: VersionAdapter.HookPoint,
            exceptionPolicy: HookExceptionPolicy,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            attempts += id
            adaptedHooks[id] = ModernMemberHookCreator(DummyExecutable::class.java.declaredMethods.first()).apply(block)
        }

        override fun constructor(
            id: String,
            constructor: Constructor<*>,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            attempts += id
            constructorHooks[id] = Entry(constructor, ModernMemberHookCreator(constructor).apply(block))
        }

        fun invokeExact(id: String, receiver: Any? = null, args: Array<Any?> = emptyArray(), original: (Array<Any?>) -> Any? = { null }): Any? {
            val entry = exactHooks.getValue(id)
            return invokeCreator(entry.callback, entry.member, receiver, args, original)
        }

        fun invokeConstructor(id: String, instance: Any, args: Array<Any?>): Any? {
            val entry = constructorHooks.getValue(id)
            return invokeCreator(entry.callback, entry.member, instance, args) { null }
        }

        private fun invokeCreator(
            creator: ModernMemberHookCreator,
            member: Executable?,
            receiver: Any?,
            originalArgs: Array<Any?>,
            original: (Array<Any?>) -> Any?
        ): Any? = creator.invoke(object : XposedInterface.Chain {
            override fun getExecutable() = member ?: error("No executable")
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

        object DummyExecutable { fun run() = Unit }
    }

    enum class LangEnum { ORIGIN, TRANSLATING, TRANSLATION }
    class RichTextBox(@JvmField val renamedRaw: String)
    class RenamedComposeModel(
        @JvmField val z1: Long,
        @JvmField val z2: Long,
        @JvmField val z3: Long,
        @JvmField val state: LangEnum,
        @JvmField val orig: RichTextBox,
        @JvmField val trans: RichTextBox,
        @JvmField val a1: RichTextBox,
        @JvmField val a2: RichTextBox
    )

    fun `compose copy and purify bridges resolve renamed fields and renderer-discovered CardStore`() {
        val modelAccess = requireNotNull(ComposeCommentText.resolve(RenamedComposeModel::class.java))
        val sample = RenamedComposeModel(
            1001L, 1002L, 1003L, LangEnum.TRANSLATION,
            RichTextBox("orig"), RichTextBox("translated"), RichTextBox("o"), RichTextBox("p")
        )
        assertEquals(1001L, modelAccess.cardId(sample))
        assertEquals(1003L, modelAccess.rootCommentId(sample))
        assertEquals("translated", modelAccess.read(sample))

        val modelPkg = "kntr.common.comment.card.model.comment"
        val renderPkg = "kntr.common.comment.card.renderer.comment"
        val userName = "$modelPkg.CommentUserInfoRendererModel"
        val decorName = "$userName\$Deco"
        val followName = "$userName\$B"
        val actionName = "kntr.common.comment.card.action.CardAction"
        val quickTypeName = "$actionName\$RenamedClickCard"
        val rendererName = "$renderPkg.CommentRichTextRenderer"
        val loader = TestRenamingClassLoader(
            javaClass.classLoader!!,
            templates = mapOf(
                "$modelPkg.CommentModel" to ComposeBridgeCommentModel::class.java,
                userName to ComposeBridgeFakeUserInfo::class.java,
                decorName to ComposeBridgeDecorated::class.java,
                followName to ComposeBridgeRenamedFollow::class.java,
                "$userName\$A" to ComposeBridgeSlotA::class.java,
                "$userName\$C" to ComposeBridgeSlotC::class.java,
                "$userName\$D" to ComposeBridgeSlotD::class.java,
                actionName to ComposeBridgeFakeCardAction::class.java,
                quickTypeName to ComposeBridgeFakeCardAction.RenamedClickCard::class.java,
                rendererName to ComposeBridgeFakeRenderer::class.java,
                "cstore" to ComposeBridgeFlattenedStore::class.java,
                "cctx" to ComposeBridgeFlattenedCtx::class.java,
                "androidx.compose.runtime.Composer" to ComposeBridgeFakeComposer::class.java
            ),
            replacements = mapOf(
                ComposeBridgeCommentModel::class.java.name.replace('.', '/') to "$modelPkg/CommentModel",
                ComposeBridgeFakeUserInfo::class.java.name.replace('.', '/') to userName.replace('.', '/'),
                ComposeBridgeDecorated::class.java.name.replace('.', '/') to decorName.replace('.', '/'),
                ComposeBridgeRenamedFollow::class.java.name.replace('.', '/') to followName.replace('.', '/'),
                ComposeBridgeSlotA::class.java.name.replace('.', '/') to "$userName\$A".replace('.', '/'),
                ComposeBridgeSlotC::class.java.name.replace('.', '/') to "$userName\$C".replace('.', '/'),
                ComposeBridgeSlotD::class.java.name.replace('.', '/') to "$userName\$D".replace('.', '/'),
                ComposeBridgeFakeCardAction::class.java.name.replace('.', '/') to actionName.replace('.', '/'),
                ComposeBridgeFakeCardAction.RenamedClickCard::class.java.name.replace('.', '/') to quickTypeName.replace('.', '/'),
                ComposeBridgeFlattenedStore::class.java.name.replace('.', '/') to "cstore",
                ComposeBridgeFlattenedCtx::class.java.name.replace('.', '/') to "cctx",
                ComposeBridgeFakeComposer::class.java.name.replace('.', '/') to "androidx/compose/runtime/Composer"
            )
        )
        val registrar = ComposeRecordingRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        val results = CommentComposePurifyBridge(vote = false, follow = true, quickReply = true)
            .install(
                HookEnvironment(
                    processName = "tv.danmaku.bili",
                    classLoader = loader,
                    hookPoints = HookPointRegistry(loader),
                    registrar = registrar,
                    logInfo = { _, _ -> },
                    logError = { _, _ -> },
                    reportStatus = { _, _ -> },
                    runtimeEvidence = { _, stage, _ -> stages += stage }
                )
            )
        assertTrue((results["comments_follow_buttons_removed"] as? FeatureInstallResult.Installed)?.complete == true)
        assertTrue((results["comments_quick_reply_blocked"] as? FeatureInstallResult.Installed)?.complete == true)
        assertTrue("comment.compose.quick_reply.cstore" in registrar.exactHooks)

        val loadedDecor = loader.loadClass(decorName)
        val loadedFollow = loader.loadClass(followName).getDeclaredConstructor(Long::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(42L)
        val followArgs = arrayOf<Any?>(null, loadedFollow, null, null)
        val decorInstance = loadedDecor.declaredConstructors.single().apply { isAccessible = true }.newInstance(*arrayOfNulls<Any>(4))
        registrar.invokeConstructor("comment.compose.follow.$decorName", decorInstance, followArgs)
        assertNull(followArgs[1])

        val clickCard = loader.loadClass(quickTypeName).declaredConstructors.single().apply { isAccessible = true }
            .newInstance(1L, 2L, 3L, 4L, 5L, "s1", "s2", true)
        registrar.invokeExact("comment.compose.quick_reply.cstore", args = arrayOf(clickCard))
        assertTrue(FeatureRuntimeStage.OBSERVED in stages)
        assertTrue(FeatureRuntimeStage.APPLIED in stages)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (CommentPurifyFeatureInstallerTest) -> Unit) {
            val s = CommentPurifyFeatureInstallerTest()
            block(s)
        }
        runIsolated { s -> s.`the search jump criterion matches the captured comment link by category`() }
        runIsolated { s -> s.`the search jump criterion does not swallow neighbouring hosts or the web url`() }
        runIsolated { s -> s.`blocks only implicit comment quick reply origins`() }
        runIsolated { s -> s.`keeps original map when no search destination exists`() }
        runIsolated { s -> s.`removes only search entries and returns immutable ordered copy`() }
        runIsolated { s -> s.`detects search uri from cached private text fields only`() }
        runIsolated { s -> s.`locates the exact quick reply collector boundary`() }
        runIsolated { s -> s.`resolves empty page defaults once and registers both protobuf versions`() }
        runIsolated { s -> s.`registers only structurally adapted vote widget binders`() }
        runIsolated { s -> s.`registers complete follow visibility state and header bind points`() }
        runIsolated { s -> s.`registers qoe public read boundaries without main list mutation`() }
        runIsolated { s -> s.`registers both operation versions without mutating main list reply`() }
        runIsolated { s -> s.`compose copy and purify bridges resolve renamed fields and renderer-discovered CardStore`() }
    }
}

internal class ComposeBridgeCommentModel
internal class ComposeBridgeRenamedFollow(@JvmField val mid: Long) {
    override fun toString() = "Follow(mid=$mid)"
}
internal class ComposeBridgeSlotA
internal class ComposeBridgeSlotC
internal class ComposeBridgeSlotD
internal class ComposeBridgeDecorated(
    @JvmField val w: ComposeBridgeSlotA?,
    @JvmField var x: ComposeBridgeRenamedFollow?,
    @JvmField val y: ComposeBridgeSlotC?,
    @JvmField val z: ComposeBridgeSlotD?
)
internal class ComposeBridgeFakeUserInfo(@JvmField val decor: ComposeBridgeDecorated?)
internal open class ComposeBridgeFakeCardAction {
    class RenamedClickCard(
        @JvmField val l1: Long,
        @JvmField val l2: Long,
        @JvmField val l3: Long,
        @JvmField val l4: Long,
        @JvmField val l5: Long,
        @JvmField val s1: String,
        @JvmField val s2: String,
        @JvmField val flag: Boolean
    ) : ComposeBridgeFakeCardAction() {
        override fun toString() = "ClickCard(id=$l1)"
    }
}
internal class ComposeBridgeFlattenedStore { fun handle(a: ComposeBridgeFakeCardAction) = Unit }
internal class ComposeBridgeFlattenedCtx(@JvmField val store: ComposeBridgeFlattenedStore)
internal interface ComposeBridgeFakeComposer
internal class ComposeBridgeFakeRenderer {
    fun render(ctx: ComposeBridgeFlattenedCtx, composer: ComposeBridgeFakeComposer) = Unit
}
