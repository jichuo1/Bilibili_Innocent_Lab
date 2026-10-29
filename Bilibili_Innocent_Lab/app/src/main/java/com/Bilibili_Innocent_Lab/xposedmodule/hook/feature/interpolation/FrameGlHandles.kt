package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/*
 * legacy 过渡补丁（性能报告 P2-05「GL 稳定状态」）。FrameGl / FrameGlCapture / FrameGlPresenter 已规划迁入 MeguvizMedia 的
 * Android 平台层（MeguvizMedia::android，GL owner 上的 capture/draw），本文件只是迁移前的小额分配优化：
 * 去掉稳态每帧的句柄查询、quad 视图与尺寸数组分配。不改变纹理方向与采样，也不据此宣称帧率提升。
 *
 * 接替任务：MeguvizMedia Android 平台层的同职能实现（src/android/ahb_bridge.cpp 的 capture/display 路径，目前仍逐帧
 * glGetUniformLocation）在它自己的 GL owner 内做同样三件事：link 成功后缓存句柄并绑定 program 身份、
 * 每个 owner 持有自己的顶点数据、Surface 尺寸数组复用（eglQuerySurface 是否减少取决于可靠的 resize 通知，见 [FrameGlSurfaceSize]）。
 * 迁移完成后删除：本文件、FrameGl.kt 的 FrameGlEs2，以及 FrameGl / FrameGlCapture / FrameGlPresenter 本体，
 * 连同 FrameGlHandlesTest 与 FrameGlSteadyStateContractTest。
 */

/** 稳态 GL 路径用到的最小调用集合：真实实现 [FrameGlEs2] 转调 GLES20，JVM 测试用记录调用次数的假实现。 */
internal interface FrameGlApi {
    fun attribLocation(program: Int, name: String): Int
    fun uniformLocation(program: Int, name: String): Int
    fun enableAttribute(location: Int)
    /** FLOAT、不归一化；[stride] 为字节步长，[data] 的当前 position 即该属性的起始偏移。 */
    fun attributePointer(location: Int, size: Int, stride: Int, data: FloatBuffer)
}

/**
 * 一个 GL 上下文（renderer）独占的 quad 顶点数据：自己的直接缓冲和两个位置固定的视图。
 * glVertexAttribPointer 在调用时读取视图的 position 并保留指向这块内存的客户端指针，所以视图只能由使用它的
 * renderer 持有；不能在多个 GL 线程间共享并各自改 position，也不再每次绑定时 duplicate。
 */
internal class FrameGlQuadViews {
    /** 交错布局 x, y, u, v；位置属性从元素 0 起。 */
    val position: FloatBuffer
    /** 纹理坐标属性从元素 2 起。 */
    val coordinate: FloatBuffer

    init {
        val storage = ByteBuffer.allocateDirect(FLIPPED.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(FLIPPED)
        position = storage.duplicate().also { it.position(0) }
        coordinate = storage.duplicate().also { it.position(2) }
    }

    companion object {
        const val COMPONENTS = 2
        const val STRIDE_BYTES = 16

        /** 显示：RGB8 首行（纹理 v = 0）画到屏幕顶部；捕获：OES 画面顶部画到帧缓冲首行，读回首行即画面顶部。 */
        private val FLIPPED = floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
    }
}

/**
 * 一个 renderer 的 program 句柄缓存与 quad 视图；只在该 renderer 的 GL 线程使用。
 *
 * [link] 在 glLinkProgram 成功后调用一次：对该 program 查询一轮 aPosition / aCoordinate 与 [uniformNames]，
 * 之后稳态的 [uniform] / [bindQuad] 不再查询。GL 规范：句柄在成功重链接前保持有效，所以只有成功的重链接
 * （再次 [link]，哪怕 program 名不变）会刷新缓存；链接失败的 program 不应调用 [link]。
 *
 * 缓存绑定 program 身份：每次取用都带上将要使用的 program，与已绑定的不同就先重新解析，不会把别的 program 的句柄用上去。
 * program 名在上下文销毁后可能被新上下文复用，所以名字相等不足以证明缓存仍属于当前上下文：
 * 上下文丢失、拆除或重建 renderer 时必须 [invalidate]（新 renderer 自带全新的实例）。
 */
internal class FrameGlProgramHandles(
    private val api: FrameGlApi,
    private val uniformNames: Array<String> = NO_UNIFORMS
) {
    private val quad = FrameGlQuadViews()
    private val uniforms = IntArray(uniformNames.size) { ABSENT }
    private var positionAttribute = ABSENT
    private var coordinateAttribute = ABSENT
    private var bound = NO_PROGRAM

    /** 已执行的整轮句柄查询次数（测试与诊断）：稳态应等于成功 link 的次数。 */
    var rounds = 0
        private set

    fun link(program: Int) {
        if (program == NO_PROGRAM) { invalidate(); return }
        positionAttribute = api.attribLocation(program, POSITION)
        coordinateAttribute = api.attribLocation(program, COORDINATE)
        for (index in uniformNames.indices) uniforms[index] = api.uniformLocation(program, uniformNames[index])
        bound = program
        rounds++
    }

    fun invalidate() {
        bound = NO_PROGRAM
        positionAttribute = ABSENT
        coordinateAttribute = ABSENT
        uniforms.fill(ABSENT)
    }

    /** [program] 的第 [index] 个 uniform（[uniformNames] 的顺序）；-1 表示着色器里没有该 uniform。 */
    fun uniform(program: Int, index: Int): Int {
        if (program != bound) link(program)
        return uniforms[index]
    }

    fun bindQuad(program: Int) {
        if (program != bound) link(program)
        api.enableAttribute(positionAttribute)
        api.attributePointer(positionAttribute, FrameGlQuadViews.COMPONENTS, FrameGlQuadViews.STRIDE_BYTES, quad.position)
        api.enableAttribute(coordinateAttribute)
        api.attributePointer(coordinateAttribute, FrameGlQuadViews.COMPONENTS, FrameGlQuadViews.STRIDE_BYTES, quad.coordinate)
    }

    private companion object {
        const val NO_PROGRAM = 0
        const val ABSENT = -1
        const val POSITION = "aPosition"
        const val COORDINATE = "aCoordinate"
        val NO_UNIFORMS = emptyArray<String>()
    }
}

/**
 * 窗口 Surface 的尺寸读取：复用一个两元素数组，每次 [refresh] 仍实际查询（真实实现是 eglQuerySurface）。
 *
 * 没有减少 eglQuerySurface：FrameGlPresenter 的目标 Surface 来自宿主（TextureView 的 SurfaceTexture 或 SurfaceView），
 * 旋转、全屏、画中画与布局变化只由宿主的视图层得知，模块没有把任何 resize 通知（Surface 回调、布局监听）接到这里；
 * 即使接了，视图层通知的时刻也不保证与 EGL 窗口表面实际改用新尺寸的时刻一致。在存在覆盖全部变化的可靠 resize 通知之前，
 * 每次绘制都查询，这里只去掉了每次绘制新建数组的分配。
 */
internal class FrameGlSurfaceSize {
    /** 把窗口 Surface 当前的宽、高写入 [into]（[0] 宽，[1] 高）；查询失败时不写。 */
    fun interface Source {
        fun query(into: IntArray)
    }

    private val extent = IntArray(2)
    val width: Int get() = extent[0]
    val height: Int get() = extent[1]

    fun refresh(source: Source) {
        // 与每次新建数组等价：查询失败得到 0x0，而不是沿用上一次的尺寸。
        extent[0] = 0
        extent[1] = 0
        source.query(extent)
    }
}
