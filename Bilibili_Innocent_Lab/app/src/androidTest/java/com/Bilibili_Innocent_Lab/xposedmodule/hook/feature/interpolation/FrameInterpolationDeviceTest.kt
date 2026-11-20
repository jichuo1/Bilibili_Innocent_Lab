package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationSession.Outcome
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing.FrameNativeLoader
import io.github.jichuo1.meguviz.media.MediaNative
import io.github.jichuo1.meguviz.media.MediaRuntime
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机证据：打包原生依赖的解包加载、Vulkan/CPU 引擎创建，以及所有权/恢复在真实线程与真实
 * Surface 消费者下闭合。不含模型与宿主播放器，故不能证明宿主时钟、捕获与宿主 Surface 接管。
 */
@RunWith(AndroidJUnit4::class)
class FrameInterpolationDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun prepareNative(): FrameNativeLoader.Status =
        FrameInterpolationNative.prepare(File(context.applicationInfo.sourceDir), context.codeCacheDir)

    @Test fun packedNativeDependencyExtractsLoadsAndReusesCache() {
        val first = prepareNative()
        assertEquals(FrameNativeLoader.Status.READY, first)
        assertTrue(FrameInterpolationNative.available)
        assertTrue(MediaRuntime.isAttached())
        assertEquals(1, MediaNative.abiVersion())
        val abi = if (android.os.Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
        val name = "libmeguviz_media_rife_ncnn.so"
        val hash = ZipInputStream(context.assets.open("native-packing/$abi/VisionTool.zip")).use { archive ->
            assertEquals("lib/$abi/$name", archive.nextEntry.name)
            sha256(archive)
        }
        val extracted = File(context.codeCacheDir, "native-dependency-packing-v1/$abi/$hash/$name")
        assertEquals(hash, extracted.inputStream().use(::sha256))
        val inode = android.system.Os.stat(extracted.absolutePath).st_ino
        assertEquals(FrameNativeLoader.Status.READY, prepareNative())
        assertEquals(inode, android.system.Os.stat(extracted.absolutePath).st_ino)
        Log.i(TAG, "native ready: $abi ${extracted.length()} bytes sha256=$hash")
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun vulkanAndCpuEnginesCreateAndRefuseInferenceWithoutModel() {
        assertEquals(FrameNativeLoader.Status.READY, prepareNative())
        for (backend in FrameInterpolationBackend.Backend.entries) {
            val created = MediaNative.frameCreate(backend.nativeValue, 0, 1, FrameInterpolationBackend.MAX_PIXELS)
            Log.i(TAG, "create $backend -> status ${created[0]} handle ${created[1]}")
            assertEquals("$backend create", 0L, created[0])
            val handle = created[1]
            assertTrue(handle > 0L)
            assertEquals(FrameInterpolationBackend.Status.INVALID_ARGUMENT.nativeValue, MediaNative.frameConfigure(handle, 256))
            assertEquals(FrameInterpolationBackend.Status.INVALID_ARGUMENT, FrameInterpolationBackend.openResearchModel(
                context.filesDir, backend, rawGpuInput = true).status)
            assertEquals(FrameInterpolationBackend.Status.INVALID_ARGUMENT, FrameInterpolationBackend.openResearchModel(
                context.filesDir, backend, adaptiveFlow = true).status)
            val (left, right, out) = List(3) { ByteBuffer.allocateDirect(4 * 2 * 3) }
            assertEquals(FrameInterpolationBackend.Status.NOT_LOADED.nativeValue,
                MediaNative.frameProcess(handle, left, right, out, 4, 2, 12, 12, 12, 0.5f, -1L, -1L, 0))
            MediaNative.frameClose(handle)
            assertEquals(FrameInterpolationBackend.Status.NOT_LOADED.nativeValue,
                MediaNative.frameProcess(handle, left, right, out, 4, 2, 12, 12, 12, 0.5f, -1L, -1L, 0))
        }
    }

    @Test fun rgbaCompactionHandlesVectorTailsAndPaddedRows() {
        assertEquals(FrameNativeLoader.Status.READY, prepareNative())
        for (width in listOf(1, 15, 16, 17, 31, 32, 33, 127)) {
            val height = 3
            val stride = width * 3 + 11
            val source = ByteBuffer.allocateDirect(width * height * 4)
            val output = ByteBuffer.allocateDirect(stride * height)
            for (i in 0 until source.capacity()) source.put(i, (i * 37).toByte())
            for (i in 0 until output.capacity()) output.put(i, 0x5a.toByte())
            assertEquals(0, MediaNative.rgbaToRgb(source, width, height, output, stride))
            for (y in 0 until height) {
                for (x in 0 until width) for (c in 0..2)
                    assertEquals("$width/$x/$y/$c", source.get((y * width + x) * 4 + c), output.get(y * stride + x * 3 + c))
                for (i in width * 3 until stride) assertEquals(0x5a.toByte(), output.get(y * stride + i))
            }
        }
    }

    @Test fun nativeSceneAnalysisMatchesReferenceAndChecksCapacity() {
        assertEquals(FrameNativeLoader.Status.READY, prepareNative())
        for ((w, h) in listOf(1 to 1, 63 to 35, 1280 to 720)) {
            val format = FrameBufferLedger.Format(w, h, w * 3 + 7)
            fun frame(kind: Int): ByteBuffer = ByteBuffer.allocateDirect(format.bytes).also { b ->
                for (y in 0 until h) for (x in 0 until w) for (c in 0..2) {
                    val value = when (kind) {
                        0 -> (x + y) % 120
                        1 -> (x + y) % 120 + if ((x + y) % 9 == 0) 1 else 0
                        2 -> 255
                        else -> if ((x / 8 + y / 8) % 2 == 0) 20 else 220
                    }
                    b.put(y * format.stride + x * 3 + c, value.toByte())
                }
            }
            val a = frame(0)
            for (kind in 0..3) {
                val b = frame(kind)
                val expected = when {
                    FrameStaticScene.isStatic(a, b, format) -> 1
                    FrameSceneChange.isCut(a, b, format) -> 2
                    else -> 0
                }
                val began = System.nanoTime()
                val actual = MediaNative.analyzeScene(a, b, w, h, format.stride)
                assertEquals("$w/$h/$kind", expected, actual)
                Log.i(TAG, "scene ${w}x$h kind=$kind code=$actual us=${(System.nanoTime() - began) / 1_000}")
                assertEquals(0, a.position())
            }
            assertEquals(-1, MediaNative.analyzeScene(a, ByteBuffer.allocateDirect(1), w, h, format.stride))
        }
    }

    private class Scenario(val presented: Int, val late: Int, val consumed: Int, val phases: List<String>,
                           val recovery: FramePresentationRecovery.Report, val inferenceAvgUs: Long)

    /** 推理耗时远小于目标周期：插帧真实上屏；Surface 重建后即便首个 begin 处于退避期，也会自动恢复插帧。 */
    @Test fun ownershipAndRecoveryCloseWithFastSynthesizer() {
        val result = runScenario("fast") { left, _, output, _, _ -> output.put(left.duplicate()) }
        assertTrue(result.presented > 0)
        assertTrue(result.consumed > 0)
        assertTrue("resumes after the replacement surface: ${result.phases}",
            result.phases.any { it.startsWith("beforeClose ACTIVE") })
    }

    /** 推理耗时超过目标周期：错过的槽位不迟交，管线回退原生，账本同样清空。 */
    @Test fun lateInferenceFallsBackWithoutPresentingLateFrames() {
        val result = runScenario("slow") { left, _, output, _, _ -> Thread.sleep(20); output.put(left.duplicate()) }
        assertEquals(0, result.late)
        assertTrue(result.recovery.state == FramePresentationRecovery.State.FALLBACK ||
            result.recovery.state == FramePresentationRecovery.State.DISABLED)
    }

    /** 真实 worker、Presenter 线程与 Choreographer 驱动，期间 seek、暂停、Surface 丢失重建，最后拆除。 */
    private fun runScenario(name: String,
                            synthesize: (ByteBuffer, ByteBuffer, ByteBuffer, FrameBufferLedger.Format, Float) -> Unit): Scenario {
        val width = 320
        val height = 180
        val fps = 120
        val ledger = FrameBufferLedger(8)
        val consumed = AtomicInteger()
        val presented = AtomicInteger()
        val late = AtomicInteger()
        val surfaceLost = AtomicInteger()
        val closes = AtomicInteger()
        val inferences = AtomicInteger()
        val inferenceNs = java.util.concurrent.atomic.AtomicLong()
        val failure = AtomicReference<Throwable?>()
        val consumerThread = HandlerThread("frame-verify-consumer").apply { start() }
        val presenterThread = HandlerThread("frame-verify-presenter").apply { start() }
        val presenterHandler = Handler(presenterThread.looper)
        fun reader() = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close(); consumed.incrementAndGet() },
                Handler(consumerThread.looper))
        }
        var imageReader = reader()
        val surface = AtomicReference<Surface>(imageReader.surface)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val rgb = ByteArray(width * height * 3)
        val sourceRgb = ByteArray(width * height * 3)
        val presentNs = java.util.concurrent.atomic.AtomicLong()

        val presenter = object : FrameInterpolationSession.Presenter {
            override fun present(frameId: Long, buffer: ByteBuffer, format: FrameBufferLedger.Format, dueNs: Long,
                                 done: (Outcome) -> Unit): Boolean = presenterHandler.post {
                val began = System.nanoTime()
                val outcome = runCatching {
                    buffer.get(rgb, 0, format.bytes)
                    for (p in 0 until width * height) {
                        val i = p * 3
                        pixels[p] = (0xff shl 24) or ((rgb[i].toInt() and 0xff) shl 16) or
                            ((rgb[i + 1].toInt() and 0xff) shl 8) or (rgb[i + 2].toInt() and 0xff)
                    }
                    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                    val target = surface.get()
                    val canvas = target.lockCanvas(null)
                    canvas.drawBitmap(bitmap, 0f, 0f, null)
                    target.unlockCanvasAndPost(canvas)
                    val now = System.nanoTime()
                    presentNs.addAndGet(now - began)
                    presented.incrementAndGet()
                    if (now - dueNs > 1_000_000_000L / fps / 2) late.incrementAndGet()
                    Outcome.Presented(now) as Outcome
                }.getOrElse { surfaceLost.incrementAndGet(); Outcome.SurfaceLost(System.nanoTime()) }
                done(outcome)
            }
            override fun restoreNative(report: FramePresentationRecovery.Report) {
                Log.i(TAG, "restoreNative ${report.state} ${report.cause} ${report.nativeStatus}")
            }
        }
        val synthesizer = object : FrameInterpolationSession.Synthesizer {
            override fun process(left: ByteBuffer, right: ByteBuffer, output: ByteBuffer,
                                 format: FrameBufferLedger.Format, alpha: Float, leftId: Long, rightId: Long): FrameInterpolationBackend.Status {
                val began = System.nanoTime()
                synthesize(left, right, output, format, alpha)
                inferenceNs.addAndGet(System.nanoTime() - began)
                inferences.incrementAndGet()
                return FrameInterpolationBackend.Status.OK
            }
            override fun close() { closes.incrementAndGet() }
        }
        val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2))
        val session = FrameInterpolationSession(fps, ledger, synthesizer,
            { task -> runCatching { executor.execute { runCatching(task).onFailure { failure.set(it) } } }.isSuccess },
            presenter, System::nanoTime,
            // 真实调度必须留出 CPU 交付时间；默认零提前量会把 Bitmap/线程唤醒抖动当成恢复失败。
            // 20 ms 的慢推理仍超过整个规划窗，保持超时回退测试的意义。
            timeline = FrameTimeline(fps, minLeadNs = 8_000_000L, maxLeadNs = 16_000_000L))

        val driverThread = HandlerThread("frame-verify-driver").apply { start() }
        val finished = CountDownLatch(1)
        val phases = ArrayList<String>()
        Handler(driverThread.looper).post {
            val start = System.nanoTime()
            var epochId = 0L
            var surfaceId = 1L
            var discontinuity = 0L
            var anchorNs = start
            var anchorMediaUs = 0L
            var nextFrame = 0L
            var paused = false
            var surfaceGone = false
            var closed = false
            fun begin(now: Long, mediaUs: Long) {
                epochId++; discontinuity++
                anchorNs = now; anchorMediaUs = mediaUs
                nextFrame = (mediaUs + 33_332L) / 33_333L
                val active = session.begin(FrameTimeline.Epoch(epochId, 1L, surfaceId, 1L, discontinuity), width, height)
                phases += "begin@${(now - start) / 1_000_000}ms active=$active"
            }
            begin(start, 0L)
            val choreographer = Choreographer.getInstance()
            choreographer.postFrameCallback(object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    try {
                        val now = System.nanoTime()
                        val elapsedMs = (now - start) / 1_000_000
                        when {
                            elapsedMs >= 7_000 && !closed -> {
                                closed = true
                                phases += "beforeClose ${session.snapshot().phase} ${session.snapshot().recovery.state}"
                                phases += "close outstanding=${session.close()}"
                                finished.countDown(); return
                            }
                            elapsedMs >= 5_800 && surfaceGone -> {
                                surfaceGone = false
                                imageReader = reader(); surface.set(imageReader.surface); surfaceId++
                                begin(now, 30_000_000L)
                            }
                            elapsedMs >= 4_500 && !surfaceGone && surfaceId == 1L -> {
                                surfaceGone = true
                                imageReader.close()
                                session.onSurfaceLost(now)
                                phases += "surfaceLost ${session.snapshot().recovery.state}"
                            }
                            elapsedMs in 3_000 until 3_300 && !paused -> {
                                paused = true
                                val media = anchorMediaUs + (now - anchorNs) / 1_000
                                phases += "pause ${session.updateClock(FrameTimeline.ClockSnapshot(media, now, 1.0, true, discontinuity, 1L))}"
                            }
                            elapsedMs >= 3_300 && paused -> { paused = false; begin(now, anchorMediaUs + 3_000_000L) }
                            elapsedMs >= 1_500 && epochId == 1L -> begin(now, 10_000_000L)
                        }
                        if (!paused && !surfaceGone && !closed) {
                            val media = anchorMediaUs + (now - anchorNs) / 1_000
                            session.updateClock(FrameTimeline.ClockSnapshot(media, now, 1.0, false, discontinuity, 1L))
                            while (nextFrame * 33_333L <= media + 66_666L) {
                                val shade = (nextFrame % 256).toByte()
                                session.capture(nextFrame * 33_333L) { buffer, format ->
                                    sourceRgb.fill(shade)
                                    buffer.put(sourceRgb, 0, format.bytes)
                                    true
                                }
                                nextFrame++
                            }
                            session.tick(System.nanoTime())
                        }
                        choreographer.postFrameCallback(this)
                    } catch (error: Throwable) { failure.set(error); finished.countDown() }
                }
            })
        }
        assertTrue(finished.await(20, TimeUnit.SECONDS))
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        val drained = CountDownLatch(1)
        presenterHandler.post { drained.countDown() }
        assertTrue(drained.await(5, TimeUnit.SECONDS))
        val snapshot = session.snapshot()
        val inferenceAvgUs = inferenceNs.get() / inferences.get().coerceAtLeast(1) / 1_000
        Log.i(TAG, "[$name] phases=$phases")
        Log.i(TAG, "[$name] inferences=${inferences.get()} avg=${inferenceAvgUs}us presented=${presented.get()} presentAvg=${presentNs.get() / presented.get().coerceAtLeast(1) / 1_000}us late=${late.get()} consumed=${consumed.get()} " +
            "surfaceLost=${surfaceLost.get()} recovery=${snapshot.recovery} ledger=${ledger.stats()}")
        driverThread.quitSafely(); presenterThread.quitSafely(); consumerThread.quitSafely()
        imageReader.close()

        failure.get()?.let { throw AssertionError("pipeline thread failed", it) }
        assertTrue("ledger must drain after teardown: ${ledger.stats()}", ledger.drained())
        assertEquals(1, closes.get())
        assertEquals(FrameInterpolationSession.Phase.CLOSED, snapshot.phase)
        return Scenario(presented.get(), late.get(), consumed.get(), phases, snapshot.recovery, inferenceAvgUs)
    }

    private companion object { const val TAG = "FrameVerify" }
}
