package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 创建支持核心线程空闲回收的宿主 UI 后台单线程执行器，避免空闲期长期滞留守护线程。 */
internal object HostUiExecutors {
    internal const val DEFAULT_KEEP_ALIVE_SECONDS = 30L

    fun newSingleThreadPool(
        threadName: String,
        keepAliveSeconds: Long = DEFAULT_KEEP_ALIVE_SECONDS
    ): ThreadPoolExecutor = ThreadPoolExecutor(
        1,
        1,
        keepAliveSeconds,
        TimeUnit.SECONDS,
        LinkedBlockingQueue()
    ) { runnable ->
        Thread(runnable, threadName).apply { isDaemon = true }
    }.apply {
        allowCoreThreadTimeOut(true)
    }
}
