package com.Bilibili_Innocent_Lab.xposedmodule.telemetry

/** 至多保留一个待处理通知，不保留旧回执；执行时重新验证版本、同意和独立额度。 */
internal class PendingVersionUpload {
    private var action: (() -> Unit)? = null

    @Synchronized fun offer(next: () -> Unit): Boolean {
        if (action != null) return false
        action = next
        return true
    }

    @Synchronized fun take(): (() -> Unit)? = action.also { action = null }
}
