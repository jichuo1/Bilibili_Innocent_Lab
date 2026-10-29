package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 性能报告 P2-05（GL 稳定状态，legacy 过渡补丁）的源码护栏：稳态绘制路径不查句柄、不生成 quad 视图或尺寸数组；
 * 句柄缓存在 link 后建立、在拆除时失效；eglQuerySurface 仍每次绘制执行。行为由 FrameGlHandlesTest 覆盖，GL 上下文本身需设备验证。
 */
class FrameGlSteadyStateContractTest {
    private fun source(name: String) = SourceContract.read("hook/feature/interpolation/$name")

    @Test fun `capture read path uses cached handles and owned views`() {
        val capture = source("FrameGlCapture.kt")
        val read = capture.after("private fun read(").before("private fun tearDown(")
        assertFalse("steady-state read must not query program handles", read.contains("glGetUniformLocation") || read.contains("glGetAttribLocation"))
        assertTrue(read.contains("handles.uniform(program, UNIFORM_TRANSFORM)"))
        assertTrue(read.contains("handles.uniform(program, UNIFORM_OFFSET)"))
        assertTrue(read.contains("handles.bindQuad(program)"))
        val link = capture.after("private fun setUp(").before("private fun onFrameAvailable(")
        assertTrue("handles are resolved right after a successful link",
            link.indexOf("handles.link(program)") > link.indexOf("program = FrameGl.program("))
        assertTrue("context teardown drops the cached handles", capture.after("private fun tearDown(").before("private companion object").contains("handles.invalidate()"))
    }

    @Test fun `presenter draw path reuses its size holder and still queries the window every draw`() {
        val presenter = source("FrameGlPresenter.kt")
        val draw = presenter.after("private fun draw(").before("// 诊断：请求的呈现时间戳间隔")
        assertFalse("no per-draw size array", draw.contains("IntArray("))
        assertFalse("no per-draw quad views", draw.contains("duplicate(") || draw.contains("glGetAttribLocation"))
        val refresh = draw.lines().single { it.contains("surfaceSize.refresh(windowExtent)") }
        assertFalse("without a reliable resize notification the query must stay unconditional (see FrameGlSurfaceSize)",
            refresh.contains("if ") || refresh.contains("?."))
        assertTrue(draw.contains("handles.bindQuad(rgbProgram)"))
        assertTrue("the surface is still queried through eglQuerySurface", presenter.after("private val windowExtent").before("@Volatile private var running").contains("EGL14.eglQuerySurface("))
        val link = presenter.after("private fun setUp()").before("private fun upload(")
        assertTrue(link.indexOf("handles.link(rgbProgram)") > link.indexOf("rgbProgram = FrameGl.program("))
        assertTrue(presenter.after("private fun tearDown()").before("private companion object").contains("handles.invalidate()"))
    }

    @Test fun `no shared mutable quad buffer remains in FrameGl`() {
        val frameGl = source("FrameGl.kt")
        assertFalse("a process-wide FloatBuffer would let GL threads move each other's position", frameGl.contains("FLIPPED_QUAD") || frameGl.contains("duplicate("))
        assertFalse(frameGl.contains("fun bindQuad("))
        assertFalse("location queries live only in the FrameGlApi adapter", frameGl.before("internal object FrameGlEs2").contains("glGetAttribLocation"))
    }

    @Test fun `each renderer builds its own views once and binding allocates nothing`() {
        val handles = source("FrameGlHandles.kt")
        val views = handles.after("internal class FrameGlQuadViews").before("internal class FrameGlProgramHandles")
        assertTrue(views.contains("allocateDirect(") && views.contains("duplicate()"))
        val bind = handles.after("fun bindQuad(").before("private companion object")
        assertFalse(bind.contains("duplicate(") || bind.contains("allocate") || bind.contains("FloatArray(") || bind.contains("IntArray("))
        assertTrue("every renderer owns an instance", source("FrameGlCapture.kt").contains("private val handles = FrameGlProgramHandles(")
            && source("FrameGlPresenter.kt").contains("private val handles = FrameGlProgramHandles("))
    }

    @Test fun `legacy transition patch keeps its successor and removal notes`() {
        val handles = source("FrameGlHandles.kt")
        assertTrue(handles.contains("legacy 过渡补丁"))
        assertTrue(handles.contains("接替任务"))
        assertTrue(handles.contains("迁移完成后删除"))
        assertTrue(handles.contains("MeguvizMedia"))
    }
}
