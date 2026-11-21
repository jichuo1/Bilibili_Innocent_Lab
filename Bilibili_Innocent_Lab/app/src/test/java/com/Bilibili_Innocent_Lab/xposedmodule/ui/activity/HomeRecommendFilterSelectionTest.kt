package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.SharedPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostConfigState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostInstallChainState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeBootstrapEvidence
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchMode
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRuntimeProfile
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchScopeProbe
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootDisplayState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.ScopeRequestResult
import java.lang.reflect.Proxy
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRecommendFilterSelectionTest {
    fun `comic filter starts off and saves independently of other categories`() {
        val key = FeaturePreferences.REMOVE_HOME_RECOMMEND_COMIC
        val draft = HomeRecommendFilterDraft(emptyMap())
        assertFalse(draft[key])
        draft[key] = true
        assertEquals(mapOf(key to true), draft.changedValues())
        assertEquals(1, draft.selectedCount())
    }

    fun `catalog preserves the nine independent preference keys in display order`() {
        assertEquals(listOf(
            FeaturePreferences.REMOVE_HOME_RECOMMEND_ADS,
            FeaturePreferences.REMOVE_HOME_RECOMMEND_PICTURES,
            FeaturePreferences.REMOVE_HOME_RECOMMEND_GAME_PROMOTIONS,
            FeaturePreferences.REMOVE_HOME_RECOMMEND_LIVE,
            FeaturePreferences.REMOVE_HOME_RECOMMEND_PGC,
            FeaturePreferences.REMOVE_HOME_RECOMMEND_COMIC,
            FeaturePreferences.REMOVE_HOME_RECOMMEND_SPECIAL_CARDS,
            FeaturePreferences.REMOVE_HOME_RECOMMEND_COURSES,
            FeaturePreferences.REMOVE_HOME_RECOMMEND_LARGE
        ), HomeRecommendFilterCatalog.preferenceKeys)
        assertEquals(9, HomeRecommendFilterCatalog.preferenceKeys.distinct().size)
    }

    fun `opening and canceling a draft leaves the initial settings untouched`() {
        val initial = mutableMapOf(
            FeaturePreferences.REMOVE_HOME_RECOMMEND_ADS to true,
            FeaturePreferences.REMOVE_HOME_RECOMMEND_CM_V2 to true,
            FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_ENABLED to true
        )
        val before = initial.toMap()
        val draft = HomeRecommendFilterDraft(initial)
        assertEquals(1, draft.selectedCount())
        assertTrue(draft.changedValues().isEmpty())
        draft.selectAll()
        draft.clear()
        assertEquals(before, initial)
        assertEquals(1, HomeRecommendFilterDraft(initial).selectedCount())
    }

    fun `select all and clear produce only changes to panel keys`() {
        val ads = FeaturePreferences.REMOVE_HOME_RECOMMEND_ADS
        val initial = mapOf(ads to true, FeaturePreferences.REMOVE_HOME_RECOMMEND_VERTICAL to true)
        val draft = HomeRecommendFilterDraft(initial)
        draft.selectAll()
        assertEquals(9, draft.selectedCount())
        assertEquals(HomeRecommendFilterCatalog.preferenceKeys.filterNot { it == ads }
            .associateWith { true }, draft.changedValues())
        draft.clear()
        assertEquals(0, draft.selectedCount())
        assertEquals(mapOf(ads to false), draft.changedValues())
        assertFalse(draft.changedValues().containsKey(FeaturePreferences.REMOVE_HOME_RECOMMEND_VERTICAL))
    }

    fun `toggling back to the original value does not write a preference`() {
        val key = FeaturePreferences.REMOVE_HOME_RECOMMEND_PICTURES
        val draft = HomeRecommendFilterDraft(emptyMap())
        draft[key] = true
        assertEquals(mapOf(key to true), draft.changedValues())
        draft[key] = false
        assertTrue(draft.changedValues().isEmpty())
    }

    fun `the disguised ad switch cannot be changed through this panel`() {
        HomeRecommendFilterDraft(emptyMap())[FeaturePreferences.REMOVE_HOME_RECOMMEND_CM_V2] = true
    }

    fun `a saved change set is detached from further draft edits`() {
        val key = FeaturePreferences.REMOVE_HOME_RECOMMEND_LIVE
        val draft = HomeRecommendFilterDraft(emptyMap())
        draft[key] = true
        val saved = draft.changedValues()
        draft.clear()
        assertEquals(mapOf(key to true), saved)
        assertTrue(draft.changedValues().isEmpty())
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HomeRecommendFilterSelectionTest) -> Unit) {
            val s = HomeRecommendFilterSelectionTest()
            block(s)
        }
        runIsolated { s -> s.`comic filter starts off and saves independently of other categories`() }
        runIsolated { s -> s.`catalog preserves the nine independent preference keys in display order`() }
        runIsolated { s -> s.`opening and canceling a draft leaves the initial settings untouched`() }
        runIsolated { s -> s.`select all and clear produce only changes to panel keys`() }
        runIsolated { s -> s.`toggling back to the original value does not write a preference`() }
        runIsolated { s -> try { s.`the disguised ad switch cannot be changed through this panel`(); org.junit.Assert.fail("Expected IllegalArgumentException") } catch (_: IllegalArgumentException) {} }
        runIsolated { s -> s.`a saved change set is detached from further draft edits`() }
    
        HostBackgroundSettingsStoreCases().verifyAllCases()
        LspatchActivationReceiptTrackerCases().verifyAllCases()
        NavigationGlowContinuityCases().verifyAllCases()
        NavigationGlowOrientationCases().verifyAllCases()
        NoRootActionPolicyCases().verifyAllCases()
    }
}

private class HostBackgroundSettingsStoreCases {
    private val previous = mapOf<String, Any>(
        FeaturePreferences.HOST_BACKGROUND_PRESET to "custom",
        FeaturePreferences.HOST_BACKGROUND_ASSET to "11111111-1111-1111-1111-111111111111",
        FeaturePreferences.HOST_BACKGROUND_BLUR to 12,
        FeaturePreferences.HOST_BACKGROUND_SATURATION to 80,
        FeaturePreferences.HOST_BACKGROUND_VEIL to 30,
        "unrelated" to "keep"
    )
    private val draft = HostBackgroundConfig(HostBackgroundPreset.CUSTOM, 22, 110, 45,
        "22222222-2222-2222-2222-222222222222")

    fun failedDiskCommitRestoresTheActualPreviousAssetAndEveryBackgroundSetting() {
        val preferences = MemoryPreferences(previous, listOf(false, true))
        assertFalse(HostBackgroundSettingsStore.save(preferences.instance, draft))
        assertEquals(previous, preferences.values)
        assertEquals(2, preferences.commits)
    }

    fun failedRollbackDiskWriteStillRestoresMemoryAndNeverReportsSuccess() {
        val preferences = MemoryPreferences(previous, listOf(false, false))
        assertFalse(HostBackgroundSettingsStore.save(preferences.instance, draft))
        assertEquals(previous, preferences.values)
        assertEquals(2, preferences.commits)
    }

    fun exceptionAfterMemoryMutationRestoresMissingKeysAsMissingAndPreservesOtherSettings() {
        val original = mapOf<String, Any>(FeaturePreferences.HOST_BACKGROUND_PRESET to "off", "unrelated" to 7)
        val preferences = MemoryPreferences(original, throwAfterFirstWrite = true)
        assertFalse(HostBackgroundSettingsStore.save(preferences.instance, draft))
        assertEquals(original, preferences.values)
    }

    fun successfulSaveNormalizesValuesAndPreservesUnrelatedSettingsWithoutRollback() {
        val preferences = MemoryPreferences(previous)
        assertTrue(HostBackgroundSettingsStore.save(preferences.instance,
            draft.copy(blur = 99, saturation = -1, veil = 100, asset = "../invalid")))
        assertEquals("custom", preferences.values[FeaturePreferences.HOST_BACKGROUND_PRESET])
        assertEquals(60, preferences.values[FeaturePreferences.HOST_BACKGROUND_BLUR])
        assertEquals(0, preferences.values[FeaturePreferences.HOST_BACKGROUND_SATURATION])
        assertEquals(90, preferences.values[FeaturePreferences.HOST_BACKGROUND_VEIL])
        assertEquals("", preferences.values[FeaturePreferences.HOST_BACKGROUND_ASSET])
        assertEquals("keep", preferences.values["unrelated"])
        assertEquals(1, preferences.commits)
    }

    /** 与平台一致：先改内存，再返回磁盘失败或抛错，不能用“失败不改内存”的假实现。 */
    private class MemoryPreferences(original: Map<String, Any>,
        private val results: List<Boolean> = listOf(true), private val throwAfterFirstWrite: Boolean = false) {
        val values = original.toMutableMap()
        var commits = 0
        val instance = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) {
                "getAll" -> values.toMap()
                "edit" -> editor()
                else -> error(method.name)
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString", "putInt" -> { pending[args!![0] as String] = args[1]; proxy }
                    "remove" -> { pending[args!![0] as String] = null; proxy }
                    "commit" -> {
                        for ((key, value) in pending) if (value == null) values.remove(key) else values[key] = value
                        commits++
                        if (throwAfterFirstWrite && commits == 1) error("Injected failure after memory write")
                        results.getOrElse(commits - 1) { true }
                    }
                    else -> error(method.name)
                }
            } as SharedPreferences.Editor
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HostBackgroundSettingsStoreCases) -> Unit) {
            val s = HostBackgroundSettingsStoreCases()
            block(s)
        }
        runIsolated { s -> s.failedDiskCommitRestoresTheActualPreviousAssetAndEveryBackgroundSetting() }
        runIsolated { s -> s.failedRollbackDiskWriteStillRestoresMemoryAndNeverReportsSuccess() }
        runIsolated { s -> s.exceptionAfterMemoryMutationRestoresMissingKeysAsMissingAndPreservesOtherSettings() }
        runIsolated { s -> s.successfulSaveNormalizesValuesAndPreservesUnrelatedSettingsWithoutRollback() }
    }
}

private class LspatchActivationReceiptTrackerCases {
    fun `same connection coalesces requests and retains a matching receipt`() {
        val tracker = LspatchActivationReceiptTracker()
        tracker.startSession()
        val request = requireNotNull(tracker.begin(7L))
        assertNull(tracker.begin(7L))

        val receipt = receipt()
        assertTrue(tracker.accept(request, 7L, receipt))
        assertSame(receipt, tracker.receiptFor(7L))
        assertNull(tracker.begin(7L))
    }

    fun `cleared or superseded request cannot restore stale host evidence`() {
        val tracker = LspatchActivationReceiptTracker()
        tracker.startSession()
        val oldRequest = requireNotNull(tracker.begin(7L))
        tracker.clearConnectionEvidence()
        assertFalse(tracker.accept(oldRequest, 7L, receipt()))
        assertNull(tracker.receiptFor(7L))

        val newRequest = requireNotNull(tracker.begin(8L))
        assertFalse(tracker.accept(newRequest, 9L, receipt()))
        assertNull(tracker.receiptFor(8L))
        assertTrue(tracker.begin(9L) != null)
    }

    fun `missing receipt remains retryable on a later lifecycle entry`() {
        val tracker = LspatchActivationReceiptTracker()
        tracker.startSession()
        val request = requireNotNull(tracker.begin(7L))
        assertTrue(tracker.accept(request, 7L, null))
        assertNull(tracker.receiptFor(7L))
        assertTrue(tracker.begin(7L) != null)
    }

    fun `pause and resume on the same connection requires a fresh request`() {
        val tracker = LspatchActivationReceiptTracker()
        tracker.startSession()
        val oldRequest = requireNotNull(tracker.begin(7L))
        assertTrue(tracker.accept(oldRequest, 7L, receipt()))

        tracker.endSession()
        tracker.startSession()
        assertFalse(tracker.accept(oldRequest, 7L, receipt()))

        val newRequest = requireNotNull(tracker.begin(7L))
        assertTrue(newRequest.token != oldRequest.token)
        assertTrue(tracker.accept(newRequest, 7L, null))
        assertNull(tracker.receiptFor(7L))
    }

    fun `service can arrive after resume while the foreground session stays active`() {
        val tracker = LspatchActivationReceiptTracker()
        tracker.startSession()
        assertTrue(tracker.begin(7L) != null)
    }

    fun `disconnect clears connection evidence but allows a later reconnect`() {
        val tracker = LspatchActivationReceiptTracker()
        tracker.startSession()
        val first = requireNotNull(tracker.begin(7L))
        assertTrue(tracker.accept(first, 7L, receipt()))

        tracker.clearConnectionEvidence()
        assertNull(tracker.receiptFor(7L))
        val reconnected = requireNotNull(tracker.begin(8L))
        val reconnectedReceipt = receipt()
        assertTrue(tracker.accept(reconnected, 8L, reconnectedReceipt))
        assertSame(reconnectedReceipt, tracker.receiptFor(8L))
    }

    private fun receipt() = HostRuntimeDiagnosticsSnapshot(
        capturedAtEpochMs = 1L,
        processName = "tv.danmaku.bili",
        features = emptyList(),
        bootstrap = HostRuntimeBootstrapEvidence(
            bootstrapReached = true,
            configState = HostConfigState.ACCEPTED,
            configGeneration = 1L,
            installChainState = HostInstallChainState.COMPLETED
        )
    )

    fun verifyAllCases() {
        fun runIsolated(block: (LspatchActivationReceiptTrackerCases) -> Unit) {
            val s = LspatchActivationReceiptTrackerCases()
            block(s)
        }
        runIsolated { s -> s.`same connection coalesces requests and retains a matching receipt`() }
        runIsolated { s -> s.`cleared or superseded request cannot restore stale host evidence`() }
        runIsolated { s -> s.`missing receipt remains retryable on a later lifecycle entry`() }
        runIsolated { s -> s.`pause and resume on the same connection requires a fresh request`() }
        runIsolated { s -> s.`service can arrive after resume while the foreground session stays active`() }
        runIsolated { s -> s.`disconnect clears connection evidence but allows a later reconnect`() }
    }
}

private class NavigationGlowContinuityCases {
    private val radius = 192f
    private fun config(continuous: Boolean) = GlowConfig.create(
        density = 3f, maxTravelPx = 36f, travelEpsPx = 1.5f,
        velocityRefPxPerSec = 720f, edgeBandPx = 84f, continuousEdgePile = continuous
    )
    private fun frame() = GlowFrame().apply {
        press = 1f; offsetY = -36f; centerX = 480f; centerY = 96f
        boundsWidth = 960f; boundsHeight = 192f; cornerRadius = 96f; pileRoomPx = 120f
    }

    fun crossingTheEdgeDoesNotShrinkBeforePileStarts() {
        val state = GlowState(); val f = frame(); val cfg = config(true)
        repeat(240) { state.update(f, 1f / 120, radius, 72, cfg) }
        val insideAlpha = state.shape.alphaUnit
        for (y in 96 downTo -6) {
            f.centerY = y.toFloat()
            repeat(4) { state.update(f, 1f / 120, radius, 72, cfg) }
            assertEquals(radius, sqrt(state.shape.radiusX * state.shape.radiusY), 0.01f)
            assertTrue("No dim valley at y=$y", state.shape.alphaUnit >= insideAlpha - 0.001f)
        }
    }

    fun continuousGestureKeepsItsScaleThroughPileAtAllRefreshRates() {
        for (fps in listOf(60, 90, 120)) {
            val state = GlowState(); val f = frame(); val cfg = config(true)
            repeat(fps) { state.update(f, 1f / fps, radius, 72, cfg) }
            var alpha = state.shape.alphaUnit
            for (i in 0..fps * 2) {
                f.centerY = 96f - 250f * i / (fps * 2)
                state.update(f, 1f / fps, radius, 72, cfg)
                assertEquals(radius, sqrt(state.shape.radiusX * state.shape.radiusY), 0.02f)
                assertTrue(state.shape.alphaUnit >= alpha - 0.001f)
                assertTrue(state.shape.visible)
                alpha = state.shape.alphaUnit
            }
            assertTrue(state.shape.pileUnit > 0.9f)
        }
    }

    fun fullPileKeepsThePreviouslyTunedBrightness() {
        fun settled(continuous: Boolean): Float {
            val state = GlowState(); val f = frame().apply { centerY = -300f }
            repeat(360) { state.update(f, 1f / 120, radius, 72, config(continuous)) }
            return state.shape.alphaUnit
        }
        assertEquals(settled(false), settled(true), 0.001f)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (NavigationGlowContinuityCases) -> Unit) {
            val s = NavigationGlowContinuityCases()
            block(s)
        }
        runIsolated { s -> s.crossingTheEdgeDoesNotShrinkBeforePileStarts() }
        runIsolated { s -> s.continuousGestureKeepsItsScaleThroughPileAtAllRefreshRates() }
        runIsolated { s -> s.fullPileKeepsThePreviouslyTunedBrightness() }
    }
}

private class NavigationGlowOrientationCases {
    private val radius = 192f
    private val config = GlowConfig.create(3f, 36f, 1.5f, 720f, 84f, continuousEdgePile = true)
    private fun frame() = GlowFrame().apply {
        press = 1f; offsetX = 36f; centerX = 480f; centerY = 96f
        boundsWidth = 960f; boundsHeight = 192f; cornerRadius = 96f; pileRoomPx = 120f
    }
    private fun crossTerm(s: GlowShape): Float {
        val angle = s.rotationDeg * Math.PI / 180
        return ((s.radiusX * s.radiusX - s.radiusY * s.radiusY) * sin(angle) * cos(angle) / (radius * radius)).toFloat()
    }
    private fun coreY(s: GlowShape): Double {
        val angle = s.rotationDeg * Math.PI / 180
        return s.coreOffsetX * sin(angle) + s.coreOffsetY * cos(angle)
    }

    fun horizontalGlowDoesNotSwivelWhenGatheringAtTheTopOrBottom() {
        for (fps in listOf(60, 90, 120)) for (side in listOf(-1f, 1f)) {
            val state = GlowState(); val f = frame()
            repeat(fps) { state.update(f, 1f / fps, radius, 72, config) }
            // 出界并聚拢，再沿原路返回；比较实际椭圆，不能仅比较等价的角度/长短轴标签。
            for (i in 0..fps * 4) {
                val distance = if (i <= fps * 2) i.toFloat() / (fps * 2) else (fps * 4 - i).toFloat() / (fps * 2)
                f.centerY = 96f + side * 280f * distance
                state.update(f, 1f / fps, radius, 72, config)
                assertEquals("No diagonal sweep at fps=$fps i=$i", 0f, crossTerm(state.shape), 0.0001f)
                assertEquals(0.0, coreY(state.shape), 0.0001)
                assertEquals(radius, sqrt(state.shape.radiusX * state.shape.radiusY), 0.02f)
            }
        }
    }

    fun normalPullChangesAxesThroughARoundShapeInsteadOfSpinning() {
        val state = GlowState(); val f = frame().apply { offsetX = 0f; offsetY = -36f }
        repeat(120) { state.update(f, 1f / 120, radius, 72, config) }
        var closestToRound = Float.MAX_VALUE
        for (i in 0..480) {
            f.centerY = 96f - 280f * i / 480
            state.update(f, 1f / 120, radius, 72, config)
            val shape = state.shape
            assertEquals(0f, crossTerm(shape), 0.0001f)
            closestToRound = minOf(closestToRound, abs(shape.radiusX - shape.radiusY))
        }
        assertTrue(closestToRound < 3f)
        assertTrue(state.shape.pileUnit > 0.99f)
    }

    fun roundedCornerKeepsTheCoreOnItsOriginalScreenDirection() {
        for (fps in listOf(60, 90, 120)) {
            val state = GlowState(); val f = frame()
            repeat(fps) { state.update(f, 1f / fps, radius, 72, config) }
            for (i in 0..fps * 2) {
                val t = i.toFloat() / (fps * 2)
                f.centerX = 780f + 250f * t; f.centerY = 96f - 180f * t
                state.update(f, 1f / fps, radius, 72, config)
                assertEquals(0.0, coreY(state.shape), 0.0001)
                assertEquals(radius, sqrt(state.shape.radiusX * state.shape.radiusY), 0.02f)
                assertTrue(state.shape.rotationDeg.isFinite())
            }
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (NavigationGlowOrientationCases) -> Unit) {
            val s = NavigationGlowOrientationCases()
            block(s)
        }
        runIsolated { s -> s.horizontalGlowDoesNotSwivelWhenGatheringAtTheTopOrBottom() }
        runIsolated { s -> s.normalPullChangesAxesThroughARoundShapeInsteadOfSpinning() }
        runIsolated { s -> s.roundedCornerKeepsTheCoreOnItsOriginalScreenDirection() }
    }
}

private class NoRootActionPolicyCases {
    private val manager = NPatchRuntimeProfile(markerPresent = true, mode = NPatchMode.MANAGER, metadataValid = true)
    private val embedded = NPatchRuntimeProfile(markerPresent = true, mode = NPatchMode.EMBEDDED, metadataValid = true)
    private val unpatched = NPatchRuntimeProfile()

    fun `missing scope on a manager patched host offers to join it`() {
        assertEquals(NoRootActionKind.JOIN_SCOPE, NoRootActionPolicy.kind(NoRootDisplayState.SCOPE_MISSING, manager))
    }

    fun `join scope is never offered where it cannot help`() {
        // Embedded scope is fixed inside the patched APK; an unpatched host has nothing to be scoped for.
        assertEquals(NoRootActionKind.VERIFY, NoRootActionPolicy.kind(NoRootDisplayState.SCOPE_MISSING, embedded))
        assertEquals(NoRootActionKind.OPEN_MANAGER, NoRootActionPolicy.kind(NoRootDisplayState.SCOPE_MISSING, unpatched))
        assertEquals(
            NoRootActionKind.OPEN_MANAGER,
            NoRootActionPolicy.kind(NoRootDisplayState.SCOPE_MISSING, manager.copy(mode = NPatchMode.UNKNOWN))
        )
    }

    fun `the pre-existing actions are unchanged`() {
        assertEquals(NoRootActionKind.VERIFY, NoRootActionPolicy.kind(NoRootDisplayState.UNCONFIRMED, manager))
        assertEquals(NoRootActionKind.OPEN_MANAGER, NoRootActionPolicy.kind(NoRootDisplayState.NOT_RUNNING, manager))
        assertEquals(NoRootActionKind.VERIFY, NoRootActionPolicy.kind(NoRootDisplayState.NOT_RUNNING, embedded))
        for (state in NoRootDisplayState.entries) {
            if (state in setOf(NoRootDisplayState.UNCONFIRMED, NoRootDisplayState.NOT_RUNNING, NoRootDisplayState.SCOPE_MISSING)) continue
            assertEquals(state.name, NoRootActionKind.NONE, NoRootActionPolicy.kind(state, manager))
        }
    }

    fun `every outcome has a distinct user-facing message where the user must act differently`() {
        val messages = listOf(
            ScopeRequestResult.Added, ScopeRequestResult.AlreadyPresent, ScopeRequestResult.ManagerMissing,
            ScopeRequestResult.ModuleNotRegistered, ScopeRequestResult.ConnectionTimeout,
            ScopeRequestResult.Failure("scope_not_applied")
        ).map(NoRootActionPolicy::message)
        assertEquals(messages.size, messages.toSet().size)
        assertTrue(messages.all { it != 0 })
        assertEquals(
            NoRootActionPolicy.message(ScopeRequestResult.Failure("a")),
            NoRootActionPolicy.message(ScopeRequestResult.Failure("b"))
        )
    }

    fun `a forced scope probe skips only the minimum interval`() {
        val interval = NPatchScopeProbe.MIN_INTERVAL_MS
        assertTrue(NPatchScopeProbe.shouldProbe(inFlight = false, lastProbeAtMs = 0L, nowMs = 100L, force = false))
        assertFalse(NPatchScopeProbe.shouldProbe(inFlight = false, lastProbeAtMs = 1_000L, nowMs = 1_000L + interval - 1, force = false))
        assertTrue(NPatchScopeProbe.shouldProbe(inFlight = false, lastProbeAtMs = 1_000L, nowMs = 1_000L + interval, force = false))
        assertTrue(NPatchScopeProbe.shouldProbe(inFlight = false, lastProbeAtMs = 1_000L, nowMs = 1_001L, force = true))
        assertFalse("never stack a probe on one in flight", NPatchScopeProbe.shouldProbe(inFlight = true, lastProbeAtMs = 0L, nowMs = 1L, force = true))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (NoRootActionPolicyCases) -> Unit) {
            val s = NoRootActionPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`missing scope on a manager patched host offers to join it`() }
        runIsolated { s -> s.`join scope is never offered where it cannot help`() }
        runIsolated { s -> s.`the pre-existing actions are unchanged`() }
        runIsolated { s -> s.`every outcome has a distinct user-facing message where the user must act differently`() }
        runIsolated { s -> s.`a forced scope probe skips only the minimum interval`() }
    }
}
