package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue

/** Host-side wiring contracts for Lumen static-backdrop isolation and gesture handoff. */
class LiquidFeedbackIsolationTest {

    fun theSuppressionReleaseWaitsOutAnActiveGesture() {
        val renderer = LceMotionSource.engine("core/liquid/LiquidActivityRenderer.kt")
        val settle = renderer.substringAfter("private fun onScrollSettleCheck()", "MISSING")
            .before("private fun clearScrollSuppression()")
        assertNotEquals("MISSING", settle)
        assertTrue("按着时必须推迟解除", settle.contains("gestureHoldsRelease("))
        assertTrue("推迟后要继续排查，不能丢掉这次解除", settle.contains("scrollSettlePending = true"))
        val hold = renderer.after("private fun gestureHoldsRelease(")
            .before("private fun onScrollSettleCheck()")
        assertTrue("推迟必须有上界", hold.contains("GESTURE_RELEASE_HOLD_MS"))
        assertTrue(renderer.contains("private const val GESTURE_RELEASE_HOLD_MS"))

        // 手势起止由 SkinnedActivity 的 dispatchTouchEvent 委托到 LumenActivityDelegate 统一转发。
        val activity = SourceContract.read("ui/skin/activity/SkinnedActivity.kt")
        val dispatch = activity.after("override fun dispatchTouchEvent(")
            .before("protected fun clearElasticInteractions()")
        assertTrue(dispatch.contains("lumen.onDispatchTouchEvent(event)"))
        val delegate = LceMotionSource.engine("core/host/LumenActivityDelegate.kt")
        assertTrue(delegate.contains("MotionEvent.ACTION_DOWN -> session?.notifyGestureActive(true)"))
        assertTrue(delegate.contains("notifyGestureActive(false)"))
    }

    /**
     * 二级页（设置备份、统一诊断）从不发起实时截图（2026-09-24 用户报告：动画结束约 0.5s
     * 后控件光影跳变，正是采样源从稳定底图换成截图的时刻）。卡片背后只有背景，截图无收益。
     */
    fun staticBackdropHostsNeverStartRealtimeCapture() {
        val renderer = LceMotionSource.engine("core/liquid/LiquidActivityRenderer.kt")
        assertTrue(renderer.contains("private val staticBackdropHost = activity is LiquidStaticBackdropHost"))
        val post = renderer.after("private fun postRealtimeFrameCallback()").take(400)
        assertTrue(post.contains("staticBackdropHost"))
        val request = renderer.after("private fun requestRealtimeCapture(").take(400)
        assertTrue(request.contains("staticBackdropHost"))
        for (name in listOf("SettingsBackupActivity", "DiagnosticsActivity")) {
            val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/$name.kt"
            val activity = LceMotionSource.read(path)
            assertTrue(name, activity.contains("LiquidStaticBackdropHost {"))
        }
        // 主界面必须保留实时截图：悬浮栏与卡片背后有滚动内容。
        val mainPath = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/MainActivity.kt"
        val main = LceMotionSource.read(mainPath)
        assertFalse(main.contains("LiquidStaticBackdropHost"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (LiquidFeedbackIsolationTest) -> Unit) {
            val s = LiquidFeedbackIsolationTest()
            block(s)
        }
        runIsolated { s -> s.theSuppressionReleaseWaitsOutAnActiveGesture() }
        runIsolated { s -> s.staticBackdropHostsNeverStartRealtimeCapture() }
    }
}
