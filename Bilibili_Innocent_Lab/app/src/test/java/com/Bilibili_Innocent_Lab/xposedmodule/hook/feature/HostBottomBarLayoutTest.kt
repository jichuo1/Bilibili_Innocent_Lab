package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostBottomBarFxConfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostBottomBarLayoutTest {
    fun iconOnlyNarrowsTheBarAndCompactMakesTheReductionStronger() {
        val normal = HostBottomBarFxConfig()
        for (mode in listOf(normal, normal.copy(compact = true))) {
            assertEquals(16f, mode.horizontalMarginDp(393f, 5), 0f)
        }
        val icons = normal.copy(iconOnly = true)
        assertEquals((393f - 32f) * 0.88f, 393f - icons.horizontalMarginDp(393f, 5) * 2f, 0.001f)
        val combined = normal.copy(compact = true, iconOnly = true)
        val margin = combined.horizontalMarginDp(393f, 5)
        assertEquals((393f - 32f) * 0.84f, 393f - margin * 2f, 0.001f)
        assertTrue(margin > icons.horizontalMarginDp(393f, 5))
        assertEquals(44f, combined.heightDp, 0f)
        assertEquals(0f, combined.copy(liquidGlass = false).horizontalMarginDp(393f, 5), 0f)
    }

    fun narrowScreensPreserveTabSpaceWithoutExpandingPastTheOriginalWidth() {
        val combined = HostBottomBarFxConfig(compact = true, iconOnly = true)
        val minimumWidth = 5 * 44f + 8f
        for (mode in listOf(combined, combined.copy(compact = false))) {
            for (parentWidth in listOf(260f, 280f, 320f)) {
                val width = parentWidth - mode.horizontalMarginDp(parentWidth, 5) * 2f
                assertTrue(width >= minimumWidth)
                assertTrue(width <= parentWidth - 32f)
            }
            assertEquals(16f, mode.horizontalMarginDp(200f, 5), 0f)
        }
    }

    fun capsuleLayoutModesReduceHeightIndividuallyAndTogether() {
        val normal = HostBottomBarFxConfig(liquidGlass = true, touchGlow = false)
        val compact = normal.copy(compact = true)
        val icons = normal.copy(iconOnly = true)
        val combined = compact.copy(iconOnly = true)
        assertEquals(64f, normal.heightDp, 0f)
        assertTrue(compact.heightDp < normal.heightDp)
        assertTrue(icons.heightDp < compact.heightDp)
        assertTrue(combined.heightDp < icons.heightDp)
        assertTrue(combined.heightDp >= 44f)
        for (mode in listOf(normal, compact, icons, combined)) {
            assertEquals(mode.heightDp, mode.copy(touchGlow = true).heightDp, 0f)
        }
    }

    fun staleCapsuleOptionsCannotResizeTheOfficialBarOrInstallLayoutHooks() {
        for (compact in listOf(false, true)) for (icons in listOf(false, true)) {
            val config = HostBottomBarFxConfig(liquidGlass = false, touchGlow = false, compact = compact, iconOnly = icons)
            assertEquals(64f, config.heightDp, 0f)
            assertEquals(0f, config.horizontalMarginDp(393f, 5), 0f)
            val environment = HookEnvironment("tv.danmaku.bili", javaClass.classLoader,
                com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry(javaClass.classLoader!!),
                TestHookRegistrar, { _, _ -> }, { _, _ -> }, { _, _ -> })
            assertEquals(FeatureInstallResult.Skipped("disabled"),
                HostBottomBarFxFeatureInstaller(false, false, null, compact, icons).install(environment))
        }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostBottomBarLayoutTest) -> Unit) {
            val s = HostBottomBarLayoutTest()
            block(s)
        }
        runIsolated { s -> s.iconOnlyNarrowsTheBarAndCompactMakesTheReductionStronger() }
        runIsolated { s -> s.narrowScreensPreserveTabSpaceWithoutExpandingPastTheOriginalWidth() }
        runIsolated { s -> s.capsuleLayoutModesReduceHeightIndividuallyAndTogether() }
        runIsolated { s -> s.staleCapsuleOptionsCannotResizeTheOfficialBarOrInstallLayoutHooks() }
    }
}
