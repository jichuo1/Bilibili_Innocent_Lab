package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameInterpolationModeTest {
    private fun packRaw(
        vendorId: Int,
        subgroupSize: Int = 0,
        flags: Long = 0L
    ): Long = flags or ((subgroupSize.toLong() and 0xFFL) shl 16) or ((vendorId.toLong() and 0xFFFFFFFFL) shl 32)

    @Test fun `qualcomm qcom and vulkan capability device exposes both qcom and rife candidates`() {
        val raw = packRaw(
            vendorId = 0x5143,
            subgroupSize = 64,
            flags = FrameGpuCapabilityProbe.FLAG_VULKAN_AVAILABLE or
                FrameGpuCapabilityProbe.FLAG_VULKAN_COMPUTE or
                FrameGpuCapabilityProbe.FLAG_QCOM_BLOCK_MATCH or
                FrameGpuCapabilityProbe.FLAG_QCOM_AHB or
                FrameGpuCapabilityProbe.FLAG_VULKAN_AHB or
                FrameGpuCapabilityProbe.FLAG_VULKAN_SYNC_FD or
                FrameGpuCapabilityProbe.FLAG_SHADER_FP16 or
                FrameGpuCapabilityProbe.FLAG_SUBGROUP_OPS or
                FrameGpuCapabilityProbe.FLAG_COOP_MATRIX
        )
        val caps = FrameGpuCapabilityProbe.unpack(raw, qcomLibraryReady = true, supportedRuntimeAbi = true)
        assertEquals(GpuArchitecture.QUALCOMM_ADRENO, caps.architecture)
        assertTrue(caps.qcomMotionSupported)
        assertTrue(caps.qcomAhbZeroCopy)
        assertTrue(caps.rifeVulkanSupported)
        assertTrue(caps.cooperativeMatrix)
        assertEquals(64, caps.subgroupSize)
        assertEquals(listOf(FrameInterpolationMode.QCOM, FrameInterpolationMode.RIFE),
            FrameInterpolationMode.supportedCandidates(caps))
    }

    @Test fun `arm mali and samsung xclipse and powervr hide qcom and show rife requiring model`() {
        val maliRaw = packRaw(
            vendorId = 0x13B5,
            subgroupSize = 16,
            flags = FrameGpuCapabilityProbe.FLAG_VULKAN_AVAILABLE or
                FrameGpuCapabilityProbe.FLAG_VULKAN_COMPUTE or
                FrameGpuCapabilityProbe.FLAG_VULKAN_AHB or
                FrameGpuCapabilityProbe.FLAG_SHADER_FP16 or
                FrameGpuCapabilityProbe.FLAG_SUBGROUP_OPS or
                FrameGpuCapabilityProbe.FLAG_DOT_PRODUCT
        )
        val mali = FrameGpuCapabilityProbe.unpack(maliRaw, qcomLibraryReady = false, supportedRuntimeAbi = true)
        assertEquals(GpuArchitecture.ARM_MALI, mali.architecture)
        assertFalse(mali.qcomMotionSupported)
        assertTrue(mali.rifeVulkanSupported)
        assertTrue(mali.integerDotProduct)
        val maliCandidates = FrameInterpolationMode.supportedCandidates(mali)
        assertEquals(listOf(FrameInterpolationMode.RIFE), maliCandidates)
        assertTrue(maliCandidates.single().requiresModel)

        val xclipse = FrameGpuCapabilityProbe.unpack(
            packRaw(
                vendorId = 0x1002,
                subgroupSize = 32,
                flags = FrameGpuCapabilityProbe.FLAG_VULKAN_AVAILABLE or
                    FrameGpuCapabilityProbe.FLAG_VULKAN_COMPUTE or
                    FrameGpuCapabilityProbe.FLAG_SHADER_FP16 or
                    FrameGpuCapabilityProbe.FLAG_SUBGROUP_OPS or
                    FrameGpuCapabilityProbe.FLAG_COOP_MATRIX
            ),
            qcomLibraryReady = false,
            supportedRuntimeAbi = true
        )
        assertEquals(GpuArchitecture.SAMSUNG_XCLIPSE_RDNA, xclipse.architecture)
        assertEquals(listOf(FrameInterpolationMode.RIFE), FrameInterpolationMode.supportedCandidates(xclipse))

        val powerVr = FrameGpuCapabilityProbe.unpack(
            packRaw(
                vendorId = 0x1010,
                subgroupSize = 32,
                flags = FrameGpuCapabilityProbe.FLAG_VULKAN_AVAILABLE or
                    FrameGpuCapabilityProbe.FLAG_VULKAN_COMPUTE or
                    FrameGpuCapabilityProbe.FLAG_SHADER_FP16
            ),
            qcomLibraryReady = false,
            supportedRuntimeAbi = true
        )
        assertEquals(GpuArchitecture.IMAGINATION_POWERVR, powerVr.architecture)
        assertEquals(listOf(FrameInterpolationMode.RIFE), FrameInterpolationMode.supportedCandidates(powerVr))
    }

    @Test fun `adreno without loaded qcom native library or without extension omits qcom`() {
        val raw = packRaw(
            vendorId = 0x5143,
            flags = FrameGpuCapabilityProbe.FLAG_VULKAN_AVAILABLE or
                FrameGpuCapabilityProbe.FLAG_VULKAN_COMPUTE or
                FrameGpuCapabilityProbe.FLAG_QCOM_BLOCK_MATCH
        )
        val missingLib = FrameGpuCapabilityProbe.unpack(raw, qcomLibraryReady = false, supportedRuntimeAbi = true)
        assertEquals(GpuArchitecture.QUALCOMM_ADRENO, missingLib.architecture)
        assertFalse(missingLib.qcomMotionSupported)
        assertEquals(listOf(FrameInterpolationMode.RIFE), FrameInterpolationMode.supportedCandidates(missingLib))

        val unsupportedAbi = FrameGpuCapabilityProbe.unpack(raw, qcomLibraryReady = true, supportedRuntimeAbi = false)
        assertEquals(emptyList<FrameInterpolationMode>(), FrameInterpolationMode.supportedCandidates(unsupportedAbi))
    }

    @Test fun `forced policy resolves device candidates and adapts restored qcom on non qcom gpu`() {
        val qcomCaps = FrameGpuCapabilityProbe.unpack(
            packRaw(0x5143, flags = FrameGpuCapabilityProbe.FLAG_VULKAN_COMPUTE or FrameGpuCapabilityProbe.FLAG_QCOM_BLOCK_MATCH),
            qcomLibraryReady = true,
            supportedRuntimeAbi = true
        )
        val maliCaps = FrameGpuCapabilityProbe.unpack(
            packRaw(0x13B5, flags = FrameGpuCapabilityProbe.FLAG_VULKAN_COMPUTE),
            qcomLibraryReady = false,
            supportedRuntimeAbi = true
        )

        val auto = FrameInterpolationModePolicy(forceMode = false, configuredMode = "rife")
        assertFalse(auto.bypassFrameBudget)
        assertNull(auto.resolveForcedMode(qcomCaps))

        val defaultForced = FrameInterpolationModePolicy(forceMode = true, configuredMode = "")
        assertTrue(defaultForced.bypassFrameBudget)
        assertEquals(FrameInterpolationMode.QCOM, defaultForced.resolveForcedMode(qcomCaps))
        assertEquals(FrameInterpolationMode.RIFE, defaultForced.resolveForcedMode(maliCaps))

        val restoredQcom = FrameInterpolationModePolicy(forceMode = true, configuredMode = "qcom")
        assertEquals(FrameInterpolationMode.QCOM, restoredQcom.resolveForcedMode(qcomCaps))
        assertEquals(FrameInterpolationMode.RIFE, restoredQcom.resolveForcedMode(maliCaps))
    }

    @Test fun `retireQcom keeps resize retries and blocks rife fallback when disallowed`() {
        val forcedQcomAttempt = FramePlaybackRoutes.Attempt()
        assertTrue(forcedQcomAttempt.retireQcom("window_size", hadMidpoint = true, allowRifeFallback = false))
        assertFalse(forcedQcomAttempt.rifeOnly)
        assertFalse(forcedQcomAttempt.retireQcom("worker:-1", hadMidpoint = false, allowRifeFallback = false))
        assertFalse("Forced QCOM must never flip rifeOnly on failure", forcedQcomAttempt.rifeOnly)

        val autoAttempt = FramePlaybackRoutes.Attempt()
        assertFalse(autoAttempt.retireQcom("worker:-1", hadMidpoint = false, allowRifeFallback = true))
        assertTrue(autoAttempt.rifeOnly)
    }

    @Test fun `ignoreInferenceBudget bypasses slow gpu frame rate gate while obeying display ceiling`() {
        assertNull(FrameRateTarget.choose(30f, 60, listOf(60f, 120f), null, inferenceMs = 92.0, ignoreInferenceBudget = false))
        assertEquals(60, FrameRateTarget.choose(30f, 60, listOf(60f, 120f), null, inferenceMs = 92.0, ignoreInferenceBudget = true))
        assertEquals(120, FrameRateTarget.choose(30f, 120, listOf(60f, 120f), null, inferenceMs = 92.0, ignoreInferenceBudget = true))
        // Still obeys measured display limit and user ceiling even when ignoring inference budget.
        assertEquals(60, FrameRateTarget.choose(30f, 120, listOf(60f, 120f), measuredLimit = 60f, inferenceMs = 92.0, ignoreInferenceBudget = true))
    }

    @Test fun `forced presentation recovery never falls back on frame drops but preserves surface clock and fatal boundaries`() {
        val recovery = FramePresentationRecovery(maxConsecutiveFailures = 3, warmupSamples = 0, fallbackOnFrameDrops = false)
        assertTrue(recovery.start(1_000_000_000L))

        repeat(20) { i ->
            val due = (i + 1L) * 33_000_000L
            val report = recovery.onPresented(dueNs = due, presentedNs = due + 100_000_000L, lateBudgetNs = 16_000_000L)
            assertEquals(FramePresentationRecovery.State.INTERPOLATING, report.state)
            assertTrue(report.interpolationAllowed)
        }
        repeat(10) { i ->
            assertEquals(FramePresentationRecovery.State.INTERPOLATING, recovery.onDropped(5_000_000_000L + i).state)
            assertEquals(FramePresentationRecovery.State.INTERPOLATING, recovery.onWorkerTimeout(6_000_000_000L + i).state)
        }

        val lost = recovery.onSurfaceLost(7_000_000_000L)
        assertEquals(FramePresentationRecovery.State.FALLBACK, lost.state)
        assertEquals(FramePresentationRecovery.Cause.SURFACE_LOST, lost.cause)

        val clockRecovery = FramePresentationRecovery(fallbackOnFrameDrops = false)
        assertTrue(clockRecovery.start(100L))
        repeat(3) { clockRecovery.onClockRejected(200L + it) }
        assertEquals(FramePresentationRecovery.State.FALLBACK, clockRecovery.report().state)
        assertEquals(FramePresentationRecovery.Cause.UNVERIFIED_CLOCK, clockRecovery.report().cause)

        val fatalRecovery = FramePresentationRecovery(fallbackOnFrameDrops = false)
        assertTrue(fatalRecovery.start(100L))
        val fatal = fatalRecovery.onNativeStatus(FrameInterpolationBackend.Status.GPU_UNAVAILABLE, 200L)
        assertEquals(FramePresentationRecovery.State.DISABLED, fatal.state)
        assertEquals(FramePresentationRecovery.Cause.NATIVE_FATAL, fatal.cause)
    }

    @Test fun `qcom route allows sdr bt601 and bt709 standards and maps forced errors accurately`() {
        val bt709 = FrameMediaPolicy.Signal(standard = FrameMediaPolicy.Standard.BT709)
        val bt601 = FrameMediaPolicy.Signal(standard = FrameMediaPolicy.Standard.BT601)
        val bt2020 = FrameMediaPolicy.Signal(standard = FrameMediaPolicy.Standard.BT2020)
        val other = FrameMediaPolicy.Signal(standard = FrameMediaPolicy.Standard.OTHER)
        val unknown = FrameMediaPolicy.Signal(standard = FrameMediaPolicy.Standard.UNKNOWN)

        assertTrue(FramePlaybackRoutes.qcomColorEligible(bt709, bt709))
        assertTrue(FramePlaybackRoutes.qcomColorEligible(bt601, bt601))
        assertTrue(FramePlaybackRoutes.qcomColorEligible(bt709, bt601))
        assertTrue(FramePlaybackRoutes.qcomColorEligible(bt601, bt709))
        for (bad in listOf(bt2020, other, unknown)) {
            assertFalse(FramePlaybackRoutes.qcomColorEligible(bad, bt709))
            assertFalse(FramePlaybackRoutes.qcomColorEligible(bt709, bad))
        }

        assertEquals(FrameStatus.Reason.RATE, FramePlaybackRoutes.forcedQcomRejectReason(target = null, qcomColor = true))
        assertEquals(FrameStatus.Reason.RATE, FramePlaybackRoutes.forcedQcomRejectReason(target = null, qcomColor = false))
        assertEquals(FrameStatus.Reason.MEDIA, FramePlaybackRoutes.forcedQcomRejectReason(target = 60, qcomColor = false))
        assertEquals(FrameStatus.Reason.ENGINE, FramePlaybackRoutes.forcedQcomRejectReason(target = 60, qcomColor = true))

        assertEquals(FrameStatus.Reason.RATE, FramePlaybackRoutes.forcedQcomFailureReason("speed"))
        assertEquals(FrameStatus.Reason.RATE, FramePlaybackRoutes.forcedQcomFailureReason("cadence"))
        assertEquals(FrameStatus.Reason.FORMAT, FramePlaybackRoutes.forcedQcomFailureReason("source_size"))
        assertEquals(FrameStatus.Reason.ENGINE, FramePlaybackRoutes.forcedQcomFailureReason("color_changed"))
        assertEquals(FrameStatus.Reason.ENGINE, FramePlaybackRoutes.forcedQcomFailureReason("worker:-1"))

        assertEquals("auto", FramePlaybackRoutes.Strategy.Auto.logName)
        assertEquals("forced:qcom", FramePlaybackRoutes.Strategy.Forced(FrameInterpolationMode.QCOM).logName)
        assertEquals("forced:rife", FramePlaybackRoutes.Strategy.Forced(FrameInterpolationMode.RIFE).logName)
        assertEquals("unavailable", FramePlaybackRoutes.Strategy.Unavailable.logName)
    }

    @Test fun `qcom runtime eligibility supports bt601 transitions, rejects hdr, and separates resize from media`() {
        assertTrue(FrameQcomBridge.supportsQcomColorStandard(1))
        assertTrue(FrameQcomBridge.supportsQcomColorStandard(2))
        assertTrue(FrameQcomBridge.supportsQcomColorStandard(4))
        assertFalse(FrameQcomBridge.supportsQcomColorStandard(6))
        assertFalse(FrameQcomBridge.supportsQcomColorStandard(null))
        assertFalse(FrameQcomBridge.supportsQcomColorStandard(0))
        assertFalse(FrameQcomBridge.supportsQcomColorStandard(99))

        val pcmAudio = FrameMediaPolicy.Audio(
            output = FrameMediaPolicy.AudioOutput.PCM,
            offloaded = FrameMediaPolicy.Evidence.NO,
            tunneled = FrameMediaPolicy.Evidence.NO
        )
        fun eval(
            mode: FrameStatus.Backend = FrameStatus.Backend.QCOM,
            configuredSource: FrameHostMediaSignals.Values = FrameHostMediaSignals.Values(mime = "video/avc"),
            input: FrameHostMediaSignals.Values? = null,
            output: FrameHostMediaSignals.Values = FrameHostMediaSignals.Values(
                mime = "video/raw",
                colorStandard = 1,
                colorTransfer = 3,
                colorRange = 2
            ),
            outputWidth: Int = 1920,
            outputHeight: Int = 1080,
            initialStandard: FrameMediaPolicy.Standard = FrameMediaPolicy.Standard.BT709
        ) = FrameQcomBridge.evaluateRuntimeEligibility(
            mode = mode,
            sourceWidth = 1920,
            sourceHeight = 1080,
            configuredSource = configuredSource,
            input = input,
            output = output,
            audio = pcmAudio,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            initialStandard = initialStandard
        )

        // Ordinary BT.601 PAL and NTSC SDR 8-bit must pass for QCOM.
        assertEquals(
            FrameQcomBridge.RuntimeEligibility.Eligible,
            eval(
                output = FrameHostMediaSignals.Values("video/raw", colorStandard = 2, colorTransfer = 3, colorRange = 2),
                initialStandard = FrameMediaPolicy.Standard.BT601
            )
        )
        assertEquals(
            FrameQcomBridge.RuntimeEligibility.Eligible,
            eval(
                output = FrameHostMediaSignals.Values("video/raw", colorStandard = 4, colorTransfer = 3, colorRange = 2),
                initialStandard = FrameMediaPolicy.Standard.BT601
            )
        )

        // configure omits colorStandard; initial was BT.601, runtime output updates to BT.709.
        val updatedOutput = FrameHostMediaSignals.Values("video/raw", colorStandard = 1, colorTransfer = 3, colorRange = 2)
        assertEquals(
            FrameQcomBridge.RuntimeEligibility.Eligible,
            eval(mode = FrameStatus.Backend.QCOM, output = updatedOutput, initialStandard = FrameMediaPolicy.Standard.BT601)
        )
        assertEquals(
            FrameQcomBridge.RuntimeEligibility.ColorChanged,
            eval(mode = FrameStatus.Backend.RIFE, output = updatedOutput, initialStandard = FrameMediaPolicy.Standard.BT601)
        )

        // configure explicitly BT.709 while decoder output reports BT.601 -> still QCOM Eligible.
        assertEquals(
            FrameQcomBridge.RuntimeEligibility.Eligible,
            eval(
                mode = FrameStatus.Backend.QCOM,
                configuredSource = FrameHostMediaSignals.Values("video/avc", colorStandard = 1),
                output = FrameHostMediaSignals.Values("video/raw", colorStandard = 4, colorTransfer = 3, colorRange = 2),
                initialStandard = FrameMediaPolicy.Standard.BT709
            )
        )

        // True HDR, BT.2020, HDR metadata, and 10-bit streams must still fail as MediaRejected on both backends.
        val hdrCases = listOf(
            FrameHostMediaSignals.Values("video/hevc") to FrameHostMediaSignals.Values("video/raw", 1, 6, 2),
            FrameHostMediaSignals.Values("video/hevc") to FrameHostMediaSignals.Values("video/raw", 1, 7, 2),
            FrameHostMediaSignals.Values("video/hevc") to FrameHostMediaSignals.Values("video/raw", 6, 3, 2),
            FrameHostMediaSignals.Values("video/avc") to FrameHostMediaSignals.Values("video/raw", 1, 3, 2, hdrStaticInfo = true),
            FrameHostMediaSignals.Values("video/hevc") to FrameHostMediaSignals.Values("video/raw", 1, 3, 2, hdr10PlusInfo = true),
            FrameHostMediaSignals.Values("video/hevc", profile = 2) to FrameHostMediaSignals.Values("video/raw", 1, 3, 2)
        )
        for ((src, out) in hdrCases) {
            for (backend in listOf(FrameStatus.Backend.QCOM, FrameStatus.Backend.RIFE)) {
                assertTrue(
                    eval(mode = backend, configuredSource = src, output = out) is FrameQcomBridge.RuntimeEligibility.MediaRejected
                )
            }
        }

        // Valid SDR resolution/crop change is SourceSizeChanged, never MediaRejected.
        assertEquals(
            FrameQcomBridge.RuntimeEligibility.SourceSizeChanged,
            eval(outputWidth = 1280, outputHeight = 720)
        )

        // configure lacks profile; runtime inputFormat supplies Main 8-bit profile=1 for HEVC and AV1.
        for (mime in listOf("video/hevc", "video/av01")) {
            assertEquals(
                FrameQcomBridge.RuntimeEligibility.Eligible,
                eval(
                    configuredSource = FrameHostMediaSignals.Values(mime = mime),
                    input = FrameHostMediaSignals.Values(mime = mime, profile = 1)
                )
            )
            assertEquals(
                FrameQcomBridge.RuntimeEligibility.MediaRejected(FrameMediaPolicy.Reason.UNSUPPORTED_BIT_DEPTH),
                eval(
                    configuredSource = FrameHostMediaSignals.Values(mime = mime),
                    input = FrameHostMediaSignals.Values(mime = mime, profile = 2)
                )
            )
        }
    }
}
