package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsUserScrollSessionTest {

    @Test
    fun keyAndPageScrollSessionTracksOnlyAcceptedNavigationKeysWithoutLeaking() {
        for (key in listOf("SPACE", "HOME", "END")) {
            val session = SettingsUserScrollSession()
            session.beginKey(true)
            session.pageResult(true)
            assertTrue(key, session.finishKey(false))
            assertFalse("$key must not leak into later programmatic scrolling", session.finishKey(false))
        }

        val declined = SettingsUserScrollSession()
        declined.beginKey(true)
        declined.pageResult(false)
        assertFalse(declined.finishKey(false))
        declined.beginKey(false)
        declined.pageResult(true)
        assertFalse("Inherited focus handling of an unrelated key is not scrolling", declined.finishKey(true))

        val programmatic = SettingsUserScrollSession()
        programmatic.pageResult(true)
        assertFalse(programmatic.finishKey(false))
        programmatic.beginKey(true)
        assertTrue(programmatic.finishKey(true))
        programmatic.pageResult(true)
        assertFalse(programmatic.finishKey(false))
        programmatic.beginKey(true)
        assertFalse(programmatic.finishKey(false))
    }
}
