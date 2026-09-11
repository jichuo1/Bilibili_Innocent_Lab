package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.SettingsUiSource
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidControlStyle
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidSurfaceAlphaPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidTokenResolver
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidVisualTuningPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Pure state tests plus source wiring guards; not an Android visual/interaction acceptance test. */
class LiquidControlStyleTest {
    @Test fun `all native state combinations preserve selection and disable interaction emphasis`() {
        for (enabled in listOf(false, true)) for (checked in listOf(false, true)) {
            for (pressed in listOf(false, true)) for (focused in listOf(false, true)) {
                val state = LiquidControlStyle.resolve(enabled, checked, pressed, focused)
                assertEquals(checked, state.selected)
                assertEquals(enabled && (pressed || focused), state.emphasized)
                assertEquals(if (enabled) 255 else 100, LiquidControlStyle.opacity(state))
                assertTrue(LiquidControlStyle.fillAlpha(state) in 1..254)
            }
        }
    }

    @Test fun `selected controls remain distinguishable without focus or press`() {
        val idle = LiquidControlStyle.resolve(true, false, false, false)
        val checked = LiquidControlStyle.resolve(true, true, false, false)
        val focused = LiquidControlStyle.resolve(true, false, false, true)
        assertTrue(LiquidControlStyle.fillAlpha(checked) > LiquidControlStyle.fillAlpha(idle))
        assertTrue(LiquidControlStyle.fillAlpha(focused) > LiquidControlStyle.fillAlpha(idle))
        assertTrue(LiquidControlStyle.opacity(idle) > LiquidControlStyle.opacity(idle.copy(enabled = false)))
    }

    @Test fun `new semantic surfaces retain fallback readability in both themes`() {
        for (dark in listOf(false, true)) {
            val params = LiquidTokenResolver.resolve(LiquidVisualTuningPolicy.resolve(dark))
            for (role in listOf(SurfaceRole.FILLED_BUTTON, SurfaceRole.TEXT_BUTTON, SurfaceRole.SELECTED_ITEM)) {
                val optical = LiquidSurfaceAlphaPolicy.resolve(role, false, params)
                val fallback = LiquidSurfaceAlphaPolicy.resolve(role, true, params)
                assertTrue(optical in 0f..1f)
                assertTrue(fallback in optical..1f)
            }
        }
    }

    private fun source(relative: String): String {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/$relative"
        return sequenceOf(File(path), File("app/$path")).first(File::isFile).readText()
    }

    @Test fun `all main activity modal creators use the skin container and shared presenter`() {
        // 弹窗正在按主题外移到 ui/activity 下的 Dialogs 文件，所以这条不变式必须**跟着代码走**。
        // 只扫 MainActivity 的话，搬走的弹窗会悄悄退出统计——而这里是唯一能发现
        // "某个弹窗漏了 createModalContainer() 或 presentModalDialog()"的地方。
        var dialogs = 0
        SettingsUiSource.settingsUiFiles().forEach { (file, text) ->
            // 类成员缩进 4，外移文件里的顶层扩展函数缩进 0。
            val indent = if (file == "MainActivity.kt") 4 else 0
            SettingsUiSource.declaredFunctions(text, indent).forEach { (name, body) ->
                if (!body.contains("val dialog = Dialog(this)")) return@forEach
                dialogs++
                assertTrue("$file: $name", body.contains("createModalContainer()"))
                if (name != "showUserTermsDialog") {
                    assertTrue("$file: $name", Regex("present(?:Sized)?ModalDialog\\(").containsMatchIn(body))
                }
            }
        }
        // 钉住总数而不是 >=：搬迁不允许让任何一个弹窗掉出统计。新增弹窗时一并改这里。
        assertEquals(29, dialogs)
        val presenter = SettingsUiSource.function("presentSizedModalDialog")
        assertTrue(presenter.indexOf("stylePreparedSkinControls(container)") in 0 until presenter.indexOf("dialog.show()"))
        val diagnostics = source("ui/activity/DiagnosticsActivity.kt")
        assertTrue(diagnostics.contains("background = skinModalBackground(monetColors.surface)"))
        assertTrue(diagnostics.contains("stylePreparedSkinControls(container)"))
    }

    @Test fun `control styling is gated and cannot change preferences or listeners`() {
        val skin = source("ui/skin/activity/SkinnedActivity.kt")
        val controls = skin.substringAfter("protected fun stylePreparedSkinControls").substringBefore("/** 让一个")
        assertTrue(controls.contains("if (!isLiquidSkinEffective || lifecycleEnded) return"))
        listOf("isChecked =", "setOnCheckedChangeListener", "setOnClickListener", "getSharedPreferences",
            "performClick(", "addOnGlobalLayoutListener", "PixelCopy", "RuntimeShader").forEach {
            assertFalse(it, controls.contains(it))
        }
        assertTrue(controls.contains("is SwitchCompat"))
        assertTrue(controls.contains("is CheckBox"))
        assertTrue(controls.contains("is EditText"))
        assertTrue(controls.contains("view.setPadding(left, top, right, bottom)"))
        assertTrue(controls.contains("view.foreground = RippleDrawable"))
    }

    @Test fun `choice drawing caches geometry and has no animator or capture loop`() {
        val drawable = source("ui/skin/liquid/LiquidChoiceDrawable.kt")
        val draw = drawable.substringAfter("override fun draw(canvas: Canvas)").substringBefore("override fun setAlpha")
        listOf("Path()", "RectF()", "LinearGradient(", "post", "invalidateSelf()").forEach {
            assertFalse(it, draw.contains(it))
        }
        assertFalse(drawable.contains("ValueAnimator"))
        assertTrue(drawable.contains("override fun isStateful() = true"))
        assertTrue(drawable.contains("if (checkbox && visualState.selected)"))
    }

    @Test fun `secondary pages and new badge keep explicit skin wiring`() {
        val backup = source("ui/activity/SettingsBackupActivity.kt")
        assertTrue(backup.contains("skinActionButton(this, filled = true, radiusDp = 14f)"))
        assertTrue(backup.contains("skinActionButton(this, filled = false, radiusDp = 14f)"))
        val main = source("ui/activity/MainActivity.kt")
        assertTrue(main.contains("skinUpdateBadge(this)"))
        assertTrue(main.contains("skinSelectionControl(this, 10f, selected = true)"))
        assertTrue(main.contains("val onPrimary = skinEmphasisTextColor"))
        assertFalse(source("hook/HookEntry.kt").contains("LiquidChoiceDrawable"))
        assertFalse(source("ui/overlay/ReplyTopologyPanelView.kt").contains("LiquidChoiceDrawable"))
    }
}
