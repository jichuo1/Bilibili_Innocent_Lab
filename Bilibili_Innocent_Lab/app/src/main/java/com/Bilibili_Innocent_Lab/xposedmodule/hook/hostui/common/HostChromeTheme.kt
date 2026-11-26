package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.lumen.coacervation.engine.model.LumenPalette
import kotlin.math.pow

/** 宿主换肤独立于系统 uiMode；栏的材质与前景必须消费同一份宿主主题。 */
internal class HostChromeTheme(
    private val context: Context,
    private val cacheTtlMs: Long = THEME_CACHE_TTL_MS,
    private val clock: () -> Long = DEFAULT_CLOCK
) {
    private val nightTheme = KavaMemberLookup.classOrNull(context.classLoader, "com.bilibili.lib.ui.util.NightTheme")
        ?.let { KavaMemberLookup.inheritedMethodOrNull(it, "isNightTheme", Context::class.java) }
    private val hostColor = KavaMemberLookup.classOrNull(context.classLoader, "com.bilibili.magicasakura.utils.ThemeUtils")
        ?.let { KavaMemberLookup.inheritedMethodOrNull(it, "getColorById", Context::class.java, Int::class.javaPrimitiveType!!) }
    private val accentId = context.resources.getIdentifier("theme_color_secondary", "color", context.packageName)
    private val pageId = context.resources.getIdentifier("Ga1", "color", context.packageName)
    private var previous: HostChromeColors? = null
    private var cachedAt = 0L
    private var cachedUiMode = 0
    private var hasCachedColors = false
    private var pageBaseValue: Int? = null
    private var pageBaseDark = false
    private var pageBaseUiMode = 0
    private var pageBaseCachedAt = 0L
    private var hasPageBaseCache = false

    fun invalidate() {
        hasCachedColors = false
        hasPageBaseCache = false
    }

    /** 逐帧只读换肤值；反射方法和资源 id 在绑定时解析一次，不跑壁纸取色/HCT，并在 TTL 内复用同配置读数。 */
    fun read(): HostChromeColors {
        val now = clock()
        val uiMode = context.resources.configuration?.uiMode ?: 0
        val cacheActive = isCacheActive(now)
        if (cacheActive && hasCachedColors && uiMode == cachedUiMode && now >= cachedAt && now - cachedAt < cacheTtlMs) {
            return previous ?: readUncached(uiMode)
        }
        val prev = previous
        val resolved = readUncached(uiMode)
        if (uiMode != cachedUiMode || resolved != prev) {
            // 主题或系统夜间配置变化时同步失效页底色缓存，避免跨明暗短暂沿用旧 Ga1。
            hasPageBaseCache = false
        }
        cachedUiMode = uiMode
        cachedAt = now
        hasCachedColors = cacheActive
        return resolved
    }

    private fun readUncached(uiMode: Int): HostChromeColors {
        val hostDark = runCatching { nightTheme?.invoke(null, context) as? Boolean }.getOrNull()
        val systemDark = uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
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
        val now = clock()
        val uiMode = context.resources.configuration?.uiMode ?: 0
        val cacheActive = isCacheActive(now)
        if (cacheActive && hasPageBaseCache && pageBaseDark == dark && pageBaseUiMode == uiMode &&
            now >= pageBaseCachedAt && now - pageBaseCachedAt < cacheTtlMs) {
            return pageBaseValue
        }
        val color = runCatching { hostColor?.invoke(null, context, pageId) as? Int }.getOrNull()
            ?: runCatching { context.getColor(pageId) }.getOrNull()
        val resolved = color?.let { HostChromeColors.pageBase(it, dark) }
        pageBaseValue = resolved
        pageBaseDark = dark
        pageBaseUiMode = uiMode
        pageBaseCachedAt = now
        hasPageBaseCache = cacheActive
        return resolved
    }

    // JVM 纯单测下未打桩的 SystemClock.uptimeMillis() 恒返回 0；默认时钟 <= 0 时旁路缓存以保持测试实时性。
    private fun isCacheActive(now: Long): Boolean = cacheTtlMs > 0L && (clock !== DEFAULT_CLOCK || now > 0L)

    companion object {
        internal const val THEME_CACHE_TTL_MS = 250L
        private val DEFAULT_CLOCK: () -> Long = { runCatching { SystemClock.uptimeMillis() }.getOrDefault(0L) }
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
