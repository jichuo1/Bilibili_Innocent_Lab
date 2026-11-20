package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing.FrameNativeLoader
import io.github.jichuo1.meguviz.media.AndroidMediaSession
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Checks completed image bytes and in-flight cancellation independently of playback's 24 ms admission gate. */
@RunWith(AndroidJUnit4::class)
class FrameRifeAhbDeviceTest {
    @Test fun gpuAndHostWaitProduceSameMidpointAndRetireCancelledWork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val model = File(context.filesDir, "rife-v4.6")
        assumeTrue("supply the fixed RIFE model for this GPU test",
            InstrumentationRegistry.getArguments().getString("fallbackModel") == "true")
        assertEquals(FrameNativeLoader.Status.READY,
            FrameInterpolationNative.prepare(File(context.applicationInfo.sourceDir), context.codeCacheDir))
        val opened = FrameInterpolationBackend.openResearchModel(model, FrameInterpolationBackend.Backend.VULKAN,
            fp16 = true, flowScale = 4, gpuIo = true, adaptiveFlow = true)
        assertEquals(FrameInterpolationBackend.Status.OK, opened.status)
        val egl = FrameQcomEgl()
        checkNotNull(opened.engine).use { engine ->
            try {
                egl.prepare(WIDTH, HEIGHT)
                FrameAhbProbeSurface().use { fixture ->
                    fixture.prepare(WIDTH, HEIGHT, WIDTH, HEIGHT)
                    val gpu = midpoint(engine, fixture, AndroidMediaSession.AHB_GPU_SYNC)
                    val host = midpoint(engine, fixture, AndroidMediaSession.AHB_HOST_WAIT)
                    assertArrayEquals(gpu, host)
                    instrumentation.sendStatus(0, android.os.Bundle().apply {
                        putString("rifeAhbPixels", "${WIDTH}x$HEIGHT GPU_SYNC=HOST_WAIT bytes=${gpu.size} cancelledJobsRetired=2")
                    })
                }
            } finally {
                AndroidMediaSession.discardPreparedCreationAsync()
                egl.close()
            }
        }
    }

    private fun midpoint(engine: FrameInterpolationBackend, fixture: FrameAhbProbeSurface, path: Int): ByteArray {
        AndroidMediaSession.prepareCreationAsync()
        val session = await("session") {
            try { engine.openWindowSession(WIDTH, HEIGHT, WIDTH, HEIGHT, path) }
            catch (error: AndroidMediaSession.MediaException) {
                if (error.status != -7) throw error
                AndroidMediaSession.servicePendingCreation()
                null
            }
        }
        val frames = mutableListOf<AndroidMediaSession.Frame>()
        try {
            assertEquals(0L, session.probe()[0])
            fun capture(index: Int): AndroidMediaSession.Frame {
                fixture.input(index)
                return session.capture(fixture.oes, fixture.transform, index + 1L, index * 33_333L, 1, 1).also(frames::add)
            }
            fun receipt(job: Long): AndroidMediaSession.Completed = await("GPU job $job") {
                session.poll(0)
                session.pollCompleted()?.takeIf { it.jobId == job }
            }
            fun pixels(frame: AndroidMediaSession.Frame): ByteArray {
                fixture.bindOutput()
                await("draw") {
                    try { session.draw(frame, WIDTH, HEIGHT); true }
                    catch (error: AndroidMediaSession.MediaException) {
                        if (error.status !in listOf(-7,-8,-12)) throw error
                        session.poll(0)
                        null
                    }
                }
                assertTrue("stable color patches were not drawn", fixture.validDraw())
                val bytes = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4)
                GLES30.glReadPixels(0, 0, WIDTH, HEIGHT, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
                assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
                return ByteArray(bytes.capacity()).also { bytes.get(it) }
            }
            val left = capture(0)
            val right = capture(1)
            val id = session.submit(left, right, 0.5, System.nanoTime() + TIMEOUT_NS, 4)
            assertTrue(id > 0)
            val completed = receipt(id)
            assertEquals(0, completed.status)
            assertEquals(2, completed.frameKind)
            val output = checkNotNull(completed.frame).also(frames::add)
            val result = pixels(output)
            assertFalse(result.contentEquals(pixels(left)))
            assertFalse(result.contentEquals(pixels(right)))
            output.close()
            frames.remove(output)
            val retiring = session.submit(left, right, 0.5, System.nanoTime() + TIMEOUT_NS, 4)
            assertTrue(retiring > 0)
            session.cancel(retiring)
            receipt(retiring).frame?.let(frames::add)
            return result
        } finally {
            frames.asReversed().forEach { it.close() }
            await("session retirement") {
                runCatching { session.poll(0) }
                try { session.close(); true }
                catch (error: AndroidMediaSession.MediaException) {
                    if (error.status !in listOf(-7,-8,-12)) throw error
                    null
                }
            }
        }
    }

    private fun <T : Any> await(stage: String, operation: () -> T?): T {
        val until = System.nanoTime() + TIMEOUT_NS
        do {
            operation()?.let { return it }
            Thread.sleep(1)
        } while (System.nanoTime() < until)
        error("timed out during $stage")
    }

    private companion object {
        const val WIDTH = 160
        const val HEIGHT = 90
        const val TIMEOUT_NS = 3_000_000_000L
    }
}
