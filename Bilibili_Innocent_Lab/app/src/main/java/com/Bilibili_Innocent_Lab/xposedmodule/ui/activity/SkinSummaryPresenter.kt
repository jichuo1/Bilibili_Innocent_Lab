package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import androidx.annotation.StringRes
import io.github.jichuo1.meguviz.android.extensions.toast
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SkinId
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinRepository
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundImportFailure
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundMode
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundStore

/**
 * 外观相关的摘要文案与失败回退：应用语言、Liquid 背景。
 *
 * 皮肤/背景的**核心侧回退**由 renderer 自己完成，这里只负责事后提示与重建界面，
 * 不做降级决策。当前生效的后端与降级原因只进本地诊断，不在一级设置页展示。
 *
 * 文件名以 `Presenter.kt` 结尾 = 落进 `SettingsUiSource.VOLUME_SUFFIXES` 的扫描集。
 */

internal fun MainActivity.currentAppLanguageSummary(): String {
    val language = currentAppLanguage()
    return getString(R.string.app_language_current, getString(language.labelRes))
}

internal fun MainActivity.currentLiquidBackgroundSummary(): String {
    val state = LiquidBackgroundStore.read(applicationContext)
    if (state.config.mode == LiquidBackgroundMode.AUTOMATIC) {
        return getString(R.string.liquid_background_summary_automatic)
    }
    if (!state.assetPresent) {
        return getString(R.string.liquid_background_summary_unavailable)
    }
    return getString(R.string.liquid_background_summary_active)
}

@StringRes
internal fun MainActivity.liquidBackgroundFailureText(reason: LiquidBackgroundImportFailure): Int =
    when (reason) {
        LiquidBackgroundImportFailure.READ_FAILED -> R.string.liquid_background_read_failed
        LiquidBackgroundImportFailure.ACCESS_DENIED -> R.string.liquid_background_access_denied
        LiquidBackgroundImportFailure.FILE_TOO_LARGE -> R.string.liquid_background_file_too_large
        LiquidBackgroundImportFailure.UNSUPPORTED_IMAGE -> R.string.liquid_background_unsupported
        LiquidBackgroundImportFailure.DIMENSIONS_TOO_LARGE ->
            R.string.liquid_background_dimensions_too_large
        LiquidBackgroundImportFailure.ENCODE_FAILED -> R.string.liquid_background_encode_failed
        LiquidBackgroundImportFailure.STORAGE_FAILED -> R.string.liquid_background_storage_failed
    }

/** renderer 已完成核心侧回退后，当前 Activity 只负责提示并重建 Material 界面。 */
internal fun MainActivity.handleSkinRendererFailure() {
    runOnUiThread {
        if (skinFailureHandled || isFinishing || isDestroyed) return@runOnUiThread
        skinFailureHandled = true
        if (SkinRepository.resolveRequestedSkin(applicationContext) == SkinId.MATERIAL_YOU) {
            toast(getString(R.string.skin_start_failed))
            recreate()
        } else {
            toast(getString(R.string.skin_recovery_save_failed))
        }
    }
}

internal fun MainActivity.finishLiquidBackgroundChange() {
    liquidBackgroundSummaryView?.text = currentLiquidBackgroundSummary()
    val dialog = liquidBackgroundDialog
    val container = liquidBackgroundDialogContainer
    if (dialog != null && container != null && dialog.isShowing) {
        dismissWithAnimation(dialog, container) {
            if (!isFinishing && !isDestroyed) recreate()
        }
    } else if (!isFinishing && !isDestroyed) recreate()
}
