package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import java.io.File
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before

/**
 * 换上新 drawable 之后必须立刻把 View 当前状态推给它（2026-09-22 真机实证）。
 *
 * `SwitchCompat.setThumbDrawable/setTrackDrawable` 与 `CompoundButton.setButtonDrawable`
 * **都不会**把状态推给新 drawable，框架只在下一次 `drawableStateChanged()` 时推。冷启动时
 * 窗口获焦会补上那一次；而换皮肤走 `recreate()`——新视图在**已获焦**的窗口里挂载，
 * `state_window_focused` 不发生变化，那次刷新永远不来。现场：已开启的开关滑块在右边、
 * 配色却是未选中的灰（`LiquidChoiceDrawable` 收不到 `state_checked`，accent 整条不参与），
 * 用户读作"开关的取色设计失效了"。
 */
class ControlDrawableStateHandoffTest {

    private fun source(relative: String): String = sequenceOf(
        File("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/$relative"),
        File("app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/$relative")
    ).firstOrNull(File::isFile)?.readText() ?: error("cannot locate $relative")

    fun theSkinControlPassRefreshesStateAfterSwappingDrawables() {
        val activity = source("ui/skin/activity/SkinnedActivity.kt")
        val pass = activity.substringAfter("protected fun stylePreparedSkinControls(", "MISSING")
            .before("/** Shared modern controls")
        assertNotEquals("MISSING", pass)

        val switchBranch = pass.after("is SwitchCompat -> {").before("is CheckBox ->")
        assertTrue("开关换 drawable 后必须刷新状态", switchBranch.contains("refreshDrawableState()"))
        assertTrue(switchBranch.indexOf("thumbDrawable = choice(") <
            switchBranch.indexOf("refreshDrawableState()"))

        val checkBoxBranch = pass.after("is CheckBox -> {").before("is EditText ->")
        assertTrue("复选框换 drawable 后必须刷新状态", checkBoxBranch.contains("refreshDrawableState()"))
    }

    fun theSelfAppliedMonetStyleRefreshesStateToo() {
        val view = source("ui/view/MaterialSwitch.kt")
        val apply = view.substringAfter("private fun applyMonetStyle()", "MISSING")
            .before("    init {")
        assertNotEquals("MISSING", apply)
        assertTrue("弹窗里的开关自取 Monet 配色后同样要刷新状态",
            apply.contains("refreshDrawableState()"))
        assertTrue(apply.indexOf("trackDrawable = choice(") < apply.indexOf("refreshDrawableState()"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (ControlDrawableStateHandoffTest) -> Unit) {
            val s = ControlDrawableStateHandoffTest()
            block(s)
        }
        runIsolated { s -> s.theSkinControlPassRefreshesStateAfterSwappingDrawables() }
        runIsolated { s -> s.theSelfAppliedMonetStyleRefreshesStateToo() }
    }
}
