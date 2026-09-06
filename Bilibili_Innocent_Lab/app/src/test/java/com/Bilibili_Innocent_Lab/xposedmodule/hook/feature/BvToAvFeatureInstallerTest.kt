package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BvToAvFeatureInstallerTest {

    @Test
    fun `recognizes only well formed bv ids`() {
        assertTrue(BvToAvFeatureInstaller.looksLikeBvId("BV1xx411c7mD"))
        assertFalse(BvToAvFeatureInstaller.looksLikeBvId("BV1xx411c7m"))
        assertFalse(BvToAvFeatureInstaller.looksLikeBvId("av170001"))
        assertFalse(BvToAvFeatureInstaller.looksLikeBvId(""))
        assertFalse(BvToAvFeatureInstaller.looksLikeBvId(null))
    }

    @Test
    fun `picks the av id regardless of parameter order`() {
        assertEquals(
            "170001",
            BvToAvFeatureInstaller.preferredAvId("BV1xx411c7mD", "170001", "BV1xx411c7mD")
        )
        assertEquals(
            "170001",
            BvToAvFeatureInstaller.preferredAvId("BV1xx411c7mD", "BV1xx411c7mD", "170001")
        )
        assertEquals(
            "av170001",
            BvToAvFeatureInstaller.preferredAvId("BV1xx411c7mD", "av170001", null)
        )
    }

    @Test
    fun `keeps the host value when there is no usable av id`() {
        assertNull(BvToAvFeatureInstaller.preferredAvId("BV1xx411c7mD", null, null))
        assertNull(BvToAvFeatureInstaller.preferredAvId("BV1xx411c7mD", "  ", ""))
        assertNull(
            BvToAvFeatureInstaller.preferredAvId("BV1xx411c7mD", "BV1xx411c7mD", "BV1yy411c7mD")
        )
    }

    @Test
    fun `does nothing when the host already returns an av id`() {
        assertNull(BvToAvFeatureInstaller.preferredAvId("170001", "170001", "BV1xx411c7mD"))
    }
}
