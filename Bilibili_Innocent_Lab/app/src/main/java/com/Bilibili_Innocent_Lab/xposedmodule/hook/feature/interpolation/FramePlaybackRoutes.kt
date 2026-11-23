package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface

internal interface FramePlaybackBridge {
    /** Called after this decoder's active bridge can resolve external surface requests. */
    fun onPublished() = Unit
    fun onFlush()
    fun close()
    /** Acquire the current codec input for a repeat of its exact logical display. */
    fun decoderInputFor(display: Surface): Surface? = null
    fun finishDecoderInput() = Unit
}

/** Runs on the attachment worker, with the host still presenting originals until a route takes over. */
internal class FramePlaybackRoutes(
    val rife: FrameInterpolationEngineHolder,
    private val strategy: Strategy = Strategy.Auto
) {
    sealed interface Strategy {
        val allowsQcom: Boolean
        val allowsRife: Boolean
        val bypassFrameBudget: Boolean
        val logName: String

        object Auto : Strategy {
            override val allowsQcom = true
            override val allowsRife = true
            override val bypassFrameBudget = false
            override val logName = "auto"
        }

        data class Forced(val mode: FrameInterpolationMode) : Strategy {
            override val allowsQcom = mode == FrameInterpolationMode.QCOM
            override val allowsRife = mode == FrameInterpolationMode.RIFE
            override val bypassFrameBudget = true
            override val logName get() = "forced:${mode.wireValue}"
        }

        object Unavailable : Strategy {
            override val allowsQcom = false
            override val allowsRife = false
            override val bypassFrameBudget = true
            override val logName = "unavailable"
        }
    }

    class Attempt {
        @Volatile var rifeOnly = false
            internal set
        @Volatile var rifeAhbTried = false
            private set
        private var consecutiveQcomResizes = 0
        var mediaRetryDelayMs = 0L
            private set

        fun waitForMedia(reason: FrameMediaPolicy.Reason): Boolean {
            if (reason !in WAITABLE) return false
            // Preloaded or paused decoders can expose their first output/PCM track much later.
            mediaRetryDelayMs = (mediaRetryDelayMs * 2).coerceIn(150L, 3_000L)
            return true
        }

        /**
         * A portrait/fullscreen resize invalidates the EGL window extent but does not mean QCOM is broken.
         * Allow QCOM to reattach at the new extent, capping consecutive zero-midpoint resizes to bound loops.
         */
        @Synchronized fun retireQcom(reason: String, hadMidpoint: Boolean, allowRifeFallback: Boolean = true): Boolean {
            if (reason == "window_size") {
                consecutiveQcomResizes = if (hadMidpoint) 0 else consecutiveQcomResizes + 1
                if (consecutiveQcomResizes <= MAX_ZERO_MIDPOINT_RESIZES) return true
            }
            if (allowRifeFallback) rifeOnly = true
            return false
        }

        /** The attach worker is the sole claimant. A restored codec keeps this attempt on retarget. */
        fun claimRifeAhb(modelLoaded: Boolean): Boolean {
            if (!modelLoaded || rifeAhbTried) return false
            rifeAhbTried = true
            return true
        }
    }

    sealed interface Result {
        class Attached(val bridge: FramePlaybackBridge, val backend: String) : Result
        object WaitForMedia : Result
        object WaitForRife : Result
        object Original : Result
    }

    fun attach(codec: MediaCodec, format: MediaFormat, display: Surface, crypto: Any?, host: FrameCaptureBridge.Host,
               attempt: Attempt, requestedTargetFps: Int? = null, isCurrent: () -> Boolean,
               attach: (Surface) -> Boolean, onWindowFailed: (String) -> Unit): Result {
        if (!isCurrent() || crypto != null || !display.isValid) return Result.Original
        val width = runCatching { format.getInteger(MediaFormat.KEY_WIDTH) }.getOrNull() ?: return Result.Original
        val height = runCatching { format.getInteger(MediaFormat.KEY_HEIGHT) }.getOrNull() ?: return Result.Original
        if (width !in 1..4096 || height !in 1..4096 || width.toLong() * height > 4096L * 2160 ||
            runCatching { codec.name.endsWith(".secure") }.getOrDefault(true) ||
            runCatching { format.getFeatureEnabled("tunneled-playback") }.getOrDefault(false) ||
            format.containsKey(MediaFormat.KEY_AUDIO_SESSION_ID)) return Result.Original

        val configured = FrameCaptureBridge.values(format)
        val input = runCatching { FrameCaptureBridge.values(codec.inputFormat) }.getOrNull()
        val source = input?.let(configured::fillFrom) ?: configured
        val output = runCatching { FrameCaptureBridge.values(codec.outputFormat) }.getOrNull()
        val decoded = output?.fillFrom(source) ?: source
        val merged = source.fillFrom(decoded)
        val signal = FrameHostMediaSignals.signal(merged)
        val decodedSignal = FrameHostMediaSignals.signal(decoded, 8)
        val decision = FrameMediaPolicy.evaluate(FrameMediaPolicy.Snapshot(width, height, signal, decodedSignal,
            FrameMediaPolicy.Capture.VERIFIED_SDR_RGB8, FrameMediaPolicy.Evidence.NO, FrameMediaPolicy.Evidence.NO,
            FrameMediaPolicy.Evidence.NO, host.audio()))
        if (!decision.eligible) {
            if (attempt.waitForMedia(decision.reason)) return Result.WaitForMedia
            host.log("route.native", "media=${decision.reason}")
            host.onStatus(FrameStatus.Inactive(FrameStatus.Reason.MEDIA))
            return Result.Original
        }

        val fps = FrameCaptureBridge.sourceFpsHint(format) ?: 30f
        val target = if (kotlin.math.abs(host.playbackRateReader(display)() - 1.0) <= 0.01)
            FrameRateTarget.midpoint(fps, requestedTargetFps ?: host.maxFps(),
                FrameCaptureBridge.displayModes(host.context()), host.displayLimitHz()) else null
        if (!strategy.allowsQcom && !strategy.allowsRife) {
            host.log("route.native", "mode_unavailable")
            host.onStatus(FrameStatus.Inactive(FrameStatus.Reason.CAPABILITY))
            return Result.Original
        }
        if (!attempt.rifeOnly && strategy.allowsQcom) {
            val qcomColor = qcomColorEligible(signal, decodedSignal)
            if (target != null && qcomColor && isCurrent()) {
                val qcom = FrameQcomBridge.create(codec, display, width, height, fps, target, source, host, isCurrent,
                    attach, initialColorStandard = merged.colorStandard, initialStandard = signal.standard,
                    bypassFrameBudget = strategy.bypassFrameBudget) { reason, hadMidpoint ->
                    val retryQcom = attempt.retireQcom(reason, hadMidpoint, allowRifeFallback = strategy.allowsRife)
                    if (retryQcom) onWindowFailed("qcom-resize")
                    else if (strategy.allowsRife) onWindowFailed("qcom")
                    else host.onStatus(FrameStatus.Inactive(forcedQcomFailureReason(reason)))
                }
                if (qcom != null) {
                    host.log("route.selected", "qcom ${width}x${height} target=$target std=${signal.standard}/${decodedSignal.standard} forced=${!strategy.allowsRife}")
                    return Result.Attached(qcom, "qcom")
                }
            }
            if (!strategy.allowsRife) {
                if (!isCurrent()) return Result.Original
                val reason = forcedQcomRejectReason(target, qcomColor)
                host.log("route.native", "qcom_forced=$reason src=${signal.standard} dec=${decodedSignal.standard} raw=${merged.colorStandard}/${decoded.colorStandard}")
                host.onStatus(FrameStatus.Inactive(reason))
                return Result.Original
            }
            attempt.rifeOnly = true
            host.log("route.fallback", "rife ${width}x${height}")
        } else if (!attempt.rifeOnly) {
            attempt.rifeOnly = true
        }
        if (!isCurrent() || !strategy.allowsRife) return Result.Original
        // A model can be ready before RGB capability measurement finishes. AHB proves its own
        // Vulkan interop, pixel extent and pair-to-draw budget on the actual EGL owner.
        val windowColor = signal.standard == decodedSignal.standard && signal.standard in SDR_WINDOW_STANDARDS
        if (attempt.claimRifeAhb(rife.ahbCandidateLoaded())) {
            if (target != null && windowColor) {
                val window = FrameQcomBridge.createRife(codec, display, width, height, fps, target, source,
                    host, isCurrent, attach, { outputWidth, outputHeight ->
                        FrameRifeAhbBackend.open(rife, width, height, outputWidth, outputHeight, fps, target, signal.standard,
                            isCurrent, androidColorStandard = merged.colorStandard ?: decoded.colorStandard,
                            bypassFrameBudget = strategy.bypassFrameBudget)
                    }, initialStandard = signal.standard, bypassFrameBudget = strategy.bypassFrameBudget) { _, _ -> onWindowFailed("rife-ahb") }
                if (window != null) {
                    if (window.awaitingRetirement) {
                        host.log("route.retiring", "rife-ahb")
                        return Result.Attached(window, "rife-ahb-retiring")
                    }
                    host.log("route.selected", "rife-ahb ${width}x${height} target=$target")
                    return Result.Attached(window, "rife-ahb")
                }
            }
            host.log("route.fallback", "rife-rgb ${width}x${height}")
        }
        if (!isCurrent()) return Result.Original
        if (!rife.ready()) {
            rife.warmUp(windowOnly = !attempt.rifeAhbTried && target != null && windowColor)
            rife.retryIfDue()
            return if (rife.state == FrameInterpolationEngineHolder.State.FAILED && !rife.recoveryPending()) {
                host.onStatus(FrameStatus.Inactive(FrameStatus.Reason.ENGINE))
                Result.Original
            } else {
                host.onStatus(FrameStatus.Inactive(FrameStatus.Reason.LOADING))
                Result.WaitForRife
            }
        }
        val fallback = FrameCaptureBridge.create(FrameCaptureBridge.Config(bypassFrameBudget = strategy.bypassFrameBudget),
            codec, format, display, crypto, rife, host, requestedTargetFps, attach, isCurrent) ?: return Result.Original
        host.log("route.selected", "rife")
        return Result.Attached(fallback, "rife")
    }

    fun rifeCanAdvance(attempt: Attempt): Boolean = strategy.allowsRife && (rife.ready() ||
        rife.state == FrameInterpolationEngineHolder.State.MODEL_READY ||
        (!attempt.rifeAhbTried && rife.ahbCandidateLoaded()) ||
        (rife.state == FrameInterpolationEngineHolder.State.FAILED && !rife.recoveryPending()))

    companion object {
        private const val MAX_ZERO_MIDPOINT_RESIZES = 2
        private val SDR_WINDOW_STANDARDS = setOf(FrameMediaPolicy.Standard.BT601, FrameMediaPolicy.Standard.BT709)
        private val WAITABLE = setOf(FrameMediaPolicy.Reason.UNKNOWN_COLOR, FrameMediaPolicy.Reason.UNKNOWN_BIT_DEPTH,
            FrameMediaPolicy.Reason.UNKNOWN_AUDIO_PATH)

        internal fun qcomColorEligible(signal: FrameMediaPolicy.Signal, decoded: FrameMediaPolicy.Signal): Boolean =
            signal.standard in SDR_WINDOW_STANDARDS && decoded.standard in SDR_WINDOW_STANDARDS

        internal fun forcedQcomRejectReason(target: Int?, qcomColor: Boolean): FrameStatus.Reason = when {
            target == null -> FrameStatus.Reason.RATE
            !qcomColor -> FrameStatus.Reason.MEDIA
            else -> FrameStatus.Reason.ENGINE
        }

        internal fun forcedQcomFailureReason(reason: String): FrameStatus.Reason = when (reason) {
            "speed", "cadence" -> FrameStatus.Reason.RATE
            "source_size" -> FrameStatus.Reason.FORMAT
            else -> FrameStatus.Reason.ENGINE
        }
    }
}
