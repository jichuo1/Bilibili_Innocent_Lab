package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.view.Surface
import java.nio.ByteBuffer

/** Offscreen OES and output window at the candidate's real dimensions, on its existing EGL owner. */
internal class FrameAhbProbeSurface : AutoCloseable {
    private val display = EGL14.eglGetCurrentDisplay()
    private val context = EGL14.eglGetCurrentContext()
    private val oldDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
    private val oldRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
    private var target = EGL14.EGL_NO_SURFACE
    private var width = 0
    private var height = 0
    private var queue: SurfaceTexture? = null
    private var surface: Surface? = null
    private val paint = Paint()
    private val pixel = ByteBuffer.allocateDirect(4)
    val transform = FloatArray(16)
    var oes = 0
        private set

    fun prepare(sourceWidth: Int, sourceHeight: Int, outputWidth: Int, outputHeight: Int) {
        val id = IntArray(1)
        check(EGL14.eglQueryContext(display, context, EGL14.EGL_CONFIG_ID, id, 0))
        val configs = arrayOfNulls<EGLConfig>(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE),
            0, configs, 0, 1, id, 0) && id[0] == 1)
        width = outputWidth
        height = outputHeight
        target = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        check(target != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, target, target, context))
        oes = FrameGl.texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        check(oes != 0)
        val input = SurfaceTexture(oes)
        queue = input
        input.setDefaultBufferSize(sourceWidth, sourceHeight)
        surface = Surface(input)
    }

    fun input(phase: Int) {
        val producer = checkNotNull(surface)
        val canvas = producer.lockCanvas(null)
        try {
            val width = canvas.width.toFloat()
            val height = canvas.height.toFloat()
            canvas.drawColor(Color.rgb(32, 32, 32))
            paint.color = Color.rgb(224, 224, 224)
            canvas.drawRect(width / 2, 0f, width, height, paint)
            paint.color = Color.rgb(40, 120, 190)
            val left = width * (0.25f + (phase % 8) * 0.018f)
            canvas.drawRect(left, height / 4, left + width * 0.18f, height * 0.75f, paint)
        } finally {
            producer.unlockCanvasAndPost(canvas)
        }
        checkNotNull(queue).apply {
            updateTexImage()
            getTransformMatrix(transform)
        }
    }

    fun bindOutput() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
    }

    fun clearForVerification() {
        bindOutput()
        GLES30.glClearColor(1f, 0f, 1f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glFinish()
    }

    /** Stable patches survive motion interpolation; also reject an undrawn/magenta or vertically empty target. */
    fun validDraw(): Boolean {
        fun patchMatches(x: Int, expected: Int): Boolean {
            pixel.clear()
            GLES30.glReadPixels(x, height / 2, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixel)
            return (0..2).all { kotlin.math.abs((pixel[it].toInt() and 255) - expected) <= 14 }
        }
        return patchMatches(width / 8, 32) && patchMatches(width * 7 / 8, 224) && GLES30.glGetError() == GLES30.GL_NO_ERROR
    }

    override fun close() {
        surface?.release()
        surface = null
        queue?.release()
        queue = null
        if (oes != 0) GLES30.glDeleteTextures(1, intArrayOf(oes), 0)
        oes = 0
        if (target != EGL14.EGL_NO_SURFACE) {
            check(EGL14.eglMakeCurrent(display, oldDraw, oldRead, context))
            check(EGL14.eglDestroySurface(display, target))
            target = EGL14.EGL_NO_SURFACE
        }
    }
}
