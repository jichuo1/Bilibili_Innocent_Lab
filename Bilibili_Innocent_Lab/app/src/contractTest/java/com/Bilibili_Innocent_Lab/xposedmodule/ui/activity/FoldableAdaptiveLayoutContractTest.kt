package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 折叠屏、大屏与多窗口自适应架构护栏：确保清单、窗口委派、悬浮 Chrome 与弹窗容器不退化回写死手机假定。
 */
class FoldableAdaptiveLayoutContractTest {

    @Test
    fun manifestWindowDelegateAndHomeRailAdaptAcrossFoldablePostures() {
        val manifest = SourceContract.read("src/main/AndroidManifest.xml")
        assertFalse("Manifest 不得锁死屏幕方向，妨碍折叠屏与分屏旋转展开", manifest.contains("screenOrientation="))

        val delegate = SourceContract.read("ui/skin/activity/BilWindowDelegate.kt")
        assertTrue(
            "API 30+ 必须使用 LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS 让内容延伸至铰链/刘海安全区",
            delegate.contains("LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS")
        )

        val presenter = SourceContract.read("ui/activity/SettingsHomePresenter.kt")
        assertTrue(presenter.contains("AdaptiveNavigationRail("))
        assertTrue(presenter.contains("updateAdaptiveLayout("))
        assertTrue(presenter.contains("chrome.attach(rail, null"))
        assertTrue(presenter.contains("favoriteSpanCount"))
        val dispose = presenter.after("fun dispose() {").before("\n    }")
        assertTrue(dispose.contains("navigationRail?.dispose()"))
    }

    @Test
    fun modalsAndSecondaryActivitiesFollowAdaptiveLayoutWithoutRawDisplayMetrics() {
        val createModal = SettingsUiSource.function("createModalContainer")
        assertTrue(createModal.contains("BoundedModalContainer(this, adaptiveModalMaxWidthPx())"))

        val presentModal = SettingsUiSource.function("presentSizedModalDialog").before("val originalPaddingTop =")
        assertTrue(presentModal.contains("usableWindowSizePx()"))
        assertTrue(presentModal.contains("AdaptiveWindowLayout.bubbleMaxWidthPx("))

        for (fileName in listOf("DiagnosticsActivity.kt", "SettingsBackupActivity.kt", "FreeCopyActivity.kt")) {
            val code = SourceContract.read("ui/activity/$fileName")
            assertTrue("$fileName 应接入 AdaptiveWindowLayout 居中与断点策略", code.contains("AdaptiveWindowLayout."))
            assertTrue("$fileName 应在重建时保存滚动偏移", code.contains("override fun onSaveInstanceState("))
        }

        val offending = SourceContract.allMainKotlinSources()
            .filter { (rel, _) ->
                rel.startsWith("ui/activity/") &&
                    !rel.removePrefix("ui/activity/").contains('/') &&
                    rel != "ui/activity/AdaptiveWindowLayout.kt"
            }
            .filter { (_, content) -> content.contains(".widthPixels") || content.contains(".heightPixels") }
            .map { (rel, _) -> rel.substringAfterLast('/') }

        assertTrue(
            "ui/activity/ 不得直接使用 displayMetrics.widthPixels/heightPixels，应经由 AdaptiveWindowLayout/usableWindowSizePx/hostWindowSizePx: $offending",
            offending.isEmpty()
        )
    }
}
