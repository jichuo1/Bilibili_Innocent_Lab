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
        val decoded = HostRuntimeDiagnosticsCodec.decodeOrNull(
            HostRuntimeDiagnosticsCodec.encode(
                HostRuntimeDiagnosticsSnapshot(
                    capturedAtEpochMs = 123L,
                    processName = HostRuntimeDiagnosticsCodec.TARGET_PACKAGE,
                    features = listOf(requireNotNull(evidence))
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
}
