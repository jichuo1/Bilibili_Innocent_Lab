package com.Bilibili_Innocent_Lab.xposedmodule.ui.theme

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion
import com.kyant.m3color.dynamiccolor.MaterialDynamicColors
import com.kyant.m3color.hct.Hct
import com.kyant.m3color.scheme.SchemeTonalSpot

/**
 * Material You（莫奈）动态取色工具。
 *
 * 使用 GitHub 开源项目 Kyant0/m3color（Google material-color-utilities 的 Java 端口）
 * 从壁纸提取种子色，并生成完整的 Material 3 动态调色板（HCT 色彩空间 + TonalSpot 方案）。
 *
 * @property primary 主色（ARGB）
 * @property onPrimary 主色上的文字色
 * @property secondary 次色
 * @property tertiary 第三色
 * @property surface 表面色
 * @property background 背景色
 * @property surfaceVariant 表面变体色（用于强调区域）
 */
class MonetColors(
    val primary: Int,
    val onPrimary: Int,
    val secondary: Int,
    val tertiary: Int,
    val surface: Int,
    val background: Int,
    val surfaceVariant: Int
) {
    companion object {
        /** 回退种子色（取壁纸失败时使用） */
        private const val FALLBACK_SEED = 0xFF656565.toInt()

        /** 从系统壁纸提取种子色并生成 Monet 调色板 */
        fun fromWallpaper(context: Context): MonetColors {
            val isDark = context.isSystemInDarkMode()
            val seed = extractSeedColor(context) ?: FALLBACK_SEED
            return fromSeed(seed, isDark)
        }

        /** 从指定种子色生成 Monet 调色板 */
        fun fromSeed(seedArgb: Int, isDark: Boolean): MonetColors {
            val hct = Hct.fromInt(seedArgb)
            val scheme = SchemeTonalSpot(hct, isDark, 0.0)
            val dynamicColors = MaterialDynamicColors()
            return MonetColors(
                primary = dynamicColors.primary().getArgb(scheme),
                onPrimary = dynamicColors.onPrimary().getArgb(scheme),
                secondary = dynamicColors.secondary().getArgb(scheme),
                tertiary = dynamicColors.tertiary().getArgb(scheme),
                surface = dynamicColors.surface().getArgb(scheme),
                background = dynamicColors.background().getArgb(scheme),
                surfaceVariant = dynamicColors.surfaceVariant().getArgb(scheme)
            )
        }

        /** Android 12+ 从系统壁纸提取种子色，低版本返回 null */
        private fun extractSeedColor(context: Context): Int? {
            if (AndroidVersion.isLessThan(AndroidVersion.S)) return null
            return try {
                val manager = WallpaperManager.getInstance(context)
                val colors = manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                colors?.primaryColor?.toArgb()
            } catch (t: Throwable) {
                null
            }
        }
    }
}

/** 判断系统是否处于夜间模式 */
private fun Context.isSystemInDarkMode(): Boolean {
    val mask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return mask == Configuration.UI_MODE_NIGHT_YES
}
