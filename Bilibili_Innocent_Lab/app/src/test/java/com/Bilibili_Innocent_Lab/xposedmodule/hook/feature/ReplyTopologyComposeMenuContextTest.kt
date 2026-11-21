package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyThreadKey
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
}
