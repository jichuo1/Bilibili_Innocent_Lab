package com.Bilibili_Innocent_Lab.xposedmodule.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryDevicePolicyTest {
    fun productLabelsAreBoundedAndCanonical() {
        assertEquals("xiaomi", TelemetryDevicePolicy.productLabel(" Xiaomi ", true))
        assertEquals("SM-S9280", TelemetryDevicePolicy.productLabel("SM-S9280"))
        assertEquals("小米 14", TelemetryDevicePolicy.productLabel("小米  14"))
        listOf(null, "", "x".repeat(65), "phone\nname", "<script>", "https://example.test/a", "a/b", "a\u0000b").forEach {
            assertEquals("unknown", TelemetryDevicePolicy.productLabel(it))
        }
    }

    fun romDetectionRequiresEvidenceAndPrioritizesSpecificFamilies() {
        assertEquals("unknown", TelemetryDevicePolicy.romFamily(emptyMap(), "Xiaomi"))
        assertEquals("hyperos", TelemetryDevicePolicy.romFamily(mapOf(
            "ro.mi.os.version.name" to "OS2.0", "ro.miui.ui.version.name" to "V816"
        ), null))
        // HyperOS 为兼容旧应用仍保留 miui 属性，只看 name 会把它错判成 MIUI。
        assertEquals("hyperos", TelemetryDevicePolicy.romFamily(mapOf(
            "ro.mi.os.version.code" to "3", "ro.miui.ui.version.name" to "V816"
        ), null))
        assertEquals("miui", TelemetryDevicePolicy.romFamily(mapOf("ro.miui.ui.version.name" to "V14"), null))
        assertEquals("lineageos", TelemetryDevicePolicy.romFamily(mapOf(
            "ro.lineage.version" to "22.2", "ro.miui.ui.version.name" to "V14"
        ), null))
        assertEquals("coloros", TelemetryDevicePolicy.romFamily(mapOf("ro.build.version.opporom" to "V15"), null))
        assertEquals("originos", TelemetryDevicePolicy.romFamily(mapOf("ro.vivo.os.name" to "OriginOS"), null))
        assertEquals("flyme", TelemetryDevicePolicy.romFamily(emptyMap(), "Flyme 10"))
        assertEquals("unknown", TelemetryDevicePolicy.romFamily(mapOf("ro.miui.ui.version.name" to "unknown"), "UP1A"))
        assertEquals("unknown", TelemetryDevicePolicy.romFamily(mapOf("ro.build.version.oneui" to "0"), null))
    }

    fun oldTelemetryConsentDoesNotAuthorizeExpandedDataAndCoreTermsStayIndependent() {
        listOf(-1, 0, 1, 2, 3, 4, 6).forEach { assertFalse(TelemetryPolicy.disclosureAuthorizesUpload(it)) }
        assertTrue(TelemetryPolicy.disclosureAuthorizesUpload(5))
        assertEquals(2, com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore.CURRENT_TERMS_VERSION)
        assertFalse(TelemetryPolicy.termsChoice(true, false))
    }

    fun oplusFamilySplitsByVendorBecauseTheRomPropertyIsNowShared() {
        val oplus = mapOf("ro.build.version.oplusrom" to "15.0")
        assertEquals("coloros", TelemetryDevicePolicy.romFamily(oplus, null, "OPPO"))
        assertEquals("oxygenos", TelemetryDevicePolicy.romFamily(oplus, null, "OnePlus"))
        assertEquals("realme_ui", TelemetryDevicePolicy.romFamily(oplus, null, "realme"))
        // 厂商认不出时不猜品牌，落回共用的 ColorOS 代码面。
        assertEquals("coloros", TelemetryDevicePolicy.romFamily(oplus, null, null))
        // 版本串自报 OxygenOS 的，厂商标签不参与判定。
        assertEquals("oxygenos", TelemetryDevicePolicy.romFamily(
            oplus + ("ro.rom.version" to "OxygenOS 15.0"), null, "OPPO"
        ))
        assertEquals("realme_ui", TelemetryDevicePolicy.romFamily(
            oplus + ("ro.build.version.realmeui" to "V6.0"), null, "OPPO"
        ))
    }

    fun honorAndSamsungNoLongerFallIntoUnknown() {
        // MagicOS 机器上 emui 属性同样存在，判定顺序必须把荣耀排在华为之前。
        assertEquals("magicos", TelemetryDevicePolicy.romFamily(mapOf(
            "ro.build.version.magic" to "MagicOS 9.0.0", "ro.build.version.emui" to "MagicUI_7.0.0"
        ), null, "HONOR"))
        assertEquals("magicos", TelemetryDevicePolicy.romFamily(
            mapOf("ro.build.version.emui" to "MagicUI_6.0.0"), null, "HUAWEI"
        ))
        assertEquals("emui", TelemetryDevicePolicy.romFamily(
            mapOf("ro.build.version.emui" to "EmotionUI_12.0.0"), null, "HUAWEI"
        ))
        assertEquals("harmonyos", TelemetryDevicePolicy.romFamily(mapOf(
            "hw_sc.build.platform.version" to "4.2.0", "ro.build.version.emui" to "EmotionUI_14"
        ), null, "HUAWEI"))
        // One UI 属性缺失的三星靠平台版本兜底，不再掉进 unknown。
        assertEquals("one_ui", TelemetryDevicePolicy.romFamily(
            mapOf("ro.build.version.sep" to "150100"), null, "samsung"
        ))
    }

    fun customRomEvidenceOutranksLeftoverVendorProperties() {
        assertEquals("aosp", TelemetryDevicePolicy.romFamily(
            mapOf("ro.modversion" to "15.0", "ro.miui.ui.version.name" to "V14"), null, "Xiaomi"
        ))
        for (display in listOf("crDroid 11.0", "PixelExperience_13.0", "AOSP 15", "EvolutionX-10.0")) {
            assertEquals(display, "aosp", TelemetryDevicePolicy.romFamily(emptyMap(), display))
        }
        // 词根太短会误伤正常构建号，这些一律不算证据。
        for (display in listOf("UP1A.231005.007", "TQ3A.230901.001", "V417IR")) {
            assertEquals(display, "unknown", TelemetryDevicePolicy.romFamily(emptyMap(), display))
        }
    }

    fun probeBudgetStaysBoundedWhileTheAllowlistCoversMoreFamilies() {
        val vendors = listOf(
            "Xiaomi", "Redmi", "OPPO", "OnePlus", "realme", "vivo", "iQOO", "samsung",
            "HUAWEI", "HONOR", "Meizu", "ZTE", "nubia", "Lenovo", "motorola", "Nothing",
            "TECNO", "Infinix", null, "", "  ", "totally-unknown-vendor"
        )
        for (vendor in vendors) {
            val label = vendor ?: "<null>"
            val keys = TelemetryDevicePolicy.probeKeys(vendor, null)
            assertTrue(label, keys.size <= TelemetryDevicePolicy.MAX_PROBE_KEYS)
            assertEquals(label, keys.size, keys.distinct().size)
            // 刷机痕迹对任何厂商都要探，否则被刷过的机器只会读到残留的原厂属性。
            assertTrue(label, keys.containsAll(listOf("ro.lineage.version", "ro.modversion")))
            assertTrue(label, TelemetryDevicePolicy.propertyKeys.containsAll(keys))
        }
        // 荣耀拆分前 MANUFACTURER 是 HUAWEI，拆分后是 HONOR；BRAND 要能顶上。
        assertTrue(TelemetryDevicePolicy.probeKeys("unknown-vendor", "HONOR").contains("ro.build.version.magic"))
        assertTrue(TelemetryDevicePolicy.probeKeys("Xiaomi", null).contains("ro.mi.os.version.code"))
    }

    fun everyDetectableFamilyIsAReportableCode() {
        val samples = mapOf(
            "ro.lineage.version" to "lineageos", "ro.modversion" to "aosp",
            "ro.mi.os.version.code" to "hyperos", "ro.miui.ui.version.name" to "miui",
            "ro.build.version.magic" to "magicos", "ro.build.version.realmeui" to "realme_ui",
            "ro.oxygen.version" to "oxygenos", "ro.build.version.opporom" to "coloros",
            "ro.build.version.oneui" to "one_ui", "ro.build.version.sep" to "one_ui",
            "hw_sc.build.platform.version" to "harmonyos", "ro.build.version.emui" to "emui",
            "ro.build.flyme.version" to "flyme", "ro.build.rom.id" to "nubiaui",
            "ro.build.myos.version" to "myos", "ro.com.zui.version" to "zui",
            "ro.nothing.version" to "nothingos"
        )
        for ((key, expected) in samples) {
            assertTrue(key, key in TelemetryDevicePolicy.propertyKeys)
            assertEquals(key, expected, TelemetryDevicePolicy.romFamily(mapOf(key to "1.0"), null))
            assertTrue(expected, expected in TelemetryDevicePolicy.romCodes)
        }
        assertEquals("hios", TelemetryDevicePolicy.romFamily(mapOf("ro.tranos.version" to "1"), null, "TECNO"))
        assertEquals("xos", TelemetryDevicePolicy.romFamily(mapOf("ro.tranos.version" to "1"), null, "Infinix"))
    }

    fun propertyAllowlistContainsNoUniqueIdentifierOrFullBuildDump() {
        // 白名单可以随家族增长，**单机实际探测量**才是隐私与耗时边界。
        assertTrue(TelemetryDevicePolicy.MAX_PROBE_KEYS <= 12)
        assertEquals(
            TelemetryDevicePolicy.propertyKeys.size,
            TelemetryDevicePolicy.propertyKeys.distinct().size
        )
        assertTrue(TelemetryDevicePolicy.propertyKeys.none {
            it.contains("serial") || it.contains("fingerprint") || it.contains("android_id") ||
                it.contains("imei") || it.contains("mac") || it.contains("uuid")
        })
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (TelemetryDevicePolicyTest) -> Unit) {
            val s = TelemetryDevicePolicyTest()
            block(s)
        }
        runIsolated { s -> s.productLabelsAreBoundedAndCanonical() }
        runIsolated { s -> s.romDetectionRequiresEvidenceAndPrioritizesSpecificFamilies() }
        runIsolated { s -> s.oldTelemetryConsentDoesNotAuthorizeExpandedDataAndCoreTermsStayIndependent() }
        runIsolated { s -> s.oplusFamilySplitsByVendorBecauseTheRomPropertyIsNowShared() }
        runIsolated { s -> s.honorAndSamsungNoLongerFallIntoUnknown() }
        runIsolated { s -> s.customRomEvidenceOutranksLeftoverVendorProperties() }
        runIsolated { s -> s.probeBudgetStaysBoundedWhileTheAllowlistCoversMoreFamilies() }
        runIsolated { s -> s.everyDetectableFamilyIsAReportableCode() }
        runIsolated { s -> s.propertyAllowlistContainsNoUniqueIdentifierOrFullBuildDump() }
    }
}
