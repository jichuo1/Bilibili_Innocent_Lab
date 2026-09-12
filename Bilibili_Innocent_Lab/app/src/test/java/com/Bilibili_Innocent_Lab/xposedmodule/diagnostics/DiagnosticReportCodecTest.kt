package com.Bilibili_Innocent_Lab.xposedmodule.diagnostics

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.ActivationDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.nio.charset.StandardCharsets

class DiagnosticReportCodecTest {
    @Test fun `query failures round trip as bounded reasons without changing host evidence`() {
        com.Bilibili_Innocent_Lab.xposedmodule.runtime.ReceiptQueryFailure.entries.forEach { reason ->
            val bytes = DiagnosticReportCodec.encode(ModuleHealthEvaluator.evaluate(inputs().copy(hostQueryFailure = reason)))
            DiagnosticReportCodec.validate(bytes)
            assertEquals(reason.name, JSONObject(String(bytes, StandardCharsets.UTF_8))
                .getJSONObject("runtime").getString("hostQueryFailure"))
        }
        val json = JSONObject(String(DiagnosticReportCodec.encode(ModuleHealthEvaluator.evaluate(inputs())), StandardCharsets.UTF_8))
        json.getJSONObject("runtime").put("hostQueryFailure", "/private/unbounded-message")
        assertTrue(runCatching { DiagnosticReportCodec.validate(json.toString().toByteArray(StandardCharsets.UTF_8)) }.isFailure)
    }

    @Test
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

    @Test
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
        assertEquals(6, json.getInt("formatVersion"))
        assertEquals(3110L, json.getJSONObject("framework").getLong("versionCode"))
        assertEquals(7L, json.getJSONObject("framework").getLong("properties"))
        assertEquals("MATCHED", json.getJSONObject("remoteConfig").getString("hostDelivery"))
        assertEquals(
            "COMMIT_ACKNOWLEDGED_AND_CLIENT_CACHE_VALIDATED",
            json.getJSONObject("remoteConfig").getString("verification")
        )
        assertFalse(json.getJSONObject("framework").has("moduleUserId"))
    }

    @Test
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

    @Test(expected = IllegalArgumentException::class)
    fun `unknown verification claims are rejected`() {
        val bytes = DiagnosticReportCodec.encode(ModuleHealthEvaluator.evaluate(inputs()))
        val json = JSONObject(bytes.toString(StandardCharsets.UTF_8))
        json.getJSONObject("remoteConfig").put("verification", "REMOTE_DATABASE_VERIFIED")
        DiagnosticReportCodec.validate(json.toString().toByteArray(StandardCharsets.UTF_8))
    }

    @Test
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

    @Test
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

    @Test(expected = IllegalArgumentException::class)
    fun `oversized report is rejected before parsing`() {
        DiagnosticReportCodec.validate(ByteArray(DiagnosticReportCodec.MAX_FILE_BYTES + 1))
    }
}
