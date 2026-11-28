package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.app.Application
import android.content.ComponentCallbacks
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewTreeObserver
import com.Bilibili_Innocent_Lab.xposedmodule.hook.CommentEmojiAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundController
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundRasterCache
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundScene
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostBottomBarFxConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostDockFitPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostCardMicaField
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedStyle
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassMaterial
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassRenderer
import com.bilab.plugin.api.model.HostThemeSnapshot
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import sun.misc.Unsafe
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class HostUiRuntimeAndPowerPolicyTest {
    private class StubView : View(null) {
        override fun getViewTreeObserver(): ViewTreeObserver? = null
    }

    private open class RecordingApplication : Application() {
        override fun getApplicationContext(): Context = this

        override fun registerComponentCallbacks(callback: ComponentCallbacks) {
            recordedCallbacks?.add(callback)
        }

        override fun unregisterComponentCallbacks(callback: ComponentCallbacks) {
            recordedCallbacks?.remove(callback)
            unregisteredCallbacks?.add(callback)
        }
    }

    private fun allocateApp(
        registered: MutableList<ComponentCallbacks>,
        unregistered: MutableList<ComponentCallbacks>
    ): Application {
        recordedCallbacks = registered
        unregisteredCallbacks = unregistered
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        return unsafe.allocateInstance(RecordingApplication::class.java) as Application
    }

    @Before
    fun setUp() {
        HostMemoryPressureBridge.resetForTests()
        HostUiPowerPolicy.resetForTests()
    }

    @After
    fun tearDown() {
        HostMemoryPressureBridge.resetForTests()
        HostUiPowerPolicy.resetForTests()
        recordedCallbacks = null
        unregisteredCallbacks = null
    }

    private fun registerEphemeralScope(
        stubView: StubView,
        queue: ReferenceQueue<HostSurfaceScope>
    ): WeakReference<HostSurfaceScope> {
        val ephemeral = HostSurfaceScope(stubView, HostChromeColors(dark = false, accent = 0xFF336699.toInt()))
        HostUiSurfaceRuntime.bindSurfaceScope(ephemeral, tag = "ephemeral-scope")
        return WeakReference(ephemeral, queue)
    }

    private fun <T> awaitEnqueuedGc(ref: WeakReference<T>, queue: ReferenceQueue<T>) {
        for (attempt in 0 until 20) {
            if (ref.get() == null) return
            System.gc()
            System.runFinalization()
            if (queue.remove(5L) != null || ref.get() == null) return
        }
    }

    @Test
    fun verifySurfaceRuntimeCircuitBreakerAndPowerPolicy() {
        // HostUiSurfaceRuntime: registerResource delegates and closes idempotently
        try {
            val recorded = ArrayList<HostTrimSeverity>()
            val stageCalls = ArrayList<String>()
            val genericLease = HostUiSurfaceRuntime.registerResource(tag = "ui-generic", onTrim = { recorded += it })
            val tieredLease = HostUiSurfaceRuntime.registerResource(
                tag = "ui-tiered",
                onHidden = { stageCalls += "hidden" },
                onModerate = { stageCalls += "moderate" },
                onCritical = null
            )
            assertEquals(2, HostUiSurfaceRuntime.activeResourceCount())

            @Suppress("DEPRECATION")
            HostMemoryPressureBridge.handleTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            HostMemoryPressureBridge.dispatchModerate()
            HostMemoryPressureBridge.dispatchCritical()
            assertEquals(listOf(HostTrimSeverity.HIDDEN, HostTrimSeverity.MODERATE, HostTrimSeverity.CRITICAL), recorded)
            assertEquals(listOf("hidden", "moderate", "moderate"), stageCalls)

            genericLease.close()
            genericLease.close()
            assertEquals(1, HostUiSurfaceRuntime.activeResourceCount())

            HostMemoryPressureBridge.dispatchModerate()
            assertEquals(3, recorded.size)
            assertEquals(4, stageCalls.size)

            tieredLease.close()
            assertEquals(0, HostUiSurfaceRuntime.activeResourceCount())
        } finally {
            HostMemoryPressureBridge.resetForTests()
        }

        // HostUiSurfaceRuntime: bindSurfaceScope closes on critical without strongly pinning scope
        try {
            val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
            val stubView = unsafe.allocateInstance(StubView::class.java) as StubView
            val scope = HostSurfaceScope(stubView, HostChromeColors(dark = true, accent = 0xFFFF6699.toInt()))
            val lease = HostUiSurfaceRuntime.bindSurfaceScope(scope, tag = "managed-scope")
            assertEquals(1, HostUiSurfaceRuntime.activeResourceCount())

            val closedField = HostSurfaceScope::class.java.getDeclaredField("closed").apply { isAccessible = true }
            assertFalse(closedField.getBoolean(scope))
            HostMemoryPressureBridge.dispatchModerate()
            assertFalse(closedField.getBoolean(scope))
            HostMemoryPressureBridge.dispatchCritical()
            assertTrue(closedField.getBoolean(scope))
            lease.close()
            assertEquals(0, HostUiSurfaceRuntime.activeResourceCount())

            val queue = ReferenceQueue<HostSurfaceScope>()
            val weakRef = registerEphemeralScope(stubView, queue)
            assertEquals(1, HostUiSurfaceRuntime.activeResourceCount())
            awaitEnqueuedGc(weakRef, queue)
            assertNull("bindSurfaceScope must not strongly pin HostSurfaceScope", weakRef.get())
            HostMemoryPressureBridge.dispatchModerate()
            assertEquals(0, HostUiSurfaceRuntime.activeResourceCount())
        } finally {
            HostMemoryPressureBridge.resetForTests()
        }

        // HostUiSurfaceRuntime: snapshotTheme builds read-only HostThemeSnapshot with defaults and custom radius
        run {
            val darkSnapshot = HostUiSurfaceRuntime.snapshotTheme(
                isDark = true,
                surfaceColor = 0xFF141418.toInt(),
                accentColor = 0xFFFB7299.toInt(),
                textPrimaryColor = 0xFFF2F2F7.toInt(),
                textSecondaryColor = 0xFFAEAEB2.toInt(),
                density = 3.0f
            )
            assertEquals(
                HostThemeSnapshot(
                    isDark = true,
                    surfaceColor = 0xFF141418.toInt(),
                    accentColor = 0xFFFB7299.toInt(),
                    textPrimaryColor = 0xFFF2F2F7.toInt(),
                    textSecondaryColor = 0xFFAEAEB2.toInt(),
                    density = 3.0f,
                    cornerRadiusDp = 16f
                ),
                darkSnapshot
            )
            val lightSnapshot = HostUiSurfaceRuntime.snapshotTheme(
                isDark = false,
                surfaceColor = 0xFFFFFFFF.toInt(),
                accentColor = 0xFF00AEEC.toInt(),
                textPrimaryColor = 0xFF18191C.toInt(),
                textSecondaryColor = 0xFF61666D.toInt(),
                density = 2.5f,
                cornerRadiusDp = 24f
            )
            assertFalse(lightSnapshot.isDark)
            assertEquals(0xFFFFFFFF.toInt(), lightSnapshot.surfaceColor)
            assertEquals(0xFF00AEEC.toInt(), lightSnapshot.accentColor)
            assertEquals(0xFF18191C.toInt(), lightSnapshot.textPrimaryColor)
            assertEquals(0xFF61666D.toInt(), lightSnapshot.textSecondaryColor)
            assertEquals(2.5f, lightSnapshot.density, 0f)
            assertEquals(24f, lightSnapshot.cornerRadiusDp, 0f)
        }

        // StageB: circuitBreakerDegradesPrismToFrostedToSoftwareAndRecoversOnlyOutOfCriticalPressure
        run {
            val breaker = HostUiSurfaceRuntime.SurfaceDegradeCircuitBreaker(
                slowFrameThresholdMs = 32L,
                slowFrameTripCount = 3,
                gpuFailureTripCount = 2,
                recoveryHealthyFrames = 6,
                recoveryCooldownMs = 1_000L
            )
            val prismRequested = HostChromeAppearance(HostGlassMaterial.PRISM, HostGlassRenderer.AUTO)
            assertEquals(HostUiSurfaceRuntime.DegradeTier.NONE, breaker.recordFrameDuration(40L, 100L, prismRequested))
            assertEquals(HostUiSurfaceRuntime.DegradeTier.NONE, breaker.recordFrameDuration(40L, 120L, prismRequested))
            assertEquals(HostUiSurfaceRuntime.DegradeTier.NONE, breaker.recordFrameDuration(8L, 140L, prismRequested))
            assertEquals(0, breaker.slowFrameStreak)
            repeat(2) { idx ->
                assertEquals(HostUiSurfaceRuntime.DegradeTier.NONE, breaker.recordFrameDuration(35L, 200L + idx * 16L, prismRequested))
            }
            assertEquals(HostUiSurfaceRuntime.DegradeTier.FROSTED, breaker.recordFrameDuration(35L, 250L, prismRequested))
            assertEquals(HostChromeAppearance(HostGlassMaterial.SOFT, HostGlassRenderer.AUTO), breaker.resolveAppearance(prismRequested))
            assertEquals(HostUiSurfaceRuntime.DegradeTier.FROSTED, breaker.recordGpuFailure(300L, prismRequested))
            assertEquals(HostUiSurfaceRuntime.DegradeTier.SOFTWARE, breaker.recordGpuFailure(320L, prismRequested))
            assertEquals(HostChromeAppearance(HostGlassMaterial.SOFT, HostGlassRenderer.SOFTWARE), breaker.resolveAppearance(prismRequested))
            breaker.recordTrimSeverity(HostTrimSeverity.CRITICAL, 400L)
            assertFalse(breaker.tryRecover(visible = false, hasWindowFocus = true, criticalPressure = false, thermalPressure = 0f, nowMs = 3_000L))
            assertFalse(breaker.tryRecover(visible = true, hasWindowFocus = false, criticalPressure = false, thermalPressure = 0f, nowMs = 3_000L))
            assertFalse(breaker.tryRecover(visible = true, hasWindowFocus = true, criticalPressure = true, thermalPressure = 1f, nowMs = 3_000L))
            assertEquals(HostUiSurfaceRuntime.DegradeTier.SOFTWARE, breaker.currentTier)
            assertTrue(breaker.tryRecover(visible = true, hasWindowFocus = true, criticalPressure = false, thermalPressure = 0f, nowMs = 3_000L))
            assertEquals(HostUiSurfaceRuntime.DegradeTier.NONE, breaker.currentTier)
            assertEquals(prismRequested, breaker.resolveAppearance(prismRequested))
        }

        // StageB: circuitBreakerSoftAutoSkipsRedundantFrostedAndRespondsToThermalPressure
        run {
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

        // StageB: runtimeSurfacePressureObserverAttachesAndReleasesCleanly
        try {
            assertEquals(0, HostUiSurfaceRuntime.activeResourceCount())
            val observed = mutableListOf<HostTrimSeverity>()
            val lease = HostUiSurfaceRuntime.registerSurfacePressureObserver("stage_b_test") { severity, _ -> observed += severity }
            try {
                assertEquals(1, HostUiSurfaceRuntime.activeResourceCount())
                HostMemoryPressureBridge.dispatchCritical()
                assertEquals(listOf(HostTrimSeverity.CRITICAL), observed)
            } finally {
                lease.close()
            }
            assertEquals(0, HostUiSurfaceRuntime.activeResourceCount())
        } finally {
            HostMemoryPressureBridge.resetForTests()
        }

        // StageB: renderTargetBackdropRequiresAndroid17Point2AndNonSoftwareFrostedFloatingWithSource
        run {
            assertFalse(HostSurfaceStyle.supportsRenderTargetBackdrop(sdkInt = 36, sdkIntFull = 3_600_000))
            assertFalse(HostSurfaceStyle.supportsRenderTargetBackdrop(sdkInt = 37, sdkIntFull = 3_700_000))
            assertFalse(HostSurfaceStyle.supportsRenderTargetBackdrop(sdkInt = 37, sdkIntFull = 3_700_001))
            assertTrue(HostSurfaceStyle.supportsRenderTargetBackdrop(sdkInt = 37, sdkIntFull = 3_700_002))
            assertTrue(HostSurfaceStyle.supportsRenderTargetBackdrop(sdkInt = 38, sdkIntFull = 3_800_000))
            val softGpu = HostSurfaceStyle.floating(false, 24f, HostChromeAppearance(HostGlassMaterial.SOFT, HostGlassRenderer.AUTO))
            assertTrue(HostSurfaceStyle.isRenderTargetBackdropEligible(softGpu, hasViewContentSource = true))
            assertFalse(HostSurfaceStyle.isRenderTargetBackdropEligible(softGpu, hasViewContentSource = false))
            assertFalse(HostSurfaceStyle.isRenderTargetBackdropEligible(softGpu.copy(clipBackground = false), hasViewContentSource = true))
            val softCpu = HostSurfaceStyle.floating(false, 24f, HostChromeAppearance(HostGlassMaterial.SOFT, HostGlassRenderer.SOFTWARE))
            assertFalse(HostSurfaceStyle.isRenderTargetBackdropEligible(softCpu, hasViewContentSource = true))
            assertFalse(HostSurfaceStyle.isRenderTargetBackdropEligible(FollowFeedStyle.card(), hasViewContentSource = true))
        }

        // StageB: dockFitAccountsForFontScaleAndRtlSymmetry
        run {
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

        // StageB: prismSurfaceSiblingParentWhitelistExcludesLinearAndScrollContainers
        run {
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

        // HostUiPowerPolicy: power saving switch, retention gating, lazy GPU probe
        try {
            assertTrue(HostUiPowerPolicy.current.retainsGpuFrames)
            assertTrue(HostUiPowerPolicy.current.shortcutsCardRendering)
            HostUiPowerPolicy.configure(false)
            assertFalse(HostUiPowerPolicy.current.retainsGpuFrames)
            assertFalse(HostUiPowerPolicy.current.shortcutsCardRendering)
            HostUiPowerPolicy.configure(true)
            assertTrue(HostUiPowerPolicy.current.retainsGpuFrames)

            assertEquals(32, HostUiPowerPolicy.RETAINED_GPU_FRAME_SDK)
            assertTrue(HostUiPowerPolicy.canRetainGpuFrame(true, 32) { true })
            assertTrue(HostUiPowerPolicy.canRetainGpuFrame(true, 36) { true })
            assertFalse(HostUiPowerPolicy.canRetainGpuFrame(false, 36) { true })
            assertFalse(HostUiPowerPolicy.canRetainGpuFrame(true, 31) { true })
            assertFalse(HostUiPowerPolicy.canRetainGpuFrame(true, 36) { false })

            var probes = 0
            HostUiPowerPolicy.canRetainGpuFrame(false, 36) { probes++; true }
            HostUiPowerPolicy.canRetainGpuFrame(true, 31) { probes++; true }
            assertEquals(0, probes)
            HostUiPowerPolicy.canRetainGpuFrame(true, 32) { probes++; true }
            assertEquals(1, probes)
        } finally {
            HostUiPowerPolicy.resetForTests()
        }
    }

    @Test
    fun verifyMemoryPressureBridgeAndBackgroundTrims() {
        // trim classification distinguishes UI hidden from background and critical levels
        assertEquals(HostMemoryPressureBridge.LEVEL_MODERATE, HostMemoryPressureBridge.classifyTrimLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertEquals(HostMemoryPressureBridge.LEVEL_MODERATE, HostMemoryPressureBridge.classifyTrimLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertEquals(HostMemoryPressureBridge.LEVEL_CRITICAL, HostMemoryPressureBridge.classifyTrimLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertEquals(HostMemoryPressureBridge.LEVEL_NONE, HostMemoryPressureBridge.classifyTrimLevel(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertEquals(HostMemoryPressureBridge.LEVEL_MODERATE, HostMemoryPressureBridge.classifyTrimLevel(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertEquals(HostMemoryPressureBridge.LEVEL_MODERATE, HostMemoryPressureBridge.classifyTrimLevel(ComponentCallbacks2.TRIM_MEMORY_MODERATE))
        assertEquals(HostMemoryPressureBridge.LEVEL_CRITICAL, HostMemoryPressureBridge.classifyTrimLevel(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
        assertEquals(HostMemoryPressureBridge.LEVEL_NONE, HostMemoryPressureBridge.classifyTrimLevel(0))

        // thermal curve maps status progressively to lumen quality pressure
        assertEquals(0f, HostMemoryPressureBridge.thermalQualityPressure(-1), 0f)
        assertEquals(0f, HostMemoryPressureBridge.thermalQualityPressure(0), 0f)
        assertEquals(0f, HostMemoryPressureBridge.thermalQualityPressure(1), 0f)
        assertEquals(0.5f, HostMemoryPressureBridge.thermalQualityPressure(2), 0f)
        assertEquals(0.75f, HostMemoryPressureBridge.thermalQualityPressure(3), 0f)
        assertEquals(1f, HostMemoryPressureBridge.thermalQualityPressure(4), 0f)
        assertEquals(1f, HostMemoryPressureBridge.thermalQualityPressure(5), 0f)
        assertEquals(1f, HostMemoryPressureBridge.thermalQualityPressure(6), 0f)

        // install is idempotent per application and forwards trim levels cleanly
        try {
            val registered = mutableListOf<ComponentCallbacks>()
            val unregistered = mutableListOf<ComponentCallbacks>()
            val app = allocateApp(registered, unregistered)
            var moderateCalls = 0
            var criticalCalls = 0
            HostMemoryPressureBridge.registerTrimCallbacks(
                onModerate = { moderateCalls++ },
                onCritical = { criticalCalls++ }
            )
            val observed = ArrayList<HostTrimSeverity>()
            val lease = HostMemoryPressureBridge.registerResource("install-test") { observed += it }
            try {
                HostMemoryPressureBridge.install(app)
                HostMemoryPressureBridge.install(app)
                assertEquals(2, registered.size)

                fun dispatchTrim(level: Int) {
                    registered.toList().forEach { (it as ComponentCallbacks2).onTrimMemory(level) }
                }

                @Suppress("DEPRECATION")
                dispatchTrim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
                assertEquals(0, moderateCalls)
                assertEquals(0, criticalCalls)
                assertEquals(listOf(HostTrimSeverity.HIDDEN), observed)

                @Suppress("DEPRECATION")
                dispatchTrim(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
                assertEquals(1, moderateCalls)
                assertEquals(0, criticalCalls)
                assertEquals(listOf(HostTrimSeverity.HIDDEN, HostTrimSeverity.MODERATE), observed)

                @Suppress("DEPRECATION")
                dispatchTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
                assertEquals(2, moderateCalls)
                assertEquals(1, criticalCalls)
                assertEquals(listOf(HostTrimSeverity.HIDDEN, HostTrimSeverity.MODERATE, HostTrimSeverity.CRITICAL), observed)

                @Suppress("DEPRECATION")
                dispatchTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
                assertEquals(3, moderateCalls)
                assertEquals(2, criticalCalls)

                registered.toList().forEach { (it as ComponentCallbacks2).onLowMemory() }
                assertEquals(4, moderateCalls)
                assertEquals(3, criticalCalls)
                assertEquals(1, observed.count { it == HostTrimSeverity.HIDDEN })

                HostMemoryPressureBridge.resetForTests()
                assertTrue(registered.isEmpty())
                assertEquals(2, unregistered.size)
            } finally {
                lease.close()
            }
        } finally {
            HostMemoryPressureBridge.resetForTests()
            recordedCallbacks = null
            unregisteredCallbacks = null
        }

        // soft floating surface disables refraction and allows render target backdrop only with view content source
        run {
            val soft = HostSurfaceStyle.floating(dark = false, radiusDp = 24f, appearance = HostChromeAppearance(HostGlassMaterial.SOFT))
            assertFalse(soft.sampling.refractionEnabled)
            assertEquals(1f, soft.backdropOpacity, 0f)
            assertTrue(HostSurfaceStyle.isRenderTargetBackdropEligible(soft, hasViewContentSource = true))
            assertFalse(HostSurfaceStyle.isRenderTargetBackdropEligible(soft, hasViewContentSource = false))

            val liquid = HostSurfaceStyle.floating(dark = true, radiusDp = 24f, appearance = HostChromeAppearance(HostGlassMaterial.LIQUID))
            assertTrue(liquid.sampling.refractionEnabled)
            assertFalse(HostSurfaceStyle.isRenderTargetBackdropEligible(liquid, hasViewContentSource = true))
        }

        // emoji adapter caches can be cleared safely under memory pressure
        try {
            CommentEmojiAdapter.clearCaches()
            val res = CommentEmojiAdapter.resolve(commentItem = null, raw = "plain", spans = emptyList())
            assertTrue(res.spanMatches.isEmpty())
            HostMemoryPressureBridge.dispatchModerate()
        } finally {
            HostMemoryPressureBridge.resetForTests()
        }

        // background executors allow core thread timeout with positive keepAliveTime
        for (executor in listOf(
            HostBackgroundScene.worker,
            HostBackgroundController.worker,
            HostBackgroundRasterCache.worker,
            HostCardMicaField.worker,
            HostGarbTheme.worker
        )) {
            assertTrue("Executor should allow core thread timeout", executor.allowsCoreThreadTimeOut())
            assertTrue("Executor keepAliveTime must be > 0", executor.getKeepAliveTime(TimeUnit.MILLISECONDS) > 0L)
        }

        // background scene pressure trim drops offscreen ready tiles and clears hidden scenes
        try {
            val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
            val scene = unsafe.allocateInstance(HostBackgroundScene::class.java) as HostBackgroundScene
            var onscreen = true
            val config = HostBackgroundConfig(preset = HostBackgroundPreset.AURORA)
            fun setSceneField(name: String, value: Any?) {
                HostBackgroundScene::class.java.getDeclaredField(name).apply { isAccessible = true }.set(scene, value)
            }
            val lock = Any()
            val version = AtomicLong()
            val wanted = (0L..3L).map { HostBackgroundScene.Key(config, false, 100, it) }
            val ready = HashMap<HostBackgroundScene.Key, Bitmap>().apply {
                for (key in wanted) put(key, unsafe.allocateInstance(Bitmap::class.java) as Bitmap)
            }
            setSceneField("config", config)
            setSceneField("lock", lock)
            setSceneField("first", 1L)
            setSceneField("last", 2L)
            setSceneField("wanted", wanted)
            setSceneField("ready", ready)
            setSceneField("version", version)
            setSceneField("isOnscreen", { onscreen })

            val scenesLock = HostBackgroundScene::class.java.getDeclaredField("scenesLock").apply { isAccessible = true }.get(null)
            @Suppress("UNCHECKED_CAST")
            val scenes = HostBackgroundScene::class.java.getDeclaredField("scenes").apply { isAccessible = true }.get(null)
                as MutableMap<HostBackgroundScene, Unit>
            synchronized(scenesLock) { scenes[scene] = Unit }
            try {
                assertEquals(4, scene.retainedTileCount)
                HostMemoryPressureBridge.dispatchModerate()
                assertEquals(2, scene.retainedTileCount)
                assertTrue(ready.keys.all { it.band in 1L..2L })
                assertEquals(1L, version.get())

                onscreen = false
                HostMemoryPressureBridge.dispatchCritical()
                assertEquals(0, scene.retainedTileCount)
                assertEquals(2L, version.get())
            } finally {
                synchronized(scenesLock) { scenes.remove(scene) }
            }
        } finally {
            HostMemoryPressureBridge.resetForTests()
        }
    }

    private companion object {
        var recordedCallbacks: MutableList<ComponentCallbacks>? = null
        var unregisteredCallbacks: MutableList<ComponentCallbacks>? = null
    }
}
