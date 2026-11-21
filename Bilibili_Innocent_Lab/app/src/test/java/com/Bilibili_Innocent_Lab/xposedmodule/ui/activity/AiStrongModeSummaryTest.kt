package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CloudKeyFreezeRules
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CloudKeyScan
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentScanEntry
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiStrongModeSummaryTest {
    fun `summary lists the two items in panel order`() {
        assertEquals(
            listOf(R.string.ai_declared_block_author, R.string.ai_declared_precheck),
            aiStrongModeActiveItems(blockAuthor = true, precheck = true, authorized = true)
        )
        assertEquals(emptyList<Int>(), aiStrongModeActiveItems(blockAuthor = false, precheck = false, authorized = true))
    }

    /** 与宿主侧 effectivePrecheck 一致：没授权时「获取 access_key」不算开着；屏蔽发布者不看授权。 */
    fun `precheck only counts with the authorization while author blocking never needs it`() {
        assertEquals(emptyList<Int>(), aiStrongModeActiveItems(blockAuthor = false, precheck = true, authorized = false))
        assertEquals(
            listOf(R.string.ai_declared_block_author),
            aiStrongModeActiveItems(blockAuthor = true, precheck = true, authorized = false)
        )
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AiStrongModeSummaryTest) -> Unit) {
            val s = AiStrongModeSummaryTest()
            block(s)
        }
        runIsolated { s -> s.`summary lists the two items in panel order`() }
        runIsolated { s -> s.`precheck only counts with the authorization while author blocking never needs it`() }
    
        CloudKeyPickStateCases().verifyAllCases()
        CompatibilityRetryTrackerCases().verifyAllCases()
        DiagnosticStatusPaletteCases().verifyAllCases()
        DiagnosticsTransitionMotionCases().verifyAllCases()
        FrameworkManagerPolicyCases().verifyAllCases()
    }
}

private class CloudKeyPickStateCases {
    private fun scan(vararg pairs: Pair<String, String>) = pairs.map { (key, value) ->
        requireNotNull(CloudKeyScan.entryOf(key, value))
    }

    private fun state(
        entries: List<MineComponentScanEntry> = scan("ff_a" to "true", "ff_b" to "false", "ff_c" to "true"),
        picked: String = "",
        manual: String = ""
    ) = CloudKeyPickState.build(entries, CloudKeyFreezeRules.parsePins(picked), CloudKeyFreezeRules.parse(manual))

    private fun CloudKeyPickState.row(key: String) = rows.single { it.key == key }

    fun `scanned keys start unchecked and default their pin to the value the cloud sends`() {
        val s = state()
        assertEquals(listOf("ff_a", "ff_b", "ff_c"), s.rows.map { it.key })
        assertTrue(s.rows.none { it.checked })
        assertEquals(true, s.row("ff_a").observed); assertEquals(true, s.row("ff_a").pin)
        assertEquals(false, s.row("ff_b").observed); assertEquals(false, s.row("ff_b").pin)
        assertEquals("", s.pinsText())
    }

    fun `ticking pins the current value and saving writes only the ticked rows in rule syntax`() {
        val s = state()
        assertTrue(s.toggle(s.row("ff_c"))); assertTrue(s.toggle(s.row("ff_b")))
        assertEquals("ff_b=false\nff_c=true", s.pinsText())
        assertEquals(mapOf("ff_b" to false, "ff_c" to true), CloudKeyFreezeRules.parsePins(s.pinsText()))
        assertTrue(s.toggle(s.row("ff_b")))
        assertEquals("ff_c=true", s.pinsText())
    }

    fun `a ticked row can be pinned to the opposite value but an unticked one cannot`() {
        val s = state()
        assertFalse(s.flipPin(s.row("ff_a")))
        s.toggle(s.row("ff_a"))
        assertTrue(s.flipPin(s.row("ff_a")))
        assertEquals("ff_a=false", s.pinsText())
        assertTrue(s.flipPin(s.row("ff_a")))
        assertEquals("ff_a=true", s.pinsText())
    }

    fun `saved pins come back ticked and sort above the rest`() {
        val s = state(picked = "ff_c=false")
        assertEquals(listOf("ff_c", "ff_a", "ff_b"), s.rows.map { it.key })
        assertTrue(s.row("ff_c").checked)
        assertEquals("cloud says on, pinned off", true to false, s.row("ff_c").observed to s.row("ff_c").pin)
    }

    fun `a pinned key missing from this scan is still listed so it can be unticked`() {
        val s = state(picked = "ff_gone=true")
        val gone = s.row("ff_gone")
        assertTrue(gone.checked); assertNull(gone.observed); assertFalse(gone.scanned)
        assertTrue(s.toggle(gone))
        assertEquals("", s.pinsText())
    }

    fun `a manual rule on the same key wins and the row is read only but the pick is kept`() {
        val s = state(picked = "ff_a=false", manual = "ff_a=true\nff_b=false")
        val a = s.row("ff_a"); val b = s.row("ff_b")
        assertTrue(a.lockedByManual); assertTrue(b.lockedByManual)
        assertFalse(s.toggle(a)); assertFalse(s.toggle(b)); assertFalse(s.flipPin(a))
        assertEquals("the earlier pick survives until the manual rule is removed", "ff_a=false", s.pinsText())
    }

    fun `a mixed key cannot be newly ticked but an existing pin on it can be removed`() {
        val s = state(entries = scan("ff_m" to "mixed", "ff_n" to "mixed"), picked = "ff_n=true")
        assertFalse(s.row("ff_m").selectable)
        assertFalse(s.toggle(s.row("ff_m")))
        assertNull(s.row("ff_m").observed); assertTrue(s.row("ff_m").mixed)
        assertTrue(s.row("ff_n").checked && s.row("ff_n").selectable)
        assertTrue(s.toggle(s.row("ff_n")))
        assertEquals("", s.pinsText())
    }

    fun `the pin limit stops new ticks but never removing one`() {
        val entries = scan(*(1..CloudKeyFreezeRules.MAX_PICKED + 5).map { "ff_%03d".format(it) to "true" }.toTypedArray())
        val s = state(entries = entries)
        s.rows.take(CloudKeyFreezeRules.MAX_PICKED).forEach { assertTrue(s.toggle(it)) }
        assertTrue(s.full)
        assertFalse(s.toggle(s.rows.last()))
        assertEquals(CloudKeyFreezeRules.MAX_PICKED, s.checkedCount)
        assertTrue(s.toggle(s.rows.first()))
        assertTrue(s.toggle(s.rows.last()))
    }

    fun `search matches any part of the key ignoring case and the filter can show only ticked rows`() {
        val s = state(entries = scan("ff_Home_tab" to "true", "dd.live_room" to "false", "ff_other" to "true"))
        assertEquals(listOf("dd.live_room"), s.visible("LIVE", false).map { it.key })
        assertEquals(listOf("dd.live_room", "ff_Home_tab", "ff_other"), s.visible("  ", false).map { it.key })
        s.toggle(s.row("ff_other"))
        assertEquals(listOf("ff_other"), s.visible("", true).map { it.key })
        assertTrue(s.visible("zzz", false).isEmpty())
    }

    fun `entries of other kinds protected keys and unreadable values never become rows`() {
        val foreign = MineComponentScanEntry.create("home_tab", "热门", "hot", null, showing = true)!!
        val unknown = MineComponentScanEntry.create("cloud_key", null, "ff_x", null, showing = true, value = "text")!!
        val s = state(entries = scan("ff_ok" to "true", "grpc.timeout" to "true") + foreign + unknown)
        assertEquals(listOf("ff_ok"), s.rows.map { it.key })
    }

    fun `saved text is deterministic and parses back to the same pins`() {
        val a = state(); a.toggle(a.row("ff_a")); a.toggle(a.row("ff_b"))
        val b = state(); b.toggle(b.row("ff_b")); b.toggle(b.row("ff_a"))
        assertEquals(a.pinsText(), b.pinsText())
        assertEquals(a.pinsText(), state(picked = a.pinsText()).pinsText())
    }

    fun verifyAllCases() {
        fun runIsolated(block: (CloudKeyPickStateCases) -> Unit) {
            val s = CloudKeyPickStateCases()
            block(s)
        }
        runIsolated { s -> s.`scanned keys start unchecked and default their pin to the value the cloud sends`() }
        runIsolated { s -> s.`ticking pins the current value and saving writes only the ticked rows in rule syntax`() }
        runIsolated { s -> s.`a ticked row can be pinned to the opposite value but an unticked one cannot`() }
        runIsolated { s -> s.`saved pins come back ticked and sort above the rest`() }
        runIsolated { s -> s.`a pinned key missing from this scan is still listed so it can be unticked`() }
        runIsolated { s -> s.`a manual rule on the same key wins and the row is read only but the pick is kept`() }
        runIsolated { s -> s.`a mixed key cannot be newly ticked but an existing pin on it can be removed`() }
        runIsolated { s -> s.`the pin limit stops new ticks but never removing one`() }
        runIsolated { s -> s.`search matches any part of the key ignoring case and the filter can show only ticked rows`() }
        runIsolated { s -> s.`entries of other kinds protected keys and unreadable values never become rows`() }
        runIsolated { s -> s.`saved text is deterministic and parses back to the same pins`() }
    }
}

private class CompatibilityRetryTrackerCases {
    private val failed = CompatibilityRetryTracker.Outcome.CONNECTION_FAILED
    fun offerAppearsOnlyAfterThreeCompletedFailures() {
        val tracker = CompatibilityRetryTracker()
        repeat(3) { index ->
            val token = checkNotNull(tracker.begin(10))
            assertEquals(index, tracker.failures)
            assertTrue(tracker.finish(token, failed))
            assertEquals(index == 2, tracker.shouldOffer(true, false))
        }
        assertFalse(tracker.shouldOffer(false, false))
        assertFalse(tracker.shouldOffer(true, true))
    }
    fun rapidTapsAndDuplicateCallbacksCountOnce() {
        val tracker = CompatibilityRetryTracker()
        val token = checkNotNull(tracker.begin(10))
        assertNull(tracker.begin(10))
        assertTrue(tracker.finish(token, failed))
        assertFalse(tracker.finish(token, failed))
        assertEquals(1, tracker.failures)
    }
    fun cancelledAndLateAttemptsCannotShowTheHint() {
        val tracker = CompatibilityRetryTracker()
        val first = checkNotNull(tracker.begin(10))
        tracker.cancel()
        val second = checkNotNull(tracker.begin(10))
        assertFalse(tracker.finish(first, failed))
        assertEquals(0, tracker.failures)
        assertTrue(tracker.finish(second, failed))
        assertEquals(1, tracker.failures)
    }
    fun successAndStorageFailuresBreakTheFailureSequence() {
        val tracker = CompatibilityRetryTracker()
        tracker.finish(checkNotNull(tracker.begin(10)), failed)
        tracker.finish(checkNotNull(tracker.begin(10)), CompatibilityRetryTracker.Outcome.SUCCESS)
        assertEquals(0, tracker.failures)
        tracker.finish(checkNotNull(tracker.begin(10)), failed)
        tracker.finish(checkNotNull(tracker.begin(10)), CompatibilityRetryTracker.Outcome.OTHER_FAILURE)
        assertEquals(0, tracker.failures)
    }
    fun aNewConsentRevisionDoesNotInheritOldFailures() {
        val tracker = CompatibilityRetryTracker()
        repeat(3) { tracker.finish(checkNotNull(tracker.begin(10)), failed) }
        assertNotNull(tracker.begin(11))
        assertFalse(tracker.shouldOffer(true, false))
        assertNull(CompatibilityRetryTracker().begin(0))
    }
    fun counterIsBoundedAndResetDiscardsInFlightWork() {
        val tracker = CompatibilityRetryTracker()
        repeat(20) { tracker.finish(checkNotNull(tracker.begin(10)), failed) }
        assertEquals(3, tracker.failures)
        val token = checkNotNull(tracker.begin(10))
        tracker.reset()
        assertFalse(tracker.finish(token, failed))
        assertEquals(0, tracker.failures)
    }
    fun recreationKeepsCompletedFailuresButNotAnInFlightAttempt() {
        val tracker = CompatibilityRetryTracker()
        tracker.restore(7, 2, 7)
        assertEquals(2, tracker.failures)
        val token = tracker.begin(7)!!
        assertTrue(tracker.finish(token, CompatibilityRetryTracker.Outcome.CONNECTION_FAILED))
        assertTrue(tracker.shouldOffer(true,false))
        tracker.synchronize(8)
        assertFalse(tracker.shouldOffer(true,false))
        tracker.restore(7,3,8)
        assertEquals(0,tracker.failures)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (CompatibilityRetryTrackerCases) -> Unit) {
            val s = CompatibilityRetryTrackerCases()
            block(s)
        }
        runIsolated { s -> s.offerAppearsOnlyAfterThreeCompletedFailures() }
        runIsolated { s -> s.rapidTapsAndDuplicateCallbacksCountOnce() }
        runIsolated { s -> s.cancelledAndLateAttemptsCannotShowTheHint() }
        runIsolated { s -> s.successAndStorageFailuresBreakTheFailureSequence() }
        runIsolated { s -> s.aNewConsentRevisionDoesNotInheritOldFailures() }
        runIsolated { s -> s.counterIsBoundedAndResetDiscardsInFlightWork() }
        runIsolated { s -> s.recreationKeepsCompletedFailuresButNotAnInFlightAttempt() }
    }
}

private class DiagnosticStatusPaletteCases {

    fun semanticTonesStayDistinctInLightAndDarkThemes() {
        listOf(false, true).forEach { darkTheme ->
            val colors = DiagnosticStatusTone.entries.map { tone ->
                DiagnosticStatusPalette.color(tone, darkTheme)
            }
            assertEquals(DiagnosticStatusTone.entries.size, colors.distinct().size)
        }
    }

    fun normalStateUsesGreenAndRequiredActionUsesRed() {
        assertEquals(0xFF15803D.toInt(), DiagnosticStatusPalette.color(DiagnosticStatusTone.OK, false))
        assertEquals(0xFF4ADE80.toInt(), DiagnosticStatusPalette.color(DiagnosticStatusTone.OK, true))
        assertEquals(
            0xFFB91C1C.toInt(),
            DiagnosticStatusPalette.color(DiagnosticStatusTone.ACTION_REQUIRED, false)
        )
        assertNotEquals(
            DiagnosticStatusPalette.color(DiagnosticStatusTone.OK, false),
            DiagnosticStatusPalette.color(DiagnosticStatusTone.ACTION_REQUIRED, false)
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (DiagnosticStatusPaletteCases) -> Unit) {
            val s = DiagnosticStatusPaletteCases()
            block(s)
        }
        runIsolated { s -> s.semanticTonesStayDistinctInLightAndDarkThemes() }
        runIsolated { s -> s.normalStateUsesGreenAndRequiredActionUsesRed() }
    }
}

private class DiagnosticsTransitionMotionCases {
    private val entryBounds = SettingsBackupMotionRect(72f, 360f, 1008f, 540f)
    private val windowBounds = SettingsBackupMotionRect(0f, 0f, 1080f, 2160f)
    private val entryTitle = SettingsBackupMotionRect(108f, 390f, 620f, 435f)
    private val toolbarTitle = SettingsBackupMotionRect(174f, 18f, 900f, 66f)

    fun diagnosticsEntryExpandsToWindowWithoutChangingTheSourceEndpoint() {
        val collapsed = frame(0f)
        val expanded = frame(1f)

        assertEquals(entryBounds, collapsed.bounds)
        assertEquals(entryTitle.left, collapsed.titleX, 0f)
        assertEquals(entryTitle.top, collapsed.titleY, 0f)
        assertEquals(0f, collapsed.contentAlpha, 0f)
        assertEquals(windowBounds, expanded.bounds)
        assertEquals(toolbarTitle.left, expanded.titleX, 0f)
        assertEquals(toolbarTitle.top, expanded.titleY, 0f)
        assertEquals(1f, expanded.contentAlpha, 0f)
    }

    fun predictiveReturnKeepsContentVisibleLongerThanTimedClose() {
        val timed = frame(0.8f, SettingsBackupContentTiming.TIMED)
        val predictive = frame(0.8f, SettingsBackupContentTiming.PREDICTIVE)

        assertEquals(0f, timed.contentAlpha, 0f)
        assertEquals(1f, predictive.contentAlpha, 0f)
        assertTrue(predictive.contentTranslationYPx < timed.contentTranslationYPx)
    }

    fun diagnosticSurfaceHandsOffOnlyAfterReachingTheSourceEndpoint() {
        assertEquals(
            0f,
            SettingsBackupMotionSpec.transitionSurfaceAlpha(
                expansion = 0f,
                handoffExpansion = DiagnosticsEntryVisualSpec.SURFACE_HANDOFF_EXPANSION
            ),
            0f
        )
        assertEquals(
            1f,
            SettingsBackupMotionSpec.transitionSurfaceAlpha(
                expansion = DiagnosticsEntryVisualSpec.SURFACE_HANDOFF_EXPANSION,
                handoffExpansion = DiagnosticsEntryVisualSpec.SURFACE_HANDOFF_EXPANSION
            ),
            0f
        )
        assertEquals(
            0.5f,
            SettingsBackupMotionSpec.transitionSurfaceAlpha(
                expansion = DiagnosticsEntryVisualSpec.SURFACE_HANDOFF_EXPANSION / 2f,
                handoffExpansion = DiagnosticsEntryVisualSpec.SURFACE_HANDOFF_EXPANSION
            ),
            0.0001f
        )
        assertEquals(1f, SettingsBackupMotionSpec.collapsedChromeFraction(0f), 0f)
        assertEquals(0f, SettingsBackupMotionSpec.collapsedChromeFraction(0.28f), 0f)
        assertEquals(12f, DiagnosticsEntryVisualSpec.CORNER_RADIUS_DP, 0f)
        assertEquals(2f, DiagnosticsEntryVisualSpec.STROKE_WIDTH_DP, 0f)
        assertTrue(DiagnosticsEntryVisualSpec.scrimAlpha(darkTheme = false) > 0x38)
        assertTrue(DiagnosticsEntryVisualSpec.scrimAlpha(darkTheme = true) > 0x38)
    }

    fun sourceWindowCoordinatesAvoidCrossActivitySystemBarOffset() {
        val origin = transitionOrigin(
            localEntry = entryBounds,
            localTitle = entryTitle,
            sourceWindowTopOnScreen = 72f
        )

        val mapped = requireNotNull(
            DiagnosticsTransitionCoordinateMapper.map(
                origin = origin,
                destinationWindowWidth = 1080,
                destinationWindowHeight = 2160,
                destinationWindowLeftOnScreen = 0f,
                destinationWindowTopOnScreen = 24f,
                tolerancePx = 4f
            )
        )

        assertTrue(mapped.usedSourceWindowCoordinates)
        assertEquals(entryBounds, mapped.entryBounds)
        assertEquals(entryTitle, mapped.titleBounds)
    }

    fun invalidSourceWindowCoordinatesUseValidatedScreenFallback() {
        val origin = transitionOrigin(
            localEntry = SettingsBackupMotionRect(-400f, -300f, -200f, -100f),
            localTitle = SettingsBackupMotionRect(-380f, -280f, -240f, -220f),
            sourceWindowTopOnScreen = 24f
        )

        val mapped = requireNotNull(
            DiagnosticsTransitionCoordinateMapper.map(
                origin = origin,
                destinationWindowWidth = 1080,
                destinationWindowHeight = 2160,
                destinationWindowLeftOnScreen = 0f,
                destinationWindowTopOnScreen = 24f,
                tolerancePx = 4f
            )
        )

        assertTrue(!mapped.usedSourceWindowCoordinates)
        assertEquals(entryBounds, mapped.entryBounds)
        assertEquals(entryTitle, mapped.titleBounds)
    }

    private fun frame(
        expansion: Float,
        timing: SettingsBackupContentTiming = SettingsBackupContentTiming.TIMED
    ): SettingsBackupMotionFrame = SettingsBackupMotionSpec.frame(
        expansion = expansion,
        collapsedBounds = entryBounds,
        expandedBounds = windowBounds,
        collapsedTitleBounds = entryTitle,
        expandedTitleBounds = toolbarTitle,
        collapsedTitleTextSizePx = 45f,
        expandedTitleTextSizePx = 51f,
        collapsedCornerRadiusPx = 45f,
        contentTravelPx = 36f,
        contentTiming = timing
    )

    private fun transitionOrigin(
        localEntry: SettingsBackupMotionRect,
        localTitle: SettingsBackupMotionRect,
        sourceWindowTopOnScreen: Float
    ): DiagnosticsTransitionOrigin = DiagnosticsTransitionOrigin(
        entryBoundsOnScreen = entryBounds.offsetBy(dy = sourceWindowTopOnScreen),
        titleBoundsOnScreen = entryTitle.offsetBy(dy = sourceWindowTopOnScreen),
        entryBoundsInSourceWindow = localEntry,
        titleBoundsInSourceWindow = localTitle,
        sourceWindowBoundsOnScreen = SettingsBackupMotionRect(
            0f,
            sourceWindowTopOnScreen,
            1080f,
            sourceWindowTopOnScreen + 2160f
        ),
        titleTextSizePx = 45f,
        titleLineCount = 1,
        titleLayoutDirection = android.view.View.LAYOUT_DIRECTION_LTR,
        sourceWindowWidth = 1080,
        sourceWindowHeight = 2160,
        displayId = 0,
        displayRotation = 0
    )

    private fun SettingsBackupMotionRect.offsetBy(
        dx: Float = 0f,
        dy: Float = 0f
    ) = SettingsBackupMotionRect(left + dx, top + dy, right + dx, bottom + dy)

    /** 2026-09-24：入口与形变表面底色随主题——浅色用表面色（柔光浅色下原来是一块中灰）。 */
    fun entrySurfaceUsesTheSurfaceColorInLightTheme() {
        val gray = 0xFF323B42.toInt()
        val surface = 0xFFF4F4F6.toInt()
        val light = DiagnosticsEntryVisualSpec.surfaceColor(false, gray, surface)
        val dark = DiagnosticsEntryVisualSpec.surfaceColor(true, gray, surface)
        assertEquals(surface and 0x00FFFFFF, light and 0x00FFFFFF)
        assertEquals(gray and 0x00FFFFFF, dark and 0x00FFFFFF)
        assertEquals(DiagnosticsEntryVisualSpec.scrimAlpha(false), light ushr 24)
        assertEquals(DiagnosticsEntryVisualSpec.scrimAlpha(true), dark ushr 24)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (DiagnosticsTransitionMotionCases) -> Unit) {
            val s = DiagnosticsTransitionMotionCases()
            block(s)
        }
        runIsolated { s -> s.diagnosticsEntryExpandsToWindowWithoutChangingTheSourceEndpoint() }
        runIsolated { s -> s.predictiveReturnKeepsContentVisibleLongerThanTimedClose() }
        runIsolated { s -> s.diagnosticSurfaceHandsOffOnlyAfterReachingTheSourceEndpoint() }
        runIsolated { s -> s.sourceWindowCoordinatesAvoidCrossActivitySystemBarOffset() }
        runIsolated { s -> s.invalidSourceWindowCoordinatesUseValidatedScreenFallback() }
        runIsolated { s -> s.entrySurfaceUsesTheSurfaceColorInLightTheme() }
    }
}

private class FrameworkManagerPolicyCases {
    fun `Irena and its actual LSPosed service name use the existing LSPosed manager`() {
        val expected = frameworkManagerTargets("LSPosed")
        assertEquals(expected, frameworkManagerTargets("Irena"))
        assertEquals(expected, frameworkManagerTargets("LSPosed-Irena"))
        assertEquals("org.lsposed.manager", expected.first().packageName)
        assertEquals("org.lsposed.manager.LAUNCH_MANAGER", expected.last().category)
    }
    fun `Vector uses its own standalone package and parasitic category`() {
        val targets = frameworkManagerTargets("Vector")
        assertEquals("org.matrix.vector.manager", targets.first().packageName)
        assertEquals("org.matrix.vector.manager.LAUNCH_MANAGER", targets.last().category)
        assertEquals("com.android.shell.BugreportWarningActivity", targets.last().activityName)
        assertFalse(targets.any { it.packageName == "org.lsposed.manager" })
    }

    fun `LSPatch uses only its independently installed manager`() {
        val targets = frameworkManagerTargets("LSPatch")
        assertEquals(listOf(FrameworkManagerTarget("org.lsposed.lspatch")), targets)
        assertFalse(targets.any { it.packageName == "org.lsposed.manager" })
        assertFalse(targets.any { it.packageName == "org.matrix.vector.manager" })
        assertFalse(targets.any { it.packageName == "com.android.shell" })
    }

    fun `LSPosed retains its existing entry and unidentified services do not guess a shell category`() {
        assertEquals("org.lsposed.manager.LAUNCH_MANAGER", frameworkManagerTargets("LSPosed").last().category)
        assertEquals(2, frameworkManagerTargets("").size)
        assertTrue(frameworkManagerTargets("").all { it.activityName == null })
    }

    fun `exported activity still requires its permission and enabled application`() {
        assertTrue(canLaunchFrameworkManager(true, true, true, false, true))
        assertFalse(canLaunchFrameworkManager(true, true, true, false, false))
        assertFalse(canLaunchFrameworkManager(true, true, false, false, true))
        assertFalse(canLaunchFrameworkManager(false, true, true, true, true))
        assertFalse(canLaunchFrameworkManager(true, false, true, true, true))
        assertTrue(canLaunchFrameworkManager(true, true, false, true, false))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FrameworkManagerPolicyCases) -> Unit) {
            val s = FrameworkManagerPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`Irena and its actual LSPosed service name use the existing LSPosed manager`() }
        runIsolated { s -> s.`Vector uses its own standalone package and parasitic category`() }
        runIsolated { s -> s.`LSPatch uses only its independently installed manager`() }
        runIsolated { s -> s.`LSPosed retains its existing entry and unidentified services do not guess a shell category`() }
        runIsolated { s -> s.`exported activity still requires its permission and enabled application`() }
    }
}
