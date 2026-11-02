package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before

class ModalMotionRefinementTest {
    @Test fun bubbleAxesHaveIndependentProfilesAndExactEndpoints() {
        for (entry in listOf(true, false)) {
            assertEquals(0f, BubbleMotionSpec.scaleX(0f, entry), 0f)
            assertEquals(0f, BubbleMotionSpec.scaleY(0f, entry), 0f)
            assertEquals(1f, BubbleMotionSpec.scaleX(1f, entry), 0f)
            assertEquals(1f, BubbleMotionSpec.scaleY(1f, entry), 0f)
            assertTrue(BubbleMotionSpec.scaleX(.5f, entry) > BubbleMotionSpec.scaleY(.5f, entry))
        }
    }

    @Test fun entryHasNoOvershootAndSettlesPrecisely() {
        var maxX = 0f
        var maxY = 0f
        for (step in 0..1000) {
            val x = BubbleMotionSpec.scaleX(step / 1000f, true)
            val y = BubbleMotionSpec.scaleY(step / 1000f, true)
            assertTrue(x in 0f..1f)
            assertTrue(y in 0f..1f)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
        }
        assertEquals(1f, maxX, 0f)
        assertEquals(1f, maxY, 0f)
        assertTrue(BubbleMotionSpec.ENTER_DURATION_MS in 240L..250L)
    }

    @Test fun settledCloseShrinksBothAxesWithoutRebound() {
        var lastX = 1f
        var lastY = 1f
        for (step in 1000 downTo 0) {
            val x = BubbleMotionSpec.scaleX(step / 1000f, false)
            val y = BubbleMotionSpec.scaleY(step / 1000f, false)
            assertTrue(x <= lastX && y <= lastY)
            assertTrue(x in 0f..1f && y in 0f..1f)
            lastX = x
            lastY = y
        }
    }

    @Test fun axisProfilesRemainContinuousAcrossGrowthAndReboundBoundaries() {
        for (entry in listOf(true, false)) {
            for (boundary in listOf(.60f, .70f, .78f, .86f, 1f)) {
                assertTrue(kotlin.math.abs(BubbleMotionSpec.scaleX(boundary - .00001f, entry) -
                    BubbleMotionSpec.scaleX(boundary + .00001f, entry)) < .0001f)
                assertTrue(kotlin.math.abs(BubbleMotionSpec.scaleY(boundary - .00001f, entry) -
                    BubbleMotionSpec.scaleY(boundary + .00001f, entry)) < .0001f)
            }
        }
    }

    @Test fun matchingTitlesCanMoveButRelatedDifferentTitlesCannot() {
        assertTrue(ModalTitleMotionSpec.matches("首页推荐过滤", "首页推荐过滤"))
        assertFalse(ModalTitleMotionSpec.matches(" Portrait content filters ", "Portrait content filters"))
        assertFalse(ModalTitleMotionSpec.matches("自定义首页组件", "隐藏首页组件规则"))
        assertFalse(ModalTitleMotionSpec.matches("首页推荐过滤\n已选择 2 项", "首页推荐过滤"))
        assertFalse(ModalTitleMotionSpec.matches("首页推荐过滤", "首页推荐过滤？"))
        assertFalse(ModalTitleMotionSpec.matches("", ""))
    }

    @Test fun titleBaselineAndSizeInterpolationHasExactClampedEndpoints() {
        assertEquals(24f, ModalTitleMotionSpec.interpolate(24f, 160f, 0f), 0f)
        assertEquals(160f, ModalTitleMotionSpec.interpolate(24f, 160f, 1f), 0f)
        assertEquals(92f, ModalTitleMotionSpec.interpolate(24f, 160f, .5f), 0f)
        assertEquals(24f, ModalTitleMotionSpec.interpolate(24f, 160f, -1f), 0f)
        assertEquals(160f, ModalTitleMotionSpec.interpolate(24f, 160f, 2f), 0f)
    }

    @Test fun carrierSurfaceFollowsTheAnimatedCardRectInsteadOfTheWindow() {
        // 形变期间可见表面是承载层的 background（全屏 View bounds）。若 drawable 按
        // 全屏矩形绘制，模态描边与顶沿高光会绕窗口计算再被 outline 裁掉——"通透
        // 光泽"要等动画播完、交还卡片自身 drawable 才出现（真机实测 pf 帧对比）。
        // 修复是两通道同步：Liquid 经 LiquidMotionSurfaceFrameProvider 读形变
        // 边界，Material 的 Drawable 读 bounds。
        val layer = source("IconAnchoredMotionLayer")
        assertTrue(layer.contains("LiquidMotionSurfaceFrameProvider"))
        assertTrue(layer.contains("override fun copyLiquidMotionBounds"))
        assertTrue(layer.contains("override fun liquidMotionCornerRadiusPx"))
        assertTrue(layer.contains("override fun liquidMotionFallbackColor"))
        val applyFrame = layer.after("fun applyFrame(")
            .before("fun clearShape(")
        assertTrue(applyFrame.contains("background?.setBounds("))
        // 收起形变后必须复位：残留卡片矩形会让"下次常驻表面"按旧边界画。
        val clearShape = layer.after("fun clearShape(")
            .before("private fun updateRestingSurface(")
        assertTrue(clearShape.contains("background?.setBounds(0, 0, width, height)"))
        // 未成形时 provider 必须回报空矩形，否则常驻态会拿着空 bounds 走运动分支。
        val provider = layer.after("override fun copyLiquidMotionBounds")
            .before("override fun liquidMotionCornerRadiusPx")
        assertTrue(provider.contains("shaped"))
        assertTrue(provider.contains("setEmpty()"))
    }

    @Test fun carrierActiveKeepsCardOwnBackgroundOutOfTheFrame() {
        // 模态表面是半透明玻璃后，承载层 drawable 与卡片自身背景两张同色同矩形
        // 叠画会让填充越叠越实、描边越叠越亮，落定摘层时通透度跳回来。承载层
        // 在场期间 contentBackground 必须归 0，只在承载层缺席的兜底路径上才按
        // strokeAlpha 渐出。
        val controller = source("IconAnchoredMotionController")
        val apply = controller.after("private fun apply(")
            .before("private fun finish(")
        assertTrue(apply.contains("!layer.usesPersistentSurface && layer.background == null"))
        val prep = controller.after("fun prepareFirstFrame(")
            .before("fun startEntry(")
        assertTrue(prep.contains("contentBackground?.alpha = 0"))
        val exit = controller.after("private fun prepareExitFrame(")
            .before("private fun animateTo(")
        assertTrue(exit.contains("contentBackground?.alpha = 0"))
        // 稳定端只有非持久路径交还卡片；持久表面不能叠上第二张玻璃。
        val settle = controller.after("private fun settleExpanded()").before("fun beginPredictiveBack(")
        assertTrue(settle.contains("contentBackground?.alpha = if (layer.usesPersistentSurface) 0 else 255"))
    }

    @Test fun expansionTargetTracksTheLiveCardRectEveryFrame() {
        // 几何在形变开始前解析一次，之后卡片仍可能被重排版（insets 落定/标题交接），
        // 陈旧的 expandedBounds 会让承载层最后一帧与卡片错位 ~1px——交接瞬间整圈
        // 描边与光学采样区平移一档（"落定瞬间边缘光跳变"）。apply() 必须用卡片
        // 当前 left/top/right/bottom 重建展开端目标。
        val controller = source("IconAnchoredMotionController")
        val apply = controller.after("private fun apply(")
            .substringBefore("private fun finish(", "MISSING")
        assertTrue(apply != "MISSING")
        assertTrue(apply.contains("content.left.toFloat()"))
        assertTrue(apply.contains("expandedBounds = liveExpanded"))
    }

    /**
     * 覆盖式子面板淡出的必须是父面板的**卡片层**，不是整张 decorView（2026-09-22 真机实证）。
     *
     * 父面板的压暗层就在 decorView 里，跟着淡到 0 就等于背景压暗消失；而子面板按
     * "父面板那层还在"的前提**故意不加自己的 scrim**，两条假设一撞，开子面板时整屏变亮
     * （实测面板外背景 BGR 25.7/29.3/26.1 → 42.0/48.0/42.7，底页文字透出）。
     */
    @Test fun coveringASubPanelKeepsTheParentScrimAlive() {
        val present = SettingsUiSource.function("presentSizedModalDialog")
        assertTrue("必须从 dialogScrims 认出父面板的压暗层", present.contains("dialogScrims[parent]"))
        assertTrue("淡出目标必须是非 scrim 的那个卡片层",
            present.contains("firstOrNull { it !== parentScrim }"))
        val coveredIndex = present.indexOf("val coveredContent")
        val decorIndex = present.indexOf("parent.window?.decorView", coveredIndex)
        assertTrue("decorView 只能作为拿不到卡片层时的兜底", coveredIndex in 0 until decorIndex)
    }

    private fun source(name: String): String {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/$name.kt"
        return LceMotionSource.read(path)
    }

    @Test fun missingSnapshotUsesSameAnchoredRuleEditorWithoutChangingSelectionRules() {
        // 这两个窗口原来靠"到下一个函数为止"划界，已经被搬迁悄悄撑破过一次：
        // showRecommendVideoDurationRangeDialog 外移后分隔符失配，substringBefore
        // 返回整段剩余源码，断言变成在半份文件里找字符串——照样通过，护栏没了。
        val fallback = SettingsUiSource.function("showComponentManualRuleEditor")
        assertTrue(fallback.contains("spec.summaryView()"))
        assertTrue(fallback.contains("spec.currentRules(), anchor"))
        val editor = SettingsUiSource.function("showRuleEditorDialog")
        assertTrue(editor.contains("presentModalDialog(dialog, container, anchor)"))
        assertFalse(fallback.contains("remove("))
        assertFalse(fallback.contains("clear("))
    }

    @Test fun titleOverlayIsOptionalRestoredAndNeverReflowsPerFrame() {
        val title = source("ModalTitleMotion")
        // 目标标题必须独占一行；来源允许是"标题 \n 摘要"的合成 TextView，
        // 由渲染后的首行复核（见 ModalTitleHandoffTest 的首行配对用例）。
        assertTrue(title.contains("targetLayout.lineCount != 1"))
        assertTrue(title.contains("renderedTitleLine("))
        assertTrue(title.contains("getEllipsisCount(0) != 0"))
        // 来源行只淡文字颜色，**不能**动 View 的 alpha：那会把 ripple 一起变透明并冻结它的
        // 动画，等形变结束才补播一次高光（见 ModalTitleHandoffTest 的 ripple 用例）。
        assertFalse(title.contains("source.alpha ="))
        assertTrue(title.contains("sourceTextColors.withAlpha("))
        assertTrue(title.contains("val restored = sourceColors.release(source, sourceOwner)"))
        assertTrue(title.contains("if (restored != null) source.setTextColor(restored)"))
        assertTrue(title.contains("sourceColors.weight(source)"))
        assertTrue(title.contains("target.alpha = targetAlpha"))
        val draw = title.after("override fun onDraw(").before("companion object")
        for (forbidden in listOf("requestLayout", "Bitmap", "find(", "TextPaint(", "textSize =")) {
            assertFalse(forbidden, draw.contains(forbidden))
        }
        val controller = source("IconAnchoredMotionController")
        assertTrue(controller.contains("titleMotion?.prepare(0f)"))
        assertTrue(controller.contains("titleMotion?.prepare(expansion)"))
        assertTrue(controller.contains("titleMotion?.apply(clamped)"))
        assertTrue(controller.contains("titleMotion?.expanded()"))
        assertTrue(controller.contains("title.finishAfterSourceDraw(onClosed)"))
        assertTrue(controller.contains("titleMotion?.dispose()"))
        assertEquals(1, Regex("InterruptibleMotionPolicy.remainingDuration\\(").findAll(controller).count())
    }

    @Test fun theMotionLayerOwnsTheShadowAcrossMorphAndRest() {
        // 2026-09-22 逐帧实测：落定瞬间卡片外 12px 环带暗 ~2 档——12dp elevation
        // 阴影在形变期被裁掉、落定一帧弹出。最终方案：阴影归承载层（构造期
        // elevation 常量），outline 形变期=形变矩形、落定后=卡片矩形，阴影全程
        // 连续。表面 View 绝不能带 elevation——ViewGroup 按 Z 排序绘制，Z>0 的
        // 表面会排到卡片之后，半透明玻璃盖住正文（实测行文字 211→66）。
        val layer = source("IconAnchoredMotionLayer")
        assertTrue(layer.contains("surfaceElevation: Float = 0f"))
        assertTrue(layer.contains("elevation = surfaceElevation"))
        // outline 三分支：形变矩形（alpha 1）→ 落定卡片矩形（alpha 1）→ 无（alpha 0）。
        val provider = layer.after("outlineProvider =")
            .before("fun applyFrame(")
        assertEquals(2, Regex("outline\\.alpha = 1f").findAll(provider).count())
        assertTrue(provider.contains("surfaceRadiusPx"))
        // 持久分支逐帧刷新投影轮廓；落定矩形回写时也刷新。
        val applyFrame = layer.after("fun applyFrame(").before("fun clearShape(")
        assertTrue(applyFrame.contains("invalidateOutline()"))
        assertTrue(layer.after("private fun updateRestingSurface(").contains("invalidateOutline()"))
        // 飞行标题浮层必须高于承载层（否则形变期被面板盖住），且自身空 outline 不投影。
        val present = SettingsUiSource.function("presentSizedModalDialog")
        assertTrue(present.contains("title.elevation = morphLayer.elevation + 1f"))
        val title = source("ModalTitleMotion")
        assertTrue(title.contains("outline.alpha = 0f"))
        // 普通面板移交卡片 elevation；覆盖式面板（cover != null）不新增阴影。
        assertTrue(present.contains("surfaceElevation = if (cover == null) container.elevation else 0f"))
        // 控制器不再逐帧搬移 elevation（常量由层构造期持有）。
        val controller = source("IconAnchoredMotionController")
        assertFalse(controller.contains("layer.elevation ="))
    }

    @Test fun theCoveredParentFadesOutLateSoTwoStrokesNeverStackAtTheEnd() {
        // 两张卡片矩形完全重合时各画一条半透明描边，叠加后比单独任何一张都亮：
        // 真机实测同一条左边缘，父面板独自稳定 87，子面板落位后 103，且这一跳在最后一帧。
        assertEquals(0f, IconAnchoredMotionSpec.coveredParentAlpha(1f), 0f)
        assertEquals(1f, IconAnchoredMotionSpec.coveredParentAlpha(0f), 0f)
        // 起点必须够晚：早了父面板先淡没、子面板没长满，外轮廓先回缩再展开（2026-09-24
        // 用户实证）。重合区叠亮由 ModalCardRoot 挖空解决，透明度只管终点共边描边。
        assertTrue(IconAnchoredMotionSpec.COVERED_PARENT_FADE_START >= 0.99f)
        assertEquals(1f, IconAnchoredMotionSpec.coveredParentAlpha(
            IconAnchoredMotionSpec.COVERED_PARENT_FADE_START), 0f)
        // 单调不回头，否则父面板会在末段闪一下。
        var previous = 1f
        for (step in 0..1000) {
            val value = IconAnchoredMotionSpec.coveredParentAlpha(step / 1000f)
            assertTrue(value in 0f..1f)
            assertTrue(value <= previous + 1e-6f)
            previous = value
        }
        val present = SettingsUiSource.function("presentSizedModalDialog")
        // 只有覆盖场景才淡父面板；普通弹窗没有父面板可淡。
        assertTrue(present.contains("val coveredContent = if (cover != null) coveredParent?.let"))
        // 淡的必须是**卡片层整层**：气泡面板的表面连同描边是 BubblePanelLayer 画的，容器自己
        // background = null，只淡容器会让文字变淡、描边纹丝不动（实测 103 没有回到 87）。
        // 但也**不能**淡整张 decorView——父面板的 scrim 在里面，跟着淡掉背景压暗就整个消失
        // （2026-09-22 真机实测：面板外 BGR 25.7/29.3/26.1 → 42.0/48.0/42.7）。
        assertTrue(present.contains("firstOrNull { it !== parentScrim }"))
        assertFalse(present.contains("coveredParent?.window?.decorView?.findViewById"))
        // 入场与退场两条 onFrame 都要驱动它，否则收起时父面板不会淡回来。
        assertEquals(2, Regex("coveredContent\\?\\.alpha = IconAnchoredMotionSpec\\.coveredParentAlpha")
            .findAll(present).count())
        // 硬关会停在半路，父面板不能留着半透明的 alpha。
        assertTrue(present.contains("coveredContent?.alpha = 1f"))
    }

    @Test fun reversalKeepsEntryShapeUntilStableEndpoint() {
        val controller = source("BubbleMotionController")
        val close = controller.after("fun requestClose(").before("fun handleWindowSizeChange")
        assertFalse(close.contains("entryShape ="))
        assertTrue(controller.contains("layer.applyFrame(clamped, entryShape)"))
        val layer = source("BubblePanelLayer")
        assertTrue(layer.contains("BubbleMotionSpec.scaleX(progress, entryShape)"))
        assertTrue(layer.contains("BubbleMotionSpec.scaleY(progress, entryShape)"))
    }

    /**
     * 背景压暗必须盖住状态栏与导航栏（2026-09-24 用户报告深浅色下都没盖住状态栏）。
     * 真机实证三层原因缺一不可：窗口按系统栏缩框；平台弹窗布局的 fitsSystemWindows 容器
     * 把内容区下推；窗口不带 DRAWS_SYSTEM_BAR_BACKGROUNDS 时系统在状态栏上画不透明黑底。
     */
    @Test fun modalScrimCoversTheSystemBars() {
        val main = source("MainActivity")
        val present = main.after("internal fun presentSizedModalDialog(")
        // 压暗层挂在最外层窗口层上，卡片层 root 按系统栏内缩，几何仍以 root 为原点。
        val frame = present.after("val windowFrame = NativeFrameLayout(this).apply {")
            .before("ViewCompat.setOnApplyWindowInsetsListener(windowFrame)")
        assertTrue(frame.indexOf("scrim?.let {") in 0 until frame.indexOf("addView(root,"))
        val rootInit = present.after("val root = ModalCardRoot(this).apply {")
            .before("val windowFrame = NativeFrameLayout(this).apply {")
        assertFalse("压暗层不能再挂在内缩后的 root 里", rootInit.contains("scrim?.let"))
        val insets = present.after("ViewCompat.setOnApplyWindowInsetsListener(windowFrame)")
            .before("// 子面板贴到父面板矩形上。")
        assertTrue(insets.contains("WindowInsetsCompat.Type.systemBars() or"))
        assertTrue(insets.contains("params.setMargins(safe.left, safe.top, safe.right, safe.bottom)"))
        assertTrue(present.contains("dialog.setContentView(windowFrame)"))
        assertTrue(present.contains("WindowCompat.setDecorFitsSystemWindows(this, false)"))
        assertTrue(present.contains("fitInsetsTypes = 0"))
        assertTrue(present.contains("addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)"))
        assertTrue(present.contains("statusBarColor = Color.TRANSPARENT"))
        assertTrue(present.contains("fitAncestor.fitsSystemWindows = false"))
        // 覆盖式子面板淡的是父面板压暗层之外的那一层（现在就是 root），不能淡整窗。
        assertTrue(present.contains("firstOrNull { it !== parentScrim }"))
    }

    /**
     * 覆盖式子面板：父面板只在子面板当前覆盖的区域里挖空（2026-09-24 两轮真机：整体淡出放在
     * 尾段会叠亮后跳暗，前移又让外轮廓先回缩再展开）。透明度只在完全盖满时收共边描边。
     */
    @Test fun coveredParentIsClippedByTheGrowingChildInsteadOfFadingEarly() {
        val present = SettingsUiSource.function("presentSizedModalDialog")
        assertTrue(present.contains("val root = ModalCardRoot(this).apply {"))
        assertTrue(present.contains("(coveredContent as? ModalCardRoot)?.excludeMotionSurface(morphLayer, morphLayer.alpha)"))
        assertTrue(present.contains("(coveredContent as? ModalCardRoot)?.clearExclusion()"))
        val root = source("ModalCardRoot")
        assertTrue(root.contains("canvas.clipOutPath(exclusion)"))
        // 区域内与子面板交叉淡变，不是一刀切（子面板开头几乎透明，一刀切会露出压暗层）。
        assertTrue(root.contains("canvas.saveLayerAlpha(exclusionRect, insideAlpha)"))
        assertTrue(root.contains("source.copyLiquidMotionBounds(exclusionRect)"))
        // 子面板没盖满之前父面板保持完整，外轮廓不回缩。
        assertEquals(1f, IconAnchoredMotionSpec.coveredParentAlpha(0.99f), 0f)
    }

    /**
     * 收起动画末帧先上屏、下一帧再移窗（2026-09-24 atrace：原来 dismiss 挤在动画结束回调里，
     * 关闭末帧 notifyAnimEnd 7–11ms）。气泡与图标锚点两条路径都要走。
     */
    @Test fun closingDefersWindowRemovalPastTheFinalFrame() {
        val present = SettingsUiSource.function("presentSizedModalDialog")
        assertEquals(2, Regex(Regex.escape("dismissAfterFinalFrame(dialog) {")).findAll(present).count())
        val helper = SettingsUiSource.function("dismissAfterFinalFrame")
        assertTrue(helper.contains("decor.postOnAnimation { finish() }"))
        assertTrue(helper.contains("if (dialog.isShowing) runCatching { dialog.dismiss() }"))
    }

    /** 更新渠道子面板的关闭按钮与 GitHub 面板那颗重合：弹性占位把空档收到关闭行上方。 */
    @Test fun updateChannelCloseButtonSitsOnTheCardBottom() {
        val channel = SettingsUiSource.function("showUpdateChannelDialog")
        assertTrue(channel.contains("NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)"))
        assertTrue(channel.contains("createPanelCloseButton { dismissWithAnimation(dialog, container) {} }"))
        assertTrue(channel.contains("presentModalDialog(dialog, container, morphAnchorBounds = origin, coverBounds = cover)"))
    }

    /**
     * 浅色主题下的形变不许出现深色（2026-09-24 用户多轮报告，逐帧检测定位三处来源）：
     * ① 背板 40% 黑：面板长满前其最终区域透出已压暗的底页 → 浅色改为背景色薄纱；
     * ② 半透明玻璃卡片带系统 elevation：阴影半影透到卡片内侧成一圈灰带 → 玻璃皮肤不带投影
     *    （试过用投影体 + clipOutPath 裁阴影：系统阴影在 Z 重排阶段绘制，不受 drawChild 裁剪，失败）；
     * ③ 气泡面板飞行图标副本是深灰 → 浅色下离开原位后降到 35%。
     */
    @Test fun lightThemeMorphsNeverFlashDark() {
        val main = source("MainActivity")
        val scrim = main.after("private fun modalScrimColor(): Int =").before("/** 正文起始位移上限")
        assertTrue(scrim.contains("SettingsModalScrim.color(monetColors.surface, monetColors.background)"))
        assertTrue(source("DiagnosticsActivity").contains("SettingsModalScrim.color(monetColors.surface, monetColors.background)"))
        assertTrue(SettingsUiSource.function("presentSizedModalDialog").contains("setBackgroundColor(modalScrimColor())"))
        val container = SettingsUiSource.function("createModalContainer")
        assertTrue(container.contains("elevation = if (isLiquidSkinEffective || isMaterialYouSkinEffective) 0f else 12 * density"))
        assertTrue(source("DiagnosticsActivity").contains("elevation = if (isLiquidSkinEffective || isMaterialYouSkinEffective) 0f else 12 * density"))
        // 飞行图标：交接段保持 1（与真实图标总量守恒），离开原位后才压低。
        assertEquals(1f, BubbleLayerMotionSpec.lightThemeTravelFactor(0.03f), 0f)
        assertEquals(BubbleLayerMotionSpec.LIGHT_THEME_TRAVEL_OPACITY,
            BubbleLayerMotionSpec.lightThemeTravelFactor(0.2f), 1e-6f)
        val proxy = source("BubbleIconProxy")
        assertTrue(proxy.contains("BubbleLayerMotionSpec.sourceIconWeight(p, lightTheme)"))
        assertTrue(proxy.contains("BubbleLayerMotionSpec.proxyIconOpacity(p, lightTheme)"))
        // 按钮原位图案总量守恒：浅色下副本压淡多少，真实图标就补回多少（否则按钮亮闪）。
        for (step in 0..1000) {
            val p = step / 1000f
            for (light in listOf(false, true)) {
                assertEquals(1f, BubbleLayerMotionSpec.sourceIconWeight(p, light) +
                    BubbleLayerMotionSpec.proxyIconOpacity(p, light), 1e-6f)
            }
        }
    }

    /**
     * 面板从按钮处长出时玻璃表面会盖住按钮原位，飞行副本已飞走 → 浅色下按钮深浅跳动
     * （2026-09-24 真机：图标区 190 → 243 → 209）。在面板里按钮原位、面板形状内补画静止图标。
     */
    @Test fun bubbleKeepsTheSourceIconVisibleWhileItsSurfaceCoversTheButton() {
        val proxy = source("BubbleIconProxy")
        assertTrue(proxy.contains("BubbleLayerMotionSpec.sourceIconWeight(p, true) * BubbleLayerMotionSpec.surfaceOpacity(p)"))
        val slot = proxy.after("fun drawSlotIcon(").before("fun drawMask(")
        assertTrue(slot.contains("clipPath(surfaceShape)") && slot.contains("drawSnapshot(this, slotPaint, sourceBounds)"))
        assertTrue(proxy.after("fun settleExpanded()").before("fun dispose()").contains("slotPaint.alpha = 0"))
        val layer = source("BubblePanelLayer")
        assertTrue(layer.contains("icon?.drawSlotIcon(canvas, surfaceShape)"))
        assertTrue(layer.indexOf("icon?.drawSlotIcon(canvas, surfaceShape)") < layer.indexOf("icon?.drawIcon(canvas)"))
        assertTrue(layer.contains("if (!tailPath.isEmpty) surfaceShape.addPath(tailPath)"))
    }

    /**
     * 顶栏按钮的 foreground 涟漪（浅色 0x8C 白）在气泡盖住按钮后还要退场几百毫秒，
     * 隔着玻璃把图标区提亮到超过打开态（真机 176→219→199）。气泡按覆盖度收掉它：
     * API 31+ 已开始的涟漪不读新颜色，必须 setVisible(false) 清热点；settle 保持压住，
     * dispose 才放开。
     */
    @Test fun bubbleRetiresTheButtonRippleOnceItCoversTheButton() {
        val ripple = source("CoverableRippleDrawable")
        assertTrue(ripple.contains(") : RippleDrawable(ColorStateList.valueOf(baseColor), content, mask)"))
        assertTrue(ripple.contains("val shouldShow = clamped < COVERED_THRESHOLD"))
        assertTrue(ripple.contains("if (shouldShow != isVisible) setVisible(shouldShow, false)"))
        val proxy = source("BubbleIconProxy")
        assertTrue(proxy.after("fun updateFrame(").before("fun drawIcon(")
            .contains("coverRipple(BubbleLayerMotionSpec.surfaceOpacity(p))"))
        assertTrue(proxy.contains("(source.foreground as? CoverableRippleDrawable)?.coverOpacity = opacity"))
        assertTrue(proxy.after("fun settleExpanded()").before("fun dispose()").contains("if (tookOver) coverRipple(1f)"))
        assertTrue(proxy.after("fun dispose()").before("private fun restoreSource()").contains("releaseRipple()"))
        assertTrue(proxy.after("private fun releaseRipple()").contains("if (tookOver) coverRipple(0f)"))
    }
}
