package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRuntimeDiagnosticsCodecTest {
    @Test
    fun `round trip keeps only bounded allowlisted evidence`() {
        var evidence: HostRuntimeFeatureEvidence? = null
        evidence = HostRuntimeDiagnosticsCodec.increment(
            evidence, "home_recommend_purify", FeatureRuntimeStage.ADAPTED, 1
        )
        evidence = HostRuntimeDiagnosticsCodec.increment(
            evidence, "home_recommend_purify", FeatureRuntimeStage.OBSERVED, 2
        )
        evidence = HostRuntimeDiagnosticsCodec.increment(
            evidence, "home_recommend_purify", FeatureRuntimeStage.APPLIED, 3
        )
        evidence = HostRuntimeDiagnosticsCodec.withInstallOutcome(
            evidence,
            "home_recommend_purify",
            HostFeatureInstallState.INSTALLED,
            hookCount = 3,
            reasonCode = null
        )
        val decoded = HostRuntimeDiagnosticsCodec.decodeOrNull(
            HostRuntimeDiagnosticsCodec.encode(
                HostRuntimeDiagnosticsSnapshot(
                    capturedAtEpochMs = 123L,
                    processName = HostRuntimeDiagnosticsCodec.TARGET_PACKAGE,
                    features = listOf(requireNotNull(evidence)),
                    bootstrap = HostRuntimeBootstrapEvidence(
                        bootstrapReached = true,
                        configState = HostConfigState.ACCEPTED,
                        configGeneration = 42L,
                        installChainState = HostInstallChainState.COMPLETED,
                        hookPointInstalledCount = 3
                    )
                )
            )
        )

        val feature = requireNotNull(decoded).features.single()
        assertEquals(1L, feature.adaptedCount)
        assertEquals(1L, feature.observedCount)
        assertEquals(1L, feature.appliedCount)
        assertEquals(1, decoded.adaptedFeatureCount)
        assertEquals(1, decoded.observedFeatureCount)
        assertEquals(1, decoded.appliedFeatureCount)
        assertEquals(HostFeatureInstallState.INSTALLED, feature.installState)
        assertEquals(3, feature.installedHookCount)
        assertEquals(42L, decoded.bootstrap.configGeneration)
        assertEquals(HostInstallChainState.COMPLETED, decoded.bootstrap.installChainState)
    }

    @Test
    fun `unknown feature never enters the protocol`() {
        assertNull(
            HostRuntimeDiagnosticsCodec.increment(
                null, "arbitrary_member_name", FeatureRuntimeStage.APPLIED, 1
            )
        )
    }

    @Test
    fun `decoder rejects unknown duplicate and invalid counters`() {
        val valid = HostRuntimeDiagnosticsCodec.encode(
            HostRuntimeDiagnosticsSnapshot(
                capturedAtEpochMs = 123L,
                processName = HostRuntimeDiagnosticsCodec.TARGET_PACKAGE,
                features = listOf(
                    HostRuntimeFeatureEvidence("comment_filter", 1L, 1L, 0L)
                )
            )
        )
        val unknown = JSONObject(valid).apply {
            getJSONArray("features").getJSONObject(0).put("id", "unknown")
        }.toString()
        val invalid = JSONObject(valid).apply {
            getJSONArray("features").getJSONObject(0).put("adapted", 2L)
        }.toString()
        val duplicate = JSONObject(valid).apply {
            getJSONArray("features").put(
                JSONObject(getJSONArray("features").getJSONObject(0).toString())
            )
        }.toString()

        assertNull(HostRuntimeDiagnosticsCodec.decodeOrNull(unknown))
        assertNull(HostRuntimeDiagnosticsCodec.decodeOrNull(invalid))
        assertNull(HostRuntimeDiagnosticsCodec.decodeOrNull(duplicate))
        assertTrue(valid.length <= HostRuntimeDiagnosticsCodec.MAX_PAYLOAD_CHARS)
    }

    @Test
    fun `decoder rejects impossible bootstrap and install combinations`() {
        val valid = HostRuntimeDiagnosticsCodec.encode(
            HostRuntimeDiagnosticsSnapshot(
                capturedAtEpochMs = 123L,
                processName = HostRuntimeDiagnosticsCodec.TARGET_PACKAGE,
                features = listOf(
                    HostRuntimeFeatureEvidence(
                        "comment_filter",
                        1L,
                        0L,
                        0L,
                        HostFeatureInstallState.INSTALLED,
                        installedHookCount = 1
                    )
                )
            )
        )
        val acceptedWithoutGeneration = JSONObject(valid).apply {
            getJSONObject("bootstrap").put("config", HostConfigState.ACCEPTED.name)
        }.toString()
        val installedWithoutHooks = JSONObject(valid).apply {
            getJSONArray("features").getJSONObject(0).put("hooks", 0)
        }.toString()

        assertNull(HostRuntimeDiagnosticsCodec.decodeOrNull(acceptedWithoutGeneration))
        assertNull(HostRuntimeDiagnosticsCodec.decodeOrNull(installedWithoutHooks))
    }
}
