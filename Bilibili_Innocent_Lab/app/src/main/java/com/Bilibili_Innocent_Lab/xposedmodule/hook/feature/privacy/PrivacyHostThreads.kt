package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.os.Handler
import android.os.Looper
import android.os.Message
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 免登录与无痕在宿主进程里自己调度的主线程投递和后台任务：逃逸的异常交给 [HostThreadGuard]
 * 记录，不能落到宿主线程的默认 handler 上杀进程。结构性地包在调度器上，新增的 `post`/`execute`
 * 不用记得逐个包裹。
 */
internal fun guardedMainHandler(key: String): Handler = object : Handler(Looper.getMainLooper()) {
    override fun dispatchMessage(msg: Message) {
        HostThreadGuard.run(key) { super.dispatchMessage(msg) }
    }
}

/** 单线程执行器的工作线程整体在守卫里运行；任务抛出后线程池按原样补一个新工作线程。 */
internal fun guardedSingleThreadExecutor(key: String, threadName: String): ExecutorService =
    Executors.newSingleThreadExecutor { task ->
        Thread({ HostThreadGuard.run(key) { task.run() } }, threadName).apply { isDaemon = true }
    }
