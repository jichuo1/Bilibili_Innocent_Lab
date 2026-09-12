package com.Bilibili_Innocent_Lab.xposedmodule.runtime

/** 只记录有界原因，不把传输失败伪装成载荷校验失败。 */
internal enum class ReceiptQueryFailure {
    NONE, UNHANDLED, TIMEOUT, SEND_FAILED, MALFORMED_RESPONSE, NONCE_MISMATCH,
    UNSUPPORTED_PROTOCOL, DIGEST_MISMATCH, SOURCE_MISMATCH, SURFACE_MISMATCH, SESSION_MISMATCH, STORE_FAILED
}

internal object ReceiptQueryPolicy {
    fun isUnavailable(reason: ReceiptQueryFailure): Boolean =
        reason == ReceiptQueryFailure.UNHANDLED || reason == ReceiptQueryFailure.TIMEOUT ||
            reason == ReceiptQueryFailure.SEND_FAILED

    fun headerFailure(handled: Boolean, hasExtras: Boolean, nonceMatches: Boolean): ReceiptQueryFailure =
        when {
            !handled -> ReceiptQueryFailure.UNHANDLED
            !hasExtras -> ReceiptQueryFailure.MALFORMED_RESPONSE
            !nonceMatches -> ReceiptQueryFailure.NONCE_MISMATCH
            else -> ReceiptQueryFailure.NONE
        }
}
