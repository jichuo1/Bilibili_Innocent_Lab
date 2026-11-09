package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.content.Context
import android.content.res.Configuration
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.lumen.coacervation.engine.model.LumenPalette
import kotlin.math.pow

/** 宿主换肤独立于系统 uiMode；栏的材质与前景必须消费同一份宿主主题。 */
internal class HostChromeTheme(private val context: Context) {
    private val nightTheme = KavaMemberLookup.classOrNull(context.classLoader, "com.bilibili.lib.ui.util.NightTheme")
        ?.let { KavaMemberLookup.inheritedMethodOrNull(it, "isNightTheme", Context::class.java) }
    private val hostColor = KavaMemberLookup.classOrNull(context.classLoader, "com.bilibili.magicasakura.utils.ThemeUtils")
        ?.let { KavaMemberLookup.inheritedMethodOrNull(it, "getColorById", Context::class.java, Int::class.javaPrimitiveType!!) }
    private val accentId = context.resources.getIdentifier("theme_color_secondary", "color", context.packageName)
    private val pageId = context.resources.getIdentifier("Ga1", "color", context.packageName)
    private var previous: HostChromeColors? = null

    /** 逐帧只读换肤值；反射方法和资源 id 在绑定时解析一次，不跑壁纸取色/HCT。 */
    fun read(): HostChromeColors {
        val hostDark = runCatching { nightTheme?.invoke(null, context) as? Boolean }.getOrNull()
        val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val accent = if (accentId == 0) null else {
            runCatching { hostColor?.invoke(null, context, accentId) as? Int }.getOrNull()
                ?: runCatching { context.getColor(accentId) }.getOrNull()
        }
        return HostChromeColors.resolve(hostDark, systemDark, accent, HostGarbTheme.accent(context), previous)
            .also { previous = it }
    }

    /** 宿主页面底色 `Ga1`（khome 信息流背景）；读不到或与当前明暗不符时返回 null。 */
    fun pageBase(dark: Boolean): Int? {
        if (pageId == 0) return null
        val color = runCatching { hostColor?.invoke(null, context, pageId) as? Int }.getOrNull()
            ?: runCatching { context.getColor(pageId) }.getOrNull() ?: return null
        return HostChromeColors.pageBase(color, dark)
    }
}

internal data class HostChromeColors(val dark: Boolean, val accent: Int, val garbAccent: Int? = null) {
    private val resolvedPalette by lazy(LazyThreadSafetyMode.NONE) { resolvePalette() }
    fun palette(): LumenPalette = resolvedPalette

    /** 与凝光引擎的 modern 调色板一致：中性深浅表面 + 宿主强调色。 */
    private fun resolvePalette(): LumenPalette {
        fun channel(shift: Int): Double {
            val value = (accent ushr shift and 255) / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        val luminance = .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
        val base = LumenPalette.modern(accent,
            if (luminance > .179) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), accent, accent, dark)
        val tint = garbAccent ?: return base
        val surface = HostGarbColors.blend(base.surface, tint, .07f)
        val readable = HostGarbColors.readable(tint, surface, dark)
        return base.copy(surface = surface,
            surfaceVariant = HostGarbColors.blend(base.surfaceVariant, tint, .09f),
            primary = readable, secondary = readable, tertiary = readable,
            onPrimary = if (HostGarbColors.contrast(readable, 0xFF000000.toInt()) >= 4.5)
                0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    }

    companion object {
        /** A resource that ignores the host's own night switch must not paint a light band in dark mode. */
        fun pageBase(color: Int, dark: Boolean): Int? {
            val luminance = (.2126 * (color ushr 16 and 255) + .7152 * (color ushr 8 and 255) + .0722 * (color and 255)) / 255.0
            return (color or 0xFF000000.toInt()).takeIf { if (dark) luminance < .5 else luminance >= .5 }
        }

        fun resolve(hostDark: Boolean?, systemDark: Boolean, accent: Int?, garbAccent: Int? = null,
                    previous: HostChromeColors? = null): HostChromeColors {
            val dark = hostDark ?: systemDark
            val resolvedAccent = garbAccent ?: accent ?: 0xFFFF6699.toInt()
            // Poll the host every time, but allocate colors and their lazy palette only on change.
            return previous?.takeIf { it.dark == dark && it.accent == resolvedAccent && it.garbAccent == garbAccent }
                ?: HostChromeColors(dark, resolvedAccent, garbAccent)
        }
    }
}
