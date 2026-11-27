package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import java.util.Locale

/** Eligibility only: capture ownership, a verified clock and presentation admission remain separate. */
internal object FrameMediaPolicy {
    enum class Evidence { YES, NO, UNKNOWN }
    enum class Transfer { SDR, LINEAR, PQ, HLG, UNKNOWN }
    enum class Standard { BT601, BT709, BT2020, OTHER, UNKNOWN }
    enum class Range { FULL, LIMITED, UNKNOWN }
    enum class Capture { VERIFIED_SDR_RGB8, OTHER, UNKNOWN }
    enum class AudioOutput { NONE, PCM, PASSTHROUGH, OFFLOAD, TUNNELED, UNKNOWN }
    enum class AudioKind { DTS, DTS_HD, DTS_UHD, DOLBY, OTHER, UNKNOWN }
    enum class Reason {
        READY, INVALID_SIZE, PROTECTED_CONTENT, UNKNOWN_PROTECTION, SECURE_DECODER,
        VIDEO_TUNNELED, UNKNOWN_VIDEO_PATH, DOLBY_VISION, HDR_DYNAMIC, HDR_PQ, HDR_HLG,
        HDR_METADATA, WIDE_COLOR, UNSUPPORTED_COLOR, UNKNOWN_COLOR, UNSUPPORTED_BIT_DEPTH,
        UNKNOWN_BIT_DEPTH, CAPTURE_NOT_VERIFIED, AUDIO_PASSTHROUGH, AUDIO_OFFLOAD,
        AUDIO_TUNNELED, UNKNOWN_AUDIO_PATH, STALE_LEASE, ALREADY_RESOLVED
    }

    /** Unknown is distinct from an explicitly absent flag. Do not populate this from a QN preference. */
    data class Signal(
        val mime: String? = null,
        val codecs: String? = null,
        val transfer: Transfer = Transfer.UNKNOWN,
        val standard: Standard = Standard.UNKNOWN,
        val range: Range = Range.UNKNOWN,
        val bitDepth: Int? = null,
        val hdrStatic: Evidence = Evidence.UNKNOWN,
        val hdrDynamic: Evidence = Evidence.UNKNOWN
    )

    data class Audio(
        val mime: String? = null,
        val output: AudioOutput = AudioOutput.UNKNOWN,
        val offloaded: Evidence = Evidence.UNKNOWN,
        val tunneled: Evidence = Evidence.UNKNOWN
    )

    data class Snapshot(
        val width: Int,
        val height: Int,
        val source: Signal,
        val decoded: Signal,
        val capture: Capture = Capture.UNKNOWN,
        val protectedContent: Evidence = Evidence.UNKNOWN,
        val secureDecoder: Evidence = Evidence.UNKNOWN,
        val videoTunneled: Evidence = Evidence.UNKNOWN,
        val audio: Audio = Audio()
    )

    data class Decision(val reason: Reason, val audioKind: AudioKind) {
        val eligible: Boolean get() = reason == Reason.READY
    }

    fun evaluate(media: Snapshot): Decision {
        val audioKind = audioKind(media.audio.mime)
        fun result(reason: Reason) = Decision(reason, audioKind)
        if (media.width !in 1..MAX_DIMENSION || media.height !in 1..MAX_DIMENSION ||
            media.width.toLong() * media.height > MAX_PIXELS)
            return result(Reason.INVALID_SIZE)
        if (media.protectedContent == Evidence.YES) return result(Reason.PROTECTED_CONTENT)
        if (media.secureDecoder == Evidence.YES) return result(Reason.SECURE_DECODER)
        if (media.protectedContent != Evidence.NO || media.secureDecoder != Evidence.NO)
            return result(Reason.UNKNOWN_PROTECTION)
        if (media.videoTunneled == Evidence.YES) return result(Reason.VIDEO_TUNNELED)
        if (media.videoTunneled != Evidence.NO) return result(Reason.UNKNOWN_VIDEO_PATH)

        // Positive special-format evidence in either layer wins over an SDR decoder/tone-map result.
        val signals = listOf(media.source, media.decoded)
        if (signals.any(::dolbyVision)) return result(Reason.DOLBY_VISION)
        if (signals.any { it.hdrDynamic == Evidence.YES }) return result(Reason.HDR_DYNAMIC)
        if (signals.any { it.transfer == Transfer.PQ }) return result(Reason.HDR_PQ)
        if (signals.any { it.transfer == Transfer.HLG }) return result(Reason.HDR_HLG)
        if (signals.any { it.hdrStatic == Evidence.YES }) return result(Reason.HDR_METADATA)
        if (signals.any { it.standard == Standard.BT2020 }) return result(Reason.WIDE_COLOR)
        if (signals.any { it.transfer == Transfer.LINEAR || it.standard == Standard.OTHER })
            return result(Reason.UNSUPPORTED_COLOR)
        if (signals.any { it.transfer != Transfer.SDR || it.standard == Standard.UNKNOWN ||
                it.range == Range.UNKNOWN || it.hdrStatic != Evidence.NO || it.hdrDynamic != Evidence.NO ||
                normalize(it.mime)?.let(VIDEO_MIME::matches) != true || !validCodecs(it.codecs) })
            return result(Reason.UNKNOWN_COLOR)
        if (signals.any { it.bitDepth != null && it.bitDepth != 8 })
            return result(Reason.UNSUPPORTED_BIT_DEPTH)
        if (signals.any { it.bitDepth == null }) return result(Reason.UNKNOWN_BIT_DEPTH)
        if (media.capture != Capture.VERIFIED_SDR_RGB8) return result(Reason.CAPTURE_NOT_VERIFIED)

        val audio = media.audio
        if (audio.mime != null && normalize(audio.mime)?.let(AUDIO_MIME::matches) != true)
            return result(Reason.UNKNOWN_AUDIO_PATH)
        if (audio.offloaded == Evidence.YES) return result(Reason.AUDIO_OFFLOAD)
        if (audio.tunneled == Evidence.YES) return result(Reason.AUDIO_TUNNELED)
        when (audio.output) {
            AudioOutput.NONE -> return result(Reason.READY) // Positively known absence of an active audio path.
            AudioOutput.PASSTHROUGH -> return result(Reason.AUDIO_PASSTHROUGH)
            AudioOutput.OFFLOAD -> return result(Reason.AUDIO_OFFLOAD)
            AudioOutput.TUNNELED -> return result(Reason.AUDIO_TUNNELED)
            AudioOutput.UNKNOWN -> return result(Reason.UNKNOWN_AUDIO_PATH)
            AudioOutput.PCM -> Unit
        }
        if (audio.offloaded != Evidence.NO || audio.tunneled != Evidence.NO)
            return result(Reason.UNKNOWN_AUDIO_PATH)
        return result(Reason.READY)
    }

    fun audioKind(mime: String?): AudioKind = when (normalize(mime)) {
        "audio/vnd.dts" -> AudioKind.DTS
        "audio/vnd.dts.hd" -> AudioKind.DTS_HD
        "audio/vnd.dts.uhd" -> AudioKind.DTS_UHD
        "audio/ac3", "audio/eac3", "audio/eac3-joc", "audio/vnd.dolby.mlp", "audio/vnd.dolby.mat" -> AudioKind.DOLBY
        null -> AudioKind.UNKNOWN
        else -> AudioKind.OTHER
    }

    private fun dolbyVision(signal: Signal): Boolean {
        if (normalize(signal.mime) == "video/dolby-vision") return true
        val codecs = signal.codecs ?: return false
        if (!validCodecs(codecs)) return false // Invalid/over-budget input is subsequently rejected as unknown.
        return codecs.split(',').any { token ->
            token.trim().substringBefore('.').lowercase(Locale.ROOT) in DOLBY_PREFIXES
        }
    }

    private fun normalize(value: String?): String? = value?.takeIf { it.length in 1..MAX_TEXT }
        ?.trim()?.takeIf { it.isNotEmpty() && it.all { char -> char.code in 33..126 } }
        ?.lowercase(Locale.ROOT)

    private fun validCodecs(value: String?): Boolean = value == null ||
        value.length in 1..MAX_TEXT && value.all { it.code in 32..126 } &&
            value.split(',').all {
                val token = it.trim().lowercase(Locale.ROOT)
                CODEC_TOKEN.matches(token) && token.substringBefore('.') in CODEC_PREFIXES
            }

    private const val MAX_TEXT = 256
    private const val MAX_DIMENSION = 4096
    private const val MAX_PIXELS = 4096L * 2160L
    private val VIDEO_MIME = Regex("video/[a-z0-9][a-z0-9.+-]*")
    private val AUDIO_MIME = Regex("audio/[a-z0-9][a-z0-9.+-]*")
    private val CODEC_TOKEN = Regex("[a-z0-9][a-z0-9._-]*")
    private val DOLBY_PREFIXES = setOf("dvhe", "dvh1", "dvav", "dva1", "dav1")
    private val CODEC_PREFIXES = DOLBY_PREFIXES + setOf("avc1", "avc3", "hvc1", "hev1", "vp08", "vp09", "vp8", "vp9", "av01", "mp4v")
}

/**
 * The synchronous format observation calls begin before deferring any metadata work.
 * Each lease resolves once; identical Epoch values never make old results current again.
 */
internal class FrameMediaGate {
    class Lease internal constructor(internal val gate: Any, val epoch: FrameTimeline.Epoch)
    class Permit internal constructor(internal val lease: Lease) {
        val epoch: FrameTimeline.Epoch get() = lease.epoch
    }
    data class Begin(val lease: Lease, val revoked: Permit?)
    data class Resolution(val decision: FrameMediaPolicy.Decision, val permit: Permit?)

    private val identity = Any()
    private var lease: Lease? = null
    private var current: Permit? = null
    private var resolved = false

    @Synchronized fun begin(epoch: FrameTimeline.Epoch): Begin {
        val previous = current
        current = null
        resolved = false
        return Begin(Lease(identity, epoch).also { lease = it }, previous)
    }

    @Synchronized fun resolve(expected: Lease, media: FrameMediaPolicy.Snapshot): Resolution {
        if (!current(expected)) return rejected(FrameMediaPolicy.Reason.STALE_LEASE)
        if (resolved) return rejected(FrameMediaPolicy.Reason.ALREADY_RESOLVED)
        val decision = FrameMediaPolicy.evaluate(media)
        resolved = true
        current = if (decision.eligible) Permit(expected) else null
        return Resolution(decision, current)
    }

    @Synchronized fun valid(permit: Permit, expected: Lease): Boolean =
        current(expected) && current === permit && permit.lease === expected

    @Synchronized fun close(expected: Lease): Boolean {
        if (!current(expected)) return false
        current = null
        lease = null
        resolved = false
        return true
    }

    private fun current(expected: Lease) = expected.gate === identity && lease === expected
    private fun rejected(reason: FrameMediaPolicy.Reason) =
        Resolution(FrameMediaPolicy.Decision(reason, FrameMediaPolicy.AudioKind.UNKNOWN), null)
}
