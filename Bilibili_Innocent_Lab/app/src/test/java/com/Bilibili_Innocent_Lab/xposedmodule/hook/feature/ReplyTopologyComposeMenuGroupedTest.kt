package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.Activity
import android.content.Context
import android.os.IBinder
import android.view.View
import androidx.compose.ui.Modifier
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterTest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentComposeMenuAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyThreadKey
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class ReplyTopologyComposeMenuGroupedTest {

    class FixtureCompositionLocal
    object FixtureAndroidLocals {
        private val localView = FixtureCompositionLocal()
        @JvmStatic
        fun getLocalView(): FixtureCompositionLocal = localView
    }
    interface FixtureComposer {
        fun consume(key: FixtureCompositionLocal): Any?
    }
    class FixturePaddingValues
    open class FixturePainter

    @Suppress("unused")
    class FixtureRootEntry(
        val a: String,
        val b: FixturePainter,
        val c: String,
        val d: () -> Unit
    ) {
        companion object {
            @JvmField val f: Int = 8
        }
    }

    object FixtureBubble {
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun d(
            items: List<*>,
            onDismiss: (Any?) -> Unit,
            composer: FixtureComposer,
            changed: Int
        ) = Unit

        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun e(
            entry: FixtureRootEntry,
            onClick: () -> Unit,
            modifier: Modifier,
            composer: FixtureComposer,
            changed: Int
        ) = Unit
    }

    object FixtureGroupedMenu {
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun b(
            groups: List<*>,
            onDismiss: (Any?) -> Unit,
            modifier: Modifier,
            padding: FixturePaddingValues,
            composer: FixtureComposer,
            changed: Int,
            defaultFlag: Int
        ) = Unit

        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun c(
            entry: FixtureRootEntry,
            padding: FixturePaddingValues,
            selected: Boolean,
            modifier: Modifier,
            composer: FixtureComposer,
            changed: Int
        ) = Unit
    }

    object FixtureLegacyBottomType

    object FixtureLegacyMenu {
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun c(
            style: FixtureLegacyBottomType,
            title: String,
            icon: FixturePainter,
            modifier: Modifier,
            trail: FixturePainter,
            accent: Boolean,
            composer: FixtureComposer,
            changed: Int,
            defaultFlag: Int
        ) = Unit
    }

    object FixtureCommentModel

    private fun hostLoader(
        entryClassName: String = "wmo1",
        bubbleClassName: String? = "kntr.common.comment.common.ui.moremenu.MoreMenuBubbleKt",
        groupedClassName: String? = "kntr.common.comment.common.ui.moremenu.x",
        legacyClassName: String? = null,
        includeCommentModel: Boolean = true
    ): ClassLoader {
        fun internal(cls: Class<*>) = cls.name.replace('.', '/')
        val templates = linkedMapOf<String, Class<*>>(
            "androidx.compose.ui.platform.AndroidCompositionLocals_androidKt" to FixtureAndroidLocals::class.java,
            "androidx.compose.runtime.CompositionLocal" to FixtureCompositionLocal::class.java,
            "androidx.compose.runtime.Composer" to FixtureComposer::class.java,
            "androidx.compose.ui.graphics.painter.Painter" to FixturePainter::class.java,
            "androidx.compose.foundation.layout.PaddingValues" to FixturePaddingValues::class.java,
            entryClassName to FixtureRootEntry::class.java,
            "$entryClassName\$Companion" to FixtureRootEntry.Companion::class.java,
            "kntr.common.bilitheme.compose.bottomsheet.a" to FixtureLegacyBottomType::class.java
        )
        val replacements = linkedMapOf(
            internal(FixtureAndroidLocals::class.java) to "androidx/compose/ui/platform/AndroidCompositionLocals_androidKt",
            internal(FixtureCompositionLocal::class.java) to "androidx/compose/runtime/CompositionLocal",
            internal(FixtureComposer::class.java) to "androidx/compose/runtime/Composer",
            internal(FixturePainter::class.java) to "androidx/compose/ui/graphics/painter/Painter",
            internal(FixturePaddingValues::class.java) to "androidx/compose/foundation/layout/PaddingValues",
            internal(FixtureRootEntry::class.java) to entryClassName.replace('.', '/'),
            internal(FixtureRootEntry.Companion::class.java) to "${entryClassName.replace('.', '/')}\$Companion",
            internal(FixtureLegacyBottomType::class.java) to "kntr/common/bilitheme/compose/bottomsheet/a"
        )
        if (bubbleClassName != null) {
            templates[bubbleClassName] = FixtureBubble::class.java
            replacements[internal(FixtureBubble::class.java)] = bubbleClassName.replace('.', '/')
        }
        if (groupedClassName != null) {
            templates[groupedClassName] = FixtureGroupedMenu::class.java
            replacements[internal(FixtureGroupedMenu::class.java)] = groupedClassName.replace('.', '/')
        }
        if (legacyClassName != null) {
            templates[legacyClassName] = FixtureLegacyMenu::class.java
            replacements[internal(FixtureLegacyMenu::class.java)] = legacyClassName.replace('.', '/')
        }
        if (includeCommentModel) {
            templates[CommentComposeMenuAdapter.COMMENT_MODEL] = FixtureCommentModel::class.java
            replacements[internal(FixtureCommentModel::class.java)] = CommentComposeMenuAdapter.COMMENT_MODEL.replace('.', '/')
        }
        return TestRenamingClassLoader(
            parent = requireNotNull(javaClass.classLoader),
            templates = templates,
            replacements = replacements
        )
    }

    private fun env(loader: ClassLoader, registrar: HookRegistrar, evidence: MutableList<FeatureRuntimeStage> = mutableListOf()) =
        HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = loader,
            hookPoints = HookPointRegistry(loader),
            registrar = registrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> },
            runtimeEvidence = { id, stage, _ -> if (id == CommentTopologyFeatureInstaller.ID) evidence += stage }
        )

    private fun testRegistrar(recorder: PlayerPortTestRegistrar) = object : HookRegistrar by recorder {
        override fun adapted(
            id: String,
            point: VersionAdapter.HookPoint,
            exceptionPolicy: HookExceptionPolicy,
            block: ModernMemberHookCreator.() -> Unit
        ) = error("Unexpected adapted")
    }

    private open class FakeActivity : Activity() {
        override fun getSystemService(name: String): Any? = null
    }
    private open class FakeView : View(null) {
        var fakeContext: Context? = null
        var attached = true
        var shown = true
        var fakeRoot: View? = null
        var fakeToken: IBinder? = null
        override fun isAttachedToWindow(): Boolean = attached
        override fun isShown(): Boolean = shown
        override fun getRootView(): View? = fakeRoot
        override fun getWindowToken(): IBinder? = fakeToken
    }

    private fun stubViews(): Pair<FakeView, FakeView> {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        val activity = unsafe.allocateInstance(FakeActivity::class.java) as FakeActivity
        val token = java.lang.reflect.Proxy.newProxyInstance(
            IBinder::class.java.classLoader,
            arrayOf(IBinder::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "hashCode" -> 1
                "equals" -> false
                else -> null
            }
        } as IBinder
        val anchor = (unsafe.allocateInstance(FakeView::class.java) as FakeView).apply {
            fakeContext = activity
            attached = true
            shown = true
            fakeRoot = this
            fakeToken = token
        }
        val menuView = (unsafe.allocateInstance(FakeView::class.java) as FakeView).apply {
            fakeContext = activity
            attached = true
            shown = true
            fakeRoot = this
            fakeToken = token
        }
        return anchor to menuView
    }

    @Test
    fun `grouped compose menu injects topology entry, clears param0 changed bits, stays idempotent, and invokes click`() {
        for ((entryName, groupedName) in listOf(
            "kntr.common.comment.common.ui.moremenu.k" to "kntr.common.comment.common.ui.moremenu.x",
            "wmo1" to "kntr.common.comment.common.ui.moremenu.k"
        )) {
            val loader = hostLoader(entryClassName = entryName, groupedClassName = groupedName)
            val recorder = PlayerPortTestRegistrar()
            val evidence = mutableListOf<FeatureRuntimeStage>()
            val opened = mutableListOf<Pair<View, ReplyTopologySeed>>()
            var now = 100L
            val menu = ReplyTopologyComposeMenu(
                uptimeMs = { now },
                viewContext = { view -> requireNotNull((view as FakeView).fakeContext) }
            ) { view, seed -> opened += view to seed }
            val result = menu.install(env(loader, testRegistrar(recorder), evidence))
            assertEquals(FeatureInstallResult.Installed(2, complete = true), result)
            assertTrue("reply.topology.compose.menu" in recorder.hooks)
            assertTrue("reply.topology.compose.grouped_menu" in recorder.hooks)

            val (anchor, menuView) = stubViews()
            val seed = ReplyTopologySeed(ReplyTopologyThreadKey(1L, 1L, 888L), 3, emptyList())
            menu.remember(anchor, seed) { true }

            val entryClass = loader.loadClass(entryName)
            val ctor = entryClass.declaredConstructors.single { !it.isSynthetic }
            val painterClass = loader.loadClass("androidx.compose.ui.graphics.painter.Painter")
            val painter = painterClass.getDeclaredConstructor().newInstance()
            var officialClicks = 0
            val officialAction: () -> Unit = { officialClicks++ }
            val originalEntry = ctor.newInstance("Copy", painter, "", officialAction)
            val dangerEntry = ctor.newInstance("Report", painter, "", officialAction)
            val originalGroup0 = listOf(originalEntry)
            val originalGroup1 = listOf(dangerEntry)
            val groups = listOf(originalGroup0, originalGroup1)

            val composerClass = loader.loadClass("androidx.compose.runtime.Composer")
            val composer = java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(composerClass)) { _, method, _ ->
                if (method.name == "consume") menuView else null
            }
            val padding = loader.loadClass("androidx.compose.foundation.layout.PaddingValues").getDeclaredConstructor().newInstance()
            val initialChanged = 0b0010_1110 // param0 changed bits set + upper bits + force bit 0
            val args1 = arrayOf<Any?>(groups, { _: Any? -> Unit }, Modifier, padding, composer, initialChanged, 0)
            var forwarded1: Array<Any?>? = null
            recorder.invoke("reply.topology.compose.grouped_menu", args = args1) { forwarded ->
                forwarded1 = forwarded.copyOf()
                null
            }

            val merged = forwarded1!![0] as List<*>
            assertEquals(2, merged.size)
            val mergedGroup0 = merged[0] as List<*>
            assertEquals(2, mergedGroup0.size)
            assertSame(originalEntry, mergedGroup0[0])
            assertSame(originalGroup1, merged[1])
            assertEquals("Original group must not be mutated", 1, originalGroup0.size)
            assertEquals(0b0010_0000, forwarded1!![5])
            assertEquals(listOf(FeatureRuntimeStage.OBSERVED), evidence)

            // Recomposition with equal freshly-allocated list reuses the exact cached replacement (no duplicate entry).
            val argsRecompose = arrayOf<Any?>(listOf(listOf(originalEntry), listOf(dangerEntry)), { _: Any? -> Unit }, Modifier, padding, composer, initialChanged, 0)
            var forwardedRecompose: Array<Any?>? = null
            recorder.invoke("reply.topology.compose.grouped_menu", args = argsRecompose) { forwarded ->
                forwardedRecompose = forwarded.copyOf()
                null
            }
            assertSame(merged, forwardedRecompose!![0])

            // Already-injected list is not double-injected even if passed back in.
            val argsAlready = arrayOf<Any?>(merged, { _: Any? -> Unit }, Modifier, padding, composer, initialChanged, 0)
            var forwardedAlready: Array<Any?>? = null
            recorder.invoke("reply.topology.compose.grouped_menu", args = argsAlready) { forwarded ->
                forwardedAlready = forwarded.copyOf()
                null
            }
            assertEquals(2, ((forwardedAlready!![0] as List<*>)[0] as List<*>).size)

            // Invoking injected entry action opens topology after host dismisses sheet (detached menuView).
            menuView.attached = false
            val actionField = entryClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.sortedBy { it.name }.last().apply { isAccessible = true }
            val actionProxy = actionField.get(mergedGroup0[1])
            val invokeMethod = actionField.type.getMethod("invoke")
            invokeMethod.invoke(actionProxy)
            assertEquals(1, opened.size)
            assertSame(anchor, opened.single().first)
            assertSame(seed, opened.single().second)
            assertEquals(0, officialClicks)
        }
    }

    @Test
    fun `grouped only menu without bubble installs via MoreMenuItem entry and cached dex points recover flattened owners`() {
        // Case 1: Bubble removed completely, only MoreMenuGroupedList + MoreMenuItem exist.
        val groupedOnlyLoader = hostLoader(
            entryClassName = "wmo1",
            bubbleClassName = null,
            groupedClassName = "kntr.common.comment.common.ui.moremenu.k"
        )
        val rec1 = PlayerPortTestRegistrar()
        val res1 = ReplyTopologyComposeMenu { _, _ -> }.install(env(groupedOnlyLoader, testRegistrar(rec1)))
        assertEquals(FeatureInstallResult.Installed(1, complete = true), res1)
        assertTrue("reply.topology.compose.grouped_menu" in rec1.hooks)

        // Case 2: Both Bubble and Grouped flattened outside direct scan list, recovered via DexMeguviz cached points.
        val flattenedLoader = hostLoader(
            entryClassName = "wmo1",
            bubbleClassName = "zBubbleFlattened",
            groupedClassName = "zGroupedFlattened",
            includeCommentModel = true
        )
        val bubbleRow = flattenedLoader.loadClass("zBubbleFlattened").declaredMethods.single(CommentComposeMenuAdapter::isBubbleRow)
        val groupedRow = flattenedLoader.loadClass("zGroupedFlattened").declaredMethods.single(CommentComposeMenuAdapter::isGroupedRow)
        val groupedItem = flattenedLoader.loadClass("zGroupedFlattened").declaredMethods.single(CommentComposeMenuAdapter::isGroupedItem)
        val session = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(bubbleRow, groupedRow, groupedItem)) },
            listOf("/base.apk"),
            flattenedLoader,
            setOf(DexAssistQuery.COMMENT_COMPOSE_MORE_MENU)
        )
        val points = CommentComposeMenuAdapter.locate(flattenedLoader, session, enabled = true, cached = null, planned = true)
        assertNotNull(points)
        assertTrue(CommentComposeMenuAdapter.resolve(flattenedLoader, points).isComplete())

        val rec2 = PlayerPortTestRegistrar()
        val res2 = ReplyTopologyComposeMenu(cachedPoints = points) { _, _ -> }.install(env(flattenedLoader, testRegistrar(rec2)))
        assertEquals(FeatureInstallResult.Installed(2, complete = true), res2)
        assertTrue("reply.topology.compose.menu" in rec2.hooks)
        assertTrue("reply.topology.compose.grouped_menu" in rec2.hooks)

        // Ambiguous grouped candidates fail closed while legacy cached point recovers drifted legacy menu.
        val ambigOtherLoader = hostLoader(
            entryClassName = "wmo1",
            bubbleClassName = null,
            groupedClassName = "zGroupedOther",
            legacyClassName = "zLegacyFlattened",
            includeCommentModel = true
        )
        val groupedRowOther = ambigOtherLoader.loadClass("zGroupedOther").declaredMethods.single(CommentComposeMenuAdapter::isGroupedRow)
        val ambigSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(groupedRow, groupedRowOther)) },
            listOf("/base.apk"),
            flattenedLoader,
            setOf(DexAssistQuery.COMMENT_COMPOSE_MORE_MENU)
        )
        assertNull(CommentComposeMenuAdapter.locate(flattenedLoader, ambigSession, enabled = true, cached = null, planned = true))

        val legacyMethod = ambigOtherLoader.loadClass("zLegacyFlattened").declaredMethods.single(CommentComposeMenuAdapter::isLegacyRow)
        val legacyOnlyLoader = hostLoader(
            entryClassName = "wmo1",
            bubbleClassName = null,
            groupedClassName = null,
            legacyClassName = "zLegacyFlattened",
            includeCommentModel = true
        )
        val legacySession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(legacyMethod)) },
            listOf("/base.apk"),
            legacyOnlyLoader,
            setOf(DexAssistQuery.COMMENT_COMPOSE_MORE_MENU)
        )
        val legacyPoints = CommentComposeMenuAdapter.locate(legacyOnlyLoader, legacySession, enabled = true, cached = null, planned = true)
        assertNotNull(legacyPoints?.legacyRow)
        assertTrue(CommentComposeMenuAdapter.resolve(legacyOnlyLoader, legacyPoints).isComplete())

        // AdaptResult round trips optional compose menu and kmp grpc wire points and tolerates absent keys.
        val loader = hostLoader(
            entryClassName = "wmo1",
            bubbleClassName = "kntr.common.comment.common.ui.moremenu.MoreMenuBubbleKt",
            groupedClassName = "kntr.common.comment.common.ui.moremenu.x"
        )
        val direct = CommentComposeMenuAdapter.direct(loader).toPoints()!!
        val wirePoint = VersionAdapter.HookPoint(
            "kntr.base.moss.ignet.impl.grpc.GrpcEngine",
            "a",
            listOf("a", "b", "[B", "c", "d")
        )
        val base = VersionAdapterTest().result()
        assertNull(base.commentComposeMenu)
        assertNull(base.kmpGrpcWireEngine)

        val updated = base.copy(commentComposeMenu = direct, kmpGrpcWireEngine = wirePoint)
        val json = updated.toJson()
        val parsed = VersionAdapter.AdaptResult.fromJson(json)
        assertNotNull(parsed)
        assertEquals(direct, parsed!!.commentComposeMenu)
        assertEquals(wirePoint, parsed.kmpGrpcWireEngine)

        json.remove("comment_compose_menu")
        json.remove("kmp_grpc_wire_engine")
        val legacyParsed = VersionAdapter.AdaptResult.fromJson(json)
        assertNotNull(legacyParsed)
        assertNull(legacyParsed!!.commentComposeMenu)
        assertNull(legacyParsed.kmpGrpcWireEngine)
    }
}
