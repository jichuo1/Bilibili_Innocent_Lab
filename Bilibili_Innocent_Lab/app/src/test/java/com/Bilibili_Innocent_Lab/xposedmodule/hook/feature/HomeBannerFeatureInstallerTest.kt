package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeBannerFeatureInstallerTest {

    @Test
    fun `accepts exact dedicated container even with additional decorations`() {
        assertTrue(
            HomeBannerFeatureInstaller.isDedicatedBannerShell(
                parentClassName = DEDICATED_CONTAINER,
                childClassNames = listOf(BANNER, INDICATOR, "android.view.View"),
                bannerClassName = BANNER
            )
        )
    }

    @Test
    fun `accepts generic container only for banner and indicator children`() {
        assertTrue(
            HomeBannerFeatureInstaller.isDedicatedBannerShell(
                parentClassName = "com.bilibili.magicasakura.widgets.TintConstraintLayout",
                childClassNames = listOf(BANNER, INDICATOR),
                bannerClassName = BANNER
            )
        )
        assertTrue(
            HomeBannerFeatureInstaller.isDedicatedBannerShell(
                parentClassName = "android.widget.FrameLayout",
                childClassNames = listOf(BANNER),
                bannerClassName = BANNER
            )
        )
    }

    @Test
    fun `rejects generic container with business or unknown children`() {
        assertFalse(
            HomeBannerFeatureInstaller.isDedicatedBannerShell(
                parentClassName = "androidx.constraintlayout.widget.ConstraintLayout",
                childClassNames = listOf(BANNER, INDICATOR, "android.widget.TextView"),
                bannerClassName = BANNER
            )
        )
        assertFalse(
            HomeBannerFeatureInstaller.isDedicatedBannerShell(
                parentClassName = "android.widget.FrameLayout",
                childClassNames = listOf(INDICATOR),
                bannerClassName = BANNER
            )
        )
    }

    private companion object {
        const val DEDICATED_CONTAINER =
            "com.bilibili.pegasus.holders.bannerv8.BannerV8Container"
        const val BANNER = "com.bilibili.pegasus.holders.bannerv8.V8Banner"
        const val INDICATOR = "com.bilibili.app.comm.list.widget.swiper.CircleIndicator"
    }
}
