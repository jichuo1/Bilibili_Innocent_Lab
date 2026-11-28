package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import com.Bilibili_Innocent_Lab.xposedmodule.contract.beforeOrRest
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticFeatureRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 遥测菜单层级、音频拯救/宿主背景/播放器缓存面板控件及推荐屏蔽分区摆放契约。 */
class SettingsPanelsWiringContractTest {

    @Test
    fun telemetryMenuEntriesBadgesCoveredParentAndBlurRulesStayConsistent() {
        val main = SourceContract.read("ui/activity/MainActivity.kt")

        val row = SettingsUiSource.function("createTelemetryMenuRow")
        assertTrue(row.contains("showControl: Boolean = false"))
        val entry = row.after("if (!showControl) {").before("var programmaticChange")
        assertTrue(entry.contains("return NativeLinearLayout(this)"))
        assertFalse(entry.contains("setOnClickListener"))
        assertTrue(entry.contains("isClickable = false"))
        assertFalse(entry.contains("writeConsentChoice"))
        assertFalse(entry.contains("telemetrySwitch"))

        val badge = main.after("// The badge stays outside the circular button and does not change its anchor geometry.")
            .before("activationCardView = this")
        assertTrue(badge.contains("params(48.dp, 48.dp) { marginEnd = 5.dp }"))
        assertTrue(badge.contains("clipChildren = false"))
        assertTrue(badge.contains("clipToPadding = false"))
        assertTrue(badge.contains("params(22.dp, 15.dp)"))
        assertTrue(badge.contains("marginEnd = -5.dp"))
        assertTrue(badge.contains("topMargin = -5.dp"))
        assertTrue(badge.contains("GithubUpdateBadgeDrawable"))
        assertTrue(badge.contains("toolbar.touchDelegate"))
        assertTrue(badge.contains("badge.visibility == View.VISIBLE"))

        val click = row.after("contentDescription = getString(R.string.telemetry_info_button)")
            .before("// GitHub 二级页只导航")
        assertTrue(click.contains("origin = modalAnchorBounds(source)"))
        assertTrue(click.contains("cover = modalSurfaceBounds(dialog, dialogContainer)"))
        assertFalse(click.contains("cover = modalAnchorBounds("))
        assertFalse("子面板要叠在父面板上，不能再先收起父面板", click.contains("dismissWithAnimation(dialog, dialogContainer)"))
        assertTrue(click.contains("parentDialog = dialog"))

        val detail = SettingsUiSource.function("showTelemetryInfoDialog")
        assertTrue(detail.contains("morphAnchorBounds = origin, coverBounds = cover"))
        assertTrue(detail.contains("origin: SettingsBackupMotionRect? = null"))
        assertTrue(detail.contains("cover: SettingsBackupMotionRect? = null"))

        assertEquals(3, Regex("(?<!fun )closeCoveredParent\\(\\)").findAll(detail).count())
        for (marker in listOf("telemetry_explanation_action", "telemetry_preview_action", "telemetry_purge_action")) {
            // 最后一行之后没有下一个 createGitHubMenuRow：有意截到函数末尾。
            val handler = detail.after(marker).beforeOrRest("createGitHubMenuRow")
            assertTrue(marker, handler.contains("closeCoveredParent()"))
        }
        val close = detail.after("createPanelCloseButton").before("presentModalDialog")
        assertFalse(close.contains("closeCoveredParent()"))
        assertTrue(SettingsUiSource.function("showGitHubMenuDialog").contains("createPanelCloseButton"))

        val present = SettingsUiSource.function("presentSizedModalDialog")
        assertTrue(present.contains("if (cover == null) activeConfirmDialog?.dismiss()"))
        assertTrue(present.contains("activeConfirmDialog = coveredParent?.takeIf { it.isShowing }"))
        assertTrue(present.contains("if (cover != null) null else ModalBackdropBlur.createOrNull("))
        val preDraw = present.after("if (morphController != null && morphLayer != null)")
        assertTrue(preDraw.indexOf("applyCoverPlacement()") < preDraw.indexOf("prepareFirstFrame()"))
        assertFalse("minimumHeight 撑不住，别再退回去", present.contains("container.minimumHeight ="))
        assertTrue(present.contains("val available = root.height - top - (16 * density).toInt()"))
        assertTrue(present.contains("maxOf(target.height.toInt(), minOf(container.measuredHeight, available))"))
        assertTrue(present.contains("View.MeasureSpec.UNSPECIFIED"))
        val surface = SettingsUiSource.function("modalSurfaceBounds")
        assertTrue(surface.contains("ModalPresenter.surfaceBounds(dialog, container, this)"))
        val impl = SettingsUiSource.file("ModalPresenter")
            .after("internal fun surfaceBounds(")
            .before("internal fun dismissAnchoredOrCentered(")
        assertTrue(impl.contains("bounds.top + insets.top"))
        assertTrue(impl.contains("bounds.bottom - insets.bottom"))

        assertTrue(present.contains("morphAnchor == null && anchorStyle == MainActivity.AnchorStyle.CONTAINER"))
        assertTrue(present.contains("morphAnchor?.let(::modalAnchorBounds) ?: capturedAnchorBounds"))
        assertTrue(present.contains("capturedAnchorBounds?.takeIf { ValueAnimator.areAnimatorsEnabled() }"))
        assertTrue(present.contains("resolveAnchorOnScreen()?.let { currentAnchor ->"))

        assertTrue(detail.contains("createTelemetryMenuRow(dialog, container, showControl = true)"))
        assertTrue(row.contains("enabled && !TelemetryStore.hasCurrentDisclosure(applicationContext)"))
        assertTrue(row.contains("if (!saved)"))
        assertTrue(row.contains("telemetry_choice_save_failed"))
        assertTrue(row.contains("animateTelemetrySummary(summary, getString("))
        assertTrue(row.contains("android.text.StaticLayout.Builder.obtain"))
    }

    @Test
    fun audioRescueHostBackgroundAndPlayerBufferPanelsKeepDraftAndCommitBoundaries() {
        // 音频拯救面板：标准控件、一次原子提交与共用样式。
        val audioPanel = SettingsUiSource.code(SettingsUiSource.function("showAudioRescueDialog"))
        assertTrue(audioPanel.contains("MaterialSwitch(this, null)"))
        assertTrue(audioPanel.contains("createSlidingChoice(labels = channelLabels, selectedIndex = channelMode)"))
        assertTrue(audioPanel.contains("createModalTip("))
        listOf("CheckBox", "RadioButton", "RadioGroup", "selfRippleBackground(", "setOnClickListener").forEach {
            assertFalse("raw control $it must not come back", audioPanel.contains(it))
        }
        assertTrue(audioPanel.contains("createTermsActionButton(getString(R.string.dialog_cancel), filled = false)"))
        val confirm = audioPanel.after("createTermsActionButton(getString(R.string.dialog_confirm), filled = true)")
        assertTrue(confirm.contains(".putBoolean(audioKeys[0], guard.isChecked).putInt(audioKeys[1], channelMode)"))
        assertTrue(confirm.contains(".putBoolean(audioKeys[2], noise.isChecked).putBoolean(audioKeys[3], voice.isChecked).commit()"))
        assertEquals("one atomic commit", 1, Regex("""\.commit\(\)""").findAll(audioPanel).count())
        val failureIdx = confirm.indexOf("return@createTermsActionButton")
        val dismissIdx = confirm.indexOf("dismissWithAnimation(dialog, container) {}")
        assertTrue("a failed write must return before the panel is dismissed", failureIdx in 0 until dismissIdx)

        val assets = SettingsUiSource.code(SettingsUiSource.function("addAudioAssetControls"))
        assertTrue(assets.contains("createTermsActionButton(getString(resource), filled, onClick)"))
        assertTrue(assets.contains("createModalStatusCard()"))
        assertTrue(assets.contains("createModalTip("))
        listOf("selfRippleBackground(", "isClickable", "setOnClickListener").forEach {
            assertFalse("clickable text row $it must not come back", assets.contains(it))
        }
        assertTrue("busy state disables through the shared helper", assets.contains("enable(download, !value.busy)"))

        val tip = SettingsUiSource.code(SettingsUiSource.function("createModalTip"))
        assertTrue(tip.contains("R.color.colorTextGray"))
        val card = SettingsUiSource.code(SettingsUiSource.function("createModalStatusCard"))
        assertTrue(card.contains("skinCardBackground(monetColors.surfaceVariant, 14f)"))

        // 宿主背景编辑器：回滚保护、预览代数门控与资产所有权移交。
        val bgEditor = SourceContract.read("ui/activity/HostBackgroundPresenter.kt")
        val save = bgEditor.after("private fun save()").before("fun close(")
        assertTrue(save.contains("check(HostBackgroundSettingsStore.save(activity.prefs(), config))"))
        val success = save.after("}.onSuccess {").before("}.onFailure")
        assertTrue(success.contains("saved = true"))
        assertTrue(success.contains("HostBackgroundImages.cleanup(activity, config.asset)"))
        assertFalse(save.before("}.onSuccess {").contains("HostBackgroundImages.cleanup("))
        assertTrue(bgEditor.after("private fun dispose()").contains("if (!saved) importedAsset?.let"))

        val render = bgEditor.after("private fun renderImage()").before("private fun save()")
        assertTrue(render.before("activity.liquidBackgroundWorker.execute").contains("rendering = true"))
        val returned = render.after("activity.runOnUiThread {")
        assertTrue(returned.before("rendering = false").contains("if (!active || closing || ticket != generation)"))
        assertTrue(save.before("val config = draft.normalized()").contains("if (importing || rendering || !active || closing) return"))

        val commitSuccess = save.after("}.onSuccess {").before("}.onFailure { Toast")
        assertTrue(commitSuccess.before("HostBackgroundImages.cleanup").contains("importedAsset = null"))
        assertTrue(commitSuccess.contains("finally { close() }"))
        assertTrue(bgEditor.contains("private val container = activity.createModalContainer()"))
        assertTrue(bgEditor.contains("activity.presentModalDialog(dialog, container, anchor, onDismissed = ::dispose)"))
        assertFalse(bgEditor.contains("dialog.setOnDismissListener"))
        val close = bgEditor.after("fun close(").before("private fun dispose()")
        assertTrue(close.contains("activity.dismissWithAnimation(dialog, container)"))
        assertFalse(close.before("} else {").contains("dispose()"))

        // 播放器缓存面板：统一入口快照、读回失败不关闭与模式门控。
        val settingsPage = SettingsUiSource.settingsPage()
        val bufferEntry = SettingsUiSource.function("playerBufferSettingsRows")
        val bufferKeys = listOf("MODE", "SECONDS", "MEMORY_MB", "DISK_MB", "WIFI_ONLY")
        bufferKeys.forEach { key -> assertTrue(settingsPage.contains("FeaturePreferences.PLAYER_BUFFER_$key")) }
        assertTrue(bufferEntry.contains("setOnClickListener { showPlayerBufferDialog(anchor = it) }"))
        assertTrue(bufferEntry.contains(".forEach { key -> bindSettingDestination(this, key) }"))
        assertTrue(settingsPage.contains("playerBufferSettingsRows(this)"))
        bufferKeys.filter { it != "WIFI_ONLY" }.forEach { key ->
            assertTrue(settingsPage.contains("uiSettings.int(FeaturePreferences.PLAYER_BUFFER_$key)"))
        }
        assertTrue(settingsPage.contains("uiSettings.bool(FeaturePreferences.PLAYER_BUFFER_WIFI_ONLY)"))

        val bufferPanel = SettingsUiSource.function("showPlayerBufferDialog")
        val saveFail = bufferPanel.indexOf("if (!saved)")
        val stateUpdate = bufferPanel.indexOf("playerBufferMode = draftMode.value")
        assertTrue(saveFail >= 0 && stateUpdate > saveFail)
        assertTrue(bufferPanel.substring(saveFail, stateUpdate).contains("return"))
        assertFalse(bufferPanel.substring(saveFail, stateUpdate).contains("dismissWithAnimation"))
        val readback = bufferPanel.indexOf("check(preferences.getBoolean(FeaturePreferences.PLAYER_BUFFER_WIFI_ONLY")
        assertTrue(readback >= 0 && readback < saveFail)
        assertTrue(bufferPanel.contains("presentModalDialog(dialog, container, anchor)"))
        assertTrue(bufferPanel.contains("dismissWithAnimation(dialog, container)"))
        assertTrue(bufferPanel.contains("if (!editor.isEnabled) return current"))
        assertTrue(bufferPanel.contains("seconds.isEnabled = draftMode == PlayerBufferMode.CUSTOM"))
        assertTrue(bufferPanel.contains("disk.isEnabled = draftMode == PlayerBufferMode.WHOLE"))
        assertTrue(bufferPanel.contains("wifiOnly.isEnabled = draftMode == PlayerBufferMode.WHOLE"))
        assertFalse(bufferPanel.contains("setWindowAnimations("))
        assertFalse(bufferPanel.contains("registerReceiver("))
    }

    @Test
    fun recommendationAndGuestSettingsStayInEnhancementBrowsingAndPullHostPicks() {
        val enhance = SettingsUiSource.function("enhanceBrowsingCategory")
        val marker = enhance.indexOf("advancedCategoryMarkers[AdvancedSettingsCategory.ENHANCE_BROWSING]")
        val switches = enhance.split("materialSwitch(").drop(1).take(2)
        assertEquals(2, switches.size)
        assertTrue(marker >= 0 && marker < enhance.indexOf("materialSwitch("))
        val settings = listOf("NO_LOGIN_ENABLED" to "no_login_enabled", "INCOGNITO_ENABLED" to "incognito_enabled")
        settings.zip(switches).forEach { (setting, block) ->
            val (key, label) = setting
            assertTrue(block.contains("bindFavoriteSwitch(this, FeaturePreferences.$key, directToggle = true)"))
            val field = if (key == "NO_LOGIN_ENABLED") "noLoginEnabled" else "incognitoEnabled"
            assertTrue(block.contains("isChecked = $field"))
            assertTrue(SettingsUiSource.file("MainActivity").contains("$field = uiSettings.bool(FeaturePreferences.$key)"))
            assertTrue(block.contains("applyModeledToggle(FeaturePreferences.$key, checked, button)"))
            assertTrue(block.contains("R.string.$label)"))
            assertTrue(block.contains("R.string.${label}_tip)"))
            settings.filter { it.first != key }.forEach { (otherKey, _) ->
                assertFalse(block.contains("FeaturePreferences.$otherKey"))
            }
        }
        val diagnostics = SettingsUiSource.file("DiagnosticsActivity")
        assertTrue(diagnostics.contains("DiagnosticFeatureRegistry.titleResId(featureId)"))
        assertEquals(R.string.no_login_enabled, DiagnosticFeatureRegistry.titleResId("guest_browsing"))
        assertEquals(R.string.incognito_enabled, DiagnosticFeatureRegistry.titleResId("incognito_privacy"))

        val purify = SettingsUiSource.function("purifyHomeCategory")
        val labels = listOf(
            "home_recommend_section_pick", "home_recommend_blocked_tids",
            "home_recommend_blocked_authors", "recommendation_blocklist_manage"
        )
        val positions = labels.map { label ->
            val position = enhance.indexOf("R.string.$label")
            assertTrue(label, position >= 0)
            assertFalse(label, purify.contains("R.string.$label"))
            position
        }
        assertEquals(positions.sorted(), positions)
        assertTrue(enhance.contains("showRecommendationBlocklistDialog(anchor = it)"))

        val open = SettingsUiSource.function("showRecommendationBlocklistDialog")
        assertTrue("必须主动查询，不能只读本地存储", open.contains("MineComponentSnapshotQueryClient.query("))
        listOf("SURFACE_SECTION_PICKS", "SURFACE_AUTHOR_PICKS").forEach { surface ->
            assertTrue(
                surface,
                SettingsUiSource.file("RecommendationBlocklistDialogs").contains("MineComponentSnapshotCodec.$surface")
            )
        }
        assertTrue("查询失败要退回本地快照", open.contains("MineComponentSnapshotStore.read("))

        assertFalse(enhance.contains("prefilledBlocked"))
        assertTrue(enhance.contains("prefs().getString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS"))
        assertTrue(enhance.contains("prefs().getString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS"))
        assertFalse(Regex("putString\\(\\s*FeaturePreferences.HOME_RECOMMEND_BLOCKED_\\w+,\\s*recommendationRuleCount")
            .containsMatchIn(enhance))
    }
}
