package com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart

import org.junit.Assert.*
import org.junit.Test

class ShizukuAvailabilityTest {
    @Test fun missingManagerAndBinderHideTheEntry() {
        val result = ShizukuAvailability.classify(false, false)
        assertEquals(ShizukuAvailability.Status.MISSING, result.status)
        assertFalse(result.visible)
        assertFalse(result.recommended)
    }
    @Test fun installedManagerWithoutLiveServiceRemainsVisibleButDisabled() {
        val result = ShizukuAvailability.classify(true, false, 13, 2000, ShizukuAvailability.Permission.GRANTED)
        assertEquals(ShizukuAvailability.Status.STOPPED, result.status)
        assertTrue(result.visible)
        assertFalse(result.recommended)
    }
    @Test fun staleApiAndUnexpectedIdentityCannotBeRecommendedEvenWhenGranted() {
        listOf(12 to 2000, 13 to 10001, 13 to -1).forEach { (version, uid) ->
            val result = ShizukuAvailability.classify(true, true, version, uid, ShizukuAvailability.Permission.GRANTED)
            assertEquals(ShizukuAvailability.Status.UNSUPPORTED, result.status)
            assertTrue(result.visible)
            assertFalse(result.recommended)
        }
    }
    @Test fun liveSupportedServiceIsRecommendedBeforeAndAfterExplicitAuthorization() {
        ShizukuAvailability.Permission.entries.forEach { permission ->
            val result = ShizukuAvailability.classify(true, true, 13, 2000, permission)
            assertTrue(result.visible)
            assertTrue(result.recommended)
            assertEquals(2000, result.uid)
        }
    }
    @Test fun availableSuiBinderDoesNotRequireTheShizukuManagerPackage() {
        val result = ShizukuAvailability.classify(false, true, 13, 0, ShizukuAvailability.Permission.GRANTED)
        assertEquals(ShizukuAvailability.Status.READY, result.status)
        assertTrue(result.recommended)
    }
}
