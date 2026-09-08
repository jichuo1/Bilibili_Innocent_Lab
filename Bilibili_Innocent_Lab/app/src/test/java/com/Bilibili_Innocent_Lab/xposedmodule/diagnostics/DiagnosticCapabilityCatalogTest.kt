package com.Bilibili_Innocent_Lab.xposedmodule.diagnostics

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsCodec
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeFeatureEvidence
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostFeatureInstallState
import com.Bilibili_Innocent_Lab.xposedmodule.telemetry.TelemetryPayloadCodec
import com.Bilibili_Innocent_Lab.xposedmodule.telemetry.TelemetryIdentity
import com.Bilibili_Innocent_Lab.xposedmodule.telemetry.TelemetryEncodingEnvironment
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DiagnosticCapabilityCatalogTest {
    @Test fun everySettingsEntryIsMappedOrExplicitlyLocalAndLeafIdsAreUnique() {
        val catalog = DiagnosticCapabilityCatalog
        assertEquals(catalog.definitions.size, catalog.leafIds.size)
        assertEquals(SettingsCatalog.specs.map { it.id }.toSet(),
            catalog.definitions.flatMap { it.settingIds }.toSet() + catalog.localOnlySettings.keys)
        assertTrue(catalog.leafIds.all { it.matches(Regex("^[a-z][a-z0-9_]{0,63}$")) })
        assertTrue(DiagnosticFeatureRegistry.ids.size <= HostRuntimeDiagnosticsCodec.MAX_FEATURE_COUNT)
        assertTrue(catalog.splitParents.intersect(catalog.leafIds).isEmpty())
    }

    @Test fun interactiveWhitelistChangesRequireExplicitDiagnosticMapping() {
        val expected = buildSet {
            VersionAdapter.PLAYER_INTERACTIVE_MOSS_FAMILIES.forEach { family ->
                assertNotEquals("unknown", family.diagnosticFamilyId)
                family.clearNames.forEach { add("${family.diagnosticFamilyId}/guide/$it") }
                family.dmClearNames.forEach { add("${family.diagnosticFamilyId}/dm/$it") }
            }
            add("command")
            add("activity")
        }
        assertEquals(expected, DiagnosticCapabilityCatalog.byLocatorKey.keys)
    }

    @Test fun serverCatalogIsAProjectionOfTheAndroidDirectoryNotASecondManualRegistry() {
        val file = File("../../server/src/analytics/capability-catalog.json")
        // 服务端目录仅保留在本地；公开仓库仍运行所有 Android 目录完整性测试。
        org.junit.Assume.assumeTrue("Local server projection is not available in this checkout", file.isFile)
        val root = JSONObject(file.readText())
        assertEquals(DiagnosticCapabilityCatalog.VERSION, root.getInt("version"))
        val rows = root.getJSONArray("capabilities")
        val ids = linkedSetOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val id = row.getString("id")
            assertTrue(ids.add(id))
            val local = DiagnosticCapabilityCatalog.byId.getValue(id)
            assertEquals(local.parentId, row.getString("parent"))
            assertEquals(local.introducedCatalogVersion, row.optInt("since", 1))
            assertEquals(DiagnosticCapabilityCatalog.runtimeSupport(id), row.getInt("runtime"))
            assertTrue(row.getString("label").isNotBlank())
        }
        assertEquals(DiagnosticCapabilityCatalog.leafIds, ids)
    }

    @Test fun allLeafCapabilitiesFitOneBoundedReportWithoutDroppingTheTail() {
        val features = DiagnosticCapabilityCatalog.definitions.map {
            DiagnosticHostFeature(it.id, DiagnosticEvidence.APPLIED, DiagnosticFeatureInstallState.PARTIAL,
                256, "PARTIAL_COVERAGE", true, runtimeError = true)
        }
        val parents = DiagnosticCapabilityCatalog.splitParents.map {
            DiagnosticHostFeature(it, DiagnosticEvidence.APPLIED, DiagnosticFeatureInstallState.PARTIAL,
                256, "PARTIAL_COVERAGE", true, runtimeError = true)
        }
        val model = ModuleHealthEvaluator.evaluate(inputs(hostRuntimeReceiptAvailable = true).copy(
            hostFeatures = features + parents, frameworkVersion = "V".repeat(64), frameworkVersionCode = 2147483647L, hostInstallChainState = DiagnosticHostInstallChainState.COMPLETED
        ))
        val bytes = TelemetryPayloadCodec.encode(model,
            TelemetryIdentity("3e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b", "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG"),
            TelemetryEncodingEnvironment("7f3c1e2a-9b44-4c8e-a1d2-0f5e6a7b8c9d","stable",100,"armeabi-v7a",100000,100000,
                device = com.Bilibili_Innocent_Lab.xposedmodule.telemetry.TelemetryDeviceProfile("厂".repeat(64),"型".repeat(64),"hyperos")))
        assertTrue(bytes.size <= TelemetryPayloadCodec.MAX_PAYLOAD_BYTES)
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        assertEquals(features.size, root.getJSONArray("features").length())
        assertTrue(root.getBoolean("snapshot_complete"))
        assertEquals(parents.size, root.getJSONArray("feature_groups").length())
        assertFalse(String(bytes, Charsets.UTF_8).contains("clearAttention"))
        val host = HostRuntimeDiagnosticsSnapshot(1, "tv.danmaku.bili",
            features.map { HostRuntimeFeatureEvidence(it.featureId,1,1,1,HostFeatureInstallState.PARTIAL,256,"PARTIAL_COVERAGE",true) })
        assertEquals(features.size, HostRuntimeDiagnosticsCodec.decodeOrNull(HostRuntimeDiagnosticsCodec.encode(host))?.features?.size)
    }

    @Test fun unverifiedAndPartialStatesSurviveTheHostProtocolWithoutBecomingInstalled() {
        val partial = HostRuntimeFeatureEvidence("comments_search_links_removed",1,1,0,HostFeatureInstallState.PARTIAL,1,"PARTIAL_COVERAGE",true)
        val unknown = HostRuntimeFeatureEvidence("comments_vote_widgets_removed",0,0,0,HostFeatureInstallState.UNKNOWN,0,"CAPABILITY_UNVERIFIED")
        val snapshot = HostRuntimeDiagnosticsSnapshot(1,"tv.danmaku.bili",listOf(partial,unknown))
        val decoded = HostRuntimeDiagnosticsCodec.decodeOrNull(HostRuntimeDiagnosticsCodec.encode(snapshot))!!
        assertEquals(HostFeatureInstallState.PARTIAL,decoded.features[0].installState)
        assertEquals(HostFeatureInstallState.UNKNOWN,decoded.features[1].installState)
        assertTrue(decoded.features[0].runtimeError)
        assertEquals(0,decoded.installedFeatureCount)
        assertEquals(1,decoded.failedFeatureCount)
    }
}
