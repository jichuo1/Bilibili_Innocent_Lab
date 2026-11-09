package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.GLES20
import java.nio.FloatBuffer

/** 捕获与呈现线程共用的最小 GLES2/EGL 工具；每个线程各有自己的显示连接与上下文。 */
internal object FrameGl {
    class Egl(val display: EGLDisplay, val config: EGLConfig, val context: EGLContext)

    /**
     * highPriority：呈现上下文请求 EGL_IMG_context_priority 的高优先级。呈现绘制与 Vulkan 推理共用 GPU，
     * 推理（约 20 ms）在跑时普通优先级的绘制要排在其后，交付时刻因此抖动约 10 ms（真机）；扩展缺失时照常创建。
     */
    fun create(surfaceType: Int, highPriority: Boolean = false): Egl? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) return null
        val attributes = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, surfaceType, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] < 1) return null
        val config = configs[0] ?: return null
        val priority = highPriority &&
            EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS)?.contains("EGL_IMG_context_priority") == true
        val contextAttributes = if (priority)
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, CONTEXT_PRIORITY_LEVEL_IMG, CONTEXT_PRIORITY_HIGH_IMG, EGL14.EGL_NONE)
            else intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttributes, 0)
        if (context == EGL14.EGL_NO_CONTEXT) return null
        return Egl(display, config, context)
    }

    fun release(egl: Egl?) {
        egl ?: return
        EGL14.eglMakeCurrent(egl.display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(egl.display, egl.context)
        EGL14.eglReleaseThread()
    }

    fun texture(kind: Int): Int {
        val names = IntArray(1)
        GLES20.glGenTextures(1, names, 0)
        GLES20.glBindTexture(kind, names[0])
        GLES20.glTexParameteri(kind, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(kind, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(kind, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(kind, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return names[0]
    }

    fun program(vertexSource: String, fragmentSource: String): Int? {
        fun shader(type: Int, source: String): Int? {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            return shader.takeIf { status[0] != 0 }
        }
        val vertex = shader(GLES20.GL_VERTEX_SHADER, vertexSource) ?: return null
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, fragmentSource) ?: return null
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        return program.takeIf { status[0] != 0 }
    }

    private const val CONTEXT_PRIORITY_LEVEL_IMG = 0x3100
    private const val CONTEXT_PRIORITY_HIGH_IMG = 0x3101
}

/** [FrameGlApi] 的真实实现：转调当前线程的 GLES20 上下文；无状态，可在各 GL 线程间共用。 */
internal object FrameGlEs2 : FrameGlApi {
    override fun attribLocation(program: Int, name: String): Int = GLES20.glGetAttribLocation(program, name)
    override fun uniformLocation(program: Int, name: String): Int = GLES20.glGetUniformLocation(program, name)
    override fun enableAttribute(location: Int) = GLES20.glEnableVertexAttribArray(location)
    override fun attributePointer(location: Int, size: Int, stride: Int, data: FloatBuffer) =
        GLES20.glVertexAttribPointer(location, size, GLES20.GL_FLOAT, false, stride, data)
}
