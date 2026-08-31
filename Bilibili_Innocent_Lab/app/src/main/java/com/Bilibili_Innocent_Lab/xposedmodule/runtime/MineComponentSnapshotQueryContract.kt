package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import java.security.MessageDigest

/** “我的”页扫描结果的模块 -> 宿主有序广播查询协议。 */
internal object MineComponentSnapshotQueryContract {
    const val PROTOCOL_VERSION = 1
    const val MODULE_PACKAGE = "com.Bilibili_Innocent_Lab.xposedmodule"
    const val TARGET_PACKAGE = "tv.danmaku.bili"

    const val ACTION_QUERY =
        "$MODULE_PACKAGE.QUERY_MINE_COMPONENT_SNAPSHOT"
    const val PERMISSION_QUERY =
        "$MODULE_PACKAGE.permission.QUERY_MINE_COMPONENT_SNAPSHOT"

    const val EXTRA_PROTOCOL_VERSION = "mine_snapshot_protocol_version"
    const val EXTRA_REQUEST_NONCE = "mine_snapshot_request_nonce"
    const val EXTRA_HANDLED = "mine_snapshot_handled"
    const val EXTRA_STATUS = "mine_snapshot_status"
    const val EXTRA_PAYLOAD = "mine_snapshot_payload"
    const val EXTRA_PAYLOAD_SHA256 = "mine_snapshot_payload_sha256"
    const val EXTRA_TARGET_VERSION = "mine_snapshot_target_version"
    const val EXTRA_TARGET_UPDATE_TIME = "mine_snapshot_target_update_time"
    const val EXTRA_MODULE_VERSION = "mine_snapshot_module_version"

    const val STATUS_READY = "ready"
    const val STATUS_WAITING_PAGE = "waiting_page"
    const val STATUS_UNSUPPORTED = "unsupported"

    const val RESULT_CODE_UNHANDLED = 0
    const val RESULT_CODE_HANDLED = 0x4d49

    fun isValidNonce(value: String): Boolean =
        value.length in 16..128 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    fun sha256(payload: String): String = MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    fun digestMatches(payload: String, expected: String): Boolean =
        expected.length == 64 && sha256(payload).equals(expected, ignoreCase = true)
}

internal data class MineComponentSnapshotSource(
    val targetVersionCode: Long,
    val targetUpdateTime: Long,
    val moduleVersionCode: Long
) {
    val isComplete: Boolean
        get() = targetVersionCode > 0L && targetUpdateTime > 0L && moduleVersionCode > 0L
}
