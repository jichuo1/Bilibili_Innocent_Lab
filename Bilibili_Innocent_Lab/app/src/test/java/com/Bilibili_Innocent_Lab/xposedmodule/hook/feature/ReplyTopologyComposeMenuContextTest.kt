package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import androidx.compose.ui.Modifier
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyThreadKey
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import io.github.jichuo1.meguviz.hook.api.ErrorPolicy
import io.github.jichuo1.meguviz.hook.api.HookId
import io.github.jichuo1.meguviz.hook.core.HookEnvironment as MeguvizHookEnvironment
import io.github.jichuo1.meguviz.hook.testing.FakeBackend
import java.lang.reflect.Constructor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class ReplyTopologyComposeMenuContextTest {
    private class Fixture {
        val context = ReplyTopologyComposeMenuContext<Any, Any>()
        val anchor = Any()
        val sourceRoot = Any()
        val sourceWindow = Any()
        val seed = Any()
        val menu = Any()
        val menuRoot = Any()
        val menuWindow = Any()

        fun remember(sourceCurrent: () -> Boolean = { true }) {
            context.remember(anchor, sourceRoot, sourceWindow, seed, 100L, sourceCurrent)
        }
    }

    fun commentWithoutSeedInvalidatesPreviousMenuAndItsCallback() {
        val f = Fixture().apply { remember() }
        val previous = f.context.current()!!
        assertTrue(f.context.bindMenu(previous, f.menu, f.menuRoot, f.menuWindow))
        previous.legacyModifier = Any()
        f.context.remember(f.anchor, f.sourceRoot, f.sourceWindow, null, 200L)
        assertNull(f.context.current())
        assertFalse(f.context.acceptsSource(previous, f.sourceRoot, f.sourceWindow, 201L))
        assertFalse(f.context.bindMenu(previous, f.menu, f.menuRoot, f.menuWindow))
    }

    fun nextCommentOnSameWindowGetsFreshSeedAndBindings() {
        val f = Fixture().apply { remember() }
        val previous = f.context.current()!!
        previous.legacyModifier = Any()
        val nextSeed = Any()
        f.context.remember(f.anchor, f.sourceRoot, f.sourceWindow, nextSeed, 200L)
        val next = f.context.current()!!
        assertSame(nextSeed, next.seed)
        assertNull(next.legacyModifier)
        assertFalse(f.context.acceptsSource(previous, f.sourceRoot, f.sourceWindow, 201L))
        assertTrue(f.context.acceptsSource(next, f.sourceRoot, f.sourceWindow, 201L))
    }

    fun resetRejectsOldCallbacksEvenWhenTheirViewsRemainAlive() {
        val f = Fixture().apply { remember() }
        val previous = f.context.current()!!
        f.context.clear()
        assertFalse(f.context.acceptsSource(previous, f.sourceRoot, f.sourceWindow, 101L))
        assertFalse(f.context.bindMenu(previous, f.menu, f.menuRoot, f.menuWindow))
    }

    fun aDifferentMenuCannotReuseAnActiveFrame() {
        val f = Fixture().apply { remember() }
        val current = f.context.current()!!
        assertTrue(f.context.bindMenu(current, f.menu, f.menuRoot, f.menuWindow))
        val menuReference = current.menu
        val rootReference = current.menuRoot
        assertTrue(f.context.bindMenu(current, f.menu, f.menuRoot, f.menuWindow))
        assertSame(menuReference, current.menu)
        assertSame(rootReference, current.menuRoot)
        assertFalse(f.context.bindMenu(current, Any(), f.menuRoot, f.menuWindow))
        assertFalse(f.context.bindMenu(current, f.menu, Any(), f.menuWindow))
        assertFalse(f.context.bindMenu(current, f.menu, f.menuRoot, Any()))
    }

    fun popupMayComposeBeforeAttachButCannotChangeWindowAfterAttach() {
        val f = Fixture().apply { remember() }
        val current = f.context.current()!!
        assertTrue(f.context.bindMenu(current, f.menu, f.menuRoot, null))
        assertTrue(f.context.bindMenu(current, f.menu, f.menuRoot, f.menuWindow))
        assertFalse(f.context.bindMenu(current, f.menu, f.menuRoot, null))
        assertFalse(f.context.bindMenu(current, f.menu, f.menuRoot, Any()))
    }

    fun hostDismissalKeepsOnlyTheKnownMenusActionValidUntilTheNextMoreAction() {
        val f = Fixture().apply { remember() }
        val current = f.context.current()!!
        assertTrue(f.context.bindMenu(current, f.menu, f.menuRoot, f.menuWindow))
        // 宿主先让 Popup 退场，原 token 消失；动作仍只属于这一个已绑定菜单。
        assertTrue(f.context.ownsMenu(current, f.menu))
        assertFalse(f.context.ownsMenu(current, Any()))
        f.context.clear()
        assertFalse(f.context.ownsMenu(current, f.menu))
    }

    fun movingTheSourceToAnotherWindowInvalidatesItsFrame() {
        val f = Fixture().apply { remember() }
        val current = f.context.current()!!
        assertFalse(f.context.acceptsSource(current, f.sourceRoot, Any(), 101L))
        assertNull(f.context.current())
        f.remember()
        assertFalse(f.context.acceptsSource(f.context.current()!!, Any(), f.sourceWindow, 101L))
        assertNull(f.context.current())
    }

    fun aReboundCardCannotOpenThePreviousThread() {
        val f = Fixture()
        var currentCard = 100L
        f.remember { currentCard == 100L }
        val current = f.context.current()!!
        assertTrue(f.context.acceptsSource(current, f.sourceRoot, f.sourceWindow, 101L))
        currentCard = 200L
        assertFalse(f.context.acceptsSource(current, f.sourceRoot, f.sourceWindow, 102L))
        assertNull(f.context.current())
    }

    fun expiredOrCollectedSourcesCannotLeaveUsableCallbacks() {
        val f = Fixture().apply { remember() }
        assertTrue(f.context.acceptsSource(f.context.current()!!, f.sourceRoot, f.sourceWindow, 30_100L))
        assertFalse(f.context.acceptsSource(f.context.current()!!, f.sourceRoot, f.sourceWindow, 30_101L))
        assertNull(f.context.current())
        f.remember()
        val current = f.context.current()!!
        current.anchor.clear()
        assertFalse(f.context.acceptsSource(current, f.sourceRoot, f.sourceWindow, 101L))
        assertNull(f.context.current())
    }

    fun collectedMenuCannotBeReboundToAnUnrelatedMenu() {
        val f = Fixture().apply { remember() }
        val current = f.context.current()!!
        assertTrue(f.context.bindMenu(current, f.menu, f.menuRoot, f.menuWindow))
        current.menu.clear()
        assertFalse(f.context.bindMenu(current, Any(), f.menuRoot, f.menuWindow))
    }

    fun sourceVerificationFailureDisablesTheEntry() {
        val f = Fixture().apply { remember { error("binding unavailable") } }
        assertFalse(f.context.acceptsSource(f.context.current()!!, f.sourceRoot, f.sourceWindow, 101L))
        assertNull(f.context.current())
    }

    fun installerMissingSeedClearsAnAlreadyRememberedMenu() {
        // 只分配身份，不调用 Android View 的测试桩方法；覆盖审查中实际失败的安装器调用。
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        val anchor = unsafe.allocateInstance(View::class.java) as View
        val seed = ReplyTopologySeed(ReplyTopologyThreadKey(1L, 1L, 100L), 1, emptyList())
        val menu = ReplyTopologyComposeMenu { _, _ -> }
        @Suppress("UNCHECKED_CAST")
        val context = menu.javaClass.getDeclaredField("context").apply { isAccessible = true }.get(menu)
            as ReplyTopologyComposeMenuContext<View, ReplyTopologySeed>
        val sourceWindow = Any()
        context.remember(anchor, anchor, sourceWindow, seed, 100L)
        val installer = CommentTopologyFeatureInstaller(false, null, null, null)
        installer.javaClass.getDeclaredField("composeMenu").apply { isAccessible = true }.set(installer, menu)
        installer.rememberComposeMenu(anchor, 200L)
        assertNull(context.current())
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologyComposeMenuContextTest) -> Unit) {
            val s = ReplyTopologyComposeMenuContextTest()
            block(s)
        }
        runIsolated { s -> s.commentWithoutSeedInvalidatesPreviousMenuAndItsCallback() }
        runIsolated { s -> s.nextCommentOnSameWindowGetsFreshSeedAndBindings() }
        runIsolated { s -> s.resetRejectsOldCallbacksEvenWhenTheirViewsRemainAlive() }
        runIsolated { s -> s.aDifferentMenuCannotReuseAnActiveFrame() }
        runIsolated { s -> s.popupMayComposeBeforeAttachButCannotChangeWindowAfterAttach() }
        runIsolated { s -> s.hostDismissalKeepsOnlyTheKnownMenusActionValidUntilTheNextMoreAction() }
        runIsolated { s -> s.movingTheSourceToAnotherWindowInvalidatesItsFrame() }
        runIsolated { s -> s.aReboundCardCannotOpenThePreviousThread() }
        runIsolated { s -> s.expiredOrCollectedSourcesCannotLeaveUsableCallbacks() }
        runIsolated { s -> s.collectedMenuCannotBeReboundToAnUnrelatedMenu() }
        runIsolated { s -> s.sourceVerificationFailureDisablesTheEntry() }
        runIsolated { s -> s.installerMissingSeedClearsAnAlreadyRememberedMenu() }
    }

    @Test
    fun verifiesComposeEntryValidationInstallationRetryAndAnchorPlanning() {
        // Entry validation accepts wmo1 and legacy shapes and rejects invalid entries.
        val loader920 = composeHostClassLoader("wmo1")
        assertTrue(isValidComposeMenuEntryClass(loader920.loadClass("wmo1")))
        assertTrue(isValidComposeMenuEntryClass(FixtureLegacyEntry::class.java))
        assertFalse(isValidComposeMenuEntryClass(FixtureBadFirstFieldEntry::class.java))
        assertFalse(isValidComposeMenuEntryClass(FixtureBadLastFieldEntry::class.java))
        assertFalse(isValidComposeMenuEntryClass(FixtureMismatchedCtorEntry::class.java))
        assertFalse(isValidComposeMenuEntryClass(FixtureEnumEntry::class.java))
        assertFalse(isValidComposeMenuEntryClass(FixtureInterfaceEntry::class.java))
        assertFalse(isValidComposeMenuEntryClass(Int::class.java))
        assertFalse(isValidComposeMenuEntryClass(Array<String>::class.java))

        // ReplyTopologyComposeMenu installs across obfuscated entry names and skips ambiguous entries.
        for (entryName in listOf("cko1", "clo1", "wmo1", "gao1", "c9o1")) {
            val loader = composeHostClassLoader(entryName)
            RecordingRegistrar().use { registrar ->
                val env = HookEnvironment(
                    processName = "tv.danmaku.bili",
                    classLoader = loader,
                    hookPoints = HookPointRegistry(loader),
                    registrar = registrar,
                    logInfo = { _, _ -> },
                    logError = { _, _ -> },
                    reportStatus = { _, _ -> }
                )
                val result = ReplyTopologyComposeMenu { _, _ -> }.install(env)
                assertEquals("Entry class $entryName should install", FeatureInstallResult.Installed(1, complete = true), result)
                assertTrue("reply.topology.compose.menu" in registrar.installedIds)
            }
        }
        val ambiguousLoader = composeHostClassLoader("wmo1", FixtureAmbiguousMoreMenuBubble::class.java)
        RecordingRegistrar().use { registrar ->
            val env = HookEnvironment(
                processName = "tv.danmaku.bili",
                classLoader = ambiguousLoader,
                hookPoints = HookPointRegistry(ambiguousLoader),
                registrar = registrar,
                logInfo = { _, _ -> },
                logError = { _, _ -> },
                reportStatus = { _, _ -> }
            )
            val result = ReplyTopologyComposeMenu { _, _ -> }.install(env)
            assertEquals(FeatureInstallResult.Skipped("missing-compose-menu-entry"), result)
            assertTrue(registrar.installedIds.isEmpty())
        }

        // CommentTopologyFeatureInstaller retry flag activates once on missing points and stops after update.
        RecordingRegistrar().use { registrar ->
            val loader = requireNotNull(javaClass.classLoader)
            val env = HookEnvironment(
                processName = "tv.danmaku.bili",
                classLoader = loader,
                hookPoints = HookPointRegistry(loader),
                registrar = registrar,
                logInfo = { _, _ -> },
                logError = { _, _ -> },
                reportStatus = { _, _ -> }
            )
            val disabled = CommentTopologyFeatureInstaller(enabled = false, points = null, lowBindPoint = null, highBindPoint = null)
            assertEquals(FeatureInstallResult.Skipped("disabled"), disabled.install(env))
            assertFalse(disabled.requiresAdaptationRetry)

            val installer = CommentTopologyFeatureInstaller(enabled = true, points = null, lowBindPoint = null, highBindPoint = null)
            assertEquals(FeatureInstallResult.Skipped("missing-adapter-point"), installer.install(env))
            assertTrue(installer.requiresAdaptationRetry)

            installer.updateAdaptedPoints(points = null, lowBindPoint = null, highBindPoint = null)
            assertEquals(FeatureInstallResult.Skipped("missing-adapter-point"), installer.install(env))
            assertFalse(installer.requiresAdaptationRetry)
        }

        // planConstrainedAnchorInsertion supports zero or one neighbor and rejects conflicts or ambiguity.
        assertEquals(
            ConstrainedAnchorPlan(updateNeighbor = false, updateReference = false),
            planConstrainedAnchorInsertion(neighborCount = 0, referenceInverseConstraint = View.NO_ID, neighborViewId = null)
        )
        assertEquals(
            ConstrainedAnchorPlan(updateNeighbor = true, updateReference = false),
            planConstrainedAnchorInsertion(neighborCount = 1, referenceInverseConstraint = View.NO_ID, neighborViewId = 77)
        )
        assertEquals(
            ConstrainedAnchorPlan(updateNeighbor = true, updateReference = true),
            planConstrainedAnchorInsertion(neighborCount = 1, referenceInverseConstraint = 77, neighborViewId = 77)
        )
        assertTrue(
            runCatching {
                planConstrainedAnchorInsertion(neighborCount = 0, referenceInverseConstraint = 42, neighborViewId = null)
            }.exceptionOrNull()?.message?.contains("constraint-reference-conflict=42") == true
        )
        assertTrue(
            runCatching {
                planConstrainedAnchorInsertion(neighborCount = 1, referenceInverseConstraint = 88, neighborViewId = 77)
            }.exceptionOrNull()?.message?.contains("constraint-reference-conflict=88") == true
        )
        assertTrue(
            runCatching {
                planConstrainedAnchorInsertion(neighborCount = 2, referenceInverseConstraint = View.NO_ID, neighborViewId = 77)
            }.exceptionOrNull()?.message?.contains("constraint-neighbor-count=2") == true
        )
    }

    internal class FixtureCompositionLocal
    internal object FixtureAndroidLocals {
        private val localView = FixtureCompositionLocal()
        @JvmStatic
        fun getLocalView(): FixtureCompositionLocal = localView
    }
    internal interface FixtureComposer {
        fun consume(key: FixtureCompositionLocal): Any?
    }
    internal abstract class FixturePainter
    @Suppress("unused")
    internal data class FixtureWmo1WithDefaults(
        val a: String,
        val b: FixturePainter,
        val c: String = "",
        val d: FixturePainter,
        val e: () -> Unit
    ) {
        companion object {
            @JvmField
            val f: Int = 8
        }
    }
    @Suppress("unused")
    internal class FixtureLegacyEntry(val a: String, val b: () -> Unit)
    @Suppress("unused")
    internal class FixtureBadFirstFieldEntry(val a: Int, val b: () -> Unit)
    @Suppress("unused")
    internal class FixtureBadLastFieldEntry(val a: String, val b: Int)
    @Suppress("unused")
    internal class FixtureMismatchedCtorEntry {
        val a: String = ""
        val b: () -> Unit = {}
    }
    internal enum class FixtureEnumEntry { ONLY }
    internal interface FixtureInterfaceEntry
    internal object FixtureMoreMenuBubble {
        @JvmStatic @Suppress("UNUSED_PARAMETER")
        fun d(items: List<*>, onDismiss: (Any?) -> Unit, composer: FixtureComposer, changed: Int) = Unit
        @JvmStatic @Suppress("UNUSED_PARAMETER")
        fun b(entry: FixtureWmo1WithDefaults, onDismiss: () -> Unit, modifier: Modifier, composer: FixtureComposer, changed: Int) = Unit
    }
    internal object FixtureAmbiguousMoreMenuBubble {
        @JvmStatic @Suppress("UNUSED_PARAMETER")
        fun d(items: List<*>, onDismiss: (Any?) -> Unit, composer: FixtureComposer, changed: Int) = Unit
        @JvmStatic @Suppress("UNUSED_PARAMETER")
        fun b(entry: FixtureWmo1WithDefaults, onDismiss: () -> Unit, modifier: Modifier, composer: FixtureComposer, changed: Int) = Unit
        @JvmStatic @Suppress("UNUSED_PARAMETER")
        fun b(entry: FixtureLegacyEntry, onDismiss: () -> Unit, modifier: Modifier, composer: FixtureComposer, changed: Int) = Unit
    }

    private class RecordingRegistrar : HookRegistrar, AutoCloseable {
        val backend = FakeBackend.create()
        private val megEnv = MeguvizHookEnvironment.create(backend)
        val installedIds = linkedSetOf<String>()

        override fun first(id: String, className: String, methodName: String, block: ModernMemberHookCreator.() -> Unit) =
            error("Unexpected first() for $id")
        override fun all(id: String, className: String, methodName: String, block: ModernMemberHookCreator.() -> Unit) =
            error("Unexpected all() for $id")
        override fun exact(
            id: String,
            owner: Class<*>,
            methodName: String,
            vararg parameterTypes: Class<*>,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            val method = owner.getDeclaredMethod(methodName, *parameterTypes)
            val spec = ModernMemberHookCreator(method).apply(block).toSpec(HookId.of(id), ErrorPolicy.PROPAGATE)
            val receipt = megEnv.install(spec)
            assertTrue(receipt.message(), receipt.isInstalled)
            installedIds += id
        }
        override fun adapted(
            id: String,
            point: VersionAdapter.HookPoint,
            exceptionPolicy: HookExceptionPolicy,
            block: ModernMemberHookCreator.() -> Unit
        ) = error("Unexpected adapted() for $id")
        override fun constructor(id: String, constructor: Constructor<*>, block: ModernMemberHookCreator.() -> Unit) =
            error("Unexpected constructor() for $id")
        override fun close() = megEnv.close()
    }

    private fun composeHostClassLoader(
        entryClassName: String,
        bubbleTemplate: Class<*> = FixtureMoreMenuBubble::class.java
    ): ClassLoader {
        fun internalName(cls: Class<*>): String = cls.name.replace('.', '/')
        val templates = mapOf(
            "androidx.compose.ui.platform.AndroidCompositionLocals_androidKt" to FixtureAndroidLocals::class.java,
            "androidx.compose.runtime.CompositionLocal" to FixtureCompositionLocal::class.java,
            "androidx.compose.runtime.Composer" to FixtureComposer::class.java,
            "androidx.compose.ui.graphics.painter.Painter" to FixturePainter::class.java,
            "kntr.common.comment.common.ui.moremenu.MoreMenuBubbleKt" to bubbleTemplate,
            entryClassName to FixtureWmo1WithDefaults::class.java,
            "$entryClassName\$Companion" to FixtureWmo1WithDefaults.Companion::class.java,
            "zAltEntry" to FixtureLegacyEntry::class.java
        )
        val replacements = linkedMapOf(
            internalName(FixtureAndroidLocals::class.java) to "androidx/compose/ui/platform/AndroidCompositionLocals_androidKt",
            internalName(FixtureCompositionLocal::class.java) to "androidx/compose/runtime/CompositionLocal",
            internalName(FixtureComposer::class.java) to "androidx/compose/runtime/Composer",
            internalName(FixturePainter::class.java) to "androidx/compose/ui/graphics/painter/Painter",
            internalName(bubbleTemplate) to "kntr/common/comment/common/ui/moremenu/MoreMenuBubbleKt",
            internalName(FixtureWmo1WithDefaults::class.java) to entryClassName.replace('.', '/'),
            internalName(FixtureWmo1WithDefaults.Companion::class.java) to "${entryClassName.replace('.', '/')}\$Companion",
            internalName(FixtureLegacyEntry::class.java) to "zAltEntry"
        )
        return TestRenamingClassLoader(
            parent = requireNotNull(javaClass.classLoader),
            templates = templates,
            replacements = replacements
        )
    }
}
