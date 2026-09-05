package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.*
import org.junit.Test

class FrameworkManagerPolicyTest {
    @Test
    fun `Irena and its actual LSPosed service name use the existing LSPosed manager`() {
        val expected = frameworkManagerTargets("LSPosed")
        assertEquals(expected, frameworkManagerTargets("Irena"))
        assertEquals(expected, frameworkManagerTargets("LSPosed-Irena"))
        assertEquals("org.lsposed.manager", expected.first().packageName)
        assertEquals("org.lsposed.manager.LAUNCH_MANAGER", expected.last().category)
    }
    @Test
    fun `Vector uses its own standalone package and parasitic category`() {
        val targets = frameworkManagerTargets("Vector")
        assertEquals("org.matrix.vector.manager", targets.first().packageName)
        assertEquals("org.matrix.vector.manager.LAUNCH_MANAGER", targets.last().category)
        assertEquals("com.android.shell.BugreportWarningActivity", targets.last().activityName)
        assertFalse(targets.any { it.packageName == "org.lsposed.manager" })
    }

    @Test
    fun `LSPosed retains its existing entry and unidentified services do not guess a shell category`() {
        assertEquals("org.lsposed.manager.LAUNCH_MANAGER", frameworkManagerTargets("LSPosed").last().category)
        assertEquals(2, frameworkManagerTargets("").size)
        assertTrue(frameworkManagerTargets("").all { it.activityName == null })
    }

    @Test
    fun `exported activity still requires its permission and enabled application`() {
        assertTrue(canLaunchFrameworkManager(true, true, true, false, true))
        assertFalse(canLaunchFrameworkManager(true, true, true, false, false))
        assertFalse(canLaunchFrameworkManager(true, true, false, false, true))
        assertFalse(canLaunchFrameworkManager(false, true, true, true, true))
        assertFalse(canLaunchFrameworkManager(true, false, true, true, true))
        assertTrue(canLaunchFrameworkManager(true, true, false, true, false))
    }
}
