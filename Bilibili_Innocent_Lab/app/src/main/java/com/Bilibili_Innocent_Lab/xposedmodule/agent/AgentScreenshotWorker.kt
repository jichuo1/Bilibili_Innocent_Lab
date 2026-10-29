package com.Bilibili_Innocent_Lab.xposedmodule.agent

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * inspect_screen 的像素后处理 worker：同一时刻最多一个在途任务（一份 HardwareBuffer + 一份 ARGB 副本）。
 *
 * 主线程只做请求准入、窗口与任务身份核对和最终交付；ARGB 复制、裁剪、缩放、JPEG 与 Base64 都在这里的线程执行。
 * 在途已满或线程池拒绝时 [trySubmit] 返回 false，任务**不会**在调用线程上运行（不用 CallerRuns），
 * 资源仍归调用方释放。接受之后 [job] 自己负责释放它拿到的缓冲与位图（成功、取消、异常各分支）。
 */
internal class AgentScreenshotWorker(threadName: String = "BIL-AgentShot") {
    private val inFlight = AtomicBoolean(false)
    private val executor = ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(1),
        { task -> Thread(task, threadName).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
        .apply { allowCoreThreadTimeOut(true) }

    fun trySubmit(job: Runnable): Boolean {
        if (!inFlight.compareAndSet(false, true)) return false
        return try {
            executor.execute {
                try { job.run() } finally { inFlight.set(false) }
            }
            true
        } catch (_: RejectedExecutionException) {
            inFlight.set(false)
            false
        }
    }

    /** 测试与诊断用：当前是否有任务在途。 */
    val busy: Boolean get() = inFlight.get()
}

/** 截图裁剪与缩放的整数运算；与历史实现逐式一致，便于单测锁定 960 上限与取整。 */
internal object AgentScreenshotGeometry {
    const val MAX_SIDE = 960f
    const val MAX_SOURCE_PIXELS = 8_500_000L
    const val MAX_ENCODED_BYTES = 130_000

    /** 返回 [left, top, width, height]（相对截图像素坐标）。区域是否落在图内由 Bitmap.createBitmap 校验。 */
    fun cropRegion(
        boundsLeft: Int, boundsTop: Int, boundsRight: Int, boundsBottom: Int,
        frameLeft: Int, frameTop: Int, frameWidth: Int, frameHeight: Int,
        sourceWidth: Int, sourceHeight: Int
    ): IntArray {
        val left = ((boundsLeft - frameLeft).toLong() * sourceWidth / frameWidth).toInt()
        val top = ((boundsTop - frameTop).toLong() * sourceHeight / frameHeight).toInt()
        val right = ((boundsRight - frameLeft).toLong() * sourceWidth / frameWidth).toInt()
        val bottom = ((boundsBottom - frameTop).toLong() * sourceHeight / frameHeight).toInt()
        return intArrayOf(left, top, maxOf(1, right - left), maxOf(1, bottom - top))
    }

    /** 返回缩放后的 [width, height]；不放大。 */
    fun scaledSize(width: Int, height: Int): IntArray {
        val ratio = minOf(1f, MAX_SIDE / maxOf(width, height))
        return intArrayOf(maxOf(1, (width * ratio).toInt()), maxOf(1, (height * ratio).toInt()))
    }
}
