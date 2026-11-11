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
import com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after

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
        return SourceContract.read(path)
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
        // 2026-09-20：液态玻璃并入柔光美学，「界面美学」单选弹窗被「高级材质」开关取代，
        // 弹窗总数 34 → 33。
        // 2026-09-26：新增「获取 access_key」风险二次确认（BiliAccessKeyDialogs.kt），33 → 34；
        // 同日强力模式改为二级勾选面板（AiStrongModeDialogs.kt），34 → 35。
        // 2026-09-29：新增 JEV 语义判定配置面板（SemanticJevDialogs.kt），35 → 36；
        // 同日新增各过滤面「屏蔽类型」勾选面板（同文件 showSemanticRulesDialog），36 → 37。
        // 2026-09-30：多来源 / 判定来源 / 自定义类型的表单弹窗统一经 presentSemanticDialog 构建并展示，
        // 它取代了原来的 JEV 面板函数，统计仍是 37（新增的弹窗都走这一个受检函数）。
        // 同日新增 AI 语义判定各面板右上角 ⓘ 的说明子面板（showSemanticInfoDialog），37 → 38。
        // 2026-10-01：新增「竖屏页互动图标」勾选面板（StoryActionIconsDialogs.kt），38 → 39。
        // 2026-10-07：新增宿主视频卡片圆角弹窗，39 → 40。
        // 2026-10-08：宿主玻璃材质与高级渲染共用一个受检选择弹窗，40 → 41。
        // 2026-10-09：合入 Agent 任务与执行日志表单，复用同一皮肤容器与公共 presenter，41 → 43。
        // 2026-10-11：合入首页视频卡片间距弹窗，43 → 44。
        // 2026-10-12：新增视频缓冲策略实验面板，44 → 45，仍复用同一皮肤容器与 presenter。
        // 2026-10-15：卡片材质改为原版 / 亚克力 / 云母选择弹窗，45 → 46。
        // 2026-10-18：新增推荐视频发布时间过滤面板（PublishTimeFilterDialogs.kt），46 → 47。
        // 2026-10-19：新增倍速菜单档位编辑和与默认倍速互斥的二级提示，47 → 49。
        // 2026-10-24：新增插帧温控二次确认面板，49 → 50。
        // 2026-11-01：新增云控键扫描勾选面板与无扫描结果时的说明面板（CloudKeyScanDialogs.kt），50 → 52，
        // 两个都走同一个皮肤容器与 presentModalDialog。
        // 合入 NPatch 与 Shizuku 设置面板，52 → 54。
        // 2026-11-11：屏蔽 AI 评论与回复的判定敏感度弹窗，54 → 55。
        assertEquals(55, dialogs)
        // 「管理常用」改用定宽 presentSizedModalDialog（EXACTLY 行宽保证把手钉右缘）。
        assertTrue(SettingsUiSource.function("showSettingsFavoritesDialog").contains("presentSizedModalDialog(dialog, container, width, anchor)"))
        val presenter = SettingsUiSource.function("presentSizedModalDialog")
        assertTrue(presenter.indexOf("stylePreparedSkinControls(container)") in 0 until presenter.indexOf("dialog.show()"))
        // 面板与底页分离：窗口内必须有随动画进度淡入的压暗层（平台 dim 不可动画，
        // 会硬切在形变/气泡入场之前），且普通退场的淡出由 dismissWithAnimation 同步。
        // 2026-09-24：压暗层颜色随主题（浅色为背景色薄纱），统一经 modalScrimColor() 取。
        assertTrue("弹窗必须铺窗口内压暗层", presenter.contains("setBackgroundColor(modalScrimColor())"))
        assertTrue("scrim 要注册进 dialogScrims", presenter.contains("dialogScrims[dialog]"))
        assertTrue("scrim alpha 要跟动画进度", presenter.contains("scrim?.alpha"))
        assertTrue("dismissWithAnimation 要同步收 scrim",
            SettingsUiSource.function("dismissWithAnimation").contains("dialogScrims[dialog]"))
        val diagnostics = source("ui/activity/DiagnosticsActivity.kt")
        assertTrue(diagnostics.contains("background = skinModalBackground(monetColors.surface)"))
        assertTrue(diagnostics.contains("stylePreparedSkinControls(container)"))
    }

    @Test fun `control styling is gated and cannot change preferences or listeners`() {
        val skin = source("ui/skin/activity/SkinnedActivity.kt")
        val controls = skin.after("protected fun stylePreparedSkinControls").before("/** 让一个")
        assertTrue(controls.contains("if (skinSessionOrNull == null || lifecycleEnded) return"))
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

    @Test fun `control outline stays pinned to the viewport of scrolled inputs`() {
        val skin = source("ui/skin/activity/SkinnedActivity.kt")
        val outline = skin.after("private fun controlOutline(").before("/** 让一个")
        assertTrue(outline.contains("return viewportForeground(StateListDrawable()"))
        val wrapper = source("ui/skin/geometry/ViewportForegroundDrawable.kt")
        assertTrue(wrapper.contains("LumenViewportForeground.wrap(drawable)"))
        val engine = LceMotionSource.engine("core/host/ViewportForegroundDrawable.kt")
        val draw = engine.after("override fun draw(canvas: Canvas)").before("canvas.restoreToCount(saved)")
        assertTrue(draw.contains("view?.foreground === outer) view.scrollX"))
        assertTrue(draw.contains("view?.foreground === outer) view.scrollY"))
        assertTrue(draw.contains("canvas.translate(x.toFloat(), y.toFloat())"))
    }

    @Test fun `choice drawing caches geometry and has no animator or capture loop`() {
        val drawable = source("ui/skin/liquid/LiquidChoiceDrawable.kt")
        val draw = drawable.after("override fun draw(canvas: Canvas)").before("override fun setAlpha")
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
        // 日志卡片已外移到 SettingsCardSections.kt，扫整个设置页。
        val main = SettingsUiSource.settingsPage()
        assertTrue(main.contains("skinUpdateBadge(this)"))
        // 日志档位滑块（LogSegmentScrubBar）保持 primary 显式填充（logLevelThumbBg），
        // 且不再被 skinSelectionControl 的 surface 表面覆盖。
        assertTrue(main.contains("thumbBackground = logLevelThumbBg()"))
        assertFalse(main.contains("skinSelectionControl(this, 10f, selected = true)"))
        // 选中档位文字保持 monet onPrimary 显式接线（LogSegmentScrubBar 颜色表入参）。
        assertTrue(main.contains("selectedText = monetColors.onPrimary"))
        assertFalse(source("hook/HookEntry.kt").contains("LiquidChoiceDrawable"))
        assertFalse(source("ui/overlay/ReplyTopologyPanelView.kt").contains("LiquidChoiceDrawable"))
    }

    /** 2026-09-24 用户报告浅色下开关颜色较浅：浅色主题加深轨道，深色配比不变。 */
    @Test fun `light theme switches keep a contrasting track`() {
        val drawable = source("ui/skin/liquid/LiquidChoiceDrawable.kt")
        assertTrue(drawable.contains("private val lightTheme = ColorUtils.calculateLuminance(surface) > 0.5"))
        fun constant(name: String) = Regex("const val $name = ([0-9.]+)f?").find(drawable)!!.groupValues[1].toFloat()
        assertTrue(constant("CHECKED_TRACK_WASH_LIGHT") > constant("CHECKED_TRACK_WASH"))
        assertEquals(0.42f, constant("CHECKED_TRACK_WASH"), 0f)
        assertTrue(constant("UNCHECKED_TRACK_SHADE_LIGHT") > 0f)
        // 只给开关轨道铺灰；复选框没有滑块，铺灰只会得到深灰方块（2026-09-24 用户报告）。
        assertTrue(drawable.contains("lightTheme && !thumb && !checkbox -> ColorUtils.blendARGB(surface, outline, UNCHECKED_TRACK_SHADE_LIGHT)"))
        assertTrue(drawable.contains("lightTheme && !thumb && !(checkbox && !checked)"))
        assertTrue(constant("UNCHECKED_EDGE_ALPHA_LIGHT") > 46f)
    }
}
