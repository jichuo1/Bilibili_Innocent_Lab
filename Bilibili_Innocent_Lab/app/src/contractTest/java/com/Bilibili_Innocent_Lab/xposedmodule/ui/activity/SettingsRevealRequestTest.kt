package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.*
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before

class SettingsRevealRequestTest {
    fun replacementReleasesOldLayoutWorkAndRejectsLateCompletionOnTheSamePage() {
        val request = SettingsRevealRequest()
        val events = mutableListOf<String>()
        val announcement = request.begin { events += "release announcement" }
        val search = request.begin { events += "release search" }
        assertEquals(listOf("release announcement"), events)
        assertFalse(request.complete(announcement))
        assertTrue(request.owns(search))
        assertTrue(request.complete(search))
        assertEquals(listOf("release announcement", "release search"), events)
        assertFalse(request.complete(search))
        assertFalse(request.isActive)
    }

    fun controlledLayoutAndScrollCallbacksCannotRunAfterPauseOrCancelledGesture() {
        for (reason in listOf("pause", "destroy", "drag returns to same page", "same tab")) {
            val request = SettingsRevealRequest()
            var releases = 0
            var scrolls = 0
            var completions = 0
            val token = request.begin { releases++ }
            val pendingLayout = { if (request.owns(token)) scrolls++ }
            val pendingScrollEnd = { if (request.complete(token)) completions++ }
            request.cancel()
            pendingLayout()
            pendingScrollEnd()
            assertEquals(reason, 0, scrolls)
            assertEquals(reason, 0, completions)
            assertEquals(reason, 1, releases)
        }
    }

    fun layoutDelayDoesNotExpireAValidRequestAndCompletionReleasesExactlyOnce() {
        val request = SettingsRevealRequest()
        var releases = 0
        val token = request.begin { releases++ }
        repeat(1000) { assertTrue(request.owns(token)) }
        assertTrue(request.complete(token))
        request.cancel()
        assertEquals(1, releases)
        assertFalse(request.owns(token))
    }

    fun activityCancellationClosesBothVisualAndAnnouncementOwnership() {
        val cancel = SettingsUiSource.function("cancelSettingsReveal")
        assertTrue(cancel.contains("settingsRevealRequest.cancel()"))
        assertTrue(cancel.contains("clearSettingsSearchTargetHighlight()"))
        assertTrue(cancel.contains("highlightNavigationGeneration++"))
        assertTrue(cancel.contains("highlightNavigationInFlight = false"))
        assertTrue(cancel.contains("if (!keepPendingHighlight) pendingHighlightDestination = null"))
        assertTrue(SettingsUiSource.function("onPause").contains("cancelSettingsReveal(keepPendingHighlight = true)"))
        val highlight = SettingsUiSource.function("revealHighlightDestination")
        assertTrue(highlight.indexOf("cancelSettingsReveal()") < highlight.indexOf("highlightNavigationInFlight = true"))
        assertTrue(highlight.contains("fromHighlight = true"))
        val install = SettingsUiSource.function("installSettingsHome")
        assertTrue(install.contains("if (settingsRevealRequest.isActive) cancelSettingsReveal()"))
        val scroll = SettingsUiSource.file("SettingsHomeScrollView")
        val down = scroll.after("MotionEvent.ACTION_DOWN ->").before("MotionEvent.ACTION_MOVE ->")
        assertTrue(down.contains("onContentTouch()"))
        val reveal = SettingsUiSource.function("revealSettingsSearchTarget")
        val release = reveal.after("token = settingsRevealRequest.begin {")
            .before("observer.addOnPreDrawListener")
        assertTrue(release.contains("removeOnPreDrawListener(listener)"))
        assertTrue(release.contains("scrollMotion.cancel()"))
        assertTrue(release.contains("scrollAnimator?.cancel()"))
        assertFalse(release.contains("smoothScrollTo"))
        assertTrue(reveal.contains("scrollMotion.frame(scrollToken, it.animatedFraction)"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (SettingsRevealRequestTest) -> Unit) {
            val s = SettingsRevealRequestTest()
            block(s)
        }
        runIsolated { s -> s.replacementReleasesOldLayoutWorkAndRejectsLateCompletionOnTheSamePage() }
        runIsolated { s -> s.controlledLayoutAndScrollCallbacksCannotRunAfterPauseOrCancelledGesture() }
        runIsolated { s -> s.layoutDelayDoesNotExpireAValidRequestAndCompletionReleasesExactlyOnce() }
        runIsolated { s -> s.activityCancellationClosesBothVisualAndAnnouncementOwnership() }
    }
}
