package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守住"会溢出自身边界的交互控件，其宿主容器必须放行绘制"这条约定。
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

    @Test
    fun scanFindsKnownOverdrawHostsAndWrapperDelegatesToScrubBar() {
        val names = overdrawHosts().map { it.first }
        assertTrue("没扫到任何宿主函数，护栏会静默通过：$names", names.size >= 2)
        assertTrue("logSettingsCard（LogSegmentScrubBar 的宿主）应在扫描范围内", "logSettingsCard" in names)
        assertTrue("install（LumenNavigationBar 的宿主）应在扫描范围内", "install" in names)
        val scrubWrapper = SourceContract.read("ui/meguviz/SettingsScope.kt")
            .after("fun LinearSettingsScope.segmentScrubRow(")
            .before("fun LinearSettingsScope.sliderRow(")
        assertTrue("segmentScrubRow 必须封装 logSegmentScrubBar", scrubWrapper.contains("logSegmentScrubBar("))
    }

    @Test
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
}
