package com.Bilibili_Innocent_Lab.xposedmodule.telemetry

import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticEvidence
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticFeatureInstallState
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticHostFeature
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.ModuleHealthEvaluator
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.inputs
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetrySuiteTest {

    @Test
    fun telemetryPolicyAndConsentGates() {
        TelemetryPolicyCases().verifyAllCases()
    }

    @Test
    fun telemetryVersionQuotaAndPendingUploadCoalescing() {
        TelemetryVersionPolicyCases().verifyAllCases()
        PendingVersionUploadCases().`busy notifications coalesce without losing the first completion callback`()
    }

    @Test
    fun deviceLabelRomDetectionAndProbeBudgets() {
        TelemetryDevicePolicyCases().verifyAllCases()
    }

    @Test
    fun payloadCodecWhitelistsAndEndpoints() {
        TelemetryPayloadCodecCases().verifyAllCases()
    }
}

private class PendingVersionUploadCases {
    fun `busy notifications coalesce without losing the first completion callback`() {
        val pending = PendingVersionUpload()
        var calls = 0
        assertTrue(pending.offer { calls++ })
        assertFalse(pending.offer { calls += 100 })
        assertEquals(0, calls)
        pending.take()!!.invoke()
        assertEquals(1, calls)
        assertNull(pending.take())
        assertTrue(pending.offer { calls++ })
    }
}

private class TelemetryPolicyCases {
    fun `manual quota is rolling persistent and fails closed on corruption or clock rollback`() {
        val now = 200_000_000L
        val window = TelemetryPolicy.SUCCESS_INTERVAL_MS
        assertEquals(emptyList<Long>(), TelemetryPolicy.activeManualAttempts("", now))
        assertEquals(listOf(now - 1), TelemetryPolicy.activeManualAttempts("${now - window},${now - 1}", now))
        assertEquals(null, TelemetryPolicy.activeManualAttempts("bad", now))
        assertEquals(null, TelemetryPolicy.activeManualAttempts("1,2,3,4", now))
        assertEquals(TelemetryAttemptDecision.MANUAL_LIMIT_REACHED,
            TelemetryPolicy.manualDecision(true, false, now, 0, "$now,$now,${now + 1}"))
        assertEquals(TelemetryAttemptDecision.MANUAL_LIMIT_REACHED,
            TelemetryPolicy.manualDecision(true, false, now, 0, "bad"))
        assertEquals(TelemetryAttemptDecision.ALLOWED,
            TelemetryPolicy.manualDecision(true, false, now, 0, "${now - window},$now,$now"))
    }

    fun `manual and automatic gates are independent but consent retirement and server backoff remain mandatory`() {
        val now = 200_000_000L
        assertEquals(TelemetryAttemptDecision.NOT_DUE,
            TelemetryPolicy.attemptDecision(true, false, now, now + 1000, now, force = false))
        assertEquals(TelemetryAttemptDecision.ALLOWED,
            TelemetryPolicy.manualDecision(true, false, now, 0, ""))
        assertEquals(TelemetryAttemptDecision.DISABLED,
            TelemetryPolicy.manualDecision(false, false, now, 0, ""))
        assertEquals(TelemetryAttemptDecision.RETIRED,
            TelemetryPolicy.manualDecision(true, true, now, 0, ""))
        assertEquals(TelemetryAttemptDecision.NOT_DUE,
            TelemetryPolicy.manualDecision(true, false, now, now + 1, ""))
        assertEquals(TelemetryAttemptDecision.ALLOWED,
            TelemetryPolicy.attemptDecision(true, false, now, now, now - TelemetryPolicy.SUCCESS_INTERVAL_MS, force = false))
    }

    fun `telemetry consent identity and runtime state stay outside settings backup`() {
        assertTrue(
            SettingsCatalog.specs.none { spec ->
                "telemetry" in spec.id || "telemetry" in spec.storageKey
            }
        )
        assertTrue(RemoteHookConfigContract.persistedKeys.none { "telemetry" in it })
    }

    fun `terms choice is visually on by default but never authorizes without a current record`() {
        assertTrue(TelemetryPolicy.termsChoice(hasCurrentChoice = false, currentChoice = false))
        assertFalse(
            TelemetryPolicy.consentAuthorizesUpload(
                termsAuthorized = true,
                hasEnabledChoice = false,
                enabled = true,
                storedTermsVersion = 2,
                currentTermsVersion = 2
            )
        )
    }

    fun `upload requires terms authorization current consent version and enabled choice`() {
        assertTrue(
            TelemetryPolicy.consentAuthorizesUpload(
                termsAuthorized = true,
                hasEnabledChoice = true,
                enabled = true,
                storedTermsVersion = 2,
                currentTermsVersion = 2
            )
        )
        listOf(
            arrayOf(false, true, true, true),
            arrayOf(true, false, true, true),
            arrayOf(true, true, false, true),
            arrayOf(true, true, true, false)
        ).forEach { conditions ->
            assertFalse(
                TelemetryPolicy.consentAuthorizesUpload(
                    termsAuthorized = conditions[0],
                    hasEnabledChoice = conditions[1],
                    enabled = conditions[2],
                    storedTermsVersion = if (conditions[3]) 2 else 1,
                    currentTermsVersion = 2
                )
            )
        }
    }

    fun `force bypasses daily interval but not disable retirement or retry window`() {
        val now = 1_000_000L
        assertEquals(
            TelemetryAttemptDecision.ALLOWED,
            TelemetryPolicy.attemptDecision(true, false, now, 0L, now - 1L, force = true)
        )
        assertEquals(
            TelemetryAttemptDecision.DISABLED,
            TelemetryPolicy.attemptDecision(false, false, now, 0L, 0L, force = true)
        )
        assertEquals(
            TelemetryAttemptDecision.RETIRED,
            TelemetryPolicy.attemptDecision(true, true, now, 0L, 0L, force = true)
        )
        assertEquals(
            TelemetryAttemptDecision.NOT_DUE,
            TelemetryPolicy.attemptDecision(true, false, now, now + 1L, 0L, force = true)
        )
    }

    fun `identity rotation is bounded and clock rollback fails closed`() {
        val now = 2_000_000_000L
        assertFalse(TelemetryPolicy.shouldRotateIdentity(now - 1_000L, now))
        assertTrue(TelemetryPolicy.shouldRotateIdentity(0L, now))
        assertTrue(TelemetryPolicy.shouldRotateIdentity(now + 1L, now))
        assertTrue(
            TelemetryPolicy.shouldRotateIdentity(
                now - TelemetryPolicy.IDENTITY_ROTATION_MS,
                now
            )
        )
    }

    fun `HTTP status classification keeps retirement and retry semantics distinct`() {
        assertEquals(TelemetryHttpOutcome.SUCCESS, TelemetryPolicy.classifyHttpStatus(204))
        assertEquals(TelemetryHttpOutcome.RETIRED, TelemetryPolicy.classifyHttpStatus(410))
        assertEquals(TelemetryHttpOutcome.RATE_LIMITED, TelemetryPolicy.classifyHttpStatus(429))
        assertEquals(TelemetryHttpOutcome.REJECTED, TelemetryPolicy.classifyHttpStatus(400))
        assertEquals(
            TelemetryHttpOutcome.RETRYABLE_FAILURE,
            TelemetryPolicy.classifyHttpStatus(503)
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (TelemetryPolicyCases) -> Unit) {
            val s = TelemetryPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`manual quota is rolling persistent and fails closed on corruption or clock rollback`() }
        runIsolated { s -> s.`manual and automatic gates are independent but consent retirement and server backoff remain mandatory`() }
        runIsolated { s -> s.`telemetry consent identity and runtime state stay outside settings backup`() }
        runIsolated { s -> s.`terms choice is visually on by default but never authorizes without a current record`() }
        runIsolated { s -> s.`upload requires terms authorization current consent version and enabled choice`() }
        runIsolated { s -> s.`force bypasses daily interval but not disable retirement or retry window`() }
        runIsolated { s -> s.`identity rotation is bounded and clock rollback fails closed`() }
        runIsolated { s -> s.`HTTP status classification keeps retirement and retry semantics distinct`() }
    }
}

private class TelemetryVersionPolicyCases {
    fun `either numeric version changing is a new pair including downgrade`() {
        val now = 200_000_000L
        val current = TelemetryVersionPolicy.key(15, 91100)!!
        assertEquals("15:91100", current)
        assertNull(TelemetryVersionPolicy.key(0, 91100))
        assertNull(TelemetryVersionPolicy.key(15, Long.MAX_VALUE))
        assertFalse(TelemetryVersionPolicy.allowed(current, current, "", 0, now))
        listOf(null, "14:91100", "16:91100", "15:91000").forEach {
            assertTrue(TelemetryVersionPolicy.allowed(current, it, "", 0, now))
        }
    }

    fun `three attempts persist across restart clock rollback and exact 24h boundary`() {
        val now = 200_000_000L
        val attempts = "$now,${now + 1},${now + 2}"
        assertFalse(TelemetryVersionPolicy.allowed("15:91100", null, attempts, 0, now - 1))
        assertFalse(TelemetryVersionPolicy.allowed("16:91100", null, attempts, 0, now + 86_399_999))
        assertTrue(TelemetryVersionPolicy.allowed("16:91100", null, attempts, 0, now + 86_400_000))
        listOf("bad", "1,2,3,4", "0").forEach {
            assertFalse(TelemetryVersionPolicy.allowed("15:91100", null, it, 0, now))
        }
        assertFalse(TelemetryVersionPolicy.allowed("15:91100", null, "", now + 1, now))
    }

    fun `version quota exhaustion does not alter manual or automatic policy`() {
        val now = 200_000_000L
        assertFalse(TelemetryVersionPolicy.allowed("15:91100", null, "$now,$now,$now", 0, now))
        assertEquals(TelemetryAttemptDecision.ALLOWED,
            TelemetryPolicy.manualDecision(true, false, now, 0, ""))
        assertEquals(TelemetryAttemptDecision.ALLOWED,
            TelemetryPolicy.attemptDecision(true, false, now, 0, 0, false))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (TelemetryVersionPolicyCases) -> Unit) {
            val s = TelemetryVersionPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`either numeric version changing is a new pair including downgrade`() }
        runIsolated { s -> s.`three attempts persist across restart clock rollback and exact 24h boundary`() }
        runIsolated { s -> s.`version quota exhaustion does not alter manual or automatic policy`() }
    }
}

private class TelemetryDevicePolicyCases {
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
        assertEquals(2, UserTermsConsentStore.CURRENT_TERMS_VERSION)
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

    fun verifyAllCases() {
        fun runIsolated(block: (TelemetryDevicePolicyCases) -> Unit) {
            val s = TelemetryDevicePolicyCases()
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

private class TelemetryPayloadCodecCases {
    fun parentStatusIsDerivedConservativelyWithoutCountingUnknownAsSuccess() {
        assertEquals("partial", TelemetryPayloadCodec.groupState("installed", listOf("installed", "missing")))
        assertEquals("unknown", TelemetryPayloadCodec.groupState("installed", listOf("installed", "unknown")))
        assertEquals("missing", TelemetryPayloadCodec.groupState("installed", listOf("missing", "missing")))
        assertEquals("not_applicable", TelemetryPayloadCodec.groupState("installed", listOf("not_applicable")))
        assertEquals("partial", TelemetryPayloadCodec.groupState("failed", listOf("installed")))
        assertEquals("unknown", TelemetryPayloadCodec.groupState("installed", emptyList()))
    }

    fun deviceAndServiceVersionAreExplicitAndStaleServiceMetadataIsNotReused() {
        val input = inputs(hostRuntimeReceiptAvailable = true).copy(
            frameworkConnected = true, frameworkApiVersion = 102,
            frameworkVersion = "2.2 (3110)", frameworkVersionCode = 3110L
        )
        val environment = TelemetryEncodingEnvironment(
            "7f3c1e2a-9b44-4c8e-a1d2-0f5e6a7b8c9d", "stable", 33, "arm64-v8a", 54, 49,
            device = TelemetryDeviceProfile("Xiaomi", "Xiaomi 14", "hyperos")
        )
        val identity = TelemetryIdentity("3e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b",
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG")
        val root = JSONObject(String(TelemetryPayloadCodec.encode(
            ModuleHealthEvaluator.evaluate(input), identity, environment
        ), Charsets.UTF_8))
        assertEquals(5, root.getInt("disclosure_version"))
        assertEquals(setOf("manufacturer", "model", "rom"),
            root.getJSONObject("device").keys().asSequence().toSet())
        assertEquals("xiaomi", root.getJSONObject("device").getString("manufacturer"))
        assertEquals("hyperos", root.getJSONObject("device").getString("rom"))
        assertEquals(3110, root.getJSONObject("runtime").getInt("framework_version_code"))
        assertEquals(102, root.getJSONObject("runtime").getInt("framework_api"))
        val disconnected = JSONObject(String(TelemetryPayloadCodec.encode(
            ModuleHealthEvaluator.evaluate(input.copy(frameworkConnected = false)), identity, environment
        ), Charsets.UTF_8)).getJSONObject("runtime")
        assertEquals("unknown", disconnected.getString("framework_version"))
        assertEquals(0, disconnected.getInt("framework_version_code"))
    }

    fun `payload is a bounded whitelist and omits disabled preference evidence`() {
        val snapshot = ModuleHealthEvaluator.evaluate(
            inputs(
                hostRuntimeReceiptAvailable = true,
                hostFeatures = listOf(
                    DiagnosticHostFeature(
                        featureId = "comments_search_links_removed",
                        evidence = DiagnosticEvidence.APPLIED,
                        installState = DiagnosticFeatureInstallState.INSTALLED,
                        installedHookCount = 3
                    ),
                    DiagnosticHostFeature(
                        featureId = "home_recommend_purify",
                        evidence = DiagnosticEvidence.NOT_AVAILABLE,
                        installState = DiagnosticFeatureInstallState.DISABLED,
                        installReasonCode = "DISABLED"
                    ),
                    DiagnosticHostFeature(
                        featureId = "player_interactive_dm_commands",
                        evidence = DiagnosticEvidence.ADAPTED,
                        installState = DiagnosticFeatureInstallState.FAILED,
                        installReasonCode = "REGISTRATION_FAILED"
                    ),
                    DiagnosticHostFeature(
                        featureId = "not_reported",
                        evidence = DiagnosticEvidence.NOT_AVAILABLE,
                        installState = DiagnosticFeatureInstallState.NOT_REPORTED
                    ),
                    DiagnosticHostFeature(
                        featureId = "legacy_disabled_skip",
                        evidence = DiagnosticEvidence.NOT_AVAILABLE,
                        installState = DiagnosticFeatureInstallState.SKIPPED,
                        installReasonCode = "DISABLED"
                    )
                )
            ).copy(
                hostHookPointResolvedCount = 58,
                hostHookPointInstalledCount = 56,
                hostHookPointMissingCount = 2,
                hostHookPointFailedCount = 0
            )
        )
        val bytes = TelemetryPayloadCodec.encode(
            snapshot,
            TelemetryIdentity(
                installId = "3e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b",
                purgeToken = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG"
            ),
            TelemetryEncodingEnvironment(
                reportId = "7f3c1e2a-9b44-4c8e-a1d2-0f5e6a7b8c9d",
                moduleChannel = "stable",
                androidSdk = 33,
                abi = "arm64-v8a",
                adapterSchemaVersion = 54,
                adapterRuleVersion = 49
            )
        )

        assertTrue(bytes.size <= TelemetryPayloadCodec.MAX_PAYLOAD_BYTES)
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        assertEquals(
            setOf(
                "schema_version",
                "feature_catalog_version",
                "snapshot_complete",
                "snapshot_state",
                "evidence_scope",
                "feature_groups",
                "device",
                "disclosure_version",
                "upload_kind",
                "client_report_id",
                "install_id",
                "purge_token",
                "module",
                "host",
                "runtime",
                "adaptation",
                "features"
            ),
            root.keys().asSequence().toSet()
        )
        assertFalse(root.has("settings"))
        assertFalse(root.has("logs"))
        assertFalse(root.has("exceptions"))

        val adaptation = root.getJSONObject("adaptation")
        assertEquals("unknown", adaptation.getString("dex_assist_used"))
        assertEquals(58, adaptation.getJSONObject("bootstrap").getInt("resolved"))

        val features = root.getJSONArray("features")
        assertEquals(2, features.length())
        assertEquals("comments_search_links_removed", features.getJSONObject(0).getString("id"))
        assertEquals("installed", features.getJSONObject(0).getString("state"))
        assertEquals(1, features.getJSONObject(0).getInt("observed"))
        assertEquals(1, features.getJSONObject(0).getInt("applied"))
        assertEquals("player_interactive_dm_commands", features.getJSONObject(1).getString("id"))
        assertEquals("failed", features.getJSONObject(1).getString("state"))

        val serialized = root.toString()
        assertFalse(serialized.contains("home_recommend_purify"))
        assertFalse(serialized.contains("not_reported"))
        assertFalse(serialized.contains("legacy_disabled_skip"))
        assertFalse(serialized.contains("DISABLED"))
    }

    fun `framework names are reduced to a bounded server enum`() {
        assertEquals("lsposed", TelemetryPayloadCodec.frameworkCode("LSPosed"))
        assertEquals("vector", TelemetryPayloadCodec.frameworkCode("Vector"))
        assertEquals("irena", TelemetryPayloadCodec.frameworkCode("Irena"))
        assertEquals("lspatch", TelemetryPayloadCodec.frameworkCode("LSPatch"))
        assertEquals("unknown", TelemetryPayloadCodec.frameworkCode("private build 123"))
    }

    fun `debug and production endpoints are compile-time fixed HTTPS hosts`() {
        assertEquals(
            "https://telemetry-staging.bilibili.date",
            TelemetryEndpoint.baseUrl(debug = true)
        )
        assertEquals(
            "https://telemetry.bilibili.date",
            TelemetryEndpoint.baseUrl(debug = false)
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (TelemetryPayloadCodecCases) -> Unit) {
            val s = TelemetryPayloadCodecCases()
            block(s)
        }
        runIsolated { s -> s.parentStatusIsDerivedConservativelyWithoutCountingUnknownAsSuccess() }
        runIsolated { s -> s.deviceAndServiceVersionAreExplicitAndStaleServiceMetadataIsNotReused() }
        runIsolated { s -> s.`payload is a bounded whitelist and omits disabled preference evidence`() }
        runIsolated { s -> s.`framework names are reduced to a bounded server enum`() }
        runIsolated { s -> s.`debug and production endpoints are compile-time fixed HTTPS hosts`() }
    }
}
