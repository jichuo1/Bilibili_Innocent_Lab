package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FullNumberFeatureInstallerTest {

    @Test
    fun `converts supported non-negative integers without allocation helpers`() {
        assertEquals("0", FullNumberFeatureInstaller.rawNumberText(0))
        assertEquals("123456789", FullNumberFeatureInstaller.rawNumberText(123456789L))
        assertEquals("12", FullNumberFeatureInstaller.rawNumberText(12.toShort()))
        assertEquals("7", FullNumberFeatureInstaller.rawNumberText(7.toByte()))
        assertEquals("00123", FullNumberFeatureInstaller.rawNumberText("00123"))
    }

    @Test
    fun `fails open for unsupported or ambiguous values`() {
        assertNull(FullNumberFeatureInstaller.rawNumberText(-1))
        assertNull(FullNumberFeatureInstaller.rawNumberText(-1L))
        assertNull(FullNumberFeatureInstaller.rawNumberText(""))
        assertNull(FullNumberFeatureInstaller.rawNumberText("12.3"))
        assertNull(FullNumberFeatureInstaller.rawNumberText(" 123 "))
        assertNull(FullNumberFeatureInstaller.rawNumberText(null))
    }
}
