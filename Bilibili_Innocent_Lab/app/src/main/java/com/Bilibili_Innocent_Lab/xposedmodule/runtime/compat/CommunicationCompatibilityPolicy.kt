package com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ReceiptQueryFailure
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ReceiptQueryPolicy

/** 只放宽可用性预算，不放宽身份、授权、数据校验或载荷上限。 */
@Suppress("UNUSED_PARAMETER")
internal object CommunicationCompatibilityPolicy {
    // 安全的等待、重试和身份校验能力属于两种模式的共同底座。
    fun binderTimeout(enabled: Boolean) = 1_800L
    fun broadcastTimeout(enabled: Boolean) = 3_000L
    fun managerTimeout(enabled: Boolean) = 6_000L
    fun managerAttempts(enabled: Boolean) = 2
    fun retryReceipt(enabled: Boolean, attempt: Int, failure: ReceiptQueryFailure): Boolean =
        attempt == 0 && ReceiptQueryPolicy.isUnavailable(failure)
    fun consentAllows(authorized: Boolean, acceptancePending: Boolean) = authorized || acceptancePending
    const val RETRY_DELAY_MS = 600L
    const val BIND_WINDOW_MS = 3_000L
    const val BIND_COOLDOWN_MS = 10_000L
}
