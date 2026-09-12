package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec

internal object HostReceiptPayload {
    fun validate(channel: String, payload: String, digest: String,
                 source: HostRuntimeDiagnosticsSource, current: HostRuntimeDiagnosticsSource): ReceiptQueryFailure {
        if (!source.isComplete || source != current) return ReceiptQueryFailure.SOURCE_MISMATCH
        val limit = if (channel == HostReceiptWire.DIAGNOSTICS) HostRuntimeDiagnosticsCodec.MAX_PAYLOAD_CHARS
            else MineComponentSnapshotCodec.MAX_PAYLOAD_BYTES
        if (payload.isEmpty() || payload.length > limit) return ReceiptQueryFailure.MALFORMED_RESPONSE
        if (!HostRuntimeDiagnosticsQueryContract.digestMatches(payload, digest)) return ReceiptQueryFailure.DIGEST_MISMATCH
        if (channel == HostReceiptWire.DIAGNOSTICS) {
            if (HostRuntimeDiagnosticsCodec.decodeOrNull(payload) == null) return ReceiptQueryFailure.MALFORMED_RESPONSE
        } else {
            if (channel !in MineComponentSnapshotCodec.ALLOWED_SURFACES) return ReceiptQueryFailure.SURFACE_MISMATCH
            val snapshot = MineComponentSnapshotCodec.decodeOrNull(payload, allowLegacy = false)
                ?: return ReceiptQueryFailure.MALFORMED_RESPONSE
            if (snapshot.surface != channel || snapshot.processName != MineComponentSnapshotQueryContract.TARGET_PACKAGE)
                return ReceiptQueryFailure.SURFACE_MISMATCH
            if (snapshot.entries.isEmpty()) return ReceiptQueryFailure.MALFORMED_RESPONSE
        }
        return ReceiptQueryFailure.NONE
    }
}
