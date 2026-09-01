package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailAppPromotionFeatureInstallerTest {

    @Test
    fun `all known renderer routes satisfy the constrained multiversion contract`() {
        listOf("getUpperNestView", "getUpperAdView", "getUpperHDView").forEach { name ->
            assertTrue(matches(method(name)))
        }
    }

    @Test
    fun `unknown and structurally incomplete methods fail open`() {
        assertFalse(matches(method("getUnknownView")))
        assertFalse(
            matches(
                Fixture::class.java.getDeclaredMethod(
                    "wrongReturn",
                    FrameLayout::class.java,
                    Bridge::class.java,
                    Config::class.java
                )
            )
        )
        assertFalse(
            matches(
                Fixture::class.java.getDeclaredMethod(
                    "missingContainer",
                    Bridge::class.java,
                    Config::class.java
                )
            )
        )
    }

    @Test
    fun `legacy preference key remains stable across the label rename`() {
        assertEquals(
            "hide_video_detail_app_promotion",
            FeaturePreferences.HIDE_VIDEO_DETAIL_APP_PROMOTION
        )
    }

    @Test
    fun `warmup never initializes an untouched host lazy`() {
        var initialized = false
        val untouched = lazy {
            initialized = true
            VideoDetail()
        }

        assertNull(initializedLazyValueOfType(listOf(untouched), VideoDetail::class.java))
        assertFalse(initialized)
    }

    @Test
    fun `warmup selects an already initialized matching host lazy`() {
        val unrelated = lazy { Any() }.also { it.value }
        val expected = VideoDetail()
        val initialized = lazy { expected }.also { it.value }

        assertSame(
            expected,
            initializedLazyValueOfType(
                listOf(unrelated, initialized),
                VideoDetail::class.java
            )
        )
    }

    private fun method(name: String) = Fixture::class.java.getDeclaredMethod(
        name,
        FrameLayout::class.java,
        Bridge::class.java,
        Config::class.java
    )

    private fun matches(method: java.lang.reflect.Method) = matchesDetailPromotionRenderMethod(
        method = method,
        callbackClass = Callback::class.java,
        bridgeClass = Bridge::class.java,
        configClass = Config::class.java
    )

    private interface Callback

    private interface Bridge

    private class Config

    private class VideoDetail

    @Suppress("UNUSED_PARAMETER")
    private class Fixture {
        fun getUpperNestView(
            container: FrameLayout,
            bridge: Bridge,
            config: Config
        ): Callback = error("signature fixture")

        fun getUpperAdView(
            container: FrameLayout,
            bridge: Bridge,
            config: Config
        ): Callback = error("signature fixture")

        fun getUpperHDView(
            container: FrameLayout,
            bridge: Bridge,
            config: Config
        ): Callback = error("signature fixture")

        fun getUnknownView(
            container: FrameLayout,
            bridge: Bridge,
            config: Config
        ): Callback = error("signature fixture")

        fun wrongReturn(
            container: FrameLayout,
            bridge: Bridge,
            config: Config
        ): String = error("signature fixture")

        fun missingContainer(
            bridge: Bridge,
            config: Config
        ): Callback = error("signature fixture")
    }
}
