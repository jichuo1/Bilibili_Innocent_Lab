package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.ImageReader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jichuo1.meguviz.media.GlesMotionWarp
import io.github.jichuo1.meguviz.media.WindowBackend
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Real asynchronous PBO input on the same SDK used when AHB registration is unavailable. */
@RunWith(AndroidJUnit4::class)
class FrameQcomPboDeviceTest {
    @Test fun asyncPboProducesAndDrawsMidpoint() {
        assumeTrue("requires a QCOM qualification device", InstrumentationRegistry.getArguments().getString("qcomDevice") == "true")
        val width = 1280
        val height = 720
        val egl = FrameQcomEgl()
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).use { display ->
            try {
                egl.prepare(width, height)
                egl.connect(display.surface, 60)
                val warp = GlesMotionWarp(width, height, true, false)
                val owned = mutableListOf<Any>()
                try {
                    val paint = Paint().apply { color = Color.WHITE }
                    fun capture(index: Int): Any {
                        val source = checkNotNull(egl.input)
                        val canvas = source.lockCanvas(null)
                        try {
                            canvas.drawColor(Color.DKGRAY)
                            for (column in 0..10) {
                                val x = (column * 120 + index * 12).toFloat()
                                canvas.drawRect(x, height / 4f, x + 42, height * 3 / 4f, paint)
                            }
                        } finally { source.unlockCanvasAndPost(canvas) }
                        val queue = checkNotNull(egl.texture)
                        queue.updateTexImage()
                        val transform = FloatArray(16).also(queue::getTransformMatrix)
                        return checkNotNull(warp.capture(egl.oes, transform, index.toLong(), index * 33_333L)).also(owned::add)
                    }
                    val left = capture(1)
                    val right = capture(2)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    var job = 0L
                    while (job <= 0 && System.nanoTime() < deadline) {
                        warp.poll()
                        job = warp.submit(left, right, deadline)
                        if (job <= 0) Thread.sleep(1)
                    }
                    assertTrue("PBO pair was never accepted", job > 0)
                    var result: WindowBackend.Completion? = null
                    while (result == null && System.nanoTime() < deadline) {
                        result = warp.poll()
                        if (result == null) Thread.sleep(1)
                    }
                    val terminal = checkNotNull(result)
                    assertEquals(job, terminal.jobId)
                    assertEquals(0, terminal.status)
                    assertEquals(2, terminal.frameKind)
                    val midpoint = checkNotNull(terminal.frame).also(owned::add)
                    fun scan(frame: Any): ByteArray {
                        warp.draw(frame, width, height)
                        egl.swap(System.nanoTime())
                        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                        var image = display.acquireNextImage()
                        while (image == null && System.nanoTime() < end) { Thread.sleep(1); image = display.acquireNextImage() }
                        return checkNotNull(image).use { rendered ->
                            val plane = rendered.planes[0]
                            ByteArray(width) { x -> plane.buffer.get(height / 2 * plane.rowStride + x * plane.pixelStride) }
                        }
                    }
                    val interpolated = scan(midpoint)
                    assertFalse(interpolated.contentEquals(scan(left)))
                    assertFalse(interpolated.contentEquals(scan(right)))
                } finally {
                    owned.asReversed().forEach(warp::release)
                    warp.close()
                }
            } finally { egl.close() }
        }
    }
}
