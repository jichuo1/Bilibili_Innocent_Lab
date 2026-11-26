package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import io.github.jichuo1.meguviz.media.GlesMotionWarp
import io.github.jichuo1.meguviz.media.FrameGenerationSession
import io.github.jichuo1.meguviz.media.WindowBackend
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToLong

/** Optional for a WindowBackend wrapper that owns a generation-aware Media session. */
internal interface FrameWindowEpoch { fun invalidate() }

/** One OES decoder producer, one EGL window owner, and at most one GPU midpoint in flight. */
internal class FrameQcomBridge private constructor(
    codec: MediaCodec,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val sourceFps: Float,
    private val targetFps: Int,
    private val source: FrameHostMediaSignals.Values,
    private val mode: FrameStatus.Backend,
    private val bypassFrameBudget: Boolean,
    private val initialStandard: FrameMediaPolicy.Standard,
    private var rifeFactory: ((Int, Int) -> WindowBackend?)?,
    private var playback: Playback?
) : FramePlaybackBridge {
    private class Playback(val display: Surface, val host: FrameCaptureBridge.Host, val isCurrent: () -> Boolean,
                           val attach: (Surface) -> Boolean, val onBackendFailed: (String, Boolean) -> Unit) {
        val rate = host.playbackRateReader(display)
    }
    private val display get() = checkNotNull(playback).display
    private val host get() = checkNotNull(playback).host
    private val observer = WeakReference(host)
    private fun isCurrent() = playback?.isCurrent?.invoke() == true
    private val logName = if (mode == FrameStatus.Backend.QCOM) "qcom" else "rife.ahb"
    private class Original(val frame: Any, val ptsNs: Long, val dueNs: Long, var shown: Boolean = false)
    private val decoder = WeakReference(codec)
    private val thread = HandlerThread(if (mode == FrameStatus.Backend.QCOM) "BIL-QcomFrames" else "BIL-RifeAhbFrames").apply { start() }
    private val handler = Handler(thread.looper)
    private val egl = FrameQcomEgl()
    private val output = FrameQcomOutputBorrow<Surface>()
    private val closed = AtomicBoolean()
    private val transform = FloatArray(16)
    private val periodNs = (2_000_000_000.0 / targetFps).roundToLong()
    private var preparedWidth = sourceWidth
    private var preparedHeight = sourceHeight
    // The right endpoint is required before estimation. Leave one full source period to finish it.
    private val delayNs = periodNs + periodNs / 2
    private var power: FramePowerAdvisor? = null
    private var warp: WindowBackend? = null
    private var preflightRetirement: AutoCloseable? = null
    @Volatile var awaitingRetirement = false
        private set
    private var ahb = true
    private var previous: Original? = null
    /** The previous source remains ours while the async PBOs become readable. */
    private var waitingLeft: Original? = null
    private var blockedOriginal: Original? = null
    private var captureRetryUntil = 0L
    private var nextId = 0L
    private var anchorPts = Long.MIN_VALUE
    private var anchorNs = 0L
    private var job = 0L
    private var midDue = 0L
    private var midPts = 0L
    private var jobObsolete = false
    private var failures = 0
    private val cadence = FrameQcomCadence(periodNs)
    private var nextPolicyCheck = 0L
    private var lastReport = 0L
    private var originals = 0L
    private var midpoints = 0L
    private var missed = 0L
    private var lastWorkNs = 0L
    private var connectedToCodec = false
    private var inputArrived = false
    private var stopping = false
    private var fallback = false
    private var failureReason = ""
    private var restored = false
    private var allowNextBackend = false
    private var afterRetirement: (() -> Unit)? = null
    private var stopAttempts = 0
    private var released = false
    @Volatile private var parked = false

    /** Only preflight runs before takeover. A late preflight can never attach to a superseded codec. */
    private fun start(): Boolean {
        val finished = CountDownLatch(1)
        var prepared = false
        handler.post {
            try {
                egl.prepare(sourceWidth, sourceHeight)
                // Querying ANativeWindow does not connect a producer. Keep the codec presenting
                // originals while any expensive RIFE configuration is measured at the actual extent.
                FrameWindowGeometry.size(display)?.let { (width, height) ->
                    check(validExtent(width, height))
                    preparedWidth = width
                    preparedHeight = height
                }
                warp = openBackend(preparedWidth, preparedHeight)
                egl.texture!!.setOnFrameAvailableListener({
                    if (egl.connected) guarded { capture() } else inputArrived = true
                }, handler)
                prepared = true
            } catch (failure: Throwable) {
                host.log("$logName.unavailable", failure.javaClass.simpleName)
            } finally {
                finished.countDown()
            }
        }
        val timeoutNs = TimeUnit.SECONDS.toNanos(if (mode == FrameStatus.Backend.QCOM) 6L else 15L)
        val started = System.nanoTime()
        val ready = try {
            while (!finished.await(100, TimeUnit.MILLISECONDS) && isCurrent() && System.nanoTime() - started < timeoutNs) {
                // Cold pipeline construction runs on its EGL owner. Release this attachment
                // worker promptly if the codec is reconfigured while the GPU is preparing.
            }
            finished.count == 0L
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (mode == FrameStatus.Backend.RIFE && isCurrent() && (!ready || preflightRetirement != null)) {
            // No decoder takeover occurred. Occupy this attempt until the original
            // EGL owner confirms cleanup, including a preflight that is still running.
            awaitingRetirement = true
            return true
        }
        if (!ready || !prepared || !isCurrent()) {
            close()
            return false
        }
        if (!runCatching { checkNotNull(playback).attach(checkNotNull(egl.input)) }.getOrDefault(false)) {
            if (mode == FrameStatus.Backend.RIFE && isCurrent()) {
                awaitingRetirement = true
                return true
            }
            close()
            return false
        }
        output.connect(display, checkNotNull(egl.input))
        connectedToCodec = true
        return true
    }

    override fun onPublished() {
        // Only claim the real display once the installer can redirect an identical
        // host setOutputSurface call to our decoder input for this exact generation.
        handler.post {
            guarded {
                if (awaitingRetirement) { fail("preflight_retirement"); return@guarded }
                if (egl.connected) return@guarded
                egl.connect(display, targetFps)
                if (egl.width != preparedWidth || egl.height != preparedHeight) {
                    if (mode == FrameStatus.Backend.RIFE) { fail("window_size_changed"); return@guarded }
                    // The public window backend draws at its configured extent. Sample OES directly
                    // into that extent so a smaller playback surface gets the complete image.
                    check(validExtent(egl.width, egl.height))
                    warp?.close()
                    warp = null
                    warp = openBackend(egl.width, egl.height)
                }
                power?.close()
                power = FramePowerAdvisor(host.context(), periodNs / 2)
                rifeFactory = null
                host.log("$logName.selected", "source=${sourceWidth}x$sourceHeight output=${egl.width}x${egl.height} sourceFps=$sourceFps target=$targetFps input=${if (ahb) "AHB" else "PBO"}")
                // A decoder can deliver its only frame while we are switching the window producer.
                if (inputArrived) { inputArrived = false; capture() }
            }
        }
    }

    private fun openBackend(width: Int, height: Int): WindowBackend {
        if (mode == FrameStatus.Backend.RIFE) {
            try { return checkNotNull(rifeFactory?.invoke(width, height)) { "RIFE AHB unavailable" } }
            catch (pending: FrameWindowRetirementPending) {
                preflightRetirement = pending.retirement
                throw pending
            }
        }
        if (ahb) try {
            return GlesMotionWarp(width, height, false, true)
        } catch (failure: IllegalStateException) {
            // 1.2.2 rethrows these capability rejections after its constructor has
            // cleaned up. A different exception can denote unproven EGL/Vulkan retirement.
            if (failure.message != "Vulkan QCOM block matcher unavailable" &&
                failure.message != "compact luma AHB preflight failed") throw failure
            ahb = false
        }
        return GlesMotionWarp(width, height, true, false)
    }

    override fun onFlush() {
        handler.post { if (parked) resumeRetirement() else guarded { cadence.reset(); resetTimeline() } }
    }

    override fun decoderInputFor(display: Surface): Surface? {
        if (closed.get() || !isCurrent()) return null
        return output.borrow(display)
    }

    override fun finishDecoderInput() {
        if (output.finish()) handler.post { if (parked) resumeRetirement() else if (stopping) stop() }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            output.revoke()
            handler.post { if (parked) resumeRetirement() else stop() }
        }
    }

    private inline fun guarded(action: () -> Unit) {
        if (stopping || fallback) return
        if (closed.get() || !isCurrent()) { stop(); return }
        try {
            action()
        } catch (failure: Throwable) {
            fail(failure.javaClass.simpleName)
        }
    }

    private val captureRetry = Runnable { guarded { if (captureRetryUntil != 0L) capture(retry = true) } }
    private fun capture(retry: Boolean = false) {
        val backend = checkNotNull(warp)
        val tex = checkNotNull(egl.texture)
        if (!retry) {
            handler.removeCallbacks(captureRetry)
            if (captureRetryUntil != 0L) {
                missed++
                captureRetryUntil = 0L
                if (!bypassFrameBudget && ++failures >= MAX_MISSES) { fail("capture_pressure"); return }
            }
            tex.updateTexImage()
        }
        val pts = tex.timestamp
        val now = System.nanoTime()
        if (abs(checkNotNull(playback).rate() - 1.0) > 0.01) { fail("speed"); return }
        if (now >= nextPolicyCheck) {
            nextPolicyCheck = now + 1_000_000_000L
            when (val check = checkRuntimeEligibility()) {
                RuntimeEligibility.Eligible -> Unit
                is RuntimeEligibility.MediaRejected -> { fail("media:${check.reason}", FrameStatus.Reason.MEDIA); return }
                RuntimeEligibility.ColorChanged -> { fail("color_changed"); return }
                RuntimeEligibility.SourceSizeChanged -> { fail("source_size"); return }
                RuntimeEligibility.DecoderUnavailable -> { fail("decoder_gone"); return }
            }
            if (!egl.extentUnchanged()) { fail("window_size"); return }
            if (FrameThermalControl.pauseHeadroom(host.thermalControlEnabled()) { power?.thermalHeadroom(now) } != null) {
                fail("thermal", FrameStatus.Reason.THERMAL); return
            }
        }
        if (previous?.ptsNs == pts) return
        poll()
        if (stopping || fallback) return
        val prev = previous
        val gap = if (prev == null) 0 else pts - prev.ptsNs
        val adjacent = prev != null && (if (retry) cadence.matches(gap) else cadence.observe(gap))
        if (cadence.unsuitable) { fail("cadence", FrameStatus.Reason.RATE); return }
        if (prev != null && (gap <= 0 || gap > periodNs * 4 || abs(now - (anchorNs + pts - anchorPts)) > 180_000_000L)) {
            resetTimeline()
        }
        if (anchorPts == Long.MIN_VALUE) {
            anchorPts = pts
            anchorNs = now
            host.setAudioDelayUs(delayNs / 1_000L)
        }
        val left = previous
        if (left != null && !left.shown) {
            // A newer input arrived while the prior midpoint was running. Preserve the
            // original, and retire that job without allowing a late frame out of order.
            if (job != 0L && !jobObsolete) {
                jobObsolete = true
                backend.cancel(job)
            }
            if (waitingLeft != null) { releaseWaiting(); missed++; if (!bypassFrameBudget) failures++ }
            if (!showOriginal(left)) { fail("original_backpressure"); return }
        }
        tex.getTransformMatrix(transform)
        val captured = backend.capture(egl.oes, transform, ++nextId, pts / 1000L)
        if (captured == null) {
            if (mode == FrameStatus.Backend.RIFE && temporary(backend.lastCaptureStatus())) {
                if (!retry) captureRetryUntil = now + periodNs / 2
                if (System.nanoTime() < captureRetryUntil) {
                    handler.postDelayed(captureRetry, 2)
                    return
                }
            }
            fail("capture_pressure")
            return
        }
        captureRetryUntil = 0L
        val right = Original(captured, pts, anchorNs + (pts - anchorPts) + delayNs)
        previous = right
        try {
            if (left == null) { showOriginal(right); return }
            val due = left.dueNs + gap / 2
            if (adjacent && job == 0L && due > System.nanoTime()) {
                midDue = due
                midPts = left.ptsNs + gap / 2
                if (!ahb && !backend.inputsReady(left.frame, right.frame)) {
                    waitingLeft = left
                    postPoll()
                    return
                }
                val accepted = backend.submit(left.frame, right.frame, due)
                if (accepted > 0L) {
                    job = accepted
                    jobObsolete = false
                    postPoll()
                    return
                }
            }
            missed++
            showOriginal(right)
            if (!bypassFrameBudget && ++failures >= MAX_MISSES) fail("midpoint_pressure")
        } finally {
            if (left != null && waitingLeft !== left) backend.release(left.frame)
        }
    }

    private val pollTask = Runnable { guarded { poll(); if (!fallback && !stopping && (job != 0L || waitingLeft != null)) postPoll() } }
    private fun postPoll() {
        handler.removeCallbacks(pollTask)
        handler.postDelayed(pollTask, ((midDue - System.nanoTime()) / 4_000_000L).coerceIn(1L, 4L))
    }

    private fun poll() {
        val backend = checkNotNull(warp)
        waitingLeft?.let { left ->
            val right = checkNotNull(previous)
            // poll() advances the compact luma readbacks even when no midpoint job exists.
            check(backend.poll() == null)
            if (System.nanoTime() >= midDue) {
                releaseWaiting()
                missed++
                showOriginal(right)
                if (!bypassFrameBudget && ++failures >= MAX_MISSES) fail("readback_deadline")
            } else if (backend.inputsReady(left.frame, right.frame)) {
                val accepted = backend.submit(left.frame, right.frame, midDue)
                if (accepted > 0L) {
                    job = accepted
                    jobObsolete = false
                    releaseWaiting()
                }
            }
            return
        }
        if (job == 0L) {
            // Progress an abandoned readback or Media's import/release fences before taking the next source slot.
            if (!ahb || mode == FrameStatus.Backend.RIFE) {
                val stray = backend.poll()
                if (stray != null) {
                    stray.frame?.let(backend::release)
                    error("unexpected midpoint receipt")
                }
            }
            return
        }
        val receipt = backend.poll()
        if (receipt == null) {
            val now = System.nanoTime()
            val workerTimeout = if (bypassFrameBudget) maxOf(periodNs * 12L, WORKER_HANG_SAFETY_NS) else periodNs * 3L
            if (now > midDue + workerTimeout) { fail("worker_timeout"); return }
            if (now >= midDue) {
                previous?.let { if (!it.shown) showOriginal(it) }
                if (bypassFrameBudget && now > midDue + periodNs * 3L && !jobObsolete) {
                    jobObsolete = true
                    backend.cancel(job)
                }
            }
            return
        }
        check(receipt.jobId == job)
        job = 0L
        handler.removeCallbacks(pollTask)
        lastWorkNs = receipt.workDurationNs
        val right = previous
        val output = receipt.frame
        val usable = !jobObsolete && right != null && !right.shown && output != null &&
            receipt.status == 0 && receipt.frameKind == 2 && System.nanoTime() < midDue
        try {
            if (usable && draw(checkNotNull(output), midDue, midPts, midpoint = true)) {
                failures = 0
            } else if (!jobObsolete) {
                missed++
                if (!bypassFrameBudget) failures++
            }
        } finally {
            if (output != null) backend.release(output)
        }
        if (right != null && !right.shown) showOriginal(right)
        if (receipt.status != 0 && receipt.status != -9 && !(jobObsolete && receipt.status == -13) ||
            (!bypassFrameBudget && failures >= MAX_MISSES)) fail("worker:${receipt.status}")
    }

    private val originalRetry = Runnable { guarded {
        val frame = blockedOriginal
        if (frame != null && frame === previous && !frame.shown) showOriginal(frame)
    } }
    private fun showOriginal(frame: Original): Boolean {
        if (!draw(frame.frame, frame.dueNs, frame.ptsNs, midpoint = false)) {
            val deadline = if (bypassFrameBudget) maxOf(periodNs * 8L, ORIGINAL_HANG_SAFETY_NS) else periodNs
            if (System.nanoTime() >= frame.dueNs + deadline) fail("original_deadline")
            else {
                blockedOriginal = frame
                handler.removeCallbacks(originalRetry)
                handler.postDelayed(originalRetry, 2)
            }
            return false
        }
        blockedOriginal = null
        handler.removeCallbacks(originalRetry)
        frame.shown = true
        return true
    }

    private fun draw(frame: Any, dueNs: Long, ptsNs: Long, midpoint: Boolean): Boolean {
        try {
            checkNotNull(warp).draw(frame, egl.width, egl.height)
        } catch (pressure: WindowBackend.Backpressure) {
            if (mode == FrameStatus.Backend.RIFE && (temporary(pressure.status) || midpoint && pressure.status == -9)) return false
            throw pressure
        }
        egl.swap(dueNs)
        val submitted = System.nanoTime()
        if (midpoint) {
            if (++midpoints == 1L) host.onStatus(FrameStatus.Active(sourceFps, targetFps, mode))
        } else originals++
        host.onFrameSubmitted(dueNs, submitted)
        if (host.diagnostics()) host.log("$logName.submit", "kind=${if (midpoint) "midpoint" else "original"} dueNs=$dueNs swapNs=$submitted ptsUs=${ptsNs / 1000L}")
        if (submitted - lastReport > 5_000_000_000L) { lastReport = submitted; report() }
        return true
    }

    private fun report() = host.log("$logName.window", "originals=$originals midpoints=$midpoints missed=$missed workNs=$lastWorkNs")

    private fun checkRuntimeEligibility(): RuntimeEligibility {
        val codec = decoder.get() ?: return RuntimeEligibility.DecoderUnavailable
        val format = runCatching { codec.outputFormat }.getOrNull() ?: return RuntimeEligibility.DecoderUnavailable
        val input = runCatching { codec.inputFormat }.getOrNull()?.let(FrameCaptureBridge::values)
        val output = FrameCaptureBridge.values(format)
        // MediaCodec can expose coded alignment in width/height and the actual picture in crop keys.
        fun extent(size: String, low: String, high: String, fallback: Int): Int =
            if (format.containsKey(low) && format.containsKey(high)) format.getInteger(high) - format.getInteger(low) + 1
            else if (format.containsKey(size)) format.getInteger(size) else fallback
        return evaluateRuntimeEligibility(
            mode = mode,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            configuredSource = source,
            input = input,
            output = output,
            audio = host.audio(),
            outputWidth = extent(MediaFormat.KEY_WIDTH, "crop-left", "crop-right", sourceWidth),
            outputHeight = extent(MediaFormat.KEY_HEIGHT, "crop-top", "crop-bottom", sourceHeight),
            initialStandard = initialStandard
        )
    }

    private fun resetTimeline() {
        handler.removeCallbacks(originalRetry)
        handler.removeCallbacks(captureRetry)
        blockedOriginal = null
        captureRetryUntil = 0L
        when (val backend = warp) {
            is FrameGenerationSession -> backend.invalidate()
            is FrameWindowEpoch -> backend.invalidate()
            else -> if (job != 0L && !jobObsolete) backend?.cancel(job)
        }
        jobObsolete = true
        releaseWaiting()
        previous?.let { warp!!.release(it.frame) }
        previous = null
        anchorPts = Long.MIN_VALUE
        failures = 0
        nextPolicyCheck = 0L
    }

    private fun releaseWaiting() {
        waitingLeft?.let { warp!!.release(it.frame) }
        waitingLeft = null
    }

    private fun fail(reason: String, nativeReason: FrameStatus.Reason? = null) {
        if (fallback) return
        output.revoke()
        host.log("$logName.fallback", reason)
        fallback = true
        failureReason = reason
        allowNextBackend = nativeReason == null
        if (nativeReason != null) host.onStatus(FrameStatus.Inactive(nativeReason))
        // Let the active capture/draw callback return its local frame references first.
        handler.post(::stop)
    }

    private fun stop() {
        if (released || parked) return
        if (!stopping) {
            stopping = true
            handler.removeCallbacks(pollTask)
            handler.removeCallbacks(originalRetry)
            handler.removeCallbacks(captureRetry)
            blockedOriginal = null
            egl.texture?.setOnFrameAvailableListener(null)
            if (connectedToCodec && isCurrent()) host.setAudioDelayUs(0)
            report()
        }
        // Disconnect EGL first. A failed retirement keeps this context and its GPU
        // resources on the original owner until the library confirms release.
        try {
            // The platform call from a repeated host setOutputSurface may still be executing.
            // No generation or codec operation runs under the borrow lock.
            check(output.revoke()) { "host_output_in_flight" }
            egl.disconnect()
            playback?.let { route ->
                if (connectedToCodec && decoder.get() != null && route.isCurrent() && !restored) {
                    check(route.attach(route.display)) { "decoder_restore" }
                    restored = true
                }
                if (allowNextBackend && !closed.get() && route.isCurrent()) {
                    val current = route.isCurrent
                    val next = route.onBackendFailed
                    val reason = failureReason
                    val hadMidpoint = midpoints > 0L
                    afterRetirement = { if (!closed.get() && current()) next(reason, hadMidpoint) }
                }
                // A late GPU fence needs its EGL owner, but no longer needs the player's
                // surface, rate reader, activity callbacks or audio/thermal context.
                playback = null
                rifeFactory = null
                power?.close()
                power = null
            }
            preflightRetirement?.close()
            preflightRetirement = null
            if (!ahb && job != 0L) {
                // Media 1.2.2's PBO close waits for its Future without a deadline.
                // Poll first on the owner, using the same bounded retirement retries as AHB.
                val backend = checkNotNull(warp)
                val receipt = checkNotNull(backend.poll()) { "PBO job still retiring" }
                check(receipt.jobId == job)
                job = 0
                receipt.frame?.let(backend::release)
            }
            releaseWaiting()
            previous?.let { warp!!.release(it.frame) }
            previous = null
            warp?.close()
            warp = null
            egl.close()
            released = true
            synchronized(unresolvedOwners) { unresolvedOwners.remove(this) }
            thread.quitSafely()
            val next = afterRetirement
            afterRetirement = null
            next?.invoke()
        } catch (failure: Throwable) {
            if (++stopAttempts <= MAX_RETIRE_RETRIES) {
                handler.postDelayed(::stop, 250L * stopAttempts)
            } else {
                // Unproven fences or a still-attached codec must keep their original owner/context.
                // Suspend new QCOM admissions; a subsequent codec lifecycle event can resume cleanup.
                parked = true
                synchronized(unresolvedOwners) { unresolvedOwners.add(this) }
                observer.get()?.log("$logName.retirement.parked", "restored=$restored cause=${failure.javaClass.simpleName}")
            }
        }
    }

    private fun resumeRetirement() {
        parked = false
        stopAttempts = 0
        stop()
    }

    internal sealed interface RuntimeEligibility {
        object Eligible : RuntimeEligibility
        data class MediaRejected(val reason: FrameMediaPolicy.Reason) : RuntimeEligibility
        object ColorChanged : RuntimeEligibility
        object SourceSizeChanged : RuntimeEligibility
        object DecoderUnavailable : RuntimeEligibility
    }

    companion object {
        private const val MAX_MISSES = 12
        private const val MAX_RETIRE_RETRIES = 3
        private const val WORKER_HANG_SAFETY_NS = 500_000_000L
        private const val ORIGINAL_HANG_SAFETY_NS = 250_000_000L
        private fun temporary(status: Int) = status == -7 || status == -8 || status == -12
        private val unresolvedOwners = mutableSetOf<FrameQcomBridge>()
        private var nextRetirementWakeNs = 0L

        internal fun supportsQcomColorStandard(colorStandard: Int?): Boolean =
            colorStandard == MediaFormat.COLOR_STANDARD_BT709 ||
                colorStandard == MediaFormat.COLOR_STANDARD_BT601_PAL ||
                colorStandard == MediaFormat.COLOR_STANDARD_BT601_NTSC

        internal fun evaluateRuntimeEligibility(
            mode: FrameStatus.Backend,
            sourceWidth: Int,
            sourceHeight: Int,
            configuredSource: FrameHostMediaSignals.Values,
            input: FrameHostMediaSignals.Values?,
            output: FrameHostMediaSignals.Values,
            audio: FrameMediaPolicy.Audio,
            outputWidth: Int,
            outputHeight: Int,
            initialStandard: FrameMediaPolicy.Standard
        ): RuntimeEligibility {
            val currentSource = input?.let(configuredSource::fillFrom) ?: configuredSource
            val actual = output.fillFrom(currentSource)
            val signal = FrameHostMediaSignals.signal(currentSource.fillFrom(actual))
            val decoded = FrameHostMediaSignals.signal(actual, 8)
            val decision = FrameMediaPolicy.evaluate(FrameMediaPolicy.Snapshot(
                sourceWidth, sourceHeight, signal, decoded,
                FrameMediaPolicy.Capture.VERIFIED_SDR_RGB8, FrameMediaPolicy.Evidence.NO,
                FrameMediaPolicy.Evidence.NO, FrameMediaPolicy.Evidence.NO, audio
            ))
            if (!decision.eligible) return RuntimeEligibility.MediaRejected(decision.reason)
            if (mode == FrameStatus.Backend.QCOM) {
                if (FrameHostMediaSignals.converterStandard(signal.standard) == null ||
                    FrameHostMediaSignals.converterStandard(decoded.standard) == null) {
                    return RuntimeEligibility.MediaRejected(FrameMediaPolicy.Reason.UNSUPPORTED_COLOR)
                }
            } else if (decoded.standard != signal.standard || decoded.standard != initialStandard) {
                return RuntimeEligibility.ColorChanged
            }
            if (outputWidth != sourceWidth || outputHeight != sourceHeight) {
                return RuntimeEligibility.SourceSizeChanged
            }
            return RuntimeEligibility.Eligible
        }

        private fun ownersReady(): Boolean {
            val owners = synchronized(unresolvedOwners) {
                if (unresolvedOwners.isEmpty()) return true
                val now = System.nanoTime()
                if (now < nextRetirementWakeNs) return false
                nextRetirementWakeNs = now + 3_000_000_000L
                unresolvedOwners.toList()
            }
            // An old codec may already have left the registry when its last fence retired.
            // New admission wakes a bounded cleanup on the original owner; never steals its EGL context.
            val checked = CountDownLatch(owners.size)
            for (owner in owners) {
                if (!owner.handler.post {
                    try { if (owner.parked) owner.resumeRetirement() } finally { checked.countDown() }
                }) checked.countDown()
            }
            try { checked.await(120, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return synchronized(unresolvedOwners) { unresolvedOwners.isEmpty() }
        }

        private fun validExtent(w: Int, h: Int) = w in 1..4096 && h in 1..4096 && w.toLong() * h <= 4096L * 2160

        fun create(codec: MediaCodec, display: Surface, width: Int, height: Int, sourceFps: Float,
                   targetFps: Int, source: FrameHostMediaSignals.Values, host: FrameCaptureBridge.Host,
                   isCurrent: () -> Boolean, attach: (Surface) -> Boolean,
                   initialColorStandard: Int? = source.colorStandard,
                   initialStandard: FrameMediaPolicy.Standard = FrameHostMediaSignals.signal(
                       FrameHostMediaSignals.Values(colorStandard = initialColorStandard)
                   ).standard,
                   bypassFrameBudget: Boolean = false,
                   onBackendFailed: (String, Boolean) -> Unit): FrameQcomBridge? {
            if (!validExtent(width, height) || !display.isValid || !sourceFps.isFinite() || targetFps !in 24..240 ||
                abs(sourceFps * 2 - targetFps) > 2.1 || !supportsQcomColorStandard(initialColorStandard) ||
                !ownersReady()) return null
            val bridge = FrameQcomBridge(codec, width, height, sourceFps, targetFps, source, FrameStatus.Backend.QCOM,
                bypassFrameBudget, initialStandard, null, Playback(display, host, isCurrent, attach, onBackendFailed))
            return if (bridge.start()) bridge else null
        }

        fun createRife(codec: MediaCodec, display: Surface, width: Int, height: Int, sourceFps: Float,
                       targetFps: Int, source: FrameHostMediaSignals.Values, host: FrameCaptureBridge.Host,
                       isCurrent: () -> Boolean, attach: (Surface) -> Boolean,
                       openBackend: (Int, Int) -> WindowBackend?,
                       initialStandard: FrameMediaPolicy.Standard = FrameHostMediaSignals.signal(source).standard,
                       bypassFrameBudget: Boolean = false,
                       onBackendFailed: (String, Boolean) -> Unit): FrameQcomBridge? {
            if (!validExtent(width, height) || !display.isValid || !sourceFps.isFinite() || targetFps !in 24..240 ||
                abs(sourceFps * 2 - targetFps) > 2.1 || !ownersReady()) return null
            val bridge = FrameQcomBridge(codec, width, height, sourceFps, targetFps, source, FrameStatus.Backend.RIFE,
                bypassFrameBudget, initialStandard, openBackend, Playback(display, host, isCurrent, attach, onBackendFailed))
            return if (bridge.start()) bridge else null
        }
    }
}

/** A sporadic seek can restart a timestamp anchor; repeated incompatible intervals must still accumulate. */
internal class FrameQcomCadence(private val periodNs: Long) {
    private var offRate = 0
    val unsuitable: Boolean get() = offRate >= 8

    fun matches(gapNs: Long) = gapNs in (periodNs - periodNs / 5)..(periodNs + periodNs / 5)

    fun observe(gapNs: Long): Boolean {
        val matches = matches(gapNs)
        offRate = if (matches) 0 else offRate + 1
        return matches
    }

    fun reset() { offRate = 0 }
}

/** Keep the capture producer alive through the platform call, including a concurrent native restore. */
internal class FrameQcomOutputBorrow<T : Any> {
    private var display: T? = null
    private var input: T? = null
    private var borrowers = 0

    @Synchronized fun connect(display: T, input: T) { this.display = display; this.input = input }
    @Synchronized fun borrow(requested: T): T? {
        if (requested !== display) return null
        val owned = input ?: return null
        borrowers++
        return owned
    }
    @Synchronized fun finish(): Boolean {
        check(borrowers > 0)
        borrowers--
        return input == null && borrowers == 0
    }
    @Synchronized fun revoke(): Boolean {
        display = null
        input = null
        return borrowers == 0
    }
}
