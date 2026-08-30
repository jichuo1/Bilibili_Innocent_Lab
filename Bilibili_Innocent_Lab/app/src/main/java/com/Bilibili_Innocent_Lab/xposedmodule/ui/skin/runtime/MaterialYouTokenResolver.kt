package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime

import android.content.Context
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.UiColorTokens
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.UiShapeTokens
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.UiTokens
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors

/** 把现有 Monet 调色板和资源色一一包装为语义令牌，不改变当前 Material You 视觉。 */
internal object MaterialYouTokenResolver {

    fun resolve(context: Context, palette: MonetColors): UiTokens {
        val textPrimary = context.getColor(R.color.colorTextGray)
        val textSecondary = context.getColor(R.color.colorTextDark)
        val surfaceIsDark = ColorUtils.calculateLuminance(palette.surface) < 0.5
        return UiTokens(
            colors = UiColorTokens(
                background = palette.background,
                surface = palette.surface,
                surfaceVariant = palette.surfaceVariant,
                primary = palette.primary,
                secondary = palette.secondary,
                tertiary = palette.tertiary,
                onAccent = palette.onPrimary,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                textDisabled = ColorUtils.setAlphaComponent(textSecondary, 0x61),
                outline = ColorUtils.setAlphaComponent(textSecondary, 0x38),
                divider = ColorUtils.setAlphaComponent(textSecondary, 0x20),
                ripple = ColorUtils.setAlphaComponent(textPrimary, 0x30),
                warning = if (surfaceIsDark) 0xFFFFB95C.toInt() else 0xFF7A4F00.toInt(),
                error = if (surfaceIsDark) 0xFFFFB4AB.toInt() else 0xFFBA1A1A.toInt(),
                scrim = 0x52000000,
                systemBarBackground = palette.background,
                useLightSystemBarIcons = ColorUtils.calculateLuminance(palette.background) < 0.5
            ),
            shapes = UiShapeTokens(
                cardRadiusDp = 15f,
                modalRadiusDp = 28f,
                controlRadiusDp = 14f,
                chipRadiusDp = 20f,
                filledButtonRadiusDp = 20f,
                textButtonRippleRadiusDp = 14f,
                collapsedMotionRadiusDp = 15f
            )
        )
    }
}
