package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostOpusChrome
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostBottomBarFxConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostDockFitPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostCardMaterialScope
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HostComposeTopTabCentering
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HostKhomeFloatPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HostKhomeTopFloat
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostDockClearance
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostMemoryPressureBridge
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostPrismSurface
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceStyle
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostTrimSeverity
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostUiSurfaceRuntime
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedStyle
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopBarFxController
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopFusionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopTabFadePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassMaterial
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassRenderer
import com.Bilibili_Innocent_Lab.xposedmodule.ui.PredictiveBack
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.subtractWindowInsets
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StageBHostUiOemTest {

    @Before
    fun setUp() {
        HostMemoryPressureBridge.resetForTests()
    }

    @After
    fun tearDown() {
        HostMemoryPressureBridge.resetForTests()
    }

    @Test
    fun circuitBreakerDegradesPrismToFrostedToSoftwareAndRecoversOnlyOutOfCriticalPressure() {
        val breaker = HostUiSurfaceRuntime.SurfaceDegradeCircuitBreaker(
            slowFrameThresholdMs = 32L,
            slowFrameTripCount = 3,
            gpuFailureTripCount = 2,
            recoveryHealthyFrames = 6,
            recoveryCooldownMs = 1_000L
        )
        val prismRequested = HostChromeAppearance(HostGlassMaterial.PRISM, HostGlassRenderer.AUTO)

        // 连续 3 帧慢帧触发 PRISM -> FROSTED；中间插入快帧会清零慢帧计数。
        assertEquals(HostUiSurfaceRuntime.DegradeTier.NONE, breaker.recordFrameDuration(40L, 100L, prismRequested))
        assertEquals(HostUiSurfaceRuntime.DegradeTier.NONE, breaker.recordFrameDuration(40L, 120L, prismRequested))
        assertEquals(HostUiSurfaceRuntime.DegradeTier.NONE, breaker.recordFrameDuration(8L, 140L, prismRequested))
        assertEquals(0, breaker.slowFrameStreak)

        repeat(2) { idx ->
            assertEquals(
                HostUiSurfaceRuntime.DegradeTier.NONE,
                breaker.recordFrameDuration(35L, 200L + idx * 16L, prismRequested)
            )
        }
        assertEquals(HostUiSurfaceRuntime.DegradeTier.FROSTED, breaker.recordFrameDuration(35L, 250L, prismRequested))
        assertEquals(
            HostChromeAppearance(HostGlassMaterial.SOFT, HostGlassRenderer.AUTO),
            breaker.resolveAppearance(prismRequested)
        )

        // 再连续 GPU 失败触发 FROSTED -> SOFTWARE。
        assertEquals(HostUiSurfaceRuntime.DegradeTier.FROSTED, breaker.recordGpuFailure(300L, prismRequested))
        assertEquals(HostUiSurfaceRuntime.DegradeTier.SOFTWARE, breaker.recordGpuFailure(320L, prismRequested))
        assertEquals(
            HostChromeAppearance(HostGlassMaterial.SOFT, HostGlassRenderer.SOFTWARE),
            breaker.resolveAppearance(prismRequested)
        )

        // 后台/失焦或处于 critical 压力时不允许自动恢复。
        breaker.recordTrimSeverity(HostTrimSeverity.CRITICAL, 400L)
        assertFalse(breaker.tryRecover(visible = false, hasWindowFocus = true, criticalPressure = false, thermalPressure = 0f, nowMs = 3_000L))
        assertFalse(breaker.tryRecover(visible = true, hasWindowFocus = false, criticalPressure = false, thermalPressure = 0f, nowMs = 3_000L))
        assertFalse(breaker.tryRecover(visible = true, hasWindowFocus = true, criticalPressure = true, thermalPressure = 1f, nowMs = 3_000L))
        assertEquals(HostUiSurfaceRuntime.DegradeTier.SOFTWARE, breaker.currentTier)

        // 重新可见、重获焦点且脱离 critical 压力并满足冷却时间后恢复。
        assertTrue(breaker.tryRecover(visible = true, hasWindowFocus = true, criticalPressure = false, thermalPressure = 0f, nowMs = 3_000L))
        assertEquals(HostUiSurfaceRuntime.DegradeTier.NONE, breaker.currentTier)
        assertEquals(prismRequested, breaker.resolveAppearance(prismRequested))
    }

    @Test
    fun circuitBreakerSoftAutoSkipsRedundantFrostedAndRespondsToThermalPressure() {
        val breaker = HostUiSurfaceRuntime.SurfaceDegradeCircuitBreaker(gpuFailureTripCount = 2)
        val softAuto = HostChromeAppearance(HostGlassMaterial.SOFT, HostGlassRenderer.AUTO)

        assertEquals(HostUiSurfaceRuntime.DegradeTier.NONE, breaker.recordGpuFailure(10L, softAuto))
        assertEquals(HostUiSurfaceRuntime.DegradeTier.SOFTWARE, breaker.recordGpuFailure(20L, softAuto))
        assertEquals(HostGlassRenderer.SOFTWARE, breaker.resolveAppearance(softAuto).effectiveRenderer)

        breaker.reset()
        assertEquals(HostUiSurfaceRuntime.DegradeTier.FROSTED, breaker.recordThermalPressure(0.75f))
        assertEquals(HostUiSurfaceRuntime.DegradeTier.SOFTWARE, breaker.recordThermalPressure(1.0f))
        assertTrue(HostUiSurfaceRuntime.isCriticalGraphicsPressure(thermalPressure = 1.0f))
        assertEquals(HostUiSurfaceRuntime.DegradeTier.NONE, breaker.recordThermalPressure(0.5f))
    }

    @Test
    fun runtimeSurfacePressureObserverAttachesAndReleasesCleanly() {
        assertEquals(0, HostUiSurfaceRuntime.activeResourceCount())
        val observed = mutableListOf<HostTrimSeverity>()
        val lease = HostUiSurfaceRuntime.registerSurfacePressureObserver("stage_b_test") { severity, _ ->
            observed += severity
        }
        try {
            assertEquals(1, HostUiSurfaceRuntime.activeResourceCount())
            HostMemoryPressureBridge.dispatchCritical()
            assertEquals(listOf(HostTrimSeverity.CRITICAL), observed)
        } finally {
            lease.close()
        }
        assertEquals(0, HostUiSurfaceRuntime.activeResourceCount())
    }

    @Test
    fun renderTargetBackdropRequiresAndroid17Point2AndNonSoftwareFrostedFloatingWithSource() {
        assertFalse(HostSurfaceStyle.supportsRenderTargetBackdrop(sdkInt = 36, sdkIntFull = 3_600_000))
        assertFalse(HostSurfaceStyle.supportsRenderTargetBackdrop(sdkInt = 37, sdkIntFull = 3_700_000))
        assertFalse(HostSurfaceStyle.supportsRenderTargetBackdrop(sdkInt = 37, sdkIntFull = 3_700_001))
        assertTrue(HostSurfaceStyle.supportsRenderTargetBackdrop(sdkInt = 37, sdkIntFull = 3_700_002))
        assertTrue(HostSurfaceStyle.supportsRenderTargetBackdrop(sdkInt = 38, sdkIntFull = 3_800_000))

        val softGpu = HostSurfaceStyle.floating(
            dark = false,
            radiusDp = 24f,
            appearance = HostChromeAppearance(HostGlassMaterial.SOFT, HostGlassRenderer.AUTO)
        )
        assertTrue(HostSurfaceStyle.isRenderTargetBackdropEligible(softGpu, hasViewContentSource = true))
        assertFalse(HostSurfaceStyle.isRenderTargetBackdropEligible(softGpu, hasViewContentSource = false))
        assertFalse(HostSurfaceStyle.isRenderTargetBackdropEligible(softGpu.copy(clipBackground = false), hasViewContentSource = true))

        val softCpu = HostSurfaceStyle.floating(
            dark = false,
            radiusDp = 24f,
            appearance = HostChromeAppearance(HostGlassMaterial.SOFT, HostGlassRenderer.SOFTWARE)
        )
        assertFalse(HostSurfaceStyle.isRenderTargetBackdropEligible(softCpu, hasViewContentSource = true))

        // FollowFeed 等静态卡片绝不能误触发 RenderTargetBackdrop。
        assertFalse(HostSurfaceStyle.isRenderTargetBackdropEligible(FollowFeedStyle.card(), hasViewContentSource = true))
    }

    @Test
    fun topBarStatusInsetTrustsNonNullInsetsZeroAndUsesDisplayCutoutMax() {
        // 分屏下屏或自由小窗下 insets != null 且 top == 0，不能回退到 status_bar_height。
        assertEquals(
            0,
            HostTopBarFxController.selectStatusBarInset(
                hasInsets = true,
                statusBarTop = 0,
                displayCutoutTop = 0,
                legacySystemWindowInsetTop = 0,
                resourceStatusBarHeight = 96,
                density = 3f,
                isAtLeastApi30 = true
            )
        )

        // 挖孔高于状态栏时取 maxOf(statusBars.top, displayCutout.top)。
        assertEquals(
            128,
            HostTopBarFxController.selectStatusBarInset(
                hasInsets = true,
                statusBarTop = 72,
                displayCutoutTop = 128,
                legacySystemWindowInsetTop = 72,
                resourceStatusBarHeight = 96,
                density = 3f,
                isAtLeastApi30 = true
            )
        )

        // 仅当 insets == null 时按系统资源 / 24dp 保守回退。
        assertEquals(
            96,
            HostTopBarFxController.selectStatusBarInset(
                hasInsets = false,
                statusBarTop = 0,
                displayCutoutTop = 0,
                resourceStatusBarHeight = 96,
                density = 3f
            )
        )
        assertEquals(
            72,
            HostTopBarFxController.selectStatusBarInset(
                hasInsets = false,
                statusBarTop = 0,
                displayCutoutTop = 0,
                resourceStatusBarHeight = 0,
                density = 3f
            )
        )
    }

    @Test
    fun topFusionFadeShrinksAndTracksLiveDensity() {
        val tall = HostTopFusionPolicy.bandHeight(240)
        val short = HostTopFusionPolicy.bandHeight(120)
        assertTrue(short < tall)

        val endNormalDensity = HostTopFusionPolicy.fadeEndFraction(160, density = 2f, statusBarInset = 60, bandHeight = tall)
        val endHighDensity = HostTopFusionPolicy.fadeEndFraction(160, density = 4f, statusBarInset = 60, bandHeight = tall)
        assertTrue(endHighDensity < endNormalDensity)
    }

    @Test
    fun cardMaterialNightTracksActualThemeFlipWhileRespectingExplicitTestBaseline() {
        // 真实主题未翻转时保持既有状态；仅当主题实际翻转才刷新。
        assertTrue(HostCardMaterialScope.resolveEffectiveNight(currentNight = true, previousThemeDark = false, latestThemeDark = false))
        assertTrue(HostCardMaterialScope.resolveEffectiveNight(currentNight = false, previousThemeDark = false, latestThemeDark = true))
        assertFalse(HostCardMaterialScope.resolveEffectiveNight(currentNight = true, previousThemeDark = true, latestThemeDark = false))
    }

    @Test
    fun khomeChurnTrackerIgnoresTargetChangesAndTripsOnlyOnHostRollbacks() {
        val tracker = HostKhomeTopFloat.ChurnTracker(limit = HostKhomeFloatPolicy.CHURN_LIMIT, windowMs = HostKhomeFloatPolicy.CHURN_WINDOW_MS)

        // 首次应用与合法目标偏移量 / 页签变化不计入抖动。
        assertFalse(tracker.record(targetOffset = 180, pageSignature = 11L, wroteLayout = true, nowMs = 100L))
        assertFalse(tracker.record(targetOffset = 210, pageSignature = 11L, wroteLayout = true, nowMs = 120L))
        assertFalse(tracker.record(targetOffset = 210, pageSignature = 22L, wroteLayout = true, nowMs = 140L))
        assertEquals(0, tracker.count)

        // 超过时间窗外的偶发写入不会累积误判。
        assertFalse(tracker.record(targetOffset = 210, pageSignature = 22L, wroteLayout = true, nowMs = 200L))
        assertEquals(1, tracker.count)
        assertFalse(tracker.record(targetOffset = 210, pageSignature = 22L, wroteLayout = true, nowMs = 2_000L))
        assertEquals(1, tracker.count)

        // 同一时间窗内目标偏移未变却连续回滚，超过阈值触发停用。
        for (step in 2..HostKhomeFloatPolicy.CHURN_LIMIT) {
            assertFalse(tracker.record(targetOffset = 210, pageSignature = 22L, wroteLayout = true, nowMs = 2_010L + step * 10L))
        }
        assertTrue(tracker.record(targetOffset = 210, pageSignature = 22L, wroteLayout = true, nowMs = 2_150L))
    }

    @Test
    fun dockFitAccountsForFontScaleAndRtlSymmetry() {
        val labeled = HostBottomBarFxConfig(iconOnly = false)
        val iconOnly = HostBottomBarFxConfig(iconOnly = true)
        val asymmetric = HostDockClearance(sideLeftDp = 24f, sideRightDp = 8f)

        val ltrClear = HostDockFitPolicy.directionalSideClearance(asymmetric, isRtl = false)
        val rtlClear = HostDockFitPolicy.directionalSideClearance(asymmetric, isRtl = true)
        assertEquals(24f, ltrClear.first, 0f)
        assertEquals(8f, ltrClear.second, 0f)
        assertEquals(8f, rtlClear.first, 0f)
        assertEquals(24f, rtlClear.second, 0f)

        val normalScale = HostDockFitPolicy.fit(labeled, parentWidthDp = 393f, tabCount = 5, clearance = asymmetric, fontScale = 1f, isRtl = false)
        val largeScale = HostDockFitPolicy.fit(labeled, parentWidthDp = 393f, tabCount = 5, clearance = asymmetric, fontScale = 1.4f, isRtl = true)
        assertEquals(normalScale.marginDp, largeScale.marginStartDp, 0.001f)
        assertEquals(largeScale.marginStartDp, largeScale.marginEndDp, 0f)
        assertTrue(largeScale.heightDp > normalScale.heightDp)

        val iconDefault = HostDockFitPolicy.fit(iconOnly, parentWidthDp = 393f, tabCount = 5, clearance = HostDockClearance(), fontScale = 1f)
        val iconLargeFont = HostDockFitPolicy.fit(iconOnly, parentWidthDp = 393f, tabCount = 5, clearance = HostDockClearance(), fontScale = 1.4f)
        assertEquals(iconDefault.heightDp, iconLargeFont.heightDp, 0f)
    }

    @Test
    fun composeTopTabCenteringHandlesRtlFadeDirectionAndRightAnchoredCentering() {
        val ltr = HostComposeTopTabCentering.resolveFadeLayout(
            tabLeft = 0,
            tabWidth = 800,
            safeLeft = 0,
            safeRight = 760,
            scrollValue = 0,
            scrollMax = 300,
            density = 2f,
            fontScale = 1f,
            isRtl = false
        )
        assertEquals(0f, ltr.start, 0f)
        assertEquals(760f - HostTopTabFadePolicy.ACTION_GAP_DP * 2f, ltr.end, 0.01f)
        assertEquals(0f, ltr.leading, 0f)
        assertEquals(1f, ltr.trailing, 0f)

        val rtl = HostComposeTopTabCentering.resolveFadeLayout(
            tabLeft = 0,
            tabWidth = 800,
            safeLeft = 120,
            safeRight = 800,
            scrollValue = 0,
            scrollMax = 300,
            density = 2f,
            fontScale = 1.25f,
            isRtl = true
        )
        assertEquals(120f + HostTopTabFadePolicy.ACTION_GAP_DP * 2f * 1.25f, rtl.start, 0.01f)
        assertEquals(800f, rtl.end, 0.01f)
        assertEquals(1f, rtl.leading, 0f)
        assertEquals(0f, rtl.trailing, 0f)

        // RTL 下左侧 actions 宽度从 100 动画扩到 200 时，tabs 右边界锚定在 1000，当前视口为 [200..1000]。
        val localX = HostComposeTopTabCentering.resolveCenteredOffset(
            count = 2,
            dockWidth = 1000,
            tabLeft = 100,
            tabWidth = 900,
            safeLeft = 100,
            safeRight = 1000,
            viewportWidth = 800,
            contentWidth = 200,
            isRtl = true
        )
        // [200..1000] 内本地 x = 200 时，dock 全局区间正好为 [400..600]（居中于 500）。
        assertEquals(200, localX)
    }

    @Test
    fun prismSurfaceSiblingParentWhitelistExcludesLinearAndScrollContainers() {
        assertTrue(HostPrismSurface.isWhitelistedSiblingParentNames(listOf("android.widget.FrameLayout", "android.view.ViewGroup")))
        assertTrue(HostPrismSurface.isWhitelistedSiblingParentNames(listOf("android.widget.RelativeLayout", "android.view.ViewGroup")))
        assertTrue(HostPrismSurface.isWhitelistedSiblingParentNames(listOf("androidx.coordinatorlayout.widget.CoordinatorLayout", "android.view.ViewGroup")))
        assertTrue(HostPrismSurface.isWhitelistedSiblingParentNames(listOf("androidx.constraintlayout.widget.ConstraintLayout", "android.view.ViewGroup")))

        assertFalse(HostPrismSurface.isWhitelistedSiblingParentNames(listOf("android.widget.LinearLayout", "android.view.ViewGroup")))
        assertFalse(HostPrismSurface.isWhitelistedSiblingParentNames(listOf("com.example.CustomLinearLayout", "android.widget.LinearLayout", "android.view.ViewGroup")))
        assertFalse(HostPrismSurface.isWhitelistedSiblingParentNames(listOf("android.widget.ScrollView", "android.widget.FrameLayout", "android.view.ViewGroup")))
        assertFalse(HostPrismSurface.isWhitelistedSiblingParentNames(listOf("androidx.core.widget.NestedScrollView", "android.widget.FrameLayout", "android.view.ViewGroup")))
        assertFalse(HostPrismSurface.isWhitelistedSiblingParentNames(listOf("com.example.FlowWrapGroup", "android.view.ViewGroup")))
    }

    @Test
    fun opusChromeStatusBarAppearanceRestoresOriginalOnCloseOnlyWhenOwned() {
        val state = HostOpusChrome.StatusBarAppearanceState()
        state.captureOriginalIfUnset(currentLight = false)
        state.markApplied(light = true)
        assertEquals(false, state.consumeRestoreTarget(currentLight = true))
        assertNull(state.consumeRestoreTarget(currentLight = true))

        // 若宿主自己已经改写回深色，则不覆盖宿主后续状态。
        state.captureOriginalIfUnset(currentLight = false)
        state.markApplied(light = true)
        assertNull(state.consumeRestoreTarget(currentLight = false))
    }

    @Test
    fun predictiveBackShortCircuitsOnApi36AndHigher() {
        assertFalse(PredictiveBack.shouldApplyRuntimeToggle(sdkInt = 33))
        assertTrue(PredictiveBack.shouldApplyRuntimeToggle(sdkInt = 34))
        assertTrue(PredictiveBack.shouldApplyRuntimeToggle(sdkInt = 35))
        assertFalse(PredictiveBack.shouldApplyRuntimeToggle(sdkInt = 36))
        assertFalse(PredictiveBack.shouldApplyRuntimeToggle(sdkInt = 37))
    }

    @Test
    fun adaptiveWindowSubtractInsetsClampsSafelyForPreApi30() {
        assertEquals(1080 to 1710, subtractWindowInsets(1080, 1920, left = 0, top = 84, right = 0, bottom = 126))
        assertEquals(1 to 1, subtractWindowInsets(320, 480, left = 400, top = 500, right = 0, bottom = 0))
        assertEquals(720 to 1280, subtractWindowInsets(720, 1280, left = -10, top = 0, right = 0, bottom = 0))
    }

    @Test
    fun monetColorsContrastSanitizationAndApi27WallpaperSupportGate() {
        assertEquals(0.5, MonetColors.sanitizeContrastLevel(0.5f), 0.0001)
        assertEquals(1.0, MonetColors.sanitizeContrastLevel(3.0f), 0.0001)
        assertEquals(-1.0, MonetColors.sanitizeContrastLevel(-2.0f), 0.0001)
        assertEquals(0.0, MonetColors.sanitizeContrastLevel(Float.NaN), 0.0001)

        assertFalse(MonetColors.canQueryWallpaperColors(sdkInt = 26, wallpaperSupported = true))
        assertTrue(MonetColors.canQueryWallpaperColors(sdkInt = 27, wallpaperSupported = true))
        assertTrue(MonetColors.canQueryWallpaperColors(sdkInt = 30, wallpaperSupported = true))
        assertTrue(MonetColors.canQueryWallpaperColors(sdkInt = 35, wallpaperSupported = true))
        assertFalse(MonetColors.canQueryWallpaperColors(sdkInt = 29, wallpaperSupported = false))
    }
}
