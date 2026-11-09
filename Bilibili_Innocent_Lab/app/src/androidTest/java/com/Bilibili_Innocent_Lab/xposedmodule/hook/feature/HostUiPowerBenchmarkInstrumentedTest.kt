package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.PixelCopy
import android.view.Window
import android.view.animation.LinearInterpolator
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceFailure
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in same-APK crossover; A = eager invalidation, B = retained GPU content. */
@SdkSuppress(minSdkVersion = 32)
@RunWith(AndroidJUnit4::class)
class HostUiPowerBenchmarkInstrumentedTest {
    @Test fun fullEffectsScroll() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in power benchmark", args.getString("innocentLab.runPowerBenchmark") == "true")
        val seconds = args.getString("innocentLab.seconds")?.toInt() ?: 20
        val order = args.getString("innocentLab.order") ?: "AB"
        val cardRenderOnly = args.getString("innocentLab.cardRenderOnly") == "true"
        require(seconds in 10..120 && order.isNotEmpty() && order.all { it == 'A' || it == 'B' })
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "host-ui-power").apply { mkdirs() }
        val phases = JSONArray()
        val campaign = JSONObject().put("order", order).put("seconds", seconds).put("phases", phases).put("complete", false)
            .put("comparison", if (cardRenderOnly) "card proxy draw and direct-child mapping; A=bf1d4f5c" else "retained GPU content")
        fun persist() = File(output, "campaign.json").writeText(campaign.toString(2))
        persist()
        val thread = HandlerThread("HostUiPowerMetrics").apply { start() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for ((index, variant) in order.withIndex()) {
                    lateinit var fixture: HostUiPowerFixture
                    scenario.onActivity { fixture = HostUiPowerFixture(it,
                        reuseGpuFrames = cardRenderOnly || variant == 'B',
                        optimizeCardRendering = !cardRenderOnly || variant == 'B') }
                    val gpu = Collections.synchronizedMap(LinkedHashMap<Long, Long>())
                    val draw = Collections.synchronizedMap(LinkedHashMap<Long, Long>())
                    val total = Collections.synchronizedMap(LinkedHashMap<Long, Long>())
                    val deadlines = Collections.synchronizedMap(LinkedHashMap<Long, Long>())
                    val droppedReports = AtomicInteger()
                    val measuring = AtomicBoolean()
                    val listener = Window.OnFrameMetricsAvailableListener { _, frame, dropped ->
                        if (measuring.get() && frame.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                            droppedReports.addAndGet(dropped)
                            val vsync = frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
                            gpu[vsync] = frame.getMetric(FrameMetrics.GPU_DURATION)
                            draw[vsync] = frame.getMetric(FrameMetrics.DRAW_DURATION)
                            total[vsync] = frame.getMetric(FrameMetrics.TOTAL_DURATION)
                            deadlines[vsync] = frame.getMetric(FrameMetrics.DEADLINE)
                        }
                    }
                    val phaseOutput = File(output, "phase-$index-$variant").apply { mkdirs() }
                    try {
                        animate(fixture, 8)
                        capture(fixture, File(phaseOutput, "offset-0.png"), 0)
                        instrumentation.runOnMainSync {
                            assertFullEffects(fixture)
                            fixture.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                        }
                        val power = instrumentation.targetContext.getSystemService(PowerManager::class.java)
                        assertTrue("Thermal throttling invalidates comparison", power.currentThermalStatus < PowerManager.THERMAL_STATUS_MODERATE)
                        var before = JSONObject()
                        instrumentation.runOnMainSync { before = work(fixture) }
                        val start = SystemClock.elapsedRealtimeNanos()
                        val monotonicStart = System.nanoTime()
                        measuring.set(true)
                        val cpu = animate(fixture, seconds)
                        measuring.set(false)
                        val end = SystemClock.elapsedRealtimeNanos()
                        val monotonicEnd = System.nanoTime()
                        SystemClock.sleep(150)
                        val gpuValues = synchronized(gpu) { gpu.values.sorted() }
                        val drawValues = synchronized(draw) { draw.values.sorted() }
                        val totalValues = synchronized(total) { total.values.sorted() }
                        // Some custom ROMs report multi-second budgets; retain the raw
                        // median for diagnosis, but never call those frames jank-free.
                        val deadlineValues = synchronized(deadlines) { deadlines.values.filter { it > 0 }.sorted() }
                        val usableDeadlines = deadlineValues.count { it <= 100_000_000L } == gpuValues.size
                        val missed = synchronized(total) { total.count { (vsync, duration) ->
                            val deadline = deadlines[vsync] ?: -1L
                            deadline > 0 && duration >= deadline
                        } }
                        assertTrue("Less than 95% of requested 90Hz frames reported", gpuValues.size > seconds * 90 * .95)
                        val result = JSONObject().put("index", index).put("variant", variant.toString())
                            .put("startElapsedNs", start).put("endElapsedNs", end)
                            .put("startMonotonicNs", monotonicStart).put("endMonotonicNs", monotonicEnd)
                            .put("frames", gpuValues.size).put("elapsedMs", (end - start) / 1e6)
                            .put("brightness", .15).put("displayHz", fixture.top.display.refreshRate)
                            .put("thermalStatus", power.currentThermalStatus).put("mainCpuMs", cpu / 1e6)
                            .put("gpuMeanMs", gpuValues.average() / 1e6).put("gpuP95Ms", gpuValues[(gpuValues.size * .95).toInt()] / 1e6)
                            .put("drawMeanMs", drawValues.average() / 1e6).put("drawP95Ms", drawValues[(drawValues.size * .95).toInt()] / 1e6)
                            .put("totalP95Ms", totalValues[(totalValues.size * .95).toInt()] / 1e6)
                            .put("deadlineMetricUsable", usableDeadlines)
                            .put("deadlineP50Ms", deadlineValues.getOrNull(deadlineValues.size / 2)?.div(1e6) ?: JSONObject.NULL)
                            .put("missedDeadlineFrames", if (usableDeadlines) missed else JSONObject.NULL)
                            .put("droppedMetricReports", droppedReports.get())
                            .put("before", before)
                        instrumentation.runOnMainSync { result.put("after", work(fixture)); assertFullEffects(fixture) }
                        phases.put(result); persist()
                        capture(fixture, File(phaseOutput, "offset-137.png"), 137)
                        capture(fixture, File(phaseOutput, "offset-559.png"), 559)
                    } finally {
                        instrumentation.runOnMainSync { fixture.window.removeOnFrameMetricsAvailableListener(listener); fixture.close() }
                    }
                }
            }
            campaign.put("complete", true); persist()
        } finally { thread.quitSafely() }
    }

    private fun assertFullEffects(fixture: HostUiPowerFixture) {
        val acrylic = requireNotNull(fixture.cards.diagnostics(fixture.cardViews.first()))
        assertTrue("Acrylic must stay visible", acrylic.acrylicVisible)
        assertEquals(LumenSurfaceBackend.GPU, acrylic.backdropBackend)
        assertEquals(LumenSurfaceFailure.NONE, fixture.cards.samplingDiagnostics().lastFailure)
        for ((scope, view) in listOf(fixture.topScope to fixture.top, fixture.bottomScope to fixture.bottom)) {
            val prism = requireNotNull(scope.prismDiagnostics(view))
            assertTrue("Prism must stay visible", prism.glassVisible)
            assertEquals(LumenSurfaceBackend.GPU, prism.backdropBackend)
            assertEquals(LumenSurfaceFailure.NONE, requireNotNull(scope.diagnostics()).lastFailure)
        }
    }

    private fun work(fixture: HostUiPowerFixture): JSONObject {
        val result = JSONObject().put("materialRecordings", fixture.cards.materialRecordings)
        for ((name, perf) in listOf("cards" to fixture.cards.samplingPerformance(),
            "top" to requireNotNull(fixture.topScope.samplingPerformance()),
            "bottom" to requireNotNull(fixture.bottomScope.samplingPerformance()))) {
            result.put(name, JSONObject().put("contentRecordings", perf.contentRecordings)
                .put("proxyRecordings", perf.proxyRecordings).put("effectChainBuilds", perf.effectChainBuilds))
        }
        return result
    }

    private fun animate(fixture: HostUiPowerFixture, seconds: Int): Long {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val done = CountDownLatch(1)
        var start = 0L
        var end = 0L
        var animator: ValueAnimator? = null
        instrumentation.runOnMainSync {
            start = Debug.threadCpuTimeNanos()
            animator = ValueAnimator.ofInt(0, 560).apply {
                duration = 1000; repeatMode = ValueAnimator.REVERSE; repeatCount = seconds - 1
                interpolator = LinearInterpolator()
                addUpdateListener { fixture.offset(it.animatedValue as Int) }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { end = Debug.threadCpuTimeNanos(); done.countDown() }
                })
                start()
            }
        }
        try { assertTrue("Animation timed out", done.await(seconds + 15L, TimeUnit.SECONDS)) }
        finally { instrumentation.runOnMainSync { animator?.cancel() } }
        return end - start
    }

    private fun capture(fixture: HostUiPowerFixture, file: File, offset: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { fixture.offset(offset) }
        SystemClock.sleep(250)
        val bitmap = Bitmap.createBitmap(fixture.window.decorView.width, fixture.window.decorView.height, Bitmap.Config.ARGB_8888)
        try {
            val done = CountDownLatch(1)
            var status = -1
            PixelCopy.request(fixture.window, bitmap, { status = it; done.countDown() }, Handler(fixture.window.decorView.handler.looper))
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, status)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
