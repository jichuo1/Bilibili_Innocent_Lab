package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.*
import org.junit.Test

class SettingsUserScrollSessionTest {
    fun acceptedSpaceHomeAndEndStillNotifyWhenTheInheritedKeyReturnIsFalse() {
        for (key in listOf("SPACE", "HOME", "END")) {
            val session = SettingsUserScrollSession()
            session.beginKey(true)
            session.pageResult(true)
            assertTrue(key, session.finishKey(false))
            assertFalse("$key must not leak into later programmatic scrolling", session.finishKey(false))
        }
    }

    fun declinedBoundaryKeysAndUnrelatedKeysDoNotCancel() {
        val session = SettingsUserScrollSession()
        session.beginKey(true)
        session.pageResult(false)
        assertFalse(session.finishKey(false))
        session.beginKey(false)
        session.pageResult(true)
        assertFalse("Inherited focus handling of an unrelated key is not scrolling", session.finishKey(true))
    }

    fun programmaticPageScrollingAndPreviousDispatchStateStaySilent() {
        val session = SettingsUserScrollSession()
        session.pageResult(true)
        assertFalse(session.finishKey(false))
        session.beginKey(true)
        assertTrue(session.finishKey(true))
        session.pageResult(true)
        assertFalse(session.finishKey(false))
        session.beginKey(true)
        assertFalse(session.finishKey(false))
    }

    fun nonTouchIntegrationPreservesSuperResultsAndOnlyAcceptedScrollActionsNotify() {
        val source = SettingsUiSource.file("SettingsHomeScrollView")
        val key = SettingsUiSource.functions(source, "executeKeyEvent").single()
        assertTrue(key.contains("event.action == KeyEvent.ACTION_DOWN"))
        assertTrue(key.contains("handled = super.executeKeyEvent(event)"))
        assertTrue(key.contains("return handled"))
        val page = SettingsUiSource.functions(source, "pageScroll").single()
        assertTrue(page.contains("val handled = super.pageScroll(direction)"))
        assertTrue(page.contains("keySession.pageResult(handled)"))
        val accessibility = SettingsUiSource.functions(source, "performAccessibilityAction").single()
        assertTrue(accessibility.contains("if (handled && scrollAction) onUserScroll()"))
        assertTrue(accessibility.contains("ACTION_SCROLL_UP"))
        assertTrue(accessibility.contains("ACTION_SCROLL_DOWN"))
        val generic = SettingsUiSource.functions(source, "onGenericMotionEvent").single()
        assertTrue(generic.contains("val handled = super.onGenericMotionEvent(event)"))
        assertTrue(generic.contains("if (handled && event.actionMasked == MotionEvent.ACTION_SCROLL) onUserScroll()"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (SettingsUserScrollSessionTest) -> Unit) {
            val s = SettingsUserScrollSessionTest()
            block(s)
        }
        runIsolated { s -> s.acceptedSpaceHomeAndEndStillNotifyWhenTheInheritedKeyReturnIsFalse() }
        runIsolated { s -> s.declinedBoundaryKeysAndUnrelatedKeysDoNotCancel() }
        runIsolated { s -> s.programmaticPageScrollingAndPreviousDispatchStateStaySilent() }
        runIsolated { s -> s.nonTouchIntegrationPreservesSuperResultsAndOnlyAcceptedScrollActionsNotify() }
    }
}
