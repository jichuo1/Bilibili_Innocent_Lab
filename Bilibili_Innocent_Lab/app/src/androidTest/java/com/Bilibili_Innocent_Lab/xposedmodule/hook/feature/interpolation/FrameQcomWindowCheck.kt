package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/** Uses the host route, an actual running decoder and a composed window. No model is supplied. */
internal object FrameQcomWindowCheck {
    fun run(context: Context, clip: File, width: Int, height: Int, seconds: Int,
            play: (MediaCodec, MediaExtractor, Double) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Some ROMs reject instrumentation's activity launch from a background test UID.
        // Enter the target's own public launcher before requesting its private window.
        if (InstrumentationRegistry.getArguments().getString("foregroundLauncher") == "true") {
            val launcher = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)?.component)
            val command = "am start -W --activity-no-animation -n ${launcher.flattenToString()}"
            val response = ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
            check(response.contains("Status: ok")) { response }
        }
        val activity = instrumentation.startActivitySync(Intent(context, FrameRateValidationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("targetFps", 60)
            .putExtra("surfaceWidth", width).putExtra("surfaceHeight", height)) as FrameRateValidationActivity
        val log = Collections.synchronizedList(mutableListOf<String>())
        val current = AtomicBoolean(true)
        val changedSpeed = AtomicBoolean()
        val restored = CountDownLatch(1)
        val fallbackReady = CountDownLatch(1)
        val modelRequests = AtomicInteger()
        val verifyRife = InstrumentationRegistry.getArguments().getString("fallbackModel") == "true"
        val verifyAhb = InstrumentationRegistry.getArguments().getString("fallbackAhb") == "true"
        val model = File(context.filesDir, "rife-v4.6")
        if (verifyRife) check(FrameModelSpec.PARTS.all { FrameModelSpec.matches(it, File(model, it.fileName)) })
        val moduleApk = File(context.applicationInfo.sourceDir)
        val rife = FrameInterpolationEngineHolder(moduleApk, context.codeCacheDir, {
            modelRequests.incrementAndGet()
            if (verifyRife) model else null
        }, { event, detail -> log.add("$event $detail") },
            // Small real inference workload for fallback ownership testing, independent of 4K QCOM.
            capabilitySizes = if (verifyRife) listOf(128 to 72, 160 to 90, 320 to 180) else FrameInterpolationEngineHolder.DEFAULT_CAPABILITY_SIZES)
        val routes = FramePlaybackRoutes(rife)
        val host = object : FrameCaptureBridge.Host {
            override fun maxFps() = 60
            override fun context() = context
            override fun diagnostics() = true
            override fun playbackRateReader(surface: Surface): () -> Double = { if (changedSpeed.get()) 1.25 else 1.0 }
            override fun audio() = FrameMediaPolicy.Audio(output = FrameMediaPolicy.AudioOutput.PCM,
                offloaded = FrameMediaPolicy.Evidence.NO, tunneled = FrameMediaPolicy.Evidence.NO)
            override fun setAudioDelayUs(delayUs: Long) = Unit
            override fun log(event: String, detail: String) { log.add("$event $detail") }
        }
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var bridge: FramePlaybackBridge? = null
        val summary = JSONObject().put("width", width).put("height", height).put("requestedSeconds", seconds)
        try {
            assertTrue("display unavailable", activity.ready.await(5, TimeUnit.SECONDS))
            assertEquals(width, activity.view.holder.surfaceFrame.width())
            assertEquals(height, activity.view.holder.surfaceFrame.height())
            extractor.setDataSource(clip.path)
            extractor.selectTrack(0)
            val format = extractor.getTrackFormat(0)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder = codec
            summary.put("decoder", codec.name).put("format", format.toString())
            codec.configure(format, activity.outputSurface, null, 0)
            codec.start()
            play(codec, extractor, 0.7)
            val attempt = FramePlaybackRoutes.Attempt()
            fun attach() = routes.attach(codec, format, activity.outputSurface, null, host, attempt,
                isCurrent = current::get, attach = { capture ->
                    codec.setOutputSurface(capture)
                    if (capture === activity.outputSurface) restored.countDown()
                    true
                }, onWindowFailed = { backend ->
                    log.add("test.windowFailed $backend")
                    if (backend == "qcom") fallbackReady.countDown()
                })
            var result = attach()
            repeat(4) {
                if (result === FramePlaybackRoutes.Result.WaitForMedia) {
                    play(codec, extractor, 0.15)
                    result = attach()
                }
            }
            val attached = result as? FramePlaybackRoutes.Result.Attached
            if (attached != null) {
                bridge = attached.bridge
                attached.bridge.onPublished()
                summary.put("backend", attached.backend)
            }
            assertTrue("route=$result events=$log", attached?.backend == "qcom")
            summary.put("sourceStartNs", System.nanoTime())
            play(codec, extractor, seconds.toDouble())
            summary.put("sourceEndNs", System.nanoTime())
            assertEquals("RIFE should remain cold on a working QCOM route", FrameInterpolationEngineHolder.State.IDLE, rife.state)
            assertEquals(0, modelRequests.get())
            val submitted = synchronized(log) { log.toList() }
            val originals = submitted.count { it.startsWith("qcom.submit kind=original ") }
            val midpoints = submitted.count { it.startsWith("qcom.submit kind=midpoint ") }
            summary.put("submittedOriginals", originals).put("submittedMidpoints", midpoints)
            assertTrue("no original or true midpoint rendered: $originals/$midpoints ${submitted.takeLast(12)}",
                originals > 0 && midpoints > 0)
            assertTrue("unexpected fallback", submitted.none { it.startsWith("test.windowFailed ") })
            summary.put("rifeRequestsDuringQcom", modelRequests.get())
            if (InstrumentationRegistry.getArguments().getString("repeatDisplay") == "true") {
                val active = checkNotNull(bridge)
                val input = checkNotNull(active.decoderInputFor(activity.outputSurface))
                assertTrue(input !== activity.outputSurface)
                try {
                    assertTrue(active.decoderInputFor(input) == null)
                    codec.setOutputSurface(input)
                } finally {
                    active.finishDecoderInput()
                }
                play(codec, extractor, 0.5)
                val after = synchronized(log) { log.count { it.startsWith("qcom.submit kind=midpoint ") } }
                assertTrue("same display interrupted midpoint generation", after > midpoints)
                summary.put("sameDisplayMappedSwitch", "returned").put("additionalMidpoints", after - midpoints)
                return
            }
            // A runtime speed change invalidates the midpoint schedule. The live decoder must
            // have its original Surface back before the fallback callback permits another route.
            changedSpeed.set(true)
            play(codec, extractor, 0.5)
            assertTrue("original Surface was never restored", restored.await(2, TimeUnit.SECONDS))
            assertTrue("missing fallback callback", fallbackReady.await(2, TimeUnit.SECONDS))
            assertTrue("QCOM failure did not advance this attempt", attempt.rifeOnly)
            if (verifyAhb) changedSpeed.set(false)
            val fallback = attach()
            assertTrue("unexpected fallback result $fallback", fallback === FramePlaybackRoutes.Result.WaitForRife ||
                fallback === FramePlaybackRoutes.Result.Original)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (modelRequests.get() == 0 && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue("fallback never looked up the model", modelRequests.get() > 0)
            if (verifyRife) {
                val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
                while (rife.state == FrameInterpolationEngineHolder.State.PREPARING && System.nanoTime() < readyDeadline)
                    Thread.sleep(40)
                assertTrue("RIFE did not pass its own admission: ${log.takeLast(8)}", rife.ready() || verifyAhb && rife.ahbCandidateLoaded())
                if (verifyAhb) {
                    // The original attempt already retired QCOM. Resume 1x so the generic
                    // midpoint window may admit its own input path on this same decoder.
                    codec.flush()
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    play(codec, extractor, 0.5)
                }
                var resumed = attach()
                while (resumed === FramePlaybackRoutes.Result.WaitForRife && System.nanoTime() < readyDeadline) {
                    Thread.sleep(50)
                    if (routes.rifeCanAdvance(attempt)) resumed = attach()
                }
                val next = resumed as? FramePlaybackRoutes.Result.Attached
                if (next != null) bridge = next.bridge
                if (verifyAhb) {
                    assertTrue("no qualified fallback: $resumed", next?.backend in setOf("rife-ahb", "rife"))
                    if (next?.backend == "rife") {
                        val rejected = synchronized(log) { log.any { it.startsWith("route.fallback rife-rgb ") } }
                        assertTrue("direct path skipped generic AHB admission", rejected)
                        if (InstrumentationRegistry.getArguments().getString("requireRifeAhb") == "true")
                            fail("generic AHB did not qualify")
                    }
                } else assertEquals("rife", next?.backend)
                checkNotNull(next).bridge.onPublished()
                summary.put("fallbackBackend", next.backend)
                if (next.backend == "rife-ahb") {
                    play(codec, extractor, 4.0)
                    val fallbackLog = synchronized(log) { log.toList() }
                    val count = fallbackLog.count { it.startsWith("rife.ahb.submit kind=midpoint ") }
                    summary.put("rifeAhbMidpoints", count)
                    assertTrue("no real RIFE AHB midpoint: ${fallbackLog.takeLast(12)}", count > 0)
                } else if (verifyAhb) {
                    play(codec, extractor, 4.0)
                    val snapshot = (next.bridge as FrameCaptureBridge).diagnostics().first
                    summary.put("rifeRgbPresented", snapshot.presented).put("rifeRgbMidpoints", snapshot.interpolated)
                }
            }
            play(codec, extractor, 0.25)
            summary.put("decoderRestored", true)
        } finally {
            current.set(false)
            bridge?.close()
            decoder?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
            val lines = synchronized(log) { log.toList() }
            summary.put("rifeState", rife.state.name).put("modelRequests", modelRequests.get())
            context.filesDir.resolve("qcom-window-events.txt").writeText(lines.joinToString("\n"))
            instrumentation.sendStatus(0, Bundle().apply { putString("qcomWindow", summary.toString()) })
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
