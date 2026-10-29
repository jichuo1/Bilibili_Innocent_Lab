package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationSession.Outcome
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing.FrameNativeLoader
import java.io.File
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 性能报告 P2-05（GL 稳定状态，legacy 过渡补丁）的真实 GL 上下文对照：句柄缓存与按 renderer 自有的 quad 视图之后，
 * 捕获读回与呈现绘制的纹理方向、采样和整面填充仍与改动前一致。不依赖模型与宿主进程，不测量耗时；
 * 同一份测试可原样跑在改动前的构建上做 A/B。窗口 Surface 的旋转/全屏/画中画变化无法用 ImageReader 目标构造，
 * 这部分只由 JVM 假 GL 测试覆盖，设备上未测。
 */
@RunWith(AndroidJUnit4::class)
class FrameGlSteadyStateDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val FRAMES = 30
        const val TOLERANCE = 3
        val QUADRANTS = intArrayOf(Color.rgb(255, 0, 0), Color.rgb(0, 255, 0), Color.rgb(0, 0, 255), Color.rgb(255, 255, 255))
    }

    private fun prepareNative() = assertEquals(FrameNativeLoader.Status.READY,
        FrameInterpolationNative.prepare(File(context.applicationInfo.sourceDir), context.codeCacheDir))

    /** 左上红、右上绿、左下蓝、右下白：同时区分上下与左右翻转。 */
    private fun quadrantColor(x: Int, y: Int, width: Int, height: Int) =
        QUADRANTS[(if (y >= height / 2) 2 else 0) + (if (x >= width / 2) 1 else 0)]

    /** 四个象限中心的采样是否都是预期颜色；[rgb] 给出 (列, 行) 的 RGB。 */
    private fun quadrantsMatch(width: Int, height: Int, rgb: (Int, Int) -> Triple<Int, Int, Int>): Boolean =
        listOf(width / 4 to height / 4, 3 * width / 4 to height / 4, width / 4 to 3 * height / 4, 3 * width / 4 to 3 * height / 4)
            .withIndex().all { (index, point) ->
                val (red, green, blue) = rgb(point.first, point.second)
                val expected = QUADRANTS[index]
                Math.abs(Color.red(expected) - red) <= TOLERANCE && Math.abs(Color.green(expected) - green) <= TOLERANCE &&
                    Math.abs(Color.blue(expected) - blue) <= TOLERANCE
            }

    private fun waitFor(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
    }

    private fun postPattern(surface: Surface) {
        val canvas = surface.lockHardwareCanvas()
        try {
            val halfWidth = canvas.width / 2f
            val halfHeight = canvas.height / 2f
            val paint = Paint()
            for (index in 0 until 4) {
                paint.color = QUADRANTS[index]
                val left = if (index % 2 == 1) halfWidth else 0f
                val top = if (index >= 2) halfHeight else 0f
                canvas.drawRect(left, top, left + halfWidth, top + halfHeight, paint)
            }
        } finally { surface.unlockCanvasAndPost(canvas) }
    }

    /** 返回（读回与图案一致的帧数, 读回失败或不一致的帧数）。 */
    private fun runCapture(width: Int, height: Int, sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        prepareNative()
        val matched = AtomicInteger()
        val failed = AtomicInteger()
        val capture = FrameGlCapture(width, height, sourceWidth, sourceHeight)
        val listener = FrameGlCapture.FrameListener { _, _, read ->
            val format = FrameBufferLedger.Format(width, height, width * 3)
            val buffer = ByteBuffer.allocateDirect(format.bytes)
            val ok = read(buffer, format) && quadrantsMatch(width, height) { x, y ->
                val offset = y * format.stride + x * 3
                Triple(buffer.get(offset).toInt() and 255, buffer.get(offset + 1).toInt() and 255, buffer.get(offset + 2).toInt() and 255)
            }
            if (ok) matched.incrementAndGet() else failed.incrementAndGet()
        }
        val surface = capture.start(listener, 5_000L)
        assertNotNull("capture starts on a real EGL/GLES context", surface)
        try {
            repeat(FRAMES) { postPattern(surface!!); Thread.sleep(25) }
            waitFor { matched.get() + failed.get() >= FRAMES / 2 }
        } finally {
            capture.close()
            Thread.sleep(300)
        }
        return matched.get() to failed.get()
    }

    @Test fun captureKeepsOrientationAcrossManyFrames() {
        val (matched, failed) = runCapture(64, 48, 64, 48)
        assertEquals("every readback matches the pattern", 0, failed)
        assertTrue("frames read back: $matched", matched >= FRAMES / 2)
    }

    @Test fun captureDownscalePathKeepsOrientationAcrossManyFrames() {
        val (matched, failed) = runCapture(64, 48, 128, 96)
        assertEquals("every downscaled readback matches the pattern", 0, failed)
        assertTrue("frames read back: $matched", matched >= FRAMES / 2)
    }

    /** 返回（与图案一致的显示帧数, 不一致的显示帧数, 回报的结果）。 */
    private fun runPresenter(): Triple<Int, Int, List<Outcome>> {
        val width = 64
        val height = 48
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4)
        val readerThread = HandlerThread("p205-reader").apply { start() }
        val matched = AtomicInteger()
        val mismatched = AtomicInteger()
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireNextImage()
            if (image != null) {
                try {
                    val plane = image.planes[0]
                    val pixels = plane.buffer
                    val ok = quadrantsMatch(width, height) { x, y ->
                        val offset = y * plane.rowStride + x * plane.pixelStride
                        Triple(pixels.get(offset).toInt() and 255, pixels.get(offset + 1).toInt() and 255, pixels.get(offset + 2).toInt() and 255)
                    }
                    if (ok) matched.incrementAndGet() else mismatched.incrementAndGet()
                } finally { image.close() }
            }
        }, Handler(readerThread.looper))
        val presenter = FrameGlPresenter(reader.surface, 60, false) { }
        val outcomes = Collections.synchronizedList(ArrayList<Outcome>())
        try {
            assertTrue("presenter connects on a real EGL window", presenter.start(5_000L))
            val format = FrameBufferLedger.Format(width, height, width * 3)
            val rgb = ByteBuffer.allocateDirect(format.bytes)
            for (y in 0 until height) for (x in 0 until width) {
                val color = quadrantColor(x, y, width, height)
                val offset = y * format.stride + x * 3
                rgb.put(offset, Color.red(color).toByte()).put(offset + 1, Color.green(color).toByte()).put(offset + 2, Color.blue(color).toByte())
            }
            val first = System.nanoTime() + 100_000_000L
            repeat(FRAMES) { index ->
                presenter.present(index.toLong(), rgb, format, first + index * 16_666_667L) { outcomes += it }
            }
            waitFor { outcomes.size >= FRAMES && matched.get() + mismatched.get() > 0 }
            Thread.sleep(200)
        } finally {
            presenter.close()
            Thread.sleep(300)
            reader.close()
            readerThread.quitSafely()
        }
        return Triple(matched.get(), mismatched.get(), outcomes.toList())
    }

    @Test fun presenterDrawsFirstRowOnTopAndFillsTheSurfaceAcrossManyFrames() {
        val (matched, mismatched, outcomes) = runPresenter()
        assertEquals("every displayed frame matches the pattern", 0, mismatched)
        assertTrue("displayed frames: $matched of ${outcomes.size} outcomes", matched >= 5)
        assertTrue("no surface loss: $outcomes", outcomes.none { it is Outcome.SurfaceLost })
        assertTrue("presented frames: ${outcomes.count { it is Outcome.Presented }}", outcomes.count { it is Outcome.Presented } >= 5)
    }

    /** 每个 renderer 自带全新句柄缓存：反复建立、关闭后新实例照常工作。 */
    @Test fun rebuiltRenderersWorkFromFreshHandles() {
        repeat(3) { round ->
            val (captureMatched, captureFailed) = runCapture(64, 48, 64, 48)
            assertEquals("capture round $round", 0, captureFailed)
            assertTrue("capture round $round read $captureMatched", captureMatched >= FRAMES / 2)
            val (displayed, mismatched, _) = runPresenter()
            assertEquals("presenter round $round", 0, mismatched)
            assertTrue("presenter round $round displayed $displayed", displayed >= 5)
        }
    }
}
