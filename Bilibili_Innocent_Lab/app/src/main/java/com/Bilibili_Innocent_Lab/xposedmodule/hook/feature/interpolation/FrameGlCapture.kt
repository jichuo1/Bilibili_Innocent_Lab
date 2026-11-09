package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 捕获线程：解码器渲染到自有 SurfaceTexture（OES）。驱动处理厂商压缩布局（如高通 UBWC）与流色彩空间的
 * YUV→RGB 矩阵；帧画进 RGBA 帧缓冲后 glReadPixels 读回并紧凑为 RGB8。
 *
 * 与呈现分线程、分上下文：读回会在 GPU 忙于推理时阻塞；同线程时这些阻塞推迟或挤压 vsync 交付，
 * 宿主 TextureView 上表现为顿挫（宿主内真机 A/B 证实）。捕获与会话驱动留在本线程，呈现线程只管画。
 *
 * width/height 为处理尺寸，可小于解码输出（sourceWidth/sourceHeight，可达 4K）：缩小在这一次绘制中完成，
 * 缩小 1.5 倍以上时每个输出像素取 4 个双线性样本（覆盖约 4× 源像素）以免锯齿闪烁；读回与后续处理只按处理尺寸。
 */
internal class FrameGlCapture(
    private val width: Int,
    private val height: Int,
    private val sourceWidth: Int = width,
    private val sourceHeight: Int = height
) {
    private val downscale = sourceWidth >= width * 3 / 2 || sourceHeight >= height * 3 / 2
    /** 在捕获线程回调：timestampNs 为解码帧时间戳（PTS），read 把该帧读成 RGB8 写入给定缓冲。 */
    fun interface FrameListener {
        fun onFrame(timestampNs: Long, arrivalNs: Long, read: (ByteBuffer, FrameBufferLedger.Format) -> Boolean)
    }

    private val thread = HandlerThread("innocent-frame-capture").apply { start() }
    val handler = Handler(thread.looper)
    /** 只记录 onFrameAvailable 时刻再转交捕获线程：捕获线程忙于读回时，到达时刻不随它排队（见 [FrameArrivalStamp]）。 */
    private val timingThread = HandlerThread("innocent-frame-arrival", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        .apply { start() }
    private val timingHandler = Handler(timingThread.looper)
    private val stamp = FrameArrivalStamp()
    private var egl: FrameGl.Egl? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    /**
     * program 句柄缓存与 quad 视图，归本捕获线程的 GL 上下文独占（legacy 过渡补丁，见 FrameGlHandles.kt）：
     * link 成功后查询一轮，稳态 read() 不再查 uniform / attrib、不再生成 quad 视图。
     */
    private val handles = FrameGlProgramHandles(FrameGlEs2, if (downscale) DOWNSCALE_UNIFORMS else BASE_UNIFORMS)
    private var oesTexture = 0
    private var framebuffer = 0
    private var readback: ByteBuffer? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private val transform = FloatArray(16)
    private val maxLatchNs = java.util.concurrent.atomic.AtomicLong()

    /** 自上次读取以来的最长纹理锁存耗时；不包含后续 RGB 读回。 */
    fun takeMaxLatchNs(): Long = maxLatchNs.getAndSet(0L)

    /** 同步建立；失败返回 null，外层应保持原生呈现。 */
    fun start(listener: FrameListener, timeoutMs: Long = 1_000L): Surface? {
        val latch = CountDownLatch(1)
        var result: Surface? = null
        handler.post {
            result = runCatching { setUp(listener) }.getOrNull()
            if (result == null) tearDown()
            latch.countDown()
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        return result
    }

    fun close() {
        handler.post { tearDown() }
        thread.quitSafely()
        timingThread.quitSafely()
    }

    private fun setUp(listener: FrameListener): Surface? {
        val created = FrameGl.create(EGL14.EGL_PBUFFER_BIT) ?: return null
        egl = created
        pbuffer = EGL14.eglCreatePbufferSurface(created.display, created.config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        if (pbuffer == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(created.display, pbuffer, pbuffer, created.context))
            return null
        program = FrameGl.program(OES_VERTEX, if (downscale) OES_DOWNSCALE_FRAGMENT else OES_FRAGMENT) ?: return null
        handles.link(program)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        oesTexture = FrameGl.texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        val target = FrameGl.texture(GLES20.GL_TEXTURE_2D)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE, null)
        val names = IntArray(1)
        GLES20.glGenFramebuffers(1, names, 0)
        framebuffer = names[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, target, 0)
        val complete = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        if (!complete || GLES20.glGetError() != GLES20.GL_NO_ERROR) return null
        readback = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        val texture = SurfaceTexture(oesTexture).apply { setDefaultBufferSize(sourceWidth, sourceHeight) }
        texture.setOnFrameAvailableListener({ available ->
            val notified = System.nanoTime()
            handler.post { onFrameAvailable(available, listener, notified) }
        }, timingHandler)
        surfaceTexture = texture
        return Surface(texture).also { surface = it }
    }

    private fun onFrameAvailable(texture: SurfaceTexture, listener: FrameListener, notifiedNs: Long) {
        if (surfaceTexture !== texture) return
        val latchStarted = System.nanoTime()
        runCatching { texture.updateTexImage() }.onFailure { return }
        val latched = System.nanoTime()
        maxLatchNs.accumulateAndGet(latched - latchStarted) { previous, next -> maxOf(previous, next) }
        texture.getTransformMatrix(transform)
        // 锁存的帧若比通知所属的帧新（BufferQueue 顶替），通知时刻会伪造提前到达；FrameArrivalStamp 按 PTS 间隔识别并改用锁存时刻。
        val timestamp = texture.timestamp
        listener.onFrame(timestamp, stamp.arrival(timestamp, notifiedNs, latched)) { buffer, format -> read(buffer, format) }
    }

    /** 画 OES 帧到 RGBA 帧缓冲（上下翻转，使读回首行为画面顶部），glReadPixels 后紧凑为 RGB8。 */
    private fun read(buffer: ByteBuffer, format: FrameBufferLedger.Format): Boolean {
        if (format.width != width || format.height != height) return false
        val pixels = readback ?: return false
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glUniformMatrix4fv(handles.uniform(program, UNIFORM_TRANSFORM), 1, false, transform, 0)
        // 四个样本位于输出像素中心 ±1/4 像素：双线性各覆盖 2×2 源像素，合起来约为输出像素的覆盖范围。
        if (downscale) GLES20.glUniform2f(handles.uniform(program, UNIFORM_OFFSET), 0.25f / width, 0.25f / height)
        handles.bindQuad(program)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        pixels.clear()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return false
        return FrameInterpolationNative.rgbaToRgb(pixels, width, height, buffer, format.stride) == 0
    }

    private fun tearDown() {
        surfaceTexture?.setOnFrameAvailableListener(null)
        surface?.release()
        surfaceTexture?.release()
        surface = null
        surfaceTexture = null
        egl?.let { if (pbuffer != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(it.display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(it.display, pbuffer)
        } }
        pbuffer = EGL14.EGL_NO_SURFACE
        // 上下文随之销毁：program 名可能被新上下文复用，缓存的句柄不能留到下一个上下文。
        handles.invalidate()
        FrameGl.release(egl)
        egl = null
        readback = null
    }

    private companion object {
        const val UNIFORM_TRANSFORM = 0
        const val UNIFORM_OFFSET = 1
        val BASE_UNIFORMS = arrayOf("uTransform")
        val DOWNSCALE_UNIFORMS = arrayOf("uTransform", "uOffset")

        // 坐标用 highp：mediump 的归一化坐标在 4K 源上只精确到约 2 个纹素。
        const val OES_VERTEX = "attribute vec2 aPosition; attribute vec2 aCoordinate; uniform mat4 uTransform;" +
            "varying highp vec2 vCoordinate; varying highp vec2 vBase;" +
            " void main() { vBase = aCoordinate; vCoordinate = (uTransform * vec4(aCoordinate, 0.0, 1.0)).xy;" +
            " gl_Position = vec4(aPosition, 0.0, 1.0); }"
        const val OES_FRAGMENT = "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float; varying highp vec2 vCoordinate; varying highp vec2 vBase;" +
            " uniform samplerExternalOES uTexture;" +
            "void main() { gl_FragColor = texture2D(uTexture, vCoordinate); }"
        const val OES_DOWNSCALE_FRAGMENT = "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float; varying highp vec2 vCoordinate; varying highp vec2 vBase;" +
            " uniform samplerExternalOES uTexture; uniform highp mat4 uTransform; uniform highp vec2 uOffset;" +
            " highp vec2 at(highp vec2 c) { return (uTransform * vec4(c, 0.0, 1.0)).xy; }" +
            " void main() { gl_FragColor = 0.25 * (texture2D(uTexture, at(vBase + vec2(-uOffset.x, -uOffset.y)))" +
            " + texture2D(uTexture, at(vBase + vec2(uOffset.x, -uOffset.y)))" +
            " + texture2D(uTexture, at(vBase + vec2(-uOffset.x, uOffset.y)))" +
            " + texture2D(uTexture, at(vBase + uOffset))); }"
    }
}
