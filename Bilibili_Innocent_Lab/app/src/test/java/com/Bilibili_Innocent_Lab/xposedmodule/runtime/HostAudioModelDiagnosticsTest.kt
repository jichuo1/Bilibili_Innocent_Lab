package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HostAudioModelDiagnosticsTest {
    private fun receipt(state: String? = null) = HostRuntimeDiagnosticsCodec.encode(
        HostRuntimeDiagnosticsSnapshot(1000, HostRuntimeDiagnosticsCodec.TARGET_PACKAGE, emptyList(), audioModelState = state))
    @Test fun `live model readiness survives the diagnostic wire`() {
        assertEquals("ready", HostRuntimeDiagnosticsCodec.decodeOrNull(receipt("ready"))?.audioModelState)
    }
    @Test fun `old receipts stay readable without claiming model readiness`() {
        val old = JSONObject(receipt()).apply { remove("audio_model") }.toString()
        assertNotNull(HostRuntimeDiagnosticsCodec.decodeOrNull(old))
        assertNull(HostRuntimeDiagnosticsCodec.decodeOrNull(old)?.audioModelState)
    }
    @Test fun `arbitrary text and non-string model states are rejected`() {
        for (value in listOf<Any>("/private/path/untrusted", 1, JSONObject())) {
            assertNull(HostRuntimeDiagnosticsCodec.decodeOrNull(JSONObject(receipt()).put("audio_model", value).toString()))
        }
    }
}
