package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentScanEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec
import org.junit.Assert.*
import org.junit.Test

class HostReceiptPayloadTest {
    private val source = HostRuntimeDiagnosticsSource(9110400, 1000, 17)
    private fun scan(surface: String) = MineComponentSnapshotCodec.encode("tv.danmaku.bili", emptySet(),
        listOf(requireNotNull(MineComponentScanEntry.create("item", "test", "1", null, true))), surface, 1)
    private fun validate(channel: String, payload: String, expected: HostRuntimeDiagnosticsSource = source,
                         digest: String = HostRuntimeDiagnosticsQueryContract.sha256(payload)) =
        HostReceiptPayload.validate(channel, payload, digest, source, expected)

    @Test fun `each surface stays in its own slot and versions must all match`() {
        val payload = scan("mine")
        assertEquals(ReceiptQueryFailure.NONE, validate("mine", payload))
        assertEquals(ReceiptQueryFailure.SURFACE_MISMATCH, validate("home_tabs", payload))
        assertEquals(ReceiptQueryFailure.SURFACE_MISMATCH, validate("unknown", payload))
        assertEquals(ReceiptQueryFailure.SOURCE_MISMATCH, validate("mine", payload, source.copy(targetVersionCode = 912)))
        assertEquals(ReceiptQueryFailure.SOURCE_MISMATCH, validate("mine", payload, source.copy(targetUpdateTime = 2000)))
        assertEquals(ReceiptQueryFailure.SOURCE_MISMATCH, validate("mine", payload, source.copy(moduleVersionCode = 18)))
    }
    @Test fun `corrupt empty and oversized publications are never accepted`() {
        assertEquals(ReceiptQueryFailure.DIGEST_MISMATCH, validate("mine", scan("mine"), digest = "0".repeat(64)))
        assertEquals(ReceiptQueryFailure.MALFORMED_RESPONSE, validate("mine", "{}"))
        assertEquals(ReceiptQueryFailure.MALFORMED_RESPONSE, validate("mine", ""))
        assertEquals(ReceiptQueryFailure.MALFORMED_RESPONSE, validate("mine", "x".repeat(65537)))
        val empty = MineComponentSnapshotCodec.encode("tv.danmaku.bili", emptySet(), emptyList(), "mine", 1)
        assertEquals(ReceiptQueryFailure.MALFORMED_RESPONSE, validate("mine", empty))
    }
    @Test fun `diagnostics codec remains mandatory independently of digest`() {
        val payload = HostRuntimeDiagnosticsCodec.encode(HostRuntimeDiagnosticsSnapshot(1, "tv.danmaku.bili", emptyList()))
        assertEquals(ReceiptQueryFailure.NONE, validate("diagnostics", payload))
        assertEquals(ReceiptQueryFailure.MALFORMED_RESPONSE, validate("diagnostics", scan("mine")))
    }
}
