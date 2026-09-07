package com.Bilibili_Innocent_Lab.xposedmodule.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryDevicePolicyTest {
    @Test fun productLabelsAreBoundedAndCanonical() {
        assertEquals("xiaomi", TelemetryDevicePolicy.productLabel(" Xiaomi ", true))
        assertEquals("SM-S9280", TelemetryDevicePolicy.productLabel("SM-S9280"))
        assertEquals("小米 14", TelemetryDevicePolicy.productLabel("小米  14"))
        listOf(null, "", "x".repeat(65), "phone\nname", "<script>", "https://example.test/a", "a/b", "a\u0000b").forEach {
            assertEquals("unknown", TelemetryDevicePolicy.productLabel(it))
        }
    }

    @Test fun romDetectionRequiresEvidenceAndPrioritizesSpecificFamilies() {
        assertEquals("unknown", TelemetryDevicePolicy.romFamily(emptyMap(), "Xiaomi"))
        assertEquals("hyperos", TelemetryDevicePolicy.romFamily(mapOf(
            "ro.mi.os.version.name" to "OS2.0", "ro.miui.ui.version.name" to "V816"
        ), null))
        assertEquals("lineageos", TelemetryDevicePolicy.romFamily(mapOf(
            "ro.lineage.version" to "22.2", "ro.miui.ui.version.name" to "V14"
        ), null))
        assertEquals("coloros", TelemetryDevicePolicy.romFamily(mapOf("ro.build.version.opporom" to "V15"), null))
        assertEquals("originos", TelemetryDevicePolicy.romFamily(mapOf("ro.vivo.os.name" to "OriginOS"), null))
        assertEquals("flyme", TelemetryDevicePolicy.romFamily(emptyMap(), "Flyme 10"))
        assertEquals("unknown", TelemetryDevicePolicy.romFamily(mapOf("ro.miui.ui.version.name" to "unknown"), "UP1A"))
        assertEquals("unknown", TelemetryDevicePolicy.romFamily(mapOf("ro.build.version.oneui" to "0"), null))
    }

    @Test fun oldTelemetryConsentDoesNotAuthorizeExpandedDataAndCoreTermsStayIndependent() {
        listOf(-1, 0, 1, 2, 3, 5).forEach { assertFalse(TelemetryPolicy.disclosureAuthorizesUpload(it)) }
        assertTrue(TelemetryPolicy.disclosureAuthorizesUpload(4))
        assertEquals(2, com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore.CURRENT_TERMS_VERSION)
        assertFalse(TelemetryPolicy.termsChoice(true, false))
    }

    @Test fun propertyAllowlistContainsNoUniqueIdentifierOrFullBuildDump() {
        assertTrue(TelemetryDevicePolicy.propertyKeys.size <= 12)
        assertTrue(TelemetryDevicePolicy.propertyKeys.none {
            it.contains("serial") || it.contains("fingerprint") || it.contains("android_id")
        })
    }
}
