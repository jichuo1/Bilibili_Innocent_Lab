package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime

import android.content.res.Configuration
import androidx.annotation.MainThread
import com.highcapable.betterandroid.ui.component.activity.AppViewsActivity
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SkinId
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.UiTokens
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors

/** 当前 Activity 创建皮肤令牌时使用的配置摘要，不持有 Resources 或 Context。 */
internal data class SkinConfigurationSnapshot(
    val nightMode: Int,
    val orientation: Int,
    val densityDpi: Int,
    val fontScale: Float,
    val localeTags: String
)

/** M0 可观测状态；后续 Liquid renderer 会在不暴露 View 的前提下扩展渲染诊断。 */
internal data class SkinSessionDiagnostics(
    val requestedSkin: SkinId,
    val effectiveSkin: SkinId,
    val fallbackReason: String?
)

/**
 * Activity 级不可变皮肤快照。
 *
 * M0 仅装配现有 Material You 调色板；不创建 Window、View、Bitmap、Shader 或后台线程。
 */
internal class ActivitySkinSession private constructor(
    val requestedSkin: SkinId,
    val effectiveSkin: SkinId,
    val materialPalette: MonetColors,
    val tokens: UiTokens,
    val configuration: SkinConfigurationSnapshot,
    val diagnostics: SkinSessionDiagnostics
) : AutoCloseable {

    var isClosed: Boolean = false
        private set

    @MainThread
    override fun close() {
        if (isClosed) return
        isClosed = true
    }

    companion object {
        @MainThread
        fun create(
            activity: AppViewsActivity,
            materialPalette: MonetColors
        ): ActivitySkinSession {
            val requestedSkin = SkinRepository.resolveRequestedSkin(activity)
            val effectiveSkin = SkinId.MATERIAL_YOU
            val configuration = activity.resources.configuration
            return ActivitySkinSession(
                requestedSkin = requestedSkin,
                effectiveSkin = effectiveSkin,
                materialPalette = materialPalette,
                tokens = MaterialYouTokenResolver.resolve(activity, materialPalette),
                configuration = configuration.toSkinSnapshot(),
                diagnostics = SkinSessionDiagnostics(
                    requestedSkin = requestedSkin,
                    effectiveSkin = effectiveSkin,
                    fallbackReason = requestedSkin.takeIf { it != effectiveSkin }?.let {
                        "renderer_not_available_in_m0"
                    }
                )
            )
        }
    }
}

private fun Configuration.toSkinSnapshot() = SkinConfigurationSnapshot(
    nightMode = uiMode and Configuration.UI_MODE_NIGHT_MASK,
    orientation = orientation,
    densityDpi = densityDpi,
    fontScale = fontScale,
    localeTags = locales.toLanguageTags()
)
