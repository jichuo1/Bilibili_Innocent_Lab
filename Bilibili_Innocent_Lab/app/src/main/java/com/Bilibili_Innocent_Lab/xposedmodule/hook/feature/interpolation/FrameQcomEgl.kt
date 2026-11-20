package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Build
import android.view.Surface

/** The pbuffer keeps the QCOM capture and retirement context current after the host window disconnects. */
internal class FrameQcomEgl {
    private var egl: FrameGl.Egl? = null
    private var scratch: EGLSurface = EGL14.EGL_NO_SURFACE
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var rateOwner: Surface? = null
    private val extent = IntArray(1)
    private var textureId = 0
    var texture: SurfaceTexture? = null
        private set
    var input: Surface? = null
        private set
    var width = 0
        private set
    var height = 0
        private set
    val oes: Int get() = textureId
    val connected: Boolean get() = window != EGL14.EGL_NO_SURFACE

    fun prepare(width: Int, height: Int) {
        val e = checkNotNull(FrameGl.create(EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT, clientVersion = 3))
        egl = e
        scratch = EGL14.eglCreatePbufferSurface(e.display, e.config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(scratch != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(e.display, scratch, scratch, e.context))
        textureId = FrameGl.texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        check(textureId != 0)
        val queue = SurfaceTexture(textureId)
        texture = queue
        queue.setDefaultBufferSize(width, height)
        input = Surface(queue)
    }

    fun connect(display: Surface, fps: Int) {
        val e = checkNotNull(egl)
        window = EGL14.eglCreateWindowSurface(e.display, e.config, display, intArrayOf(EGL14.EGL_NONE), 0)
        check(connected && EGL14.eglMakeCurrent(e.display, window, window, e.context))
        width = dimension(EGL14.EGL_WIDTH)
        height = dimension(EGL14.EGL_HEIGHT)
        if (Build.VERSION.SDK_INT >= 31) runCatching {
            display.setFrameRate(fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS)
            rateOwner = display
        }
    }

    private fun dimension(key: Int): Int {
        check(EGL14.eglQuerySurface(checkNotNull(egl).display, window, key, extent, 0))
        return extent[0]
    }

    fun extentUnchanged() = width == dimension(EGL14.EGL_WIDTH) && height == dimension(EGL14.EGL_HEIGHT)

    fun swap(dueNs: Long) {
        val e = checkNotNull(egl)
        check(EGLExt.eglPresentationTimeANDROID(e.display, window, dueNs))
        check(EGL14.eglSwapBuffers(e.display, window))
    }

    fun disconnect() {
        if (!connected) return
        val e = checkNotNull(egl)
        check(EGL14.eglMakeCurrent(e.display, scratch, scratch, e.context))
        if (Build.VERSION.SDK_INT >= 31) runCatching {
            rateOwner?.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
        rateOwner = null
        check(EGL14.eglDestroySurface(e.display, window))
        window = EGL14.EGL_NO_SURFACE
    }

    fun close() {
        disconnect()
        input?.release()
        input = null
        texture?.release()
        texture = null
        if (textureId != 0) GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
        textureId = 0
        val e = egl ?: return
        if (scratch != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(e.display, scratch)
        scratch = EGL14.EGL_NO_SURFACE
        FrameGl.release(e)
        egl = null
    }
}
