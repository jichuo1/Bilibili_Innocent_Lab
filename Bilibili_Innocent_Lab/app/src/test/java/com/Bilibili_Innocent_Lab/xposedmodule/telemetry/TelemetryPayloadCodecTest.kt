package com.Bilibili_Innocent_Lab.xposedmodule.telemetry

import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticEvidence
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticFeatureInstallState
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticHostFeature
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.ModuleHealthEvaluator
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.inputs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryPayloadCodecTest {
    @Test
    fun parentStatusIsDerivedConservativelyWithoutCountingUnknownAsSuccess() {
        assertEquals("partial", TelemetryPayloadCodec.groupState("installed", listOf("installed", "missing")))
        assertEquals("unknown", TelemetryPayloadCodec.groupState("installed", listOf("installed", "unknown")))
        assertEquals("missing", TelemetryPayloadCodec.groupState("installed", listOf("missing", "missing")))
        assertEquals("not_applicable", TelemetryPayloadCodec.groupState("installed", listOf("not_applicable")))
        assertEquals("partial", TelemetryPayloadCodec.groupState("failed", listOf("installed")))
        assertEquals("unknown", TelemetryPayloadCodec.groupState("installed", emptyList()))
    }

    @Test
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

    @Test
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

    @Test
    fun `framework names are reduced to a bounded server enum`() {
        assertEquals("lsposed", TelemetryPayloadCodec.frameworkCode("LSPosed"))
        assertEquals("vector", TelemetryPayloadCodec.frameworkCode("Vector"))
        assertEquals("irena", TelemetryPayloadCodec.frameworkCode("Irena"))
        assertEquals("lspatch", TelemetryPayloadCodec.frameworkCode("LSPatch"))
        assertEquals("unknown", TelemetryPayloadCodec.frameworkCode("private build 123"))
    }

    @Test
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
}
