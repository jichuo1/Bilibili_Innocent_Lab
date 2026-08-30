package com.Bilibili_Innocent_Lab.xposedmodule.hook.modern

import android.util.Log

/** API 102 模块日志的进程内窄入口，避免业务 Hook 反向依赖框架实例。 */
internal object ModernHookLog {
    private const val TAG = "BilibiliInnocentLab"

    @Volatile
    private var sink: ((String, Throwable?) -> Unit)? = null

    fun bind(runtime: ModernHookRuntime) {
        sink = runtime::log
    }

    fun info(message: String) {
        val target = sink
        if (target != null) target(message, null) else Log.i(TAG, message)
    }

    fun error(message: String, throwable: Throwable? = null) {
        val target = sink
        if (target != null) target(message, throwable) else Log.e(TAG, message, throwable)
    }
}
