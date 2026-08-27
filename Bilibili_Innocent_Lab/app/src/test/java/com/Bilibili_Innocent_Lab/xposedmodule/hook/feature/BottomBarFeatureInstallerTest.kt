package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomBarFeatureInstallerTest {

    @Test
    fun `never hides every bottom bar entry`() {
        assertTrue(BottomBarFeatureInstaller.canHide(total = 5, matched = 2))
        assertFalse(BottomBarFeatureInstaller.canHide(total = 5, matched = 0))
        assertFalse(BottomBarFeatureInstaller.canHide(total = 5, matched = 5))
    }
}
