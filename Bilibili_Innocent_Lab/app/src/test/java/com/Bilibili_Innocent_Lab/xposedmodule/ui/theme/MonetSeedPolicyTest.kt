package com.Bilibili_Innocent_Lab.xposedmodule.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取色种子来源链（2026-09-22 用户报告"取色设计失效、全界面没有颜色"）。
 *
 * 旧实现是"壁纸取色 ?: 中性灰"。`WallpaperManager.getWallpaperColors(FLAG_SYSTEM)`
 * 对动态壁纸与部分 OEM ROM 返回 null，于是整套调色板由灰种子推出——chroma≈0，
 * 全部强调色去饱和；而调色板在 Activity 内缓存，一次失败会锁到下次重建为止。
 */
class MonetSeedPolicyTest {

    private val grey = 0xFF656565.toInt()

    fun wallpaperWinsWhenAvailableSoSuccessfulExtractionIsUnchanged() {
        assertEquals(0xFF2277EE.toInt(), MonetSeedPolicy.resolve(
            wallpaper = 0xFF2277EE.toInt(),
            systemAccent = 0xFFAA3366.toInt(),
            cached = 0xFF11CC44.toInt(),
            fallback = grey
        ))
    }

    fun platformAccentCoversWallpaperExtractionReturningNull() {
        assertEquals(0xFFAA3366.toInt(), MonetSeedPolicy.resolve(
            wallpaper = null,
            systemAccent = 0xFFAA3366.toInt(),
            cached = 0xFF11CC44.toInt(),
            fallback = grey
        ))
    }

    fun theCachedSeedCoversBothLiveSourcesBeingUnavailable() {
        assertEquals(0xFF11CC44.toInt(), MonetSeedPolicy.resolve(
            wallpaper = null, systemAccent = null, cached = 0xFF11CC44.toInt(), fallback = grey
        ))
    }

    fun greyIsOnlyTheLastResort() {
        assertEquals(grey, MonetSeedPolicy.resolve(null, null, null, grey))
    }

    fun onlyLiveSourcesAreWrittenBackToTheCache() {
        assertTrue(MonetSeedPolicy.shouldRemember(live = 0xFF2277EE.toInt(), cached = null))
        assertTrue(MonetSeedPolicy.shouldRemember(0xFF2277EE.toInt(), cached = 0xFF11CC44.toInt()))
        // 没有实时来源时不得写缓存——灰兜底一旦写进去，取色恢复后仍会被它压着。
        assertFalse(MonetSeedPolicy.shouldRemember(live = null, cached = null))
        assertFalse(MonetSeedPolicy.shouldRemember(live = null, cached = 0xFF11CC44.toInt()))
        // 值没变就不必写盘。
        assertFalse(MonetSeedPolicy.shouldRemember(0xFF2277EE.toInt(), cached = 0xFF2277EE.toInt()))
    }

    /**
     * 钉住"为什么灰色兜底等于没有颜色"，免得以后有人把灰色挪回第一顺位。
     *
     * 判据不引 m3color：那个库是 Java 21 字节码，JVM 单测（17）加载不了它的类。
     * 三通道相等即 HCT chroma 恒为 0，TonalSpot 由它推出的整套调色板必然全灰。
     */
    fun theGreyFallbackIsAchromaticBySeedConstruction() {
        val r = (grey shr 16) and 0xFF
        val g = (grey shr 8) and 0xFF
        val b = grey and 0xFF
        assertTrue("兜底种子三通道相等 ⇒ chroma≡0 ⇒ 推导出的强调色全部去饱和", r == g && g == b)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (MonetSeedPolicyTest) -> Unit) {
            val s = MonetSeedPolicyTest()
            block(s)
        }
        runIsolated { s -> s.wallpaperWinsWhenAvailableSoSuccessfulExtractionIsUnchanged() }
        runIsolated { s -> s.platformAccentCoversWallpaperExtractionReturningNull() }
        runIsolated { s -> s.theCachedSeedCoversBothLiveSourcesBeingUnavailable() }
        runIsolated { s -> s.greyIsOnlyTheLastResort() }
        runIsolated { s -> s.onlyLiveSourcesAreWrittenBackToTheCache() }
        runIsolated { s -> s.theGreyFallbackIsAchromaticBySeedConstruction() }
    }
}
