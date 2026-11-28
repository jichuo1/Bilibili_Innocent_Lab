package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插帧流水线运行期护栏：
 * - 重复设置输出 Surface 的引用传递、撤销代次防回写、时钟拒绝与锁存时间戳顺序；
 * - GL 稳态读写复用已链接句柄与独占顶点视图，不留共享可变 FloatBuffer；
 * - 准备阶段租约复用、模型读取校验、能力探测失败回退与设备丢失退役接线。
 */
class FrameInterpolationPipelineContractTest {

    private fun read(name: String) = SourceContract.read("hook/feature/interpolation/$name")

    @Test
    fun `capture timing, revocation guards, and clock observation order remain intact`() {
        val installer = read("FrameInterpolationFeatureInstaller.kt")
        val hook = installer.after("hook(\"output.surface\")").before("hook(\"flush\")")
        val before = hook.after("before {").before("after {")
        assertTrue(before.contains("decoderGeneration.commit(codec, decoder.ticket)"))
        assertTrue(before.contains("bridge.decoderInputFor(requested) ?: return@commit"))
        assertTrue(before.contains("args[0] = input"))
        assertFalse(before.contains("result ="))
        val after = hook.after("after {")
        assertTrue(after.contains("borrowed == null && !hasThrowable"))
        assertTrue(after.after("finally {").contains("borrowed?.finishDecoderInput()"))
        val published = installer.after("is FramePlaybackRoutes.Result.Attached ->").before("FramePlaybackRoutes.Result.WaitForRife")
        val registered = published.indexOf("bridges[codec] = outcome.bridge")
        assertTrue(registered >= 0 && published.indexOf("outcome.bridge.onPublished()") > registered)

        val bridge = read("FrameCaptureBridge.kt")
        val restore = bridge.after("private fun onNativeRestored(").before("companion object")
        val guard = restore.indexOf("if (closed.get() || !isCurrent())")
        val switch = restore.indexOf("decoder.setOutputSurface(display)")
        assertTrue(guard >= 0 && switch > guard)
        val close = bridge.after("fun close()").before("private fun onFrame(")
        assertTrue(close.contains("if (isCurrent()) host.setAudioDelayUs(0L)"))
        assertTrue(installer.contains("isCurrent = ticket::isActive"))
        assertTrue(installer.contains("pending.await(codec, decoder, { canAttempt(decoder) }"))
        val change = installer.after("private fun changeOutput(").before("private fun reattach(")
        assertTrue(change.contains("decoderGeneration.commit(codec, ticket)"))
        assertTrue(change.indexOf("externalChanges != 0") in 0 until change.indexOf("codec.setOutputSurface(surface)"))

        val rejection = bridge.after("if (state == FrameTimeline.ClockState.RESTART_REQUIRED").before("clock.reset()")
        assertTrue(
            "repeated invalid snapshots cannot bypass fallback by starting another epoch",
            rejection.contains("if (!session.onClockRejected(now)) return")
        )

        // 通知时刻只能经 FrameArrivalStamp 使用；锁存时刻必须在可能阻塞的 updateTexImage 之后测得。
        val callback = read("FrameGlCapture.kt").after("private fun onFrameAvailable(").before("private fun read(")
        val latch = callback.indexOf("runCatching { texture.updateTexImage() }")
        val observation = callback.indexOf("val latched = System.nanoTime()")
        val delivery = callback.indexOf("listener.onFrame(timestamp, stamp.arrival(timestamp, notifiedNs, latched))")
        assertTrue("latch time is measured after the potentially blocking latch", latch >= 0 && observation > latch)
        assertTrue("notification time is only used through FrameArrivalStamp", delivery > observation)
        assertFalse("raw notification time must not reach the clock", callback.contains("listener.onFrame(timestamp, notifiedNs"))
    }

    @Test
    fun `gl steady-state capture and presenter paths reuse cached handles and owned views`() {
        val capture = read("FrameGlCapture.kt")
        val readPath = capture.after("private fun read(").before("private fun tearDown(")
        assertFalse("steady-state read must not query program handles", readPath.contains("glGetUniformLocation") || readPath.contains("glGetAttribLocation"))
        assertTrue(readPath.contains("handles.uniform(program, UNIFORM_TRANSFORM)"))
        assertTrue(readPath.contains("handles.uniform(program, UNIFORM_OFFSET)"))
        assertTrue(readPath.contains("handles.bindQuad(program)"))
        val captureLink = capture.after("private fun setUp(").before("private fun onFrameAvailable(")
        assertTrue(
            "handles are resolved right after a successful link",
            captureLink.indexOf("handles.link(program)") > captureLink.indexOf("program = FrameGl.program(")
        )
        assertTrue(
            "context teardown drops the cached handles",
            capture.after("private fun tearDown(").before("private companion object").contains("handles.invalidate()")
        )

        val presenter = read("FrameGlPresenter.kt")
        val draw = presenter.after("private fun draw(").before("private var lastTimestampNs = 0L")
        assertFalse("no per-draw size array", draw.contains("IntArray("))
        assertFalse("no per-draw quad views", draw.contains("duplicate(") || draw.contains("glGetAttribLocation"))
        val refresh = draw.lines().single { it.contains("surfaceSize.refresh(windowExtent)") }
        assertFalse(
            "without a reliable resize notification the query must stay unconditional (see FrameGlSurfaceSize)",
            refresh.contains("if ") || refresh.contains("?.")
        )
        assertTrue(draw.contains("handles.bindQuad(rgbProgram)"))
        assertTrue(
            "the surface is still queried through eglQuerySurface",
            presenter.after("private val windowExtent").before("@Volatile private var running").contains("EGL14.eglQuerySurface(")
        )
        val presenterLink = presenter.after("private fun setUp()").before("private fun upload(")
        assertTrue(presenterLink.indexOf("handles.link(rgbProgram)") > presenterLink.indexOf("rgbProgram = FrameGl.program("))
        assertTrue(presenter.after("private fun tearDown()").before("private companion object").contains("handles.invalidate()"))

        val frameGl = read("FrameGl.kt")
        assertFalse(
            "a process-wide FloatBuffer would let GL threads move each other's position",
            frameGl.contains("FLIPPED_QUAD") || frameGl.contains("duplicate(")
        )
        assertFalse(frameGl.contains("fun bindQuad("))
        assertFalse(
            "location queries live only in the FrameGlApi adapter",
            frameGl.before("internal object FrameGlEs2").contains("glGetAttribLocation")
        )

        val handles = read("FrameGlHandles.kt")
        val views = handles.after("internal class FrameGlQuadViews").before("internal class FrameGlProgramHandles")
        assertTrue(views.contains("allocateDirect(") && views.contains("duplicate()"))
        val bind = handles.after("fun bindQuad(").before("private companion object")
        assertFalse(bind.contains("duplicate(") || bind.contains("allocate") || bind.contains("FloatArray(") || bind.contains("IntArray("))
        assertTrue(
            "every renderer owns an instance",
            capture.contains("private val handles = FrameGlProgramHandles(") &&
                presenter.contains("private val handles = FrameGlProgramHandles(")
        )
    }

    @Test
    fun `preparation scratch arena, model verification, capability probe, and device-loss recovery hold`() {
        val holder = read("FrameInterpolationEngineHolder.kt")
        assertFalse(holder.contains("ByteBuffer.allocateDirect("))
        assertFalse(holder.contains("fun medianMs("))
        assertTrue(holder.contains("withScratch(WARM_SIZES + capabilitySizes)"))
        assertTrue(holder.contains("FrameCapabilityProbe(arena)"))
        assertTrue(holder.contains("withScratch(listOf(size), inputs = 2)"))

        val queued = holder.after("fun prewarm(width:").before("private fun warm(")
        assertTrue(queued.contains("engine.claimWarmup(size)"))
        assertTrue(queued.contains("backend === engine && ready()"))
        assertTrue(queued.after("finally {").contains("engine.finishWarmup(size, status)"))
        assertTrue(queued.contains("engine.finishWarmup(size, FrameInterpolationBackend.Status.BUSY)"))
        val warm = holder.after("private fun warm(").before("private fun robustMs(")
        assertTrue(warm.contains("if (status != FrameInterpolationBackend.Status.OK) retireLostDevice(engine)"))

        val session = holder.after("private fun withScratch(").before("@Volatile private var modelName")
        assertTrue(session.contains("FrameScratchArena.withMemory(bytes, inputs)"))
        val arena = read("FrameScratchArena.kt")
        val scope = arena.after("fun withMemory(")
        assertTrue(scope.contains("FramePreparationMemory.withStorage("))
        assertTrue(scope.after("finally {").contains("arena.invalidate()"))
        val memory = read("FramePreparationMemory.kt")
        assertTrue(memory.contains("ManagedMemoryDomain.system("))
        assertTrue(memory.contains(".use { allocation ->"))
        assertTrue(memory.contains("allocation.borrowWrite().use { lease ->"))
        assertTrue(memory.contains("lease.withDirectBuffer { storage ->"))

        val backend = read("FrameInterpolationBackend.kt")
        val loan = backend.after("FramePreparationMemory.withStorage(").before("opened = openVerifiedModel(")
        assertTrue(loan.contains("readVerified(paramFile, FrameModelSpec.PARAM, param)"))
        assertTrue(loan.contains("readVerified(weightsFile, FrameModelSpec.WEIGHTS, weights)"))
        assertTrue(
            backend.after("private fun readVerified(").before("private fun planeValid(")
                .contains("FrameModelSpec.matches(part, input)")
        )
        assertTrue(backend.contains("MediaNative.frameLoad(handle, param, weights)"))
        assertFalse(backend.contains("ByteBuffer.allocateDirect"))

        val measure = holder.after("private fun measureCapability(").before("/** 为宿主实际尺寸预热")
        assertTrue(measure.contains("measured != null && measured.also { chosenMs = it } <="))
        assertTrue(measure.contains("engine.probeInterpolation("))
        val evidence = backend.after("fun probeInterpolation(").before("fun unreliablePermille(")
        assertTrue(evidence.contains("if (status != Status.OK) return status"))
        assertTrue(evidence.contains("frameLastFrameKind(handle) == FRAME_INFERRED"))
        assertTrue(evidence.contains("frameLastEffectiveFlowScale(handle) == flowScale"))
        val robust = holder.after("private fun robustMs(").before("@Synchronized private fun fail(")
        assertTrue(robust.contains("is FrameCapabilityProbe.Result.Failed"))
        assertTrue(robust.contains("engine.capability.failed"))
        assertTrue(robust.contains("null"))

        val probe = read("FrameCapabilityProbe.kt")
        assertTrue(probe.contains("val warm = step(0)"))
        assertTrue(probe.contains("if (warm != FrameInterpolationBackend.Status.OK) return Result.Failed(warm, 0)"))
        assertTrue(probe.contains("if (status != FrameInterpolationBackend.Status.OK) return Result.Failed(status, index + 1)"))
        assertTrue(probe.contains("Result.Measured(times[1], times)"))
        assertTrue(probe.before("val started = nanoTime()").contains("arena.input(it, bytes)"))

        assertTrue(backend.contains("MediaNative.frameDeviceLossStatus(handle) == MGC_DEVICE_LOST"))
        val retire = holder.after("private fun retireLostDevice(").before("@Synchronized private fun fail(")
        assertTrue(retire.before("backend = null").contains("if (backend !== engine || !engine.deviceLost()) return false"))
        assertTrue(retire.contains("engine.close()"))
        assertTrue(retire.after("finally {").contains("fail(\"device-lost\", FramePreparationRetry.Work.LOAD)"))

        val arenaCode = arena.after("internal class FrameScratchArena(")
        assertFalse(arenaCode.contains("FrameBufferLedger."))
        assertFalse(arenaCode.contains("ByteBuffer.allocateDirect"))
        assertTrue(arenaCode.contains("slot.asReadOnlyBuffer()"))
    }
}
