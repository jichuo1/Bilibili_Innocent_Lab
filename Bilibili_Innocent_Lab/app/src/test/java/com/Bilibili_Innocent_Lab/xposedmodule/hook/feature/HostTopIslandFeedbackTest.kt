package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardGeometry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardSpacing
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardStyleSpec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSpringAxis
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopIslandFeedback
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopIslandGesture
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopIslandGesture.Decision
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopIslandMotionSpec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopIslandRefreshAccess
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTopIslandFeedbackTest {
    fun refreshStartsImmediatelyAndRunsForTheActualNativeRequest() {
        val feedback = HostTopIslandFeedback()
        feedback.clicked(HostTopIslandFeedback.Operation.REFRESH, 100L)
        assertTrue(feedback.refreshing)
        assertTrue(feedback.busy)
        assertTrue(feedback.scale < 1f)
        feedback.updateState(atTop = true, nativeRefreshing = true)
        assertTrue(feedback.needsFrame(10_000L))
        feedback.updateState(atTop = true, nativeRefreshing = false)
        repeat(90) { feedback.advance(1f / 60f) }
        assertFalse(feedback.refreshing)
        assertFalse(feedback.busy)
        assertFalse(feedback.needsFrame(10_100L))
    }

    fun returnToTopHasArrowFeedbackAndDoesNotStartLoading() {
        val feedback = HostTopIslandFeedback()
        feedback.clicked(HostTopIslandFeedback.Operation.TOP, 100L)
        feedback.updateState(atTop = false, nativeRefreshing = false)
        assertTrue(feedback.busy)
        assertFalse(feedback.refreshing)
        assertTrue(feedback.arrowOffset(260L) < -2f)
        feedback.updateState(atTop = true, nativeRefreshing = false)
        assertFalse(feedback.busy)
        assertTrue(feedback.showTopArrow(260L))
        assertFalse(feedback.showTopArrow(500L))
    }

    fun cancelledPressReturnsToNormalWithoutAnActionOrAnEndlessFrameLoop() {
        val feedback = HostTopIslandFeedback()
        feedback.setPressed(true)
        repeat(8) { feedback.advance(1f / 60f) }
        assertTrue(feedback.scale < .98f)
        feedback.setPressed(false)
        repeat(90) { feedback.advance(1f / 60f) }
        assertEquals(1f, feedback.scale, .001f)
        assertFalse(feedback.busy)
        assertFalse(feedback.needsFrame(1000L))
    }

    fun refreshStartedElsewhereAlsoShowsLoadingAndDetachClearsFeedback() {
        val feedback = HostTopIslandFeedback()
        feedback.updateState(atTop = true, nativeRefreshing = true)
        assertTrue(feedback.refreshing)
        feedback.reset()
        assertFalse(feedback.needsFrame(1000L))
        assertEquals(1f, feedback.scale, 0f)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostTopIslandFeedbackTest) -> Unit) {
            val s = HostTopIslandFeedbackTest()
            block(s)
        }
        runIsolated { s -> s.refreshStartsImmediatelyAndRunsForTheActualNativeRequest() }
        runIsolated { s -> s.returnToTopHasArrowFeedbackAndDoesNotStartLoading() }
        runIsolated { s -> s.cancelledPressReturnsToNormalWithoutAnActionOrAnEndlessFrameLoop() }
        runIsolated { s -> s.refreshStartedElsewhereAlsoShowsLoadingAndDetachClearsFeedback() }
    
        HostTopIslandGestureCases().verifyAllCases()
        HostTopIslandMotionSpecCases().verifyAllCases()
        HostTopIslandRefreshAccessCases().verifyAllCases()
        HostVideoCardStyleSpecCases().verifyAllCases()
        InnerVisibilityBookCases().verifyAllCases()
        ItemCollapseBookCases().verifyAllCases()
        KotlinDynamicMossWatchCases().verifyAllCases()
    }
}

private class HostTopIslandGestureCases {
    private fun gesture(x: Float, left: Boolean = false, right: Boolean = false, action: Boolean = false) =
        HostTopIslandGesture(x, 400f, 96f, 8f, 24f, action, left, right)

    fun centerAlwaysBelongsToTabsEvenAtTheBoundary() {
        assertEquals(Decision.NATIVE, gesture(200f).move(120f, 0f))
        assertEquals(Decision.NATIVE, gesture(200f).move(-120f, 0f))
    }

    fun inwardSwipesCollapseAtBothBoundaries() {
        assertEquals(Decision.COLLAPSE, gesture(20f, right = true).move(30f, 1f))
        assertEquals(Decision.COLLAPSE, gesture(350f, left = true).move(-30f, 1f))
    }

    fun inwardSwipesScrollTabsWhenThereIsRoomInThatDirection() {
        assertEquals(Decision.NATIVE, gesture(20f, left = true).move(30f, 0f, nativeMoved = true))
        assertEquals(Decision.NATIVE, gesture(350f, right = true).move(-30f, 0f, nativeMoved = true))
    }

    fun actionButtonCollapsesEvenWhenTabsCanScroll() {
        assertEquals(Decision.COLLAPSE, gesture(380f, right = true, action = true).move(-30f, 0f))
    }

    fun scrollGestureCannotBecomeCollapseWhenItReachesTheEndOrReverses() {
        val gesture = gesture(350f, right = true)
        assertEquals(Decision.NATIVE, gesture.move(-12f, 0f, nativeMoved = true))
        assertEquals(Decision.NATIVE, gesture.move(-180f, 0f))
        assertEquals(Decision.NATIVE, gesture.move(40f, 0f))
    }

    fun outwardAndVerticalSwipesStayNative() {
        assertEquals(Decision.NATIVE, gesture(20f).move(-30f, 0f))
        assertEquals(Decision.NATIVE, gesture(350f).move(30f, 0f))
        val vertical = gesture(20f)
        assertEquals(Decision.NATIVE, vertical.move(4f, 30f))
        assertEquals(Decision.NATIVE, vertical.move(60f, 30f))
    }

    fun tapsAndSmallJitterDoNotCollapse() {
        val gesture = gesture(380f, action = true)
        assertEquals(Decision.PENDING, gesture.move(-3f, 2f))
        assertEquals(Decision.PENDING, gesture.move(-15f, 2f))
        assertEquals(Decision.COLLAPSE, gesture.move(-30f, 2f))
    }

    fun multiTouchDisablesCollapseForTheRemainderOfTheGesture() {
        val gesture = gesture(20f)
        gesture.keepNative()
        assertEquals(Decision.NATIVE, gesture.move(100f, 0f))
    }

    fun scrollableButUnresponsiveEdgeDoesNotLeaveADeadGesture() {
        val gesture = gesture(350f, right = true)
        assertEquals(Decision.PENDING, gesture.move(-30f, 0f))
        assertEquals(Decision.COLLAPSE, gesture.move(-42f, 15f))
    }

    fun nativeScrollGetsItsFirstMoveToTakeOverBeforeFallback() {
        val gesture = gesture(350f, right = true)
        assertEquals(Decision.PENDING, gesture.move(-30f, 0f))
        assertEquals(Decision.NATIVE, gesture.move(-42f, 0f, nativeMoved = true))
    }

    fun expandedEdgeAndShortSlightlyDiagonalSwipeCollapse() {
        val gesture = HostTopIslandGesture(120f, 400f, 132f, 8f, 12f, false, false, false)
        assertEquals(Decision.COLLAPSE, gesture.move(14f, 9f))
    }

    fun unrelatedTabFlingDoesNotBlockTheFixedActionButton() {
        assertEquals(Decision.COLLAPSE, gesture(380f, action = true).move(-30f, 0f, nativeMoved = true))
    }

    fun earlyDiagonalWobbleCanStillBecomeAnInwardCollapse() {
        val gesture = gesture(350f)
        assertEquals(Decision.PENDING, gesture.move(-7f, 9f))
        assertEquals(Decision.COLLAPSE, gesture.move(-26f, 20f))
    }

    fun tinyOutwardStartDoesNotDiscardTheLaterInwardSwipe() {
        val gesture = gesture(350f)
        assertEquals(Decision.PENDING, gesture.move(10f, 2f))
        assertEquals(Decision.COLLAPSE, gesture.move(-28f, 3f))
    }

    fun shortSwipeWithOnlyOneMoveStillCollapsesOnRelease() {
        val gesture = gesture(350f, right = true)
        assertEquals(Decision.PENDING, gesture.move(-26f, 0f))
        assertEquals(Decision.COLLAPSE, gesture.move(-28f, 0f, finishing = true))
    }

    fun boundaryOverscrollDoesNotCountAsARealCategoryScroll() {
        assertEquals(Decision.COLLAPSE, gesture(350f).move(-28f, 1f, nativeMoved = true))
    }

    fun centerStaysNativeWithTheWiderEdgeArea() {
        val gesture = HostTopIslandGesture(200f, 400f, 168f, 8f, 8f, false, false, false)
        assertEquals(Decision.NATIVE, gesture.move(-12f, 2f))
    }

    fun nativeTabsCanCrossTheDeviceDragSlopBeforeStartingTheirFirstScroll() {
        val gesture = HostTopIslandGesture(850f, 1000f, 420f, 24f, 24f, false, true, true, 36f)
        assertEquals(Decision.PENDING, gesture.move(-14f, 0f))
        assertEquals(Decision.PENDING, gesture.move(-28f, 0f))
        assertEquals(Decision.NATIVE, gesture.move(-32f, 0f, nativeMoved = true))
        assertEquals(Decision.NATIVE, gesture.move(-180f, 0f))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostTopIslandGestureCases) -> Unit) {
            val s = HostTopIslandGestureCases()
            block(s)
        }
        runIsolated { s -> s.centerAlwaysBelongsToTabsEvenAtTheBoundary() }
        runIsolated { s -> s.inwardSwipesCollapseAtBothBoundaries() }
        runIsolated { s -> s.inwardSwipesScrollTabsWhenThereIsRoomInThatDirection() }
        runIsolated { s -> s.actionButtonCollapsesEvenWhenTabsCanScroll() }
        runIsolated { s -> s.scrollGestureCannotBecomeCollapseWhenItReachesTheEndOrReverses() }
        runIsolated { s -> s.outwardAndVerticalSwipesStayNative() }
        runIsolated { s -> s.tapsAndSmallJitterDoNotCollapse() }
        runIsolated { s -> s.multiTouchDisablesCollapseForTheRemainderOfTheGesture() }
        runIsolated { s -> s.scrollableButUnresponsiveEdgeDoesNotLeaveADeadGesture() }
        runIsolated { s -> s.nativeScrollGetsItsFirstMoveToTakeOverBeforeFallback() }
        runIsolated { s -> s.expandedEdgeAndShortSlightlyDiagonalSwipeCollapse() }
        runIsolated { s -> s.unrelatedTabFlingDoesNotBlockTheFixedActionButton() }
        runIsolated { s -> s.earlyDiagonalWobbleCanStillBecomeAnInwardCollapse() }
        runIsolated { s -> s.tinyOutwardStartDoesNotDiscardTheLaterInwardSwipe() }
        runIsolated { s -> s.shortSwipeWithOnlyOneMoveStillCollapsesOnRelease() }
        runIsolated { s -> s.boundaryOverscrollDoesNotCountAsARealCategoryScroll() }
        runIsolated { s -> s.centerStaysNativeWithTheWiderEdgeArea() }
        runIsolated { s -> s.nativeTabsCanCrossTheDeviceDragSlopBeforeStartingTheirFirstScroll() }
    }
}

private class HostTopIslandMotionSpecCases {
    private val width = 992f
    private val height = 121f
    private val density = 2.75f

    fun springStartsGentlyAndKeepsAVisibleDecelerationTail() {
        for (compact in listOf(false, true)) {
            val target = if (compact) 1f else 0f
            val spring = HostSpringAxis(1f - target)
            val damping = if (compact) HostTopIslandMotionSpec.DAMPING_RATIO else HostTopIslandMotionSpec.EXPAND_DAMPING_RATIO
            val stiffness = if (compact) HostTopIslandMotionSpec.COLLAPSE_STIFFNESS else HostTopIslandMotionSpec.EXPAND_STIFFNESS
            repeat(3) { spring.advance(1f / 60f, target, stiffness, damping) }
            assertTrue("前 50ms 不能突然走掉大半行程", abs(spring.value - (1f - target)) < .15f)
            var frames = 3
            while (!spring.atRest(target, .5f / (width - height)) && frames < 120) {
                spring.advance(1f / 60f, target, stiffness, damping)
                frames++
            }
            assertTrue("收尾必须保留减速时间，且不无限爬行: $frames", frames in 36..84)
            val before = HostTopIslandMotionSpec.shellWidth(spring.value, width, height, density)
            val end = HostTopIslandMotionSpec.shellWidth(target, width, height, density)
            assertTrue("静止落位不能产生像素跳变", abs(before - end) <= .5f)
        }
    }

    fun reboundCannotSqueezeTheBallFlatOrGrowBeyondTheSideMargins() {
        for (step in -20..220) {
            val progress = step / 200f
            val shell = HostTopIslandMotionSpec.shellWidth(progress, width, height, density)
            assertTrue(shell >= height * .93f)
            assertTrue(shell <= width + 12f * density)
            val vertical = HostTopIslandMotionSpec.verticalInset(progress, width, height, density)
            assertTrue(height - 2f * vertical > 0f)
            assertTrue(abs(vertical) < 2f * density)
        }
    }

    fun expansionHasAVisibleOvershootAndThenReturnsToItsFinalSize() {
        val spring = HostSpringAxis(1f)
        var maxStretch = 0f
        repeat(90) {
            spring.advance(1f / 60f, 0f, HostTopIslandMotionSpec.EXPAND_STIFFNESS,
                HostTopIslandMotionSpec.EXPAND_DAMPING_RATIO)
            maxStretch = maxOf(maxStretch, HostTopIslandMotionSpec.shellWidth(spring.value, width, height, density) - width)
        }
        assertTrue("展开要有肉眼可见的舒展回弹", maxStretch > 8f * density)
        assertEquals(width, HostTopIslandMotionSpec.shellWidth(spring.value, width, height, density), .5f)
    }

    fun nativeTextAndCompactGlyphHandOffWithoutOverlapping() {
        assertEquals(1f, HostTopIslandMotionSpec.contentAlpha(0f), 0f)
        assertEquals(0f, HostTopIslandMotionSpec.glyphAlpha(0f), 0f)
        assertEquals(0f, HostTopIslandMotionSpec.contentAlpha(1f), 0f)
        assertEquals(1f, HostTopIslandMotionSpec.glyphAlpha(1f), 0f)
        for (step in 0..100) {
            val progress = step / 100f
            assertEquals(0f, HostTopIslandMotionSpec.contentAlpha(progress) * HostTopIslandMotionSpec.glyphAlpha(progress), 0f)
        }
    }

    fun retargetingContinuesTheCurrentMomentumAndThenReturnsSmoothly() {
        val spring = HostSpringAxis()
        repeat(10) { spring.advance(1f / 60f, 1f, HostTopIslandMotionSpec.COLLAPSE_STIFFNESS, HostTopIslandMotionSpec.DAMPING_RATIO) }
        val position = spring.value
        val velocity = spring.velocity
        assertTrue(velocity > 0f)
        spring.advance(1f / 120f, 0f, HostTopIslandMotionSpec.EXPAND_STIFFNESS, HostTopIslandMotionSpec.EXPAND_DAMPING_RATIO)
        assertTrue("换目标不能立刻丢弃收缩惯性", spring.value > position)
        assertTrue(abs(spring.value - position) < .04f)
        repeat(168) { spring.advance(1f / 120f, 0f, HostTopIslandMotionSpec.EXPAND_STIFFNESS, HostTopIslandMotionSpec.EXPAND_DAMPING_RATIO) }
        assertEquals(0f, spring.value, .001f)
    }

    fun springTrajectoryIsTheSameAtSixtyAndOneHundredTwentyHertz() {
        val sixty = HostSpringAxis()
        val highRefresh = HostSpringAxis()
        repeat(30) { sixty.advance(1f / 60f, 1f, HostTopIslandMotionSpec.COLLAPSE_STIFFNESS, HostTopIslandMotionSpec.DAMPING_RATIO) }
        repeat(60) { highRefresh.advance(1f / 120f, 1f, HostTopIslandMotionSpec.COLLAPSE_STIFFNESS, HostTopIslandMotionSpec.DAMPING_RATIO) }
        assertEquals(sixty.value, highRefresh.value, .00001f)
        assertEquals(sixty.velocity, highRefresh.velocity, .00001f)
    }

    fun expansionCanReverseIntoCollapseBeforeItsSpringTailFinishes() {
        val spring = HostSpringAxis(1f)
        repeat(20) { spring.advance(1f / 60f, 0f, HostTopIslandMotionSpec.EXPAND_STIFFNESS,
            HostTopIslandMotionSpec.EXPAND_DAMPING_RATIO) }
        assertTrue(!spring.atRest(0f, .5f / (width - height)))
        val position = spring.value
        spring.advance(1f / 120f, 1f, HostTopIslandMotionSpec.COLLAPSE_STIFFNESS,
            HostTopIslandMotionSpec.DAMPING_RATIO)
        assertTrue("反向不能跳到另一端或清空动画", abs(spring.value - position) < .03f)
        repeat(90) { spring.advance(1f / 60f, 1f, HostTopIslandMotionSpec.COLLAPSE_STIFFNESS,
            HostTopIslandMotionSpec.DAMPING_RATIO) }
        assertEquals(1f, spring.value, .001f)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostTopIslandMotionSpecCases) -> Unit) {
            val s = HostTopIslandMotionSpecCases()
            block(s)
        }
        runIsolated { s -> s.springStartsGentlyAndKeepsAVisibleDecelerationTail() }
        runIsolated { s -> s.reboundCannotSqueezeTheBallFlatOrGrowBeyondTheSideMargins() }
        runIsolated { s -> s.expansionHasAVisibleOvershootAndThenReturnsToItsFinalSize() }
        runIsolated { s -> s.nativeTextAndCompactGlyphHandOffWithoutOverlapping() }
        runIsolated { s -> s.retargetingContinuesTheCurrentMomentumAndThenReturnsSmoothly() }
        runIsolated { s -> s.springTrajectoryIsTheSameAtSixtyAndOneHundredTwentyHertz() }
        runIsolated { s -> s.expansionCanReverseIntoCollapseBeforeItsSpringTailFinishes() }
    }
}

private class HostTopIslandRefreshAccessCases {
    open class NativeRefresh {
        var busy = false
        var notifications = 0
        fun isRefreshing() = busy
        @Suppress("UNUSED_PARAMETER") open fun setOnRefreshListener(listener: Runnable) = Unit
        @Suppress("unused") private fun m(refreshing: Boolean, notify: Boolean) {
            busy = refreshing
            if (notify) notifications++
        }
    }
    class HostSubclass : NativeRefresh() {
        override fun setOnRefreshListener(listener: Runnable) = Unit
    }
    class NamedRefresh {
        var busy = false
        var notified = false
        fun isRefreshing() = busy
        @Suppress("unused") private fun setRefreshing(refreshing: Boolean, notify: Boolean) {
            busy = refreshing
            notified = notify
        }
    }
    class AmbiguousRefresh {
        @Suppress("UNUSED_PARAMETER") fun setOnRefreshListener(listener: Runnable) = Unit
        @Suppress("UNUSED_PARAMETER", "unused") private fun a(x: Boolean, y: Boolean) = Unit
        @Suppress("UNUSED_PARAMETER", "unused") private fun b(x: Boolean, y: Boolean) = Unit
    }

    fun nativeNotificationPathWorksThroughAHostSubclassAndPreventsDuplicates() {
        val target = HostSubclass()
        assertTrue(HostTopIslandRefreshAccess.refresh(target))
        assertTrue(target.busy)
        assertEquals(1, target.notifications)
        assertFalse(HostTopIslandRefreshAccess.refresh(target))
        assertEquals(1, target.notifications)
        target.busy = false
        assertTrue(HostTopIslandRefreshAccess.refresh(target))
        assertEquals(2, target.notifications)
    }

    fun ambiguousObfuscatedMethodsAreNeverInvoked() {
        assertNull(HostTopIslandRefreshAccess.notifyMethod(AmbiguousRefresh::class.java))
    }

    fun standardAndroidxNamedEntryAlsoNotifiesTheNativeListener() {
        val target = NamedRefresh()
        assertTrue(HostTopIslandRefreshAccess.refresh(target))
        assertTrue(target.busy)
        assertTrue(target.notified)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostTopIslandRefreshAccessCases) -> Unit) {
            val s = HostTopIslandRefreshAccessCases()
            block(s)
        }
        runIsolated { s -> s.nativeNotificationPathWorksThroughAHostSubclassAndPreventsDuplicates() }
        runIsolated { s -> s.ambiguousObfuscatedMethodsAreNeverInvoked() }
        runIsolated { s -> s.standardAndroidxNamedEntryAlsoNotifiesTheNativeListener() }
    }
}

private class HostVideoCardStyleSpecCases {
    fun customRadiusUsesDensityAndFitsSmallCovers() {
        assertEquals(12f, HostVideoCardStyleSpec.coverRadius(200, 100), 0.001f)
        assertEquals(48f, HostVideoCardStyleSpec.cardRadius(200, 300, -1, 3f), 0f)
        assertEquals(0f, HostVideoCardStyleSpec.coverRadius(200, 100, 0, 3f), 0f)
        assertEquals(24f, HostVideoCardStyleSpec.coverRadius(200, 100, 8, 3f), 0f)
        assertEquals(50f, HostVideoCardStyleSpec.coverRadius(200, 100, 40, 3f), 0f)
        assertEquals(24f, HostVideoCardStyleSpec.cardRadius(200, 300, 8, 3f), 0f)
        assertEquals(-1, HostVideoCardStyleSpec.normalizeRadius(-2))
        assertEquals(-1, HostVideoCardStyleSpec.normalizeRadius(41))
    }

    fun recycledCardCanSwitchColumnsWithoutAccumulatingSpacing() {
        val spacing = HostVideoCardSpacing(3, 4, 5, 6, 2.75f)
        repeat(100) {
            assertEquals(41, spacing.left(0))
            assertEquals(24, spacing.right(0))
            assertEquals(22, spacing.left(1))
            assertEquals(43, spacing.right(1))
            assertEquals(17, spacing.top(1))
            assertEquals(39, spacing.bottom(1))
        }
        // 换成全宽/单列卡片后，宿主原始边距必须完整恢复。
        assertEquals(3, spacing.left(-1))
        assertEquals(4, spacing.top(-1))
        assertEquals(5, spacing.right(-1))
        assertEquals(6, spacing.bottom(-1))
    }

    fun scrollingAndRebindingSameSizeDoNotRebuildGeometry() {
        val geometry = HostVideoCardGeometry()
        assertTrue(geometry.update(462, 576, 76.12f))
        repeat(100) { assertFalse(geometry.update(462, 576, 76.12f)) }
        // 标题换行只改变高度，封面重测只改变顶部半径，二者都不能漏更新。
        assertTrue(geometry.update(462, 582, 76.12f))
        assertTrue(geometry.update(462, 582, 77f))
        assertFalse(geometry.update(462, 582, 77f))
    }

    fun fullWidthRowsKeepGuttersWithoutAccumulatingOrReducingHostSpacing() {
        val spacing = HostVideoCardSpacing(0, 0, 0, 0, 2f)
        repeat(100) {
            assertEquals(24, spacing.left(2))
            assertEquals(24, spacing.right(2))
            assertEquals(10, spacing.top(2))
            assertEquals(10, spacing.bottom(2))
        }
        val spacious = HostVideoCardSpacing(40, 30, 40, 30, 2f)
        assertEquals(40, spacious.left(2))
        assertEquals(30, spacious.top(2))
        assertEquals(40, spacious.right(2))
        assertEquals(30, spacious.bottom(2))
        assertEquals(0, spacing.left(-1))
        assertEquals(0, spacing.left(2, 24))
        assertEquals(12, spacing.right(2, 12))
        assertEquals(0, spacing.left(2, 40))
    }

    fun tabletMiddleColumnAppliesSymmetricInnerGuttersWithoutOverwritingOriginalInsets() {
        val spacing = HostVideoCardSpacing(3, 4, 5, 6, 2f)
        assertEquals(3 + 14, spacing.left(3))
        assertEquals(5 + 14, spacing.right(3))
        assertEquals(4 + 10, spacing.top(3))
        assertEquals(6 + 24, spacing.bottom(3))
        // Adjacent columns: col0 right + mid left == mid right + col1 left == 28px (2x inner).
        assertEquals(28, (spacing.right(0) - 5) + (spacing.left(3) - 3))
        assertEquals(28, (spacing.right(3) - 5) + (spacing.left(1) - 3))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostVideoCardStyleSpecCases) -> Unit) {
            val s = HostVideoCardStyleSpecCases()
            block(s)
        }
        runIsolated { s -> s.customRadiusUsesDensityAndFitsSmallCovers() }
        runIsolated { s -> s.recycledCardCanSwitchColumnsWithoutAccumulatingSpacing() }
        runIsolated { s -> s.scrollingAndRebindingSameSizeDoNotRebuildGeometry() }
        runIsolated { s -> s.fullWidthRowsKeepGuttersWithoutAccumulatingOrReducingHostSpacing() }
        runIsolated { s -> s.tabletMiddleColumnAppliesSymmetricInnerGuttersWithoutOverwritingOriginalInsets() }
    }
}

private class InnerVisibilityBookCases {

    private class FakeBox(override var visibility: Int) : VisibilityBox {
        override val key: Any get() = this
    }

    private val book = InnerVisibilityBook()

    fun `hiding a visible control and restoring brings it back visible`() {
        val box = FakeBox(VISIBLE)
        assertTrue(book.hide(box))
        assertEquals(GONE, box.visibility)
        assertTrue(book.tracks(box))
        assertTrue(book.restore(box))
        assertEquals(VISIBLE, box.visibility)
        assertFalse(book.tracks(box))
    }

    fun `hiding an invisible control restores it as invisible`() {
        val box = FakeBox(INVISIBLE)
        assertTrue(book.hide(box))
        assertEquals(GONE, box.visibility)
        assertTrue(book.restore(box))
        assertEquals(INVISIBLE, box.visibility)
    }

    fun `a control that is already gone is neither changed nor tracked`() {
        val box = FakeBox(GONE)
        assertFalse(book.hide(box))
        assertFalse(book.tracks(box))
        assertFalse(book.restore(box))
        assertEquals(GONE, box.visibility)
    }

    fun `hiding twice keeps the first original visibility`() {
        val box = FakeBox(INVISIBLE)
        assertTrue(book.hide(box))
        assertFalse(book.hide(box))
        assertTrue(book.restore(box))
        assertEquals(INVISIBLE, box.visibility)
    }

    fun `restore leaves a control the host already made visible and untracks it`() {
        val box = FakeBox(INVISIBLE)
        book.hide(box)
        box.visibility = VISIBLE
        assertTrue(book.restore(box))
        assertEquals(VISIBLE, box.visibility)
        assertFalse(book.tracks(box))
    }

    fun `tracked keys are a snapshot that survives restoring during iteration`() {
        val a = FakeBox(VISIBLE)
        val b = FakeBox(INVISIBLE)
        book.hide(a)
        book.hide(b)
        for (key in book.trackedKeys()) book.restore(key as FakeBox)
        assertEquals(emptyList<Any>(), book.trackedKeys())
        assertEquals(VISIBLE, a.visibility)
        assertEquals(INVISIBLE, b.visibility)
    }

    private companion object {
        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8
    }

    fun verifyAllCases() {
        fun runIsolated(block: (InnerVisibilityBookCases) -> Unit) {
            val s = InnerVisibilityBookCases()
            block(s)
        }
        runIsolated { s -> s.`hiding a visible control and restoring brings it back visible`() }
        runIsolated { s -> s.`hiding an invisible control restores it as invisible`() }
        runIsolated { s -> s.`a control that is already gone is neither changed nor tracked`() }
        runIsolated { s -> s.`hiding twice keeps the first original visibility`() }
        runIsolated { s -> s.`restore leaves a control the host already made visible and untracks it`() }
        runIsolated { s -> s.`tracked keys are a snapshot that survives restoring during iteration`() }
    }
}

private class ItemCollapseBookCases {

    /** 可直接断言的假 item：真 `View` 在单测里是会抛的桩（工程没有 Robolectric）。 */
    private class FakeBox(
        override var height: Int = 175,
        override var topMargin: Int = 12,
        override var bottomMargin: Int = 8,
        override var gone: Boolean = false
    ) : ItemBox {
        override val key: Any get() = this
        fun snapshot() = listOf(height, topMargin, bottomMargin, if (gone) 1 else 0)
    }

    private val book = ItemCollapseBook()

    fun `collapsing zeroes the height and the vertical margins as well as hiding`() {
        val box = FakeBox()
        assertTrue(book.collapse(box))
        assertEquals(listOf(0, 0, 0, 1), box.snapshot())
        assertTrue(book.tracks(box))
    }

    /** 折叠前的尺寸要原样回来，包括 `WRAP_CONTENT` 这种负数常量。 */
    fun `restoring brings back the exact original geometry`() {
        val wrapContent = -2
        val box = FakeBox(height = wrapContent, topMargin = 30, bottomMargin = 4)
        book.collapse(box)
        assertTrue(book.restore(box))
        assertEquals(listOf(wrapContent, 30, 4, 0), box.snapshot())
        assertFalse(book.tracks(box))
    }

    /**
     * 最危险的一条：重复折叠**不许**重新记账。
     *
     * 若第二次折叠把已经归零的值记成"原始尺寸"，还原就永远回不到 175，
     * 这一项会永久空白。
     */
    fun `collapsing twice reapplies collapsed geometry but keeps the first recorded geometry`() {
        val box = FakeBox()
        assertTrue(book.collapse(box))
        // RecyclerView 复用/绑定时宿主可能把 LayoutParams 写回原值；第二次命中要压平它们，
        // 但绝不能把这些回填后的值当作新的原始尺寸记账。
        box.height = 244
        box.topMargin = 17
        box.bottomMargin = 9
        // 复现现场：宿主已保留 GONE，只是把占位尺寸写回了非零值。
        box.gone = true
        assertFalse("第二次折叠不是一次新的隐藏", book.collapse(box))
        assertEquals(listOf(0, 0, 0, 1), box.snapshot())
        book.restore(box)
        assertEquals(listOf(175, 12, 8, 0), box.snapshot())
    }

    /** 回收复用 → 还原 → 又命中 → 再折叠，这条来回路径必须收敛。 */
    fun `a recycled box survives repeated collapse and restore cycles`() {
        val box = FakeBox()
        repeat(3) {
            assertTrue(book.collapse(box))
            assertEquals(listOf(0, 0, 0, 1), box.snapshot())
            assertTrue(book.restore(box))
            assertEquals(listOf(175, 12, 8, 0), box.snapshot())
        }
    }

    /** 没记录就什么都不动：绝大多数子项走这条路径，不能被误改。 */
    fun `restoring an untracked box changes nothing`() {
        val box = FakeBox()
        assertFalse(book.tracks(box))
        assertFalse(book.restore(box))
        assertEquals(listOf(175, 12, 8, 0), box.snapshot())
    }

    /** 宿主自己就把它藏了的话，还原时不能"顺手"帮它变可见。 */
    fun `restoring keeps a host hidden item hidden`() {
        val box = FakeBox(gone = true)
        book.collapse(box)
        book.restore(box)
        assertTrue("宿主的可见性不该被我们改成可见", box.gone)
        assertEquals(175, box.height)
    }

    /** 账本按身份记，不同 item 各记各的。 */
    fun `each box is tracked independently`() {
        val banner = FakeBox(height = 175)
        val other = FakeBox(height = 96)
        book.collapse(banner)
        assertTrue(book.tracks(banner))
        assertFalse(book.tracks(other))
        assertEquals(96, other.height)
        book.restore(banner)
        assertEquals(175, banner.height)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ItemCollapseBookCases) -> Unit) {
            val s = ItemCollapseBookCases()
            block(s)
        }
        runIsolated { s -> s.`collapsing zeroes the height and the vertical margins as well as hiding`() }
        runIsolated { s -> s.`restoring brings back the exact original geometry`() }
        runIsolated { s -> s.`collapsing twice reapplies collapsed geometry but keeps the first recorded geometry`() }
        runIsolated { s -> s.`a recycled box survives repeated collapse and restore cycles`() }
        runIsolated { s -> s.`restoring an untracked box changes nothing`() }
        runIsolated { s -> s.`restoring keeps a host hidden item hidden`() }
        runIsolated { s -> s.`each box is tracked independently`() }
    }
}

private class KotlinDynamicMossWatchCases {

    /** 形状模仿 9.14.0 `KDynamicMoss`：类型化入口（2 参）、泛型合成重载（5 参）、静态桥、其它接口。 */
    @Suppress("unused")
    private class FakeKotlinMoss {
        fun dynAll(request: String, continuation: Any): Any? = null
        fun dynAll(request: Any, a: Any, b: Any, c: Any, d: Any): Any? = null
        fun dynVideo(request: String, handler: Runnable) = Unit
        fun dynDetail(request: String, continuation: Any): Any? = null
        fun dynAllPersonal(request: String, continuation: Any): Any? = null

        companion object {
            @JvmStatic
            fun dynAll(request: String, continuation: String): Any? = null
        }
    }

    private fun watched(): Set<String> = FakeKotlinMoss::class.java.declaredMethods
        .filter(KotlinDynamicMossWatch::isWatchedEntry)
        .map { method -> method.name + method.parameterTypes.joinToString(",", "(", ")") { it.simpleName } }
        .toSet()

    fun `watches only the two-argument dynAll and dynVideo instance entries`() {
        assertEquals(setOf("dynAll(String,Object)", "dynVideo(String,Runnable)"), watched())
    }

    fun `does not watch generic overloads static bridges or unrelated moss calls`() {
        val names = FakeKotlinMoss::class.java.declaredMethods
            .filterNot(KotlinDynamicMossWatch::isWatchedEntry)
            .map { it.name }
            .toSet()
        // 5 参泛型重载、静态桥、dynDetail、dynAllPersonal 都必须落在"不观测"一侧。
        assertTrue(names.containsAll(setOf("dynAll", "dynDetail", "dynAllPersonal")))
        assertFalse(
            "static two-argument bridge must not be watched",
            FakeKotlinMoss::class.java.declaredMethods.any {
                KotlinDynamicMossWatch.isWatchedEntry(it) && java.lang.reflect.Modifier.isStatic(it.modifiers)
            }
        )
    }

    fun `class name is the unobfuscated host name`() {
        assertEquals("com.bapis.bilibili.app.dynamic.v2.KDynamicMoss", KotlinDynamicMossWatch.K_MOSS_CLASS)
    }

    fun `only entries the kotlin filter did not take are watched`() {
        val entries = FakeKotlinMoss::class.java.declaredMethods.toList()
        // 一个都没接上：两个入口都要观测。
        assertEquals(
            setOf("dynAll", "dynVideo"),
            KotlinDynamicMossWatch.selectUncovered(entries, emptySet()).map { it.name }.toSet()
        )
        // 只接上 dynAll：dynVideo 仍必须被观测，否则它不产生任何错误证据而状态照报 success。
        assertEquals(
            setOf("dynVideo"),
            KotlinDynamicMossWatch.selectUncovered(entries, setOf("dynAll")).map { it.name }.toSet()
        )
        // 全接上：没有要观测的入口。
        assertTrue(KotlinDynamicMossWatch.selectUncovered(entries, setOf("dynAll", "dynVideo")).isEmpty())
    }

    fun verifyAllCases() {
        fun runIsolated(block: (KotlinDynamicMossWatchCases) -> Unit) {
            val s = KotlinDynamicMossWatchCases()
            block(s)
        }
        runIsolated { s -> s.`watches only the two-argument dynAll and dynVideo instance entries`() }
        runIsolated { s -> s.`does not watch generic overloads static bridges or unrelated moss calls`() }
        runIsolated { s -> s.`class name is the unobfuscated host name`() }
        runIsolated { s -> s.`only entries the kotlin filter did not take are watched`() }
    }
}
