package com.Bilibili_Innocent_Lab.xposedmodule.diagnostics

import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsCodec
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ReceiptQueryFailure
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.ActivationDisplayState
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DiagnosticPipelineAndRegistryTest {

    @Test
    fun configDeliveryAndFeatureRegistryPolicies() {
        DiagnosticConfigDeliveryCases().verifyAllCases()
        DiagnosticFeatureRegistryCases().verifyAllCases()
    }

    @Test
    fun diagnosticReportCodecAndValidationBounds() {
        DiagnosticReportCodecCases().verifyAllCases()
    }
}

private class DiagnosticConfigDeliveryCases {
    private fun published() = inputs().copy(
        frameworkConnectionId = 1L, remoteConnectionId = 1L,
        hostRuntimeReceiptAvailable = true, hostQueryState = DiagnosticHostQueryState.READY,
        hostConfigState = DiagnosticHostConfigState.ACCEPTED, hostConfigGeneration = 42L
    )

    fun `successful publication is not a host receipt`() {
        assertEquals(DiagnosticConfigDelivery.HOST_UNAVAILABLE, configDelivery(published().copy(
            hostRuntimeReceiptAvailable = false
        )))
        assertEquals(DiagnosticConfigDelivery.HOST_UNAVAILABLE, configDelivery(published().copy(
            hostQueryState = DiagnosticHostQueryState.INVALID_RESPONSE
        )))
    }

    fun `same older and newer host generations are distinct`() {
        assertEquals(DiagnosticConfigDelivery.MATCHED, configDelivery(published()))
        assertEquals(DiagnosticConfigDelivery.HOST_OLDER, configDelivery(published().copy(hostConfigGeneration = 41L)))
        assertEquals(DiagnosticConfigDelivery.HOST_NEWER, configDelivery(published().copy(hostConfigGeneration = 43L)))
    }

    fun `old connection failure and pending publication cannot confirm delivery`() {
        listOf(
            published().copy(frameworkConnectionId = 2L),
            published().copy(frameworkConnected = false),
            published().copy(remotePublishPending = true),
            published().copy(remotePublishState = DiagnosticRemotePublishState.FAILED)
        ).forEach { assertEquals(DiagnosticConfigDelivery.NOT_PUBLISHED, configDelivery(it)) }
    }

    fun `denial receipt can confirm delivery without claiming authorization`() {
        assertEquals(DiagnosticConfigDelivery.MATCHED, configDelivery(published().copy(
            hostConfigState = DiagnosticHostConfigState.NOT_AUTHORIZED
        )))
        assertEquals(DiagnosticConfigDelivery.HOST_REJECTED, configDelivery(published().copy(
            hostConfigState = DiagnosticHostConfigState.REJECTED
        )))
        assertEquals(DiagnosticConfigDelivery.HOST_NOT_CHECKED, configDelivery(published().copy(
            hostConfigState = DiagnosticHostConfigState.NOT_CHECKED
        )))
    }

    fun `NPatch selection never compares the host admission with the bypassed standard publisher`() {
        // 2026-09-30 TB320FC：NPatch 已选中、宿主 admission 存在但对不上标准发布权威，
        // 旧实现得出 HOST_OLDER（“宿主仍使用代次 X，模块已提交代次 0”），实际投递是通的。
        val npatchSelected = published().copy(
            noRootDesiredEnabled = true,
            hostAdmissionPresent = true,
            hostAdmissionCurrent = false,
            remotePublishState = DiagnosticRemotePublishState.FAILED,
            remoteGeneration = 0L
        )
        assertEquals(DiagnosticConfigDelivery.NOT_APPLICABLE, configDelivery(npatchSelected))
        // 未选 NPatch 的既有语义不变：admission 对不上仍是 HOST_OLDER。
        assertEquals(
            DiagnosticConfigDelivery.HOST_OLDER,
            configDelivery(npatchSelected.copy(noRootDesiredEnabled = false))
        )
        // 兼容模式直连另有自己的判据，不被 NPatch 选中遮住。
        assertEquals(
            DiagnosticConfigDelivery.DIRECT_STALE,
            configDelivery(
                npatchSelected.copy(
                    hostConfigSource = "module_direct",
                    hostRuntimeReceiptAvailable = true,
                    hostQueryState = DiagnosticHostQueryState.READY,
                    hostConfigState = DiagnosticHostConfigState.ACCEPTED
                )
            )
        )
    }

    fun `NPatch remains an independent publication path`() {
        assertEquals(DiagnosticConfigDelivery.NOT_APPLICABLE, configDelivery(published().copy(
            activationState = DiagnosticActivationState.ACTIVE_NPATCH
        )))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (DiagnosticConfigDeliveryCases) -> Unit) {
            val s = DiagnosticConfigDeliveryCases()
            block(s)
        }
        runIsolated { s -> s.`successful publication is not a host receipt`() }
        runIsolated { s -> s.`same older and newer host generations are distinct`() }
        runIsolated { s -> s.`old connection failure and pending publication cannot confirm delivery`() }
        runIsolated { s -> s.`denial receipt can confirm delivery without claiming authorization`() }
        runIsolated { s -> s.`NPatch selection never compares the host admission with the bypassed standard publisher`() }
        runIsolated { s -> s.`NPatch remains an independent publication path`() }
    }
}

private class DiagnosticFeatureRegistryCases {
    fun `guest browsing and incognito are independent general groups expecting runtime evidence`() {
        listOf("guest_browsing", "incognito_privacy").forEach { id ->
            val descriptor = requireNotNull(DiagnosticFeatureRegistry.descriptorOrNull(id))
            assertEquals(DiagnosticFeatureCategory.GENERAL, descriptor.category)
            assertTrue(descriptor.runtimeEvidenceExpected)
            assertEquals(1, DiagnosticFeatureRegistry.descriptors.count { it.id == id })
        }
    }

    fun `registry is unique bounded and covers specialized feature paths`() {
        assertEquals(512, HostRuntimeDiagnosticsCodec.MAX_FEATURE_COUNT)
        assertEquals(128 * 1024, HostRuntimeDiagnosticsCodec.MAX_PAYLOAD_CHARS)
        assertEquals(3, HostRuntimeDiagnosticsCodec.CURRENT_SCHEMA_VERSION)
        assertEquals(
            DiagnosticCapabilityCatalog.leafIds.size + DiagnosticCapabilityCatalog.splitParents.size,
            DiagnosticFeatureRegistry.descriptors.size
        )
        assertTrue(
            DiagnosticFeatureRegistry.descriptors.size <=
                HostRuntimeDiagnosticsCodec.MAX_FEATURE_COUNT
        )
        assertEquals(
            DiagnosticFeatureRegistry.descriptors.size,
            DiagnosticFeatureRegistry.ids.size
        )
        assertTrue("free_copy" in DiagnosticFeatureRegistry.ids)
        assertTrue("roaming_compat" in DiagnosticFeatureRegistry.ids)
        assertTrue("player_interactive_overlay" in DiagnosticFeatureRegistry.ids)
        assertTrue("home_frame_lock" in DiagnosticFeatureRegistry.ids)
        assertTrue("home_recommend_early_load" in DiagnosticFeatureRegistry.ids)
        assertTrue("cloud_key_freeze" in DiagnosticFeatureRegistry.ids)
        // 哔哩漫游移植批次：九个新安装器都必须能在诊断中心单独看到运行证据。
        listOf(
            "danmaku_purify",
            "live_room_widgets",
            "share_purify",
            "external_browser",
            "system_media_notification",
            "splash_auto_night",
            "bv_to_av",
            "dynamic_purify",
            "search_purify",
            "player_capabilities",
            "player_speed"
        ).forEach { id ->
            assertTrue(
                "missing ported diagnostic feature $id",
                DiagnosticFeatureRegistry.descriptorOrNull(id)?.runtimeEvidenceExpected == true
            )
        }
        assertTrue(DiagnosticFeatureRegistry.descriptorOrNull("pgc_auto_activity_popup")?.runtimeEvidenceExpected == true)
        assertTrue(
            DiagnosticFeatureRegistry.descriptors
                .filter { it.runtimeEvidenceExpected }
                .all { it.id in DiagnosticFeatureRegistry.ids }
        )
    }

    fun `titleResId covers every registered feature id and prefers capability label`() {
        DiagnosticFeatureRegistry.descriptors.forEach { descriptor ->
            assertNotNull("missing labelRes on descriptor ${descriptor.id}", descriptor.labelRes)
            assertTrue("zero labelRes on descriptor ${descriptor.id}", descriptor.labelRes != 0)
            val resolved = DiagnosticFeatureRegistry.titleResId(descriptor.id)
            assertNotNull("missing titleResId for ${descriptor.id}", resolved)
            assertTrue("zero titleResId for ${descriptor.id}", resolved != 0)
        }
        DiagnosticCapabilityCatalog.definitions.forEach { definition ->
            assertEquals(
                "catalog label must win for ${definition.id}",
                definition.labelRes,
                DiagnosticFeatureRegistry.titleResId(definition.id)
            )
        }
        assertEquals(R.string.no_login_enabled, DiagnosticFeatureRegistry.titleResId("guest_browsing"))
        assertEquals(R.string.incognito_enabled, DiagnosticFeatureRegistry.titleResId("incognito_privacy"))
        assertEquals(R.string.diagnostics_host_feature_home_feed, DiagnosticFeatureRegistry.titleResId("home_recommend_purify"))
        assertEquals(R.string.detail_module_purify_settings, DiagnosticFeatureRegistry.titleResId("detail_module_purify"))
        assertEquals(R.string.block_ai_declared_videos, DiagnosticFeatureRegistry.titleResId("ai_declared_video_block"))
        assertEquals(R.string.purify_splash_ads, DiagnosticFeatureRegistry.titleResId("splash_ad_purify"))
        assertEquals(R.string.sponsorblock_enabled, DiagnosticFeatureRegistry.titleResId("sponsorblock"))
        assertNull(DiagnosticFeatureRegistry.titleResId("unknown_feature_id"))
        assertNull(DiagnosticFeatureRegistry.titleResId(""))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (DiagnosticFeatureRegistryCases) -> Unit) {
            val s = DiagnosticFeatureRegistryCases()
            block(s)
        }
        runIsolated { s -> s.`guest browsing and incognito are independent general groups expecting runtime evidence`() }
        runIsolated { s -> s.`registry is unique bounded and covers specialized feature paths`() }
        runIsolated { s -> s.`titleResId covers every registered feature id and prefers capability label`() }
    }
}

private class DiagnosticReportCodecCases {
    fun `query failures round trip as bounded reasons without changing host evidence`() {
        ReceiptQueryFailure.entries.forEach { reason ->
            val bytes = DiagnosticReportCodec.encode(ModuleHealthEvaluator.evaluate(inputs().copy(hostQueryFailure = reason)))
            DiagnosticReportCodec.validate(bytes)
            assertEquals(
                reason.name,
                JSONObject(String(bytes, StandardCharsets.UTF_8))
                    .getJSONObject("runtime").getString("hostQueryFailure")
            )
        }
        val json = JSONObject(String(DiagnosticReportCodec.encode(ModuleHealthEvaluator.evaluate(inputs())), StandardCharsets.UTF_8))
        json.getJSONObject("runtime").put("hostQueryFailure", "/private/unbounded-message")
        assertTrue(runCatching { DiagnosticReportCodec.validate(json.toString().toByteArray(StandardCharsets.UTF_8)) }.isFailure)
    }

    fun `LSPatch display states retain compatible bounded activation wire values`() {
        val expected = mapOf(
            ActivationDisplayState.ACTIVE_LSPATCH to DiagnosticActivationState.ACTIVE_LSPOSED,
            ActivationDisplayState.LSPATCH_WAITING_FOR_HOST to DiagnosticActivationState.CHECKING,
            ActivationDisplayState.LSPATCH_HOST_FAILED to DiagnosticActivationState.UNAVAILABLE
        )
        expected.forEach { (displayState, diagnosticState) ->
            assertEquals(diagnosticState, displayState.toDiagnosticActivationState())
            val bytes = DiagnosticReportCodec.encode(
                ModuleHealthEvaluator.evaluate(inputs(activationState = diagnosticState))
            )
            DiagnosticReportCodec.validate(bytes)
            val runtime = JSONObject(bytes.toString(StandardCharsets.UTF_8))
                .getJSONObject("runtime")
            assertEquals(diagnosticState.name, runtime.getString("activation"))
        }
    }

    fun `format four separates framework build commit acknowledgement and host receipt`() {
        val state = inputs().copy(
            frameworkName = "Vector", frameworkVersion = "2.2 (3110) c4a701aa",
            frameworkVersionCode = 3110L, frameworkProperties = 7L,
            frameworkConnectionId = 2L, remoteConnectionId = 2L,
            hostRuntimeReceiptAvailable = true, hostQueryState = DiagnosticHostQueryState.READY,
            hostConfigState = DiagnosticHostConfigState.ACCEPTED, hostConfigGeneration = 42L
        )
        val bytes = DiagnosticReportCodec.encode(ModuleHealthEvaluator.evaluate(state))
        DiagnosticReportCodec.validate(bytes)
        val json = JSONObject(bytes.toString(StandardCharsets.UTF_8))
        assertEquals(7, json.getInt("formatVersion"))
        assertEquals(3110L, json.getJSONObject("framework").getLong("versionCode"))
        assertEquals(7L, json.getJSONObject("framework").getLong("properties"))
        assertEquals("MATCHED", json.getJSONObject("remoteConfig").getString("hostDelivery"))
        assertEquals(
            "COMMIT_ACKNOWLEDGED_AND_CLIENT_CACHE_VALIDATED",
            json.getJSONObject("remoteConfig").getString("verification")
        )
        assertFalse(json.getJSONObject("framework").has("moduleUserId"))
    }

    fun `unavailable metadata stays null and unbounded failures are not exported`() {
        val bytes = DiagnosticReportCodec.encode(ModuleHealthEvaluator.evaluate(inputs().copy(
            frameworkFailureCode = "exception with a private path"
        )))
        DiagnosticReportCodec.validate(bytes)
        val framework = JSONObject(bytes.toString(StandardCharsets.UTF_8)).getJSONObject("framework")
        assertTrue(framework.isNull("versionCode"))
        assertTrue(framework.isNull("properties"))
        assertEquals("unknown", framework.getString("failureCode"))
        assertFalse(bytes.toString(StandardCharsets.UTF_8).contains("private path"))
    }

    fun `unknown verification claims are rejected`() {
        val bytes = DiagnosticReportCodec.encode(ModuleHealthEvaluator.evaluate(inputs()))
        val json = JSONObject(bytes.toString(StandardCharsets.UTF_8))
        json.getJSONObject("remoteConfig").put("verification", "REMOTE_DATABASE_VERIFIED")
        DiagnosticReportCodec.validate(json.toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun `encoded report validates and declares its privacy exclusions`() {
        val snapshot = ModuleHealthEvaluator.evaluate(
            inputs(
                hostRuntimeReceiptAvailable = true,
                hostAdaptedFeatureCount = 1,
                hostFeatures = listOf(
                    DiagnosticHostFeature(
                        "home_recommend_purify",
                        DiagnosticEvidence.ADAPTED,
                        installState = DiagnosticFeatureInstallState.INSTALLED,
                        installedHookCount = 2,
                        runtimeEvidenceExpected = true
                    )
                )
            )
        )
        val bytes = DiagnosticReportCodec.encode(snapshot)
        val metadata = DiagnosticReportCodec.validate(bytes)
        val text = bytes.toString(StandardCharsets.UTF_8)

        assertTrue(bytes.size < DiagnosticReportCodec.MAX_FILE_BYTES)
        assertEquals(snapshot.inputs.collectedAtEpochMs, metadata.collectedAtEpochMs)
        assertEquals(snapshot.overallSeverity, metadata.overallSeverity)
        assertEquals(DiagnosticItemId.entries.size, metadata.itemCount)
        DiagnosticReportCodec.excludedCategories.forEach { category ->
            assertTrue(text.contains(category))
        }
        assertTrue(text.contains("home_recommend_purify"))
        assertTrue(text.contains("hostBootstrap"))
        assertTrue(text.contains("installedHookCount"))
    }

    fun `report schema never contains user values paths logs or exception details`() {
        val text = DiagnosticReportCodec.encode(
            ModuleHealthEvaluator.evaluate(
                inputs(
                    remoteFailureCode = "C:\\private\\remote_failure.txt",
                    skinFallbackCode = "java.lang.IllegalStateException: secret"
                )
            )
        ).toString(StandardCharsets.UTF_8)

        listOf(
            "preferenceValue",
            "storageKey",
            "customRuleValue",
            "stackTrace",
            "exceptionMessage",
            "cachePath",
            "memberName",
            "logText"
        ).forEach { forbiddenKey ->
            assertFalse("forbidden key: $forbiddenKey", text.contains(forbiddenKey))
        }
        assertFalse(text.contains("private\\remote_failure"))
        assertFalse(text.contains("IllegalStateException"))
    }

    fun `oversized report is rejected before parsing`() {
        DiagnosticReportCodec.validate(ByteArray(DiagnosticReportCodec.MAX_FILE_BYTES + 1))
    }

    fun `host feature bound follows expanded 512 limit`() {
        assertEquals(
            512,
            HostRuntimeDiagnosticsCodec.MAX_FEATURE_COUNT
        )
        assertEquals(
            128 * 1024,
            HostRuntimeDiagnosticsCodec.MAX_PAYLOAD_CHARS
        )
        val json = JSONObject(
            DiagnosticReportCodec.encode(ModuleHealthEvaluator.evaluate(inputs())).toString(StandardCharsets.UTF_8)
        )
        val hostFeatures = json.getJSONObject("runtime").getJSONArray("hostFeatures")
        val template = JSONObject()
            .put("id", "comment_filter")
            .put("evidence", "ADAPTED")
            .put("installState", "INSTALLED")
            .put("installedHookCount", 1)
            .put("runtimeError", false)
            .put("installReasonCode", JSONObject.NULL)
            .put("runtimeEvidenceExpected", true)
        repeat(HostRuntimeDiagnosticsCodec.MAX_FEATURE_COUNT + 1) {
            hostFeatures.put(JSONObject(template.toString()))
        }
        assertTrue(
            runCatching {
                DiagnosticReportCodec.validate(json.toString().toByteArray(StandardCharsets.UTF_8))
            }.isFailure
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (DiagnosticReportCodecCases) -> Unit) {
            val s = DiagnosticReportCodecCases()
            block(s)
        }
        runIsolated { s -> s.`query failures round trip as bounded reasons without changing host evidence`() }
        runIsolated { s -> s.`LSPatch display states retain compatible bounded activation wire values`() }
        runIsolated { s -> s.`format four separates framework build commit acknowledgement and host receipt`() }
        runIsolated { s -> s.`unavailable metadata stays null and unbounded failures are not exported`() }
        runIsolated { s -> try { s.`unknown verification claims are rejected`(); fail("Expected IllegalArgumentException") } catch (_: IllegalArgumentException) {} }
        runIsolated { s -> s.`encoded report validates and declares its privacy exclusions`() }
        runIsolated { s -> s.`report schema never contains user values paths logs or exception details`() }
        runIsolated { s -> try { s.`oversized report is rejected before parsing`(); fail("Expected IllegalArgumentException") } catch (_: IllegalArgumentException) {} }
        runIsolated { s -> s.`host feature bound follows expanded 512 limit`() }
    }
}
