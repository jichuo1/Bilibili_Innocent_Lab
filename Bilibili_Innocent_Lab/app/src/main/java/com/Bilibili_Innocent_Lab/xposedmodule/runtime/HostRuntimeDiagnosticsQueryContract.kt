package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import java.security.MessageDigest

internal object HostRuntimeDiagnosticsQueryContract {
    const val PROTOCOL_VERSION = 1
    val MODULE_PACKAGE: String = BuildConfig.APPLICATION_ID
    const val TARGET_PACKAGE = HostRuntimeDiagnosticsCodec.TARGET_PACKAGE

    val ACTION_QUERY: String = "$MODULE_PACKAGE.QUERY_HOST_RUNTIME_DIAGNOSTICS"
    val PERMISSION_QUERY: String = "$MODULE_PACKAGE.permission.QUERY_HOST_RUNTIME_DIAGNOSTICS"

    const val EXTRA_PROTOCOL_VERSION = "host_diagnostics_protocol_version"
    const val EXTRA_REQUEST_NONCE = "host_diagnostics_request_nonce"
    const val EXTRA_HANDLED = "host_diagnostics_handled"
    const val EXTRA_STATUS = "host_diagnostics_status"
    const val EXTRA_PAYLOAD = "host_diagnostics_payload"
    const val EXTRA_PAYLOAD_SHA256 = "host_diagnostics_payload_sha256"
    const val EXTRA_TARGET_VERSION = "host_diagnostics_target_version"
    const val EXTRA_TARGET_UPDATE_TIME = "host_diagnostics_target_update_time"
    const val EXTRA_MODULE_VERSION = "host_diagnostics_module_version"

    const val STATUS_READY = "ready"
    const val STATUS_UNSUPPORTED = "unsupported"
    const val RESULT_CODE_UNHANDLED = 0
    const val RESULT_CODE_HANDLED = 0x4844

    fun isValidNonce(value: String): Boolean =
        value.length in 16..128 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    fun sha256(payload: String): String = MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    fun digestMatches(payload: String, expected: String): Boolean =
        expected.length == 64 && sha256(payload).equals(expected, ignoreCase = true)
}

internal data class HostRuntimeDiagnosticsSource(
    val targetVersionCode: Long,
    val targetUpdateTime: Long,
    val moduleVersionCode: Long
) {
    val isComplete: Boolean
        get() = targetVersionCode > 0L && targetUpdateTime > 0L && moduleVersionCode > 0L
}
