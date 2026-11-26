package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before

/**
 * 守住"会溢出自身边界的交互控件，其宿主容器必须放行绘制"这条约定。
 *
 * 2026-09-20 真机实证：「日志详细度」滑块（LogSegmentScrubBar）长按拖动时右端/左端
 * 呈竖直齐切的"矩形断框"而非圆角。根因是宿主的 clipToPadding 默认 true 把子视图裁到
 * 内容盒，而滑块 widthMatchParent、边缘与内容盒精确重合——按压缩放（thumb.scaleX/Y，
 * 每侧约 4dp）与整条弹性位移（MAX_TRAVEL_DP = 4dp）的溢出全被齐平切断。控件自身
 * clipChildren = false 只放行"控件→子视图"这一层，管不了"容器→控件"这一层。
 *
 * 判据是**实例化签名**：任何 builder 函数里出现已知溢出控件的实例化，该函数
 * （即直接宿主容器的构造处）就必须含两个 flag。新增同类控件时把签名加进
 * [OVERDRAW_CONTROLS] 即自动纳入管辖。
 */
class InteractiveOverdrawClipConventionTest {

    private companion object {
        /** 已知会溢出自身边界的交互控件：实例化签名字符串。 */
        val OVERDRAW_CONTROLS = listOf("logSegmentScrubBar(", "segmentScrubRow(", "LumenNavigationBar(")
    }

    /** MainActivity 与全部外移分卷的 builder 函数（类成员 4 缩进 + 顶层扩展 0 缩进，去重）。 */
    private fun builderFunctions(): List<Pair<String, String>> {
        val files = listOf("MainActivity.kt") + SettingsUiSource.volumeFileNames
        return files.flatMap { name ->
            val text = SettingsUiSource.file(name)
            SettingsUiSource.declaredFunctions(text, 4) + SettingsUiSource.declaredFunctions(text, 0)
        }.distinct()
    }

    private fun overdrawHosts(): List<Pair<String, String>> =
        builderFunctions().filter { (_, body) -> OVERDRAW_CONTROLS.any { body.contains(it) } }

    fun theScanActuallySeesTheKnownHosts() {
        val names = overdrawHosts().map { it.first }
        assertTrue("没扫到任何宿主函数，护栏会静默通过：$names", names.size >= 2)
        assertTrue("logSettingsCard（LogSegmentScrubBar 的宿主）应在扫描范围内", "logSettingsCard" in names)
        assertTrue("install（LumenNavigationBar 的宿主）应在扫描范围内", "install" in names)
        val scrubWrapper = SourceContract.read("ui/meguviz/SettingsScope.kt")
            .after("fun LinearSettingsScope.segmentScrubRow(")
            .before("fun LinearSettingsScope.sliderRow(")
        assertTrue("segmentScrubRow 必须封装 logSegmentScrubBar", scrubWrapper.contains("logSegmentScrubBar("))
    }

    fun overdrawingControlsLiveInContainersWithBothClipFlagsOff() {
        val offenders = overdrawHosts()
            .filter { (_, body) ->
                !body.contains("clipChildren = false") || !body.contains("clipToPadding = false")
            }
            .map { it.first }
        assertEquals(
            "会溢出自身边界的交互控件（按压缩放 / 弹性位移 / 光晕）不能放在默认裁剪的容器里：" +
                "clipToPadding 默认 true 会把子视图裁到内容盒，控件边缘与内容盒齐平时圆角被竖直切断。" +
                "请在直接宿主容器的 init 里加 clipChildren = false 与 clipToPadding = false，" +
                "并确认溢出上界小于容器内边距（不渗出卡片）。",
            emptyList<String>(),
            offenders
        )
    }

    fun theControlsThemselvesLetTheirChildrenOverdraw() {
        listOf("widget/LumenSegmentScrubBar.kt", "widget/LumenNavigationBar.kt").forEach { enginePath ->
            assertTrue(
                "$enginePath 自身必须 clipChildren = false，否则滑块/光晕画不出控件边界",
                LceMotionSource.engine(enginePath).contains("clipChildren = false")
            )
        }
    }

    /**
     * 静态放行只覆盖"已知控件自己的宿主容器"；弹性长按拖动的形变组可以是任意
     * 卡片/图标/行（「兼容」卡、工具栏图标），它们沿途的祖先裁剪必须在拖动期间
     * 由控制器临时解除、收尾统一恢复——否则溢出的拉伸/位移仍被裁成矩形断边。
     */
    fun elasticDragRelievesAncestorClippingAtRuntime() {
        val controller = LceMotionSource.read("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/interaction/ElasticInteractionController.kt")
        // 解除发生在进入 DRAG 的那一刻（PRESS 只有按压缩小、不会溢出）。
        val dragEntry = controller.after("motion = Motion.DRAG")
        assertTrue(dragEntry.contains("relieveAncestorClipping()"))
        assertTrue(controller.contains("clipChildren = false"))
        assertTrue(controller.contains("clipToPadding = false"))
        // 恢复统一挂在 removeVisual：clear/UP/CANCEL/relinquish 全部汇聚于此。
        val removeVisual = controller.after("private fun removeVisual(")
            .before("private fun relinquish(")
        assertTrue(removeVisual.contains("restoreAncestorClipping()"))
        // 恢复体必须写回租约里保存的原值，而不是拍死成某个常量。
        val restore = controller.after("private fun restoreAncestorClipping()")
        assertTrue(restore.contains("relief.view.clipChildren = relief.clipChildren"))
        assertTrue(restore.contains("relief.view.clipToPadding = relief.clipToPadding"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (InteractiveOverdrawClipConventionTest) -> Unit) {
            val s = InteractiveOverdrawClipConventionTest()
            block(s)
        }
        runIsolated { s -> s.theScanActuallySeesTheKnownHosts() }
        runIsolated { s -> s.overdrawingControlsLiveInContainersWithBothClipFlagsOff() }
        runIsolated { s -> s.theControlsThemselvesLetTheirChildrenOverdraw() }
        runIsolated { s -> s.elasticDragRelievesAncestorClippingAtRuntime() }
    }
}
