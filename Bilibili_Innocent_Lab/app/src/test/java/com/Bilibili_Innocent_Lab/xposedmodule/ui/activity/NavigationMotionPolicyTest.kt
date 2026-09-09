package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class NavigationMotionPolicyTest {
    @Test fun aBackPressKeepsTouchOwnershipAcrossAnimationCompletionUntilUpOrCancel() {
        assertTrue(NavigationMotionPolicy.keepInputBlocked(true, false))
        assertTrue(NavigationMotionPolicy.keepInputBlocked(true, true))
        assertTrue(NavigationMotionPolicy.keepInputBlocked(false, true))
        assertFalse(NavigationMotionPolicy.keepInputBlocked(false, false))
        // If UP starts closing before the press clears, the closing blocker must remain.
        assertTrue(NavigationMotionPolicy.keepInputBlocked(true, false))
    }
    @Test fun entryExpandedAndCancelReboundAllowNavigationButNeverBusinessWork() {
        for (phase in NavigationMotionPhase.entries) {
            assertFalse(NavigationMotionPolicy.canNavigate(phase, true))
            assertEquals(phase == NavigationMotionPhase.ENTERING || phase == NavigationMotionPhase.EXPANDED ||
                phase == NavigationMotionPhase.CANCELLING_BACK, NavigationMotionPolicy.canNavigate(phase, false))
        }
    }
    @Test fun onlyUnsettledReusableFramesPreserveTheirOriginalProfile() {
        for (phase in NavigationMotionPhase.entries) {
            assertEquals(phase in listOf(NavigationMotionPhase.ENTERING, NavigationMotionPhase.PREDICTIVE_BACK,
                NavigationMotionPhase.CANCELLING_BACK), NavigationMotionPolicy.preserveFrame(phase))
        }
    }
    @Test fun sameExpansionCannotBeUsedToSwitchContentProfilesMidAnimation() {
        assertEquals(0f, SettingsBackupMotionSpec.contentFraction(.8f, SettingsBackupContentTiming.TIMED), 0f)
        assertEquals(1f, SettingsBackupMotionSpec.contentFraction(.8f, SettingsBackupContentTiming.PREDICTIVE), 0f)
        for (profile in SettingsBackupContentTiming.entries) {
            val before = SettingsBackupMotionSpec.contentFraction(.8f, profile)
            val continuation = NavigationMotionContinuation(.8f, 0f, 1f, 200L)
            assertEquals(before, SettingsBackupMotionSpec.contentFraction(continuation.value(0f), profile), 0f)
        }
    }
    @Test fun interruptedMotionStartsAtTheExactCurrentValueAndEndsAtTheRequestedEndpoint() {
        for (start in listOf(0f, .01f, .3f, .8f, .99f, 1f)) for (target in listOf(0f, 1f)) {
            for (velocity in listOf(-8f, -1f, 0f, 1f, 8f)) {
                val curve = NavigationMotionContinuation(start, target, velocity, 320L)
                assertEquals(start, curve.value(0f), 0f)
                assertEquals(target, curve.value(1f), .000001f)
                repeat(1001) { assertTrue(curve.value(it / 1000f) in 0f..1f) }
            }
        }
    }
    @Test fun moderateReversalContinuesVelocityBeforeTurningWithoutAPositionJump() {
        val curve = NavigationMotionContinuation(.5f, 0f, .5f, 300L)
        val initialVelocity = (curve.value(.0001f) - .5f) / .00003f
        assertEquals(.5f, initialVelocity, .015f)
        assertTrue(curve.value(.001f) > .5f)
        assertTrue(curve.value(.8f) < .5f)
    }
    @Test fun continuationFinishesAtRestAndDoesNotOvershootAtEitherBoundary() {
        for (target in listOf(0f, 1f)) {
            val curve = NavigationMotionContinuation(.5f, target, -2f, 320L)
            assertTrue(abs(curve.value(1f) - curve.value(.9999f)) < .00001f)
        }
        assertEquals(1f, NavigationMotionContinuation(1f, 1f, 8f, 200L).value(.5f), 0f)
    }
    @Test fun reboundDurationUsesRemainingDistanceWithABoundedMinimum() {
        assertEquals(210L, NavigationMotionPolicy.remainingDuration(210L, 0f, 1f))
        assertEquals(105L, NavigationMotionPolicy.remainingDuration(210L, .5f, 1f))
        assertEquals(80L, NavigationMotionPolicy.remainingDuration(210L, .99f, 1f))
    }
    @Test fun oldEntryAnimatorAndFinishTokensCannotTakeOverANewerRequest() {
        val session = NavigationMotionSession()
        val entry = session.invalidate()
        val animator = session.invalidate()
        assertFalse(session.owns(entry))
        val gesture = session.invalidate()
        assertFalse(session.owns(animator))
        val close = session.invalidate()
        assertFalse(session.owns(gesture))
        assertTrue(session.owns(close))
        session.invalidate()
        assertFalse(session.owns(close))
    }
    @Test fun velocityIsBoundedAndStaleSamplesAreNotReused() {
        val session = NavigationMotionSession()
        session.reset(.2f, 1000L)
        session.sample(.3f, 1050L)
        assertEquals(2f, session.velocity(1050L), .00001f)
        assertEquals(0f, session.velocity(1200L), 0f)
        session.sample(1f, 1051L)
        assertEquals(8f, session.velocity(1051L), 0f)
        session.reset(.6f, 2000L)
        assertEquals(0f, session.velocity(2000L), 0f)
    }
    @Test fun sameTimestampUpdatesUseTheLatestVisualPositionForTheNextSample() {
        val session = NavigationMotionSession()
        session.reset(.2f, 1000L)
        session.sample(.3f, 1050L)
        session.sample(.4f, 1050L)
        session.sample(.45f, 1100L)
        assertEquals(1f, session.velocity(1100L), .00001f)
    }

    private fun source(name: String): String {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/$name.kt"
        return sequenceOf(File(path), File("app/$path")).first(File::isFile).readText()
    }
    @Test fun bothPagesFreezeTheVisualProfileAndInvalidatePostedEntryWork() {
        for (page in listOf("SettingsBackupActivity", "DiagnosticsActivity")) {
            val code = source(page)
            val prepare = code.substringAfter("private fun prepareExitMotion(").substringBefore("private fun requestClose(")
            assertTrue(prepare.indexOf("NavigationMotionPolicy.preserveFrame(motionState)") < prepare.indexOf("resolveMotionGeometry("))
            assertTrue(prepare.substringBefore("resolveMotionGeometry(").contains("return"))
            assertTrue(code.contains("!motionSession.owns(entryToken)"))
            assertTrue(code.contains("!motionSession.owns(finishToken)"))
            assertTrue(code.contains("motionState == MotionState.CLOSING"))
            assertTrue(code.contains("current && !cancelled && !isFinishing && !isDestroyed"))
            assertTrue(code.contains("animator.removeAllListeners()"))
        }
    }
    @Test fun transitionsDoNotAllocateFrameSnapshotsOrRebuildListsPerFrame() {
        for (page in listOf("SettingsBackupActivity", "DiagnosticsActivity")) {
            val apply = source(page).substringAfter("private fun applyMotionExpansion(").substringBefore("private fun completeExpandedMotion(")
            for (forbidden in listOf("snapshot()", "Bitmap", "resolveMotionGeometry", "renderHome", "removeAllViews")) {
                assertFalse(forbidden, apply.contains(forbidden))
            }
        }
        val diagnostics = source("DiagnosticsActivity")
        assertTrue(diagnostics.contains("pendingScreenState = state"))
        assertTrue(diagnostics.contains("exportRunning || pickerOpen || activeDialog != null"))
        assertTrue(source("SettingsBackupActivity").contains("busy || pickerOpen || page == Page.WORKING"))
    }
    @Test fun touchInterceptionOnlyRelaysTheVisibleRegisteredBackControl() {
        val host = source("SettingsBackupMotionHost")
        assertTrue(host.contains("pressedBackTarget === navigationBackTarget"))
        assertTrue(host.contains("MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> clearBackPress()"))
        assertTrue(host.contains("currentPage?.alpha"))
        assertTrue(host.contains("event.rawX >= backLocation[0]"))
        assertFalse(host.contains("dispatchTouchEvent(event)"))
        assertTrue(source("SettingsBackupActivity").contains("motionHost.registerNavigationBack(this)"))
        assertTrue(source("DiagnosticsActivity").contains("motionHost::registerNavigationBack"))
    }
}
