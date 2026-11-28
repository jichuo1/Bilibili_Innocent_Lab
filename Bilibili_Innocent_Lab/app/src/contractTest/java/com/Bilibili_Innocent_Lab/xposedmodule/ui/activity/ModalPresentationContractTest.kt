package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 弹窗呈现、窗口配置、背板压暗与模糊门控、锚点标题复用及开关确认动效的本地源码契约。 */
class ModalPresentationContractTest {

    @Test
    fun anchoredAndBubbleModalsConfigureWindowRootAndScrimWithoutLosingBounds() {
        val main = SourceContract.read("ui/activity/MainActivity.kt")
        val presenter = SourceContract.read("ui/activity/ModalPresenter.kt")
        val present = SettingsUiSource.function("presentSizedModalDialog")
        val diagnostics = SourceContract.read("ui/activity/DiagnosticsActivity.kt")

        // 平台级窗口动画必须在 setContentView 之后关闭，且不得写回之前的 apply{} 块。
        assertTrue(
            "presentSizedModalDialog must delegate window setup to configureFullscreenWindow",
            present.contains("ModalPresenter.configureFullscreenWindow(dialog, this.window, windowFrame)")
        )
        val configure = presenter.after("internal fun configureFullscreenWindow(")
            .before("internal fun observeSafeMargins(")
        val setContent = configure.indexOf("dialog.setContentView(contentRoot)")
        val disable = configure.indexOf("dialog.window?.setWindowAnimations(0)")
        assertTrue("setContentView not found", setContent > 0)
        assertTrue("window animation must be disabled", disable > 0)
        assertTrue("must be disabled after setContentView", disable > setContent)
        val windowBlock = configure.after("dialog.window?.apply {").before("if (contentRoot != null)")
        assertFalse(windowBlock.contains("setWindowAnimations"))

        // 背景压暗层覆盖系统栏，卡片根按安全区内缩，覆盖式子面板仅淡出/挖空非 scrim 的卡片层。
        val frame = present.after("val windowFrame = NativeFrameLayout(this).apply {")
            .before("val closeInsets = ModalPresenter.observeSafeMargins(")
        assertTrue(frame.indexOf("scrim?.let {") in 0 until frame.indexOf("addView(root,"))
        val rootInit = present.after("val root = ModalCardRoot(this).apply {")
            .before("val windowFrame = NativeFrameLayout(this).apply {")
        assertFalse("压暗层不能再挂在内缩后的 root 里", rootInit.contains("scrim?.let"))
        val margins = presenter.after("internal fun observeSafeMargins(")
            .before("internal fun animateCenteredEnter(")
        assertTrue(margins.contains("WindowInsetsCompat.Type.systemBars() or"))
        assertTrue(margins.contains("params.setMargins(left, top, right, bottom)"))
        assertTrue(configure.contains("WindowCompat.setDecorFitsSystemWindows(this, false)"))
        assertTrue(configure.contains("fitInsetsTypes = 0"))
        assertTrue(configure.contains("WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS"))
        assertTrue(configure.contains("statusBarColor = Color.TRANSPARENT"))
        assertTrue(configure.contains("fitAncestor.fitsSystemWindows = false"))

        assertTrue("必须从 ModalPresenter.scrimFor 认出父面板的压暗层", present.contains("ModalPresenter.scrimFor(parent)"))
        assertTrue(presenter.after("internal fun scrimFor(").before("internal fun setSurfaceInsets(").contains("dialogScrims[dialog]"))
        assertTrue("淡出目标必须是非 scrim 的那个卡片层", present.contains("firstOrNull { it !== parentScrim }"))
        val coveredIndex = present.indexOf("val coveredContent")
        val decorIndex = present.indexOf("parent.window?.decorView", coveredIndex)
        assertTrue("decorView 只能作为拿不到卡片层时的兜底", coveredIndex in 0 until decorIndex)

        assertTrue(present.contains("title.elevation = morphLayer.elevation + 1f"))
        assertTrue(present.contains("surfaceElevation = if (cover == null) container.elevation else 0f"))
        assertTrue(present.contains("val coveredContent = if (cover != null) coveredParent?.let"))
        assertFalse(present.contains("coveredParent?.window?.decorView?.findViewById"))
        assertEquals(2, Regex("coveredContent\\?\\.alpha = IconAnchoredMotionSpec\\.coveredParentAlpha").findAll(present).count())
        assertTrue(present.contains("coveredContent?.alpha = 1f"))
        assertTrue(present.contains("val root = ModalCardRoot(this).apply {"))
        assertTrue(present.contains("(coveredContent as? ModalCardRoot)?.excludeMotionSurface(morphLayer, morphLayer.alpha)"))
        assertTrue(present.contains("(coveredContent as? ModalCardRoot)?.clearExclusion()"))

        // 收起动画末帧先上屏、下一帧再移窗；更新渠道底部关闭按钮与居中弹窗浅色主题配色。
        assertEquals(2, Regex(Regex.escape("dismissAfterFinalFrame(dialog) {")).findAll(present).count())
        val dismissHelper = SettingsUiSource.function("dismissAfterFinalFrame")
        assertTrue(dismissHelper.contains("decor.postOnAnimation { finish() }"))
        assertTrue(dismissHelper.contains("if (dialog.isShowing) runCatching { dialog.dismiss() }"))

        val channel = SettingsUiSource.function("showUpdateChannelDialog")
        assertTrue(channel.contains("NativeLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)"))
        assertTrue(channel.contains("createPanelCloseButton { dismissWithAnimation(dialog, container) {} }"))
        assertTrue(channel.contains("presentModalDialog(dialog, container, morphAnchorBounds = origin, coverBounds = cover)"))

        val scrim = presenter.after("internal fun scrimColor(").before("internal fun createScrimView(")
        assertTrue(scrim.contains("SettingsModalScrim.color(monetColors.surface, monetColors.background)"))
        assertTrue(presenter.after("internal fun createScrimView(").before("internal fun configureFullscreenWindow(")
            .contains("setBackgroundColor(scrimColor(monetColors))"))
        assertTrue(diagnostics.contains("ModalPresenter.presentCenteredDialog("))
        assertTrue(presenter.after("internal fun presentCenteredDialog(").before("internal fun surfaceBounds(")
            .contains("createScrimView(activity, activity.monetColors)"))
        assertTrue(present.contains("ModalPresenter.createScrimView(this, monetColors)"))
        val container = SettingsUiSource.function("createModalContainer")
        assertTrue(container.contains("elevation = if (isModalLiquidSkinEffective || isModalMaterialYouSkinEffective) 0f else 12 * density"))
        assertTrue(main.contains("val isModalLiquidSkinEffective: Boolean get() = isLiquidSkinEffective"))
        assertTrue(main.contains("val isModalMaterialYouSkinEffective: Boolean get() = isMaterialYouSkinEffective"))
        assertTrue(diagnostics.contains("elevation = if (isLiquidSkinEffective || isMaterialYouSkinEffective) 0f else 12 * density"))
    }

    @Test
    fun bubbleAndAnchorPlacementRefreshGeometryAndEnforceBackAndKeyboardTiming() {
        val main = SourceContract.read("ui/activity/MainActivity.kt")
        val presenter = SourceContract.read("ui/activity/ModalPresenter.kt")
        val present = SettingsUiSource.function("presentSizedModalDialog")

        // 气泡借用弹窗皮肤，不使用不透明替代路径；背手势回调在 dialog.show() 之后注册。
        val applyBubble = present.after("fun applyBubbleSurface(").before("if (bubblePlacement != null)")
        assertFalse(applyBubble.contains("BubbleSurfaceDrawable("))
        val bubble = present.after("val bubbleLayer = if (bubblePlacement != null)").before("val morphLayer =")
        assertFalse(bubble.contains("BubbleSurfaceDrawable("))
        assertTrue(bubble.contains("modalBackground ?: modalSurfaceBackground"))
        assertTrue(bubble.contains("modalSurfaceBackground(monetColors.surface, 0f)"))
        assertTrue(
            main.after("internal fun modalSurfaceBackground(").before("internal val isModalLiquidSkinEffective")
                .contains("skinModalBackground(color, radiusDp)")
        )

        val show = present.indexOf("dialog.show()")
        val register = present.indexOf("registerBackCallback()", show)
        assertTrue("dialog.show() not found", show > 0)
        assertTrue("back callback must be registered after dialog.show()", register > show)
        assertEquals(-1, present.substring(0, show).indexOf(".registerOnBackInvokedCallback("))
        assertTrue(present.contains("val dispatcher = registeredBackDispatcher"))
        assertTrue(present.contains("PredictiveBackApi33.unregister(dispatcher, callback)"))
        assertEquals(-1, present.indexOf("onBackInvokedDispatcher"))
        assertTrue(present.contains("register OnBackInvokedCallback failed"))
        assertTrue(present.contains("keyCode == KeyEvent.KEYCODE_BACK"))

        // 搜索气泡等待首轮稳定再拉起输入法；气泡几何在真实布局落地后再刷新。
        val search = SettingsUiSource.function("showSettingsSearchDialog")
        assertTrue(search.contains("AnchorStyle.BUBBLE, onExpanded ="))
        assertFalse(search.contains("editor.postDelayed"))
        assertTrue(search.contains("SOFT_INPUT_STATE_ALWAYS_HIDDEN"))

        val geometryCode = present.after("fun updateBubbleGeometry(): Boolean").before("val bubbleLayoutListener")
        assertTrue(geometryCode.contains("bubbleGeometryPending || geometryChanged || actualBoundsChanged"))
        assertTrue(geometryCode.contains("!layoutChanged && !container.isLayoutRequested"))
        assertTrue(geometryCode.indexOf("bubbleLayer?.setAnchor(localAnchor)") < geometryCode.indexOf("handleWindowSizeChange()"))
        assertTrue(geometryCode.indexOf("container.layoutParams = params") < geometryCode.indexOf("handleWindowSizeChange()"))
        assertTrue(geometryCode.contains("previousBubbleBottom = container.bottom"))

        assertTrue(presenter.contains("if (!dialog.isShowing || bubbleController.isClosing) return true"))
        assertTrue(presenter.contains("bubbleSourceLocation[0] - bubbleRootLocation[0]"))
        assertTrue(presenter.contains("bubbleSourceLocation[1] - bubbleRootLocation[1]"))
        assertTrue(presenter.contains("bubbleLayer?.setAnchor(localAnchor)"))
        assertTrue(presenter.contains("bubbleLayer?.setPlacement(placement)"))
        assertTrue(presenter.contains("if (!bubbleController.isClosing) pendingAnchoredAfterClose.set(after)"))
        assertTrue(presenter.contains("removeOnGlobalLayoutListener(bubbleLayoutListener)"))
        assertTrue(presenter.contains("updateBubbleGeometry() || container.isLayoutRequested"))

        // 锚点几何使用可点击摘要与可见屏幕矩形，每次展示与回退重算来源位置。
        val editors = SettingsUiSource.function("showComponentManualRuleEditor") +
            SettingsUiSource.function("showComponentPickerDialog")
        assertEquals(2, Regex("val anchor = spec.summaryView\\(\\)").findAll(editors).count())
        assertFalse(editors.contains("it.parent"))
        assertFalse(editors.contains("parentOrNull"))

        val fallback = SettingsUiSource.function("showComponentManualRuleEditor")
        assertTrue(fallback.contains("spec.summaryView()"))
        assertTrue(fallback.contains("spec.currentRules(), anchor"))
        val editor = SettingsUiSource.function("showRuleEditorDialog")
        assertTrue(editor.contains("presentModalDialog(dialog, container, anchor)"))
        assertFalse(fallback.contains("remove("))
        assertFalse(fallback.contains("clear("))

        val origin = SettingsUiSource.function("modalAnchorBounds")
        assertTrue(origin.contains("getGlobalVisibleRect(visible)"))
        assertTrue(origin.contains("val sourceRoot = anchor.rootView"))
        assertTrue(origin.contains("sourceRoot.getLocationOnScreen(rootLocation)"))
        assertTrue(origin.contains("visible.offset(rootLocation[0], rootLocation[1])"))
        assertTrue(origin.contains("sourceRoot.scaleX != 1f"))
        assertTrue(origin.contains("!anchor.isShown"))
        assertTrue(origin.contains("visible.bottom.toFloat()"))
        assertTrue(present.contains("resolveAnchorOnScreen()?.let { currentAnchor ->"))
        assertTrue(present.contains("morphAnchor?.let(::modalAnchorBounds) ?: capturedAnchorBounds"))
    }

    @Test
    fun modalBackdropBlurIsGatedByDefaultOffUserToggleAndMaterialYouSkin() {
        val storeCode = SourceContract.read("settings/appearance/ModalBackdropBlurStore.kt")
        assertTrue(storeCode.contains("const val DEFAULT = false"))
        assertTrue(storeCode.contains("getBoolean(PREF_KEY, DEFAULT)"))
        assertTrue(storeCode.contains("getOrDefault(DEFAULT)"))

        val main = SettingsUiSource.settingsPage()
        val presenter = SourceContract.read("ui/activity/ModalPresenter.kt")
        assertTrue(presenter.contains("userEnabled = ModalBackdropBlurStore.read(this)"))
        assertTrue(presenter.contains("userEnabled = ModalBackdropBlurStore.read(activity)"))
        val layout = SettingsUiSource.settingsPageFiles().map { it.second }.singleOrNull {
            it.contains("R.string.panel_window_blur_title") &&
                it.contains("R.string.material_color_spec_summary")
        }
        assertTrue("blur toggle and color-spec row must live in one settings file", layout != null)
        assertTrue(
            layout!!.indexOf("R.string.panel_window_blur_title") >
                layout.indexOf("R.string.material_color_spec_summary")
        )
        assertTrue(main.contains("putBoolean(ModalBackdropBlurStore.PREF_KEY, checked)"))

        val skinned = SourceContract.read("ui/skin/activity/SkinnedActivity.kt")
        assertTrue(skinned.contains("isMaterialYouSkinEffective"))
        assertTrue(skinned.contains("!lumen.isLiquidEffective"))
        assertTrue(SourceContract.read("ui/activity/MainActivity.kt")
            .contains("val isModalMaterialYouSkinEffective: Boolean get() = isMaterialYouSkinEffective"))
        assertTrue(presenter.contains("materialYouSkin = isModalMaterialYouSkinEffective"))
        assertEquals(
            2,
            Regex("onFrame = \\{ progress ->[\\s\\S]{0,160}?backdropBlur\\?\\.apply\\(progress\\)")
                .findAll(presenter).count()
        )
        assertTrue(
            Regex("setUpdateListener \\{[\\s\\S]{0,160}?backdropBlur\\?\\.apply\\(container\\.alpha\\)")
                .containsMatchIn(presenter)
        )
        assertTrue(presenter.contains("backdropBlur?.clear()"))
    }

    @Test
    fun anchoredModalTitlesAndSwitchConfirmationsReuseRowStringsAndAnchors() {
        val dialogSources = SettingsUiSource.dialogFileNames.map { it to SettingsUiSource.file(it) }
        assertTrue("没扫到任何 *Dialogs.kt，护栏会静默通过", dialogSources.size >= 4)
        val dialogFunctions = dialogSources.flatMap { (fileName, text) ->
            SettingsUiSource.declaredFunctions(text, indent = 0)
                .filter { (_, decl) -> decl.contains("fun MainActivity.") }
                .map { (fnName, decl) -> Triple(fileName, fnName, decl) }
        }
        assertTrue("没解析出弹窗函数：${dialogSources.map { it.first }}", dialogFunctions.size >= 10)
        assertTrue("showPlayerQualityDialog 应当在扫描范围内",
            dialogFunctions.any { it.second == "showPlayerQualityDialog" })

        val dialogOffenders = dialogFunctions.filter { (_, _, body) ->
            body.contains("anchor: View?") && Regex("""R\.string\.\w*_dialog_title""").containsMatchIn(body)
        }.map { (fileName, fnName, body) ->
            val key = Regex("""R\.string\.(\w*_dialog_title)""").find(body)?.groupValues?.get(1)
            "$fileName#$fnName 用了 R.string.$key"
        }
        assertEquals(
            "带 anchor 的选择面板不能用独立的 *_dialog_title 当标题——那会让 ModalTitleMotion " +
                "配不上、文字平移静默退化成只有容器形变。改成来源行那一行用的同一个 string。",
            emptyList<String>(),
            dialogOffenders
        )

        val ruleEditorOffenders = mutableListOf<String>()
        var callSiteCount = 0
        val callPattern = Regex("""\bshowRuleEditorDialog\s*\(""")
        for ((fileName, raw) in SettingsUiSource.settingsPageFiles()) {
            val masked = SettingsUiSource.masked(raw)
            val code = SettingsUiSource.code(raw)
            for (match in callPattern.findAll(masked)) {
                callSiteCount++
                val firstArg = firstCallArgument(masked, code, match.range.last)
                val titleRef = Regex("""R\.string\.(\w+)""").find(firstArg)?.groupValues?.get(1)
                if (titleRef != null && titleRef.endsWith("_dialog_title")) {
                    val lineNumber = raw.substring(0, match.range.first).count { it == '\n' } + 1
                    ruleEditorOffenders += "$fileName:$lineNumber → $titleRef"
                }
            }
        }
        assertTrue("没扫到 showRuleEditorDialog 调用点，护栏会静默通过", callSiteCount >= 5)
        assertEquals(
            "showRuleEditorDialog 的标题实参要传来源行那一行用的 string，不能另写一条 " +
                "*_dialog_title——弹窗标题与来源行文案不同就配不上文字平移。",
            emptyList<String>(),
            ruleEditorOffenders
        )

        // 开关确认弹窗沿用开关标题与触发控件自身作为动画锚点。
        val confirmBody = SettingsUiSource.function("showAutoLightConfirmDialog")
        assertTrue(confirmBody.contains("anchor: View? = null"))
        val title = confirmBody.indexOf("getString(R.string.free_copy_light_mode)")
        val warning = confirmBody.indexOf("getString(R.string.free_copy_light_mode_confirm_title)")
        assertTrue(title >= 0 && warning > title)
        assertTrue(confirmBody.contains("presentModalDialog(dialog, container, anchor, onBackDismiss = onCancel)"))
        assertTrue(confirmBody.contains("dismissWithAnimation(dialog, container) { onCancel() }"))
        assertTrue(confirmBody.contains("dismissWithAnimation(dialog, container) { onConfirm() }"))

        val pageCode = SettingsUiSource.code(SettingsUiSource.settingsPage())
        assertTrue(Regex("applyModeledToggle\\(HookEntry\\.PREF_FREE_COPY_LIGHT_MODE,\\s*checked,\\s*button\\)")
            .containsMatchIn(pageCode))
        assertTrue(SettingsUiSource.function("applyModeledToggle")
            .contains("requestSettingCommand(descriptor.id, requested, button)"))
        assertTrue(SettingsUiSource.function("requestSettingCommand")
            .contains("requestManualLightSetting(id, requested, anchor)"))
        assertTrue(Regex("showAutoLightConfirmDialog\\(\\s*anchor = anchor,")
            .containsMatchIn(SettingsUiSource.function("requestManualLightSetting")))
    }

    private fun firstCallArgument(masked: String, code: String, openParenIndex: Int): String {
        var parens = 0
        var braces = 0
        var brackets = 0
        for (i in openParenIndex until masked.length) {
            when (masked[i]) {
                '(' -> parens++
                ')' -> {
                    parens--
                    if (parens == 0) return code.substring(openParenIndex + 1, i)
                }
                '{' -> braces++
                '}' -> braces--
                '[' -> brackets++
                ']' -> brackets--
                ',' -> if (parens == 1 && braces == 0 && brackets == 0) {
                    return code.substring(openParenIndex + 1, i)
                }
            }
        }
        error("unbalanced call parentheses at $openParenIndex")
    }
}
