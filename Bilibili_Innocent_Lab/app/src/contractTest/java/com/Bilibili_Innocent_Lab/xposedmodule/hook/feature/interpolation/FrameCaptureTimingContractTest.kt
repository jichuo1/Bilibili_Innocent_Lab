package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

class FrameCaptureTimingContractTest {
    @Test fun `a repeated host display uses the published codec input and still calls the platform setter`() {
        val installer = SourceContract.read("hook/feature/interpolation/FrameInterpolationFeatureInstaller.kt")
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
    }

    @Test fun `revoked bridges cannot restore codec output or reset a replacement audio clock`() {
        val source = SourceContract.read("hook/feature/interpolation/FrameCaptureBridge.kt")
        val restore = source.after("private fun onNativeRestored(").before("companion object")
        val guard = restore.indexOf("if (closed.get() || !isCurrent())")
        val switch = restore.indexOf("decoder.setOutputSurface(display)")
        assertTrue(guard >= 0 && switch > guard)
        val close = source.after("fun close()").before("private fun onFrame(")
        assertTrue(close.contains("if (isCurrent()) host.setAudioDelayUs(0L)"))
        val installer = SourceContract.read("hook/feature/interpolation/FrameInterpolationFeatureInstaller.kt")
        assertTrue(installer.contains("isCurrent = ticket::isActive"))
        assertTrue(installer.contains("pending.await(codec, decoder, { canAttempt(decoder) }"))
        val change = installer.after("private fun changeOutput(").before("private fun reattach(")
        assertTrue(change.contains("decoderGeneration.commit(codec, ticket)"))
        assertTrue(change.indexOf("externalChanges != 0") in 0 until change.indexOf("codec.setOutputSurface(surface)"))
    }
    @Test fun `clock rejection enters recovery before its observation window is reset`() {
        val rejection = SourceContract.read("hook/feature/interpolation/FrameCaptureBridge.kt")
            .after("if (state == FrameTimeline.ClockState.RESTART_REQUIRED").before("clock.reset()")
        assertTrue("repeated invalid snapshots cannot bypass fallback by starting another epoch",
            rejection.contains("if (!session.onClockRejected(now)) return"))
    }

    /**
     * 通知时刻只能经 [FrameArrivalStamp] 使用：它在 PTS 节奏不一致（BufferQueue 顶替、首帧、回跳）时改用锁存之后的时刻，
     * 因此通知时刻不会描述锁存期间换上的更新的帧；锁存时刻必须在可能阻塞的 updateTexImage 之后测得。
     */
    @Test fun `host clock observation never pairs a notification time with a newer latched frame`() {
        val callback = SourceContract.read("hook/feature/interpolation/FrameGlCapture.kt")
            .after("private fun onFrameAvailable(").before("private fun read(")
        val latch = callback.indexOf("runCatching { texture.updateTexImage() }")
        val observation = callback.indexOf("val latched = System.nanoTime()")
        val delivery = callback.indexOf("listener.onFrame(timestamp, stamp.arrival(timestamp, notifiedNs, latched))")
        assertTrue("latch time is measured after the potentially blocking latch", latch >= 0 && observation > latch)
        assertTrue("notification time is only used through FrameArrivalStamp", delivery > observation)
        assertFalse("raw notification time must not reach the clock", callback.contains("listener.onFrame(timestamp, notifiedNs"))
    }
}
