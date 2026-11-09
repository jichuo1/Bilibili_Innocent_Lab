package com.bilibili.app.dialogmanager

import android.content.Context

/** 8.90.2 的精确签名；队列行为由测试 Chain 模拟，不运行 Android Context。 */
class MainDialogManager {
    class DialogManagerInfo(private val mKey: String?, private val failRead: Boolean = false) {
        fun getKey(): String? {
            check(!failRead) { "fixture key read failure" }
            return mKey
        }
    }

    companion object {
        @JvmStatic
        fun addDialog(info: DialogManagerInfo, context: Context?): Boolean =
            context != null && info.getKey() != null

        @JvmStatic
        fun addDialog(info: DialogManagerInfo): Boolean = info.getKey() != null
    }
}
