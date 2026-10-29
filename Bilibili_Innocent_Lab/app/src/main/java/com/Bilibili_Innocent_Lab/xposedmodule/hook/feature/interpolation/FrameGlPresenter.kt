package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 呈现线程：RGB8 帧经 glTexSubImage2D 上传后画到宿主显示 Surface，在槽位前精确定时交付并附呈现时刻；
 * 上传返回即完成对客户端内存的拷贝，随后立即 done 归还缓冲。本线程不做捕获读回，避免读回阻塞推迟交付。
 *
 * [restoreNative] 先销毁显示 EGL 窗口（断开生产者连接），再回调外层把解码器输出切回显示 Surface；
 * 顺序颠倒会让 MediaCodec 连接失败。
 */
internal class FrameGlPresenter(
    private val target: Surface,
    private val targetFps: Int = 60,
    private val stageUploads: Boolean = true,
    private val onNativeRestored: (FramePresentationRecovery.Report) -> Unit
) : FrameInterpolationSession.Presenter {
    private val lock = Object()
    private val commands = ArrayDeque<() -> Unit>()
    private val pending = java.util.PriorityQueue<Scheduled>(compareBy { it.dueNs })
    /**
     * 呈现时间戳提前半个目标周期：槽位网格锚在 vsync 时刻上，若时间戳正落在刷新边沿，几毫秒抖动就决定赶上本次还是
     * 下一次刷新，两帧挤进同一刷新、下一刷新没有新帧（宿主真机 SurfaceFlinger timestats：每秒约 3 次 33 ms 间隔）。
     * 放在刷新间隔中间后，SurfaceFlinger 总在目标刷新显示，与提交抖动无关（Android Frame Pacing 的做法）。
     */
    private val timestampLeadNs = 1_000_000_000L / targetFps.coerceAtLeast(1) / 2
    /** 跟随真实 vsync 交付的网格（见 [onVsync]）；须在呈现线程启动前初始化。 */
    private val vsync = FrameVsyncGrid(1_000_000_000L / targetFps.coerceAtLeast(1))
    /**
     * program 句柄缓存、quad 视图与尺寸数组，归本呈现线程的 GL 上下文独占（legacy 过渡补丁，见 FrameGlHandles.kt）。
     * 同样须在呈现线程启动前初始化；只由呈现线程的 setUp / draw / tearDown 使用。
     */
    private val handles = FrameGlProgramHandles(FrameGlEs2)
    private val surfaceSize = FrameGlSurfaceSize()
    private val windowExtent = FrameGlSurfaceSize.Source { into ->
        val display = egl!!.display
        EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, into, 0)
        EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, into, 1)
    }
    @Volatile private var running = true
    private val thread = Thread({ loop() }, "innocent-frame-present").apply {
        priority = Thread.MAX_PRIORITY
        isDaemon = true
        start()
    }
    private var egl: FrameGl.Egl? = null
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var rgbProgram = 0
    private var rgbTexture = 0
    private var rgbWidth = 0
    private var rgbHeight = 0
    private var staged: Scheduled? = null
    @Volatile private var connected = false
    var onSubmitted: ((Long, Long) -> Unit)? = null

    /** 同步连接显示 Surface；失败时不连接，外层应保持原生呈现。 */
    fun start(timeoutMs: Long = 1_000L): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        post {
            ok = runCatching { setUp() }.getOrDefault(false)
            if (!ok) tearDown()
            latch.countDown()
        }
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS) && ok
    }

    /**
     * 精确定时交付：宿主用 TextureView 呈现（主进程 SurfaceTexture），它不理会呈现时间戳，按到达在下一 UI 帧显示；
     * 交付时刻的抖动直接变成画面顿挫。Handler 定时只有毫秒精度且受消息排队影响，无窗口进程的 Choreographer
     * 又会被系统降频（真机两帧挤进一个 vsync 而丢帧），因此用独立线程 parkNanos 睡到槽位前 [PRESENT_LEAD_NS] 再画。
     */
    override fun present(frameId: Long, buffer: ByteBuffer, format: FrameBufferLedger.Format, dueNs: Long,
                         done: (FrameInterpolationSession.Outcome) -> Unit): Boolean {
        synchronized(lock) {
            if (!running) return false
            pending += Scheduled(buffer, format, dueNs, done)
            lock.notifyAll()
        }
        return true
    }

    override fun restoreNative(report: FramePresentationRecovery.Report) {
        post {
            settlePending()
            tearDown()
            runCatching { onNativeRestored(report) }
        }
    }

    /** 拆除：撤回未呈现帧并断开 EGL 后结束线程。 */
    fun close() {
        post { settlePending(); tearDown() }
        synchronized(lock) { running = false; lock.notifyAll() }
    }

    private fun post(command: () -> Unit) = synchronized(lock) { commands += command; lock.notifyAll() }

    private fun loop() {
        while (true) {
            val command: (() -> Unit)?
            val head: Scheduled?
            synchronized(lock) {
                while (commands.isEmpty()) {
                    val next = pending.peek()
                    if (next == null) {
                        if (!running) return
                        lock.wait()
                        continue
                    }
                    if (stageUploads && staged !== next) break
                    if (vsync.lastNs != 0L) {
                        // vsync 跟随：等到 onVsync 唤醒，或在下一个外推 vsync 复查（驱动线程可能漏掉回调）。
                        val now = System.nanoTime()
                        if (releasable(next, now)) break
                        lock.wait(vsync.untilNextNs(now) / 1_000_000L + 1)
                        continue
                    }
                    // Object.wait 只有毫秒级唤醒；最后 SPIN_THRESHOLD_NS 改为出锁 parkNanos 精确等待。
                    val wait = waitNs(next) - SPIN_THRESHOLD_NS
                    if (wait <= 0L) break
                    lock.wait(wait / 1_000_000L, (wait % 1_000_000L).toInt())
                }
                command = commands.removeFirstOrNull()
                head = if (command == null) pending.peek() else null
            }
            if (command != null) { runCatching(command); continue }
            val target = head ?: continue
            if (stageUploads && staged !== target) {
                // Upload while the frame still has its planning lead, rather than in the final 4 ms.
                // A newly arrived earlier proposal replaces the texture; the later head is restaged.
                if (!runCatching { upload(target.buffer, target.format) }.getOrDefault(false)) {
                    val item = synchronized(lock) { if (pending.peek() === target) pending.poll() else null }
                    item?.done?.invoke(FrameInterpolationSession.Outcome.SurfaceLost(System.nanoTime()))
                } else staged = target
                continue
            }
            if (vsync.lastNs == 0L) {
                val wait = waitNs(target)
                if (wait > 0L) java.util.concurrent.locks.LockSupport.parkNanos(wait)
            } else if (!releasable(target, System.nanoTime())) continue
            val item = synchronized(lock) { if (pending.peek() === target) pending.poll() else null } ?: continue
            deliver(item)
        }
    }

    private fun waitNs(item: Scheduled) = item.dueNs - timestampLeadNs - presentLeadNs - System.nanoTime()

    /** 已错过下一个槽位的帧不再画（画了只会挤掉后一帧）；其余按时画并回报。 */
    private fun deliver(item: Scheduled) {
        val started = System.nanoTime()
        val next = synchronized(lock) { pending.peek() }
        // 同一刷新已有更新的一帧可交付：本帧作废，保证每个刷新最多提交一帧。
        // 只有下一帧也属于即将到来的这次刷新时本帧才作废；面板快于内容（120 Hz 播 60 fps）时本帧照常交付，下一帧留到后一次刷新。
        if (next != null && (if (!vsync.stale(started)) vsync.sameRefresh(next.dueNs, started)
                else next.dueNs - timestampLeadNs - presentLeadNs <= started)) {
            // 面板比目标慢（目标按面板模式定为 90 fps、面板停在 60 Hz）时每几帧必有一帧让位，这是刷新上限而非故障。
            val slowPanel = !vsync.stale(started) && vsync.slowerThanContent()
            if (slowPanel) superseded++
            runCatching { item.done(if (slowPanel) FrameInterpolationSession.Outcome.Superseded(started)
                else FrameInterpolationSession.Outcome.Dropped(started)) }
            return
        }
        val outcome = runCatching {
            if ((!stageUploads || staged !== item) && !upload(item.buffer, item.format)) false else draw(item.dueNs - timestampLeadNs)
        }.getOrDefault(false)
        staged = null
        if (outcome && !vsync.stale(started)) releasedVsyncNs = vsync.current(started)
        val submitted = System.nanoTime()
        if (outcome) runCatching { onSubmitted?.invoke(item.dueNs, submitted) }
        item.done(if (outcome) FrameInterpolationSession.Outcome.Presented(submitted)
            else FrameInterpolationSession.Outcome.SurfaceLost(System.nanoTime()))
    }

    /**
     * 撤回所有尚未呈现的帧并逐个以 Dropped 回调，保证每个 PRESENTER 持有都被归还（真机曾因丢弃的延后消息
     * 在拆除后残留 1–2 帧）。
     */
    private fun settlePending() {
        val drained = synchronized(lock) { pending.toList().also { pending.clear() } }
        staged = null
        val now = System.nanoTime()
        for (item in drained) runCatching { item.done(FrameInterpolationSession.Outcome.Dropped(now)) }
    }

    private class Scheduled(val buffer: ByteBuffer, val format: FrameBufferLedger.Format, val dueNs: Long,
                            val done: (FrameInterpolationSession.Outcome) -> Unit)

    private fun setUp(): Boolean {
        val created = FrameGl.create(EGL14.EGL_WINDOW_BIT, highPriority = true) ?: return false
        egl = created
        window = EGL14.eglCreateWindowSurface(created.display, created.config, target, intArrayOf(EGL14.EGL_NONE), 0)
        if (window == EGL14.EGL_NO_SURFACE) return false
        connected = true
        if (!EGL14.eglMakeCurrent(created.display, window, window, created.context)) return false
        // Request on the actual producer after connection, not only on its enclosing activity.
        // The system still owns thermal/user limits; this is a cadence hint, never a global override.
        if (android.os.Build.VERSION.SDK_INT >= 31) runCatching {
            target.setFrameRate(targetFps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                Surface.CHANGE_FRAME_RATE_ALWAYS)
        }
        rgbProgram = FrameGl.program(VERTEX, RGB_FRAGMENT) ?: return false
        handles.link(rgbProgram)
        rgbTexture = FrameGl.texture(GLES20.GL_TEXTURE_2D)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        return GLES20.glGetError() == GLES20.GL_NO_ERROR
    }

    private fun upload(buffer: ByteBuffer, format: FrameBufferLedger.Format): Boolean {
        if (!connected || format.stride != format.width * 3) return false
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rgbTexture)
        val pixels = buffer.duplicate().also { it.position(0) }
        if (format.width != rgbWidth || format.height != rgbHeight) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, format.width, format.height, 0,
                GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, pixels)
            rgbWidth = format.width
            rgbHeight = format.height
        } else {
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, format.width, format.height,
                GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, pixels)
        }
        return GLES20.glGetError() == GLES20.GL_NO_ERROR
    }

    private fun draw(dueNs: Long): Boolean {
        if (!connected) return false
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rgbTexture)
        // 每次绘制仍查询窗口尺寸：没有覆盖旋转/全屏/画中画/布局变化的可靠 resize 通知（见 FrameGlSurfaceSize）。
        surfaceSize.refresh(windowExtent)
        GLES20.glViewport(0, 0, surfaceSize.width, surfaceSize.height)
        GLES20.glUseProgram(rgbProgram)
        handles.bindQuad(rgbProgram)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return false
        if (!EGLExt.eglPresentationTimeANDROID(egl!!.display, window, dueNs)) timestampRejected++
        val swapStarted = System.nanoTime()
        val swapped = EGL14.eglSwapBuffers(egl!!.display, window)
        recordTiming(dueNs, swapStarted)
        // 诊断：本帧绘制在 GPU 上完成所需时间（含排在推理计算之后的等待）。仅统计，不影响交付。
        if (measureGpu) {
            val waitStart = System.nanoTime()
            GLES20.glFinish()
            val gpuMs = (System.nanoTime() - waitStart) / 1_000_000.0
            val bucket = when { gpuMs < 2 -> 0; gpuMs < 5 -> 1; gpuMs < 10 -> 2; gpuMs < 15 -> 3; else -> 4 }
            synchronized(timestampGaps) { gpuDone[bucket]++ }
        }
        return swapped
    }

    // 诊断：请求的呈现时间戳间隔（应为整周期）与提交时刻相对时间戳的偏差，按统计窗口汇总。
    private var lastTimestampNs = 0L
    @Volatile private var timestampRejected = 0L
    @Volatile private var superseded = 0L
    private val gpuDone = IntArray(5)
    /** 诊断开关：交付后等待 GPU 完成以统计耗时。 */
    @Volatile var measureGpu = false
    /** 提交相对请求呈现时刻的提前量；默认 [PRESENT_LEAD_NS]，debug 验证构建可在线调整以测定 SurfaceFlinger 取帧相位。 */
    @Volatile var presentLeadNs = PRESENT_LEAD_NS

    /**
     * 跟随真实 vsync 交付：驱动线程每个 vsync 调 [onVsync]，呈现线程在该 vsync 之后提交「下一刷新」到期的那一帧。
     * 宿主真机全屏 SurfaceView 图层上 SurfaceFlinger 不理会呈现时间戳、缓冲就绪即上屏，按固定时间网格 parkNanos 定时
     * 提交（开环）时，提交相位与 SurfaceFlinger 取帧时刻的相对漂移与抖动让部分刷新拿到两帧、下一刷新没有新帧（timestats
     * 每秒约 2 次 33 ms 间隔，而本端时间戳与提交都准时）。跟随 vsync 后每个刷新最多提交一帧、相位固定。
     */

    /** 最近一次成功交付所在的（外推）vsync，只在呈现线程读写。 */
    private var releasedVsyncNs = 0L

    fun onVsync(vsyncNs: Long) = synchronized(lock) {
        if (vsync.onVsync(vsyncNs)) lock.notifyAll()
    }

    /** 已收到 vsync 时，帧在「上一 vsync 起算的下一刷新」到期即可提交；否则沿用时间网格。 */
    private fun releasable(item: Scheduled, nowNs: Long): Boolean {
        // 面板快于内容时每个刷新至多提交一帧：本刷新已交付过一帧，其余帧等下一个 vsync（等速时由作废规则保证）。
        return if (!vsync.stale(nowNs)) vsync.releasable(item.dueNs, nowNs) &&
                !(vsync.fasterThanContent() && vsync.current(nowNs) == releasedVsyncNs)
            else waitNs(item) <= 0L
    }
    private val timestampGaps = IntArray(8)
    private val submitSlack = IntArray(6)

    private fun recordTiming(timestampNs: Long, submittedNs: Long) {
        val period = 1_000_000_000L / targetFps.coerceAtLeast(1)
        if (lastTimestampNs != 0L) {
            // 以周期为单位：0 重复/倒退，1 正常，2 跳过一个槽位，… ；非整周期（偏差 > 2 ms）计入 7。
            val gap = timestampNs - lastTimestampNs
            val periods = Math.round(gap.toDouble() / period).toInt()
            val bucket = if (kotlin.math.abs(gap - periods * period) > 2_000_000L) 7 else periods.coerceIn(0, 6)
            synchronized(timestampGaps) { timestampGaps[bucket]++ }
        }
        lastTimestampNs = timestampNs
        // 提交时刻相对时间戳：<-8、-8..-4、-4..0、0..4、4..8、>8 ms（正值表示提交晚于请求的呈现时刻）。
        val slackMs = (submittedNs - timestampNs) / 1_000_000.0
        val bucket = when { slackMs < -8 -> 0; slackMs < -4 -> 1; slackMs < 0 -> 2; slackMs < 4 -> 3; slackMs < 8 -> 4; else -> 5 }
        synchronized(timestampGaps) { submitSlack[bucket]++ }
    }

    /** 自上次调用以来的时间戳间隔与提交偏差分布（诊断日志用），并清零。 */
    fun timingSummary(): String = synchronized(timestampGaps) {
        ("tsRejected=$timestampRejected superseded=$superseded gaps[dup,1,2,3,4,5,6+,off]=${timestampGaps.joinToString(",")} " +
            "submit[<-8,-8,-4,0,4,>8]=${submitSlack.joinToString(",")} " +
            "gpu[<2,2-5,5-10,10-15,>15ms]=${gpuDone.joinToString(",")}").also {
            timestampGaps.fill(0); submitSlack.fill(0); gpuDone.fill(0)
        }
    }


    /** 断开显示窗口并释放上下文；之后解码器才能重新连接显示 Surface。 */
    private fun tearDown() {
        val current = egl ?: return
        if (android.os.Build.VERSION.SDK_INT >= 31) runCatching {
            target.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
        EGL14.eglMakeCurrent(current.display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(current.display, window)
        window = EGL14.EGL_NO_SURFACE
        connected = false
        // 上下文随之销毁：program 名可能被新上下文复用，缓存的句柄不能留到下一个上下文。
        handles.invalidate()
        FrameGl.release(current)
        egl = null
    }

    private companion object {
        /**
         * 提交比请求的呈现时刻早 20 ms（超过一个 60 Hz 周期）。宿主真机：只早 4 ms 时，缓冲常在 SurfaceFlinger 为目标刷新
         * 取帧之后才由 GPU 画完，平时只是顺延一刷新，偶尔两帧同时就绪、前一帧被静默替换（timestats 0 丢弃却少约 3% 的帧，
         * 每秒约 2 次空刷新）。呈现时间戳保证不会提前显示，早提交只让缓冲按时就绪。
         */
        const val PRESENT_LEAD_NS = 20_000_000L
        const val SPIN_THRESHOLD_NS = 2_000_000L
        const val VERTEX = "attribute vec2 aPosition; attribute vec2 aCoordinate; varying vec2 vCoordinate;" +
            "void main() { vCoordinate = aCoordinate; gl_Position = vec4(aPosition, 0.0, 1.0); }"
        const val RGB_FRAGMENT = "precision mediump float; varying vec2 vCoordinate; uniform sampler2D uTexture;" +
            "void main() { gl_FragColor = texture2D(uTexture, vCoordinate); }"
    }
}
