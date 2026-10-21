package com.Bilibili_Innocent_Lab.xposedmodule.hook.modern

import android.util.Log

/**
 * API 102 模块日志的进程内窄入口，避免业务 Hook 反向依赖框架实例。
 *
 * **记日志绝不能抛**：全仓日志都汇到这里，调用方常在 Hook 链之外（宿主主线程的 post、
 * 模块后台线程、代理回调）。框架 binder 断开时 `module.log` 会抛，未绑定 sink 时
 * `android.util.Log` 在 JVM 单测里也会抛；任何一种逃逸到宿主栈都会杀进程。
 * Logcat 与框架日志分别尝试、分别保护；任一通道失败都不妨碍另一通道，均不可用时丢弃这一条。
 */
internal object ModernHookLog {
    private const val TAG = "BilibiliInnocentLab"

    @Volatile
    private var sink: ((String, Throwable?) -> Unit)? = null

    fun bind(runtime: ModernHookRuntime) {
        sink = runtime::log
    }

    internal fun bindSink(target: ((String, Throwable?) -> Unit)?) {
        sink = target
    }

    fun info(message: String) = write(message, null, error = false)

    fun error(message: String, throwable: Throwable? = null) = write(message, throwable, error = true)

    private fun write(message: String, throwable: Throwable?, error: Boolean) {
        try {
            if (error) Log.e(TAG, message, throwable) else Log.i(TAG, message)
        } catch (_: Throwable) {
        }
        val target = sink
        if (target != null) {
            try {
                target(message, throwable)
            } catch (_: Throwable) {
            }
        }
    }
}
