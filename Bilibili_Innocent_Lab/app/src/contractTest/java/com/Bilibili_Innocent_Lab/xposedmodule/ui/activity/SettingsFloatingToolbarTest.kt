package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before

/**
 * 顶部工具栏悬浮层约定：三个按钮连同磨砂表面一起浮在滚动内容之上，
 * 内容上滚时从栏背后穿过被盖住，而不是在栏下沿被截断。
 */
class SettingsFloatingToolbarTest {
    private fun source(relative: String): String =
        SourceContract.read(relative)

    fun toolbarIsLiftedIntoThePageLayerAboveTheScrollingPages() {
        val home = source(
            "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/SettingsHomePresenter.kt")
        assertTrue(home.contains("floatingToolbar?.let { toolbar ->"))
        assertTrue(home.contains("toolbar.removeSelf()"))
        // 长胶囊：左右留白包住三枚按钮，29dp 圆角配 58dp 高度成正胶囊端头。
        assertTrue(home.contains("background = activity.skinFloatingBackground(activity.monetColors.surface, 29f)"))
        assertTrue(home.contains("pageLayer.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {"))
        // 2026-09-23：页面层铺到窗口顶端，胶囊外边距自己让开状态栏。
        assertTrue(home.contains("setMargins(dp(10), dp(4) + systemTopInset, dp(10), 0)"))
        // 栏高折算成滚动页上内缩：静止时内容从栏下沿开始，上滚时滑到栏背后。
        assertTrue(home.contains("headerInset = bottom - top + dp(8)"))
        assertTrue(home.contains("private fun applyScrollInsets()"))
        assertTrue(home.contains("val top = (if (headerInset > 0) headerInset else dp(8)) + systemTopInset"))
        // 搜索定位按"滚动页顶端到栏下沿"对齐，页面层从窗口顶端开始后必须含状态栏高度。
        assertTrue(home.contains("internal val currentHeaderInset: Int get() = headerInset + systemTopInset"))
    }

    /**
     * 状态栏任何时刻都透出内容（2026-09-23 用户要求）：原来只有长按弹性临时放开祖先裁剪时
     * 才透，常态被根布局的状态栏 padding + clipToPadding 裁在状态栏下沿。
     */
    fun statusBarAlwaysShowsContentScrollingBehindIt() {
        val home = source(
            "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/SettingsHomePresenter.kt")
        val install = home.after("private fun installTopInset()").before("private fun applyScrollInsets()")
        assertTrue(install.contains("activity.observeModuleInsets(shell)"))
        assertTrue(install.contains("WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()"))
        // 只接管顶部：左、右、下照旧缩进，底栏与导航栏行为不变。
        assertTrue(install.contains("shell.setPadding(safe.left, 0, safe.right, safe.bottom)"))
        assertTrue(install.contains("params.topMargin = dp(4) + systemTopInset"))
        assertTrue(install.contains("applyScrollInsets()"))
        assertTrue(install.contains("ViewCompat.requestApplyInsets(shell)"))
        assertTrue(home.contains("SettingsFavoritesRepository.read(activity.applicationContext, this)\n        installTopInset()"))
    }

    fun capsuleItselfIsAnElasticTargetWhileButtonsKeepTheirOwnGroups() {
        val home = source(
            "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/SettingsHomePresenter.kt")
        val header = home.after("val header = FrameLayout(activity).apply {")
            .before("pageLayer.addView(header")
        // 胶囊可点击才能消费 DOWN、成为弹性手势目标：长按拖动磨砂区域时
        // 整条胶囊（含三枚按钮）一起形变位移。
        assertTrue(header.contains("isClickable = true"))
        // 工具栏行必须保持透明：背景若加在行上，弹性提升会把三枚图标并成一个组。
        val main = source(
            "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/MainActivity.kt")
        val rowInit = main.after("settingsFloatingToolbar = this").before("}")
        assertFalse(rowInit.contains("background ="))
        assertFalse(rowInit.contains("skinFloatingBackground"))
        assertFalse(rowInit.contains("skinTopBarBackground"))
    }

    fun searchRevealStopsBelowTheFloatingHeader() {
        val reveal = source(
            "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/MainActivity.kt")
            .after("internal fun revealSettingsSearchTarget(")
            .before("internal fun launchSettingsBackup()")
        assertTrue(reveal.contains("home?.currentHeaderInset"))
        assertTrue(reveal.indexOf("val topOffset") < reveal.indexOf("val desiredY"))
    }

    fun toolbarRowStaysTransparentSoEachIconKeepsItsOwnElasticGroup() {
        val main = source(
            "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/MainActivity.kt")
        assertTrue(main.contains("floatingToolbar = settingsFloatingToolbar"))
        // 磨砂背景只加在 presenter 包的外壳上。工具栏行若自带背景，弹性手势会按
        // "有表面的控件"把三枚图标并成一个形变组，各自回弹随之失效。
        // 只取行的 init 块（到最近的 } 为止）：子图标各自的圆形背景不算。
        val rowInit = main.after("settingsFloatingToolbar = this").before("}")
        assertFalse(rowInit.contains("background ="))
        assertFalse(rowInit.contains("skinTopBarBackground"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (SettingsFloatingToolbarTest) -> Unit) {
            val s = SettingsFloatingToolbarTest()
            block(s)
        }
        runIsolated { s -> s.toolbarIsLiftedIntoThePageLayerAboveTheScrollingPages() }
        runIsolated { s -> s.statusBarAlwaysShowsContentScrollingBehindIt() }
        runIsolated { s -> s.capsuleItselfIsAnElasticTargetWhileButtonsKeepTheirOwnGroups() }
        runIsolated { s -> s.searchRevealStopsBelowTheFloatingHeader() }
        runIsolated { s -> s.toolbarRowStaysTransparentSoEachIconKeepsItsOwnElasticGroup() }
    }
}
