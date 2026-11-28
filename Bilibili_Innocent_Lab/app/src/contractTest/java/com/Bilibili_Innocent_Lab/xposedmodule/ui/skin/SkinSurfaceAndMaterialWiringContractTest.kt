package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.contract.KotlinSourceScanner
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.DiagnosticsEntryVisualSpec
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.SettingsUiSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主侧窗口所有权、表面材质装配、弹窗皮肤覆盖、控件状态同步与背景预览的局部源码护栏。
 */
class SkinSurfaceAndMaterialWiringContractTest {

    @Test
    fun windowOwnershipMotionSurfaceAndSamplingGeometryStayCallerOwned() {
        val mount = SourceContract.read("ui/meguviz/SettingsScope.kt")
            .after("internal fun SkinnedActivity.setMeguvizContentView")
        assertTrue(mount.contains("AndroidUiBinding.open(moduleWindowSession(), parent)"))
        assertTrue(mount.contains("binding.close()"))

        val activity = SourceContract.read("ui/skin/activity/SkinnedActivity.kt")
        val window = activity.after("internal fun moduleWindowSession()").before("override fun setContentView")
        assertTrue(window.contains("windowSession ?: WindowSession.open"))
        assertTrue(window.contains("BilWindowDelegate(this, content, moduleWindowSession())"))
        assertTrue(activity.after("override fun onDestroy()").contains("windowOwner?.close()"))

        val homePresenter = SourceContract.read("ui/activity/SettingsHomePresenter.kt")
            .after("private fun installTopInset()")
            .before("private fun applyScrollInsets()")
        assertTrue(homePresenter.contains("topInsetRegistration = activity.observeModuleInsets(shell)"))

        assertTrue(activity.contains("windowDelegate?.observeContentInsets(root, observer)"))
        val delegate = SourceContract.read("ui/skin/activity/BilWindowDelegate.kt")
            .after("fun observeContentInsets(root: View, observer: (InsetsSnapshot) -> Unit)")
            .before("override fun close()")
        assertTrue(delegate.contains("rootView !== root"))
        assertTrue(delegate.contains("defaultPadding?.close()"))
        assertTrue(delegate.contains("return insets?.observe(observer)"))

        for (dark in listOf(false, true)) {
            assertTrue(DiagnosticsEntryVisualSpec.scrimAlpha(dark) in 1..255)
        }
        for (name in listOf("SettingsBackupActivity", "DiagnosticsActivity")) {
            val hostActivity = SourceContract.read("ui/activity/$name.kt")
            assertTrue(name, hostActivity.contains("motionHost.setMotionSurfaceBackground("))
            assertTrue(name, hostActivity.contains("skinMotionSurfaceBackground("))
            assertTrue(name, hostActivity.contains("bindPreparedSkinRoot(motionHost.liquidBackdropRoot())"))
        }

        val diagnostics = SourceContract.read("ui/activity/DiagnosticsActivity.kt")
        val preview = KotlinSourceScanner.functions(diagnostics, "showExportPreview").single()
        assertTrue(preview.contains("background = skinModalBackground(monetColors.surface)"))
        assertFalse("must not install elastic interaction before setContentView", preview.contains("installDialogElasticInteraction("))
        assertTrue(preview.contains("ModalPresenter.presentCenteredDialog("))

        val modalPresenter = SourceContract.read("ui/activity/ModalPresenter.kt")
        val configure = KotlinSourceScanner.functions(modalPresenter, "configureFullscreenWindow").single()
        assertTrue(configure.contains("dialog.setContentView(contentRoot)"))
        val centered = KotlinSourceScanner.functions(modalPresenter, "presentCenteredDialog").single()
        val setContent = centered.indexOf("configureFullscreenWindow(dialog, activity.window, root)")
        val install = centered.indexOf("val disposeElasticInteraction = activity.installDialogElasticInteraction(dialog)")
        assertTrue(setContent >= 0 && install > setContent)
        assertTrue(centered.indexOf("dialog.setOnDismissListener") > install)
        assertTrue(centered.contains("disposeElasticInteraction()"))

        assertTrue(activity.contains("ModernPalette.resolve(this)"))
        assertFalse(SourceContract.read("ui/theme/MonetColors.kt").contains("ModernPalette"))
        val neutral = KotlinSourceScanner.functions(activity, "neutralWindowBackground").single()
        assertTrue(neutral.contains("lumen.neutralWindowBackground()"))
        assertFalse(neutral.contains("prepareSkinSession"))
        assertFalse(neutral.contains("SkinPrefs"))
        assertTrue(activity.contains("lumen.surface(color, radiusDp, SurfaceRole.FLOATING)"))
        assertTrue(activity.contains("lumen.surface(color, radiusDp, SurfaceRole.TOP_BAR)"))
        assertTrue(activity.contains("lumen.surface(color, radiusDp, SurfaceRole.SELECTED_ITEM)"))

        val samplingHelper = SourceContract.read("ui/skin/geometry/ViewSamplingMatrix.kt")
        assertTrue(samplingHelper.contains("current.matrix.getValues(step)"))
        assertTrue(samplingHelper.contains("parent?.scrollX"))
        assertTrue(samplingHelper.contains("parent?.scrollY"))
        assertFalse(samplingHelper.contains(".transformMatrixToGlobal("))
    }

    @Test
    fun modalSkinCoverageSecondaryPagesAndBubbleGeometryStayWired() {
        var dialogs = 0
        SettingsUiSource.settingsUiFiles().forEach { (file, text) ->
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
        assertEquals(58, dialogs)

        assertTrue(
            SettingsUiSource.function("showSettingsFavoritesDialog")
                .contains("presentSizedModalDialog(dialog, container, width, anchor)")
        )
        val presenter = SettingsUiSource.function("presentSizedModalDialog")
        val modalFile = SettingsUiSource.file("ModalPresenter")
        val main = SettingsUiSource.mainActivity()
        assertTrue(presenter.indexOf("styleModalSkinControls(container)") in 0 until presenter.indexOf("dialog.show()"))
        assertTrue(main.contains("internal fun styleModalSkinControls(root: View) = stylePreparedSkinControls(root)"))
        assertTrue(
            "弹窗必须铺窗口内压暗层",
            KotlinSourceScanner.functions(modalFile, "createScrimView").single()
                .contains("setBackgroundColor(scrimColor(monetColors))")
        )
        assertTrue("presentSizedModalDialog 要建 scrim", presenter.contains("ModalPresenter.createScrimView(this, monetColors)"))
        assertTrue("scrim 要注册进 dialogScrims", presenter.contains("ModalPresenter.registerScrim(dialog, it)"))
        assertTrue(
            "registerScrim 要写入 dialogScrims",
            KotlinSourceScanner.functions(modalFile, "registerScrim").single()
                .contains("dialogScrims[dialog]")
        )
        assertTrue("scrim alpha 要跟动画进度", presenter.contains("scrim?.alpha"))
        assertTrue(
            "dismissWithAnimation 要同步收 scrim",
            SettingsUiSource.function("dismissWithAnimation").contains("ModalPresenter.dismissAnchoredOrCentered(")
        )
        assertTrue(
            "dismissAnchoredOrCentered 要同步收 scrim",
            KotlinSourceScanner.functions(modalFile, "dismissAnchoredOrCentered").single()
                .contains("dialogScrims[dialog]")
        )

        val diagnostics = SourceContract.read("ui/activity/DiagnosticsActivity.kt")
        assertTrue(diagnostics.contains("background = skinModalBackground(monetColors.surface)"))
        assertTrue(diagnostics.contains("styleControls = ::stylePreparedSkinControls"))
        val centered = KotlinSourceScanner.functions(modalFile, "presentCenteredDialog").single()
        assertTrue(centered.indexOf("styleControls(container)") in 0 until centered.indexOf("dialog.show()"))

        val backup = SourceContract.read("ui/activity/SettingsBackupActivity.kt")
        assertTrue(backup.contains("skinActionButton(this, filled = true, radiusDp = 14f)"))
        assertTrue(backup.contains("skinActionButton(this, filled = false, radiusDp = 14f)"))

        val settingsPage = SettingsUiSource.settingsPage()
        assertTrue(settingsPage.contains("skinUpdateBadge(this)"))
        assertTrue(settingsPage.contains("thumbBackground = logLevelThumbBg()"))
        assertFalse(settingsPage.contains("skinSelectionControl(this, 10f, selected = true)"))
        assertTrue(settingsPage.contains("selectedText = monetColors.onPrimary"))
        assertFalse(SourceContract.read("hook/HookEntry.kt").contains("LiquidChoiceDrawable"))
        assertFalse(SourceContract.read("ui/overlay/ReplyTopologyPanelView.kt").contains("LiquidChoiceDrawable"))

        assertTrue(
            SourceContract.read("ui/widget/BubbleDrawable.kt")
                .contains("typealias BubbleDrawable = com.lumen.coacervation.engine.widget.BubbleDrawable")
        )
        val badge = SourceContract.read("ui/widget/GithubUpdateBadgeDrawable.kt")
        assertTrue(badge.contains("private val bubble = BubbleDrawable("))
        assertTrue(badge.contains("canvas.scale(1f, -1f)"))
        assertTrue(badge.contains("bubble.draw(canvas)"))
        assertTrue(badge.contains("bubble.alpha = alpha"))
        assertTrue(badge.contains("bubble.colorFilter = colorFilter"))
        assertTrue(badge.contains("strokeInset = strokeWidthPx / 2f"))
        assertTrue(badge.contains("2f * strokeInset"))
        assertTrue(badge.contains("ceil(strokeInset)"))
        assertTrue(badge.contains("floor(bounds.width() - strokeInset)"))
        assertTrue(badge.contains("bounds.top + strokeInset + bodyHeight.toFloat()"))
    }

    @Test
    fun controlDrawableStateHandoffAndBackgroundPreviewRemainGatedAndBounded() {
        val activity = SourceContract.read("ui/skin/activity/SkinnedActivity.kt")
        val pass = KotlinSourceScanner.functions(activity, "stylePreparedSkinControls").single()
        assertTrue(pass.contains("if (!lumen.isPrepared || lifecycleEnded) return"))
        listOf(
            "isChecked =", "setOnCheckedChangeListener", "setOnClickListener", "getSharedPreferences",
            "performClick(", "addOnGlobalLayoutListener", "PixelCopy", "RuntimeShader"
        ).forEach { forbidden ->
            assertFalse(forbidden, pass.contains(forbidden))
        }
        assertTrue(pass.contains("is SwitchCompat"))
        assertTrue(pass.contains("is CheckBox"))
        assertTrue(pass.contains("is EditText"))
        assertTrue(pass.contains("view.foreground = lumen.controlOutline(14f)"))

        val switchBranch = pass.after("is SwitchCompat -> {").before("is CheckBox ->")
        assertTrue("开关换 drawable 后必须刷新状态", switchBranch.contains("refreshDrawableState()"))
        assertTrue(switchBranch.indexOf("thumbDrawable = choice(") < switchBranch.indexOf("refreshDrawableState()"))

        val checkBoxBranch = pass.after("is CheckBox -> {").before("is EditText ->")
        assertTrue("复选框换 drawable 后必须刷新状态", checkBoxBranch.contains("refreshDrawableState()"))

        val materialSwitch = SourceContract.read("ui/view/MaterialSwitch.kt")
        val apply = KotlinSourceScanner.functions(materialSwitch, "applyMonetStyle").single()
        assertTrue("弹窗里的开关自取 Monet 配色后同样要刷新状态", apply.contains("refreshDrawableState()"))
        assertTrue(apply.indexOf("trackDrawable = choice(") < apply.indexOf("refreshDrawableState()"))

        val summaryPresenter = SourceContract.read("ui/activity/SkinSummaryPresenter.kt")
        val backgroundSummary = KotlinSourceScanner.functions(summaryPresenter, "currentLiquidBackgroundSummary").single()
        assertTrue(backgroundSummary.contains("R.string.liquid_background_summary_active"))
        assertFalse(backgroundSummary.contains("isLiquidSkinRequested"))
        assertFalse(backgroundSummary.contains("liquid_background_summary_saved"))

        val appearance = SourceContract.read("ui/activity/AppearanceDialogs.kt")
        val preview = KotlinSourceScanner.functions(appearance, "loadLiquidBackgroundPreview").single()
        assertTrue(preview.contains("preview.doOnLayout { view ->"))
        assertTrue(preview.contains("view.width <= 0 || view.height <= 0"))
        assertTrue(preview.indexOf("val viewWidth = view.width") < preview.indexOf("liquidBackgroundWorker.execute"))
        assertTrue(preview.contains("LiquidBackgroundStore.decodePreview("))
        assertTrue(preview.contains("viewWidth = viewWidth"))
        assertTrue(preview.contains("viewHeight = viewHeight"))
        assertFalse(preview.contains("targetWidth = 640"))
        assertFalse(preview.contains("targetHeight = 360"))
        assertTrue(preview.indexOf("liquidBackgroundDialog !== dialog") < preview.indexOf("liquidBackgroundWorker.execute"))
        val delivery = preview.after("runOnUiThread {")
        assertTrue(delivery.contains("!isFinishing && !isDestroyed && dialog.isShowing"))
        assertTrue(delivery.contains("liquidBackgroundDialog === dialog && preview.isAttachedToWindow"))
        assertTrue(delivery.contains("else bitmap.recycle()"))
    }
}
