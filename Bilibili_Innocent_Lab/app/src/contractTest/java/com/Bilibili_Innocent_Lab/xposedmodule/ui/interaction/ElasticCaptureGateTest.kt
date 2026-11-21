package com.Bilibili_Innocent_Lab.xposedmodule.ui.interaction

import com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before

/**
 * 长按拖动弹性手势的两条约定：
 * 1. 捕获门槛看**命中控件**的 pressed 状态——形变组可能是无点击语义的外层包裹
 *    （GitHub 图标的角标 FrameLayout），它永远不 pressed，拿它判会把长按拖动误杀；
 * 2. 角标之类的叠加控件不独占手势，整枚图标（含角标）作为一个形变组。
 */
class ElasticCaptureGateTest {

    fun captureGateChecksTheHitViewNotThePromotedGroup() {
        val controller = source(
            "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/interaction/ElasticInteractionController.kt")
        assertTrue(controller.contains("val pressedView = highlightHost ?: target"))
        assertTrue(controller.contains("if (pressedView?.isPressed != true)"))
        assertFalse(controller.contains("if (target?.isPressed != true)"))
    }

    fun hitViewIsAssignedAfterTheVisualTeardown() {
        val controller = source(
            "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/interaction/ElasticInteractionController.kt")
        val begin = controller.after("private fun begin(event: MotionEvent)")
            .before("private fun activatePreparedPress()")
        // removeVisual 会把 highlightHost 清空；命中控件必须在它之后赋值。
        // 提前赋值时每次新按压 highlightHost 都是 null，捕获门槛退回到形变组
        // （GitHub 图标是外层 FrameLayout，永不 pressed），长按拖动被静默清掉。
        assertTrue(begin.contains("highlightHost = view"))
        assertTrue(begin.indexOf("highlightHost = view") >
            begin.indexOf("removeVisual(restore = true, releaseLease = true)"))
    }

    fun newUpdateBadgeDoesNotStealTheIconGesture() {
        val main = source(
            "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/MainActivity.kt")
        val badge = main.after("githubUpdateBadge = this")
            .before("visibility = View.INVISIBLE")
        assertTrue(badge.contains("ElasticInteractionController.EXCLUDED_TAG"))
    }

    private fun source(relative: String): String =
        LceMotionSource.read(relative)

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (ElasticCaptureGateTest) -> Unit) {
            val s = ElasticCaptureGateTest()
            block(s)
        }
        runIsolated { s -> s.captureGateChecksTheHitViewNotThePromotedGroup() }
        runIsolated { s -> s.hitViewIsAssignedAfterTheVisualTeardown() }
        runIsolated { s -> s.newUpdateBadgeDoesNotStealTheIconGesture() }
    }
}
