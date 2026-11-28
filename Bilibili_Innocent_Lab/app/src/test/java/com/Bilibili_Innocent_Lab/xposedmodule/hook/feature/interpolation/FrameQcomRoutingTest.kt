package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import io.github.jichuo1.meguviz.media.WindowBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameQcomRoutingTest {
    private fun packRaw(
        vendorId: Int,
        subgroupSize: Int = 0,
        flags: Long = 0L
    ): Long = flags or ((subgroupSize.toLong() and 0xFFL) shl 16) or ((vendorId.toLong() and 0xFFFFFFFFL) shl 32)

    private class FakeWindowBackend : WindowBackend {
        override fun capture(oesTexture: Int, transform: FloatArray, frameId: Long, ptsUs: Long): Any? = null
        override fun submit(left: Any, right: Any, dueNs: Long): Long = 0L
        override fun poll(): WindowBackend.Completion? = null
        override fun draw(frame: Any, width: Int, height: Int) = Unit
        override fun release(frame: Any) = Unit
        override fun close() = Unit
    }

    @Test
    fun verifyGpuArchitectureAndAutoRouteStrategies() {
        // huawei maleoon vendor id maps cleanly and auto routes non-qcom gpus to rife without qcom bridge
        run {
            val maleoon = FrameGpuCapabilityProbe.unpack(
                packRaw(
                    vendorId = 0x19E5,
                    subgroupSize = 16,
                    flags = FrameGpuCapabilityProbe.FLAG_VULKAN_AVAILABLE or
                        FrameGpuCapabilityProbe.FLAG_VULKAN_COMPUTE or
                        FrameGpuCapabilityProbe.FLAG_VULKAN_AHB or
                        FrameGpuCapabilityProbe.FLAG_SHADER_FP16
                ),
                qcomLibraryReady = false,
                supportedRuntimeAbi = true
            )
            assertEquals(GpuArchitecture.HUAWEI_MALEOON, maleoon.architecture)
            assertFalse(maleoon.qcomMotionSupported)
            assertTrue(maleoon.rifeVulkanSupported)
            assertEquals(listOf(FrameInterpolationMode.RIFE), FrameInterpolationMode.supportedCandidates(maleoon))

            val adrenoMissingNamespace = FrameGpuCapabilityProbe.unpack(
                packRaw(
                    vendorId = 0x5143,
                    flags = FrameGpuCapabilityProbe.FLAG_VULKAN_AVAILABLE or
                        FrameGpuCapabilityProbe.FLAG_VULKAN_COMPUTE or
                        FrameGpuCapabilityProbe.FLAG_QCOM_BLOCK_MATCH
                ),
                qcomLibraryReady = false,
                supportedRuntimeAbi = true
            )
            assertEquals(GpuArchitecture.QUALCOMM_ADRENO, adrenoMissingNamespace.architecture)
            assertFalse(adrenoMissingNamespace.qcomMotionSupported)
            assertTrue(adrenoMissingNamespace.rifeVulkanSupported)

            val installer = FrameInterpolationFeatureInstaller(enabled = true, verificationAllowed = false, moduleApk = null)
            val autoPolicy = FrameInterpolationModePolicy(forceMode = false)
            for (caps in listOf(maleoon, adrenoMissingNamespace)) {
                val strategy = installer.resolveRouteStrategy(autoPolicy, caps)
                assertFalse(strategy.allowsQcom)
                assertTrue(strategy.allowsRife)
                assertFalse("Auto RIFE route must preserve frame budget fallback", strategy.bypassFrameBudget)
                assertEquals("auto:rife", strategy.logName)
            }
        }

        // auto mode enables qcom only when qcomMotionSupported is true and disables cpu software renderers
        run {
            val installer = FrameInterpolationFeatureInstaller(enabled = true, verificationAllowed = false, moduleApk = null)
            val autoPolicy = FrameInterpolationModePolicy(forceMode = false)

            val fullAdreno = FrameGpuCapabilityProbe.unpack(
                packRaw(
                    vendorId = 0x5143,
                    flags = FrameGpuCapabilityProbe.FLAG_VULKAN_AVAILABLE or
                        FrameGpuCapabilityProbe.FLAG_VULKAN_COMPUTE or
                        FrameGpuCapabilityProbe.FLAG_QCOM_BLOCK_MATCH
                ),
                qcomLibraryReady = true,
                supportedRuntimeAbi = true
            )
            assertSame(FramePlaybackRoutes.Strategy.Auto, installer.resolveRouteStrategy(autoPolicy, fullAdreno))

            val swiftShaderCpu = FrameGpuCapabilityProbe.unpack(
                packRaw(vendorId = 0x1AE0, flags = FrameGpuCapabilityProbe.FLAG_VULKAN_AVAILABLE),
                qcomLibraryReady = false,
                supportedRuntimeAbi = true
            )
            assertEquals(GpuArchitecture.UNAVAILABLE, swiftShaderCpu.architecture)
            assertFalse(swiftShaderCpu.rifeVulkanSupported)
            assertFalse(swiftShaderCpu.qcomMotionSupported)
            assertSame(FramePlaybackRoutes.Strategy.Unavailable, installer.resolveRouteStrategy(autoPolicy, swiftShaderCpu))
            assertSame(
                FramePlaybackRoutes.Strategy.Unavailable,
                installer.resolveRouteStrategy(FrameInterpolationModePolicy(forceMode = true, configuredMode = "rife"), swiftShaderCpu)
            )

            val fallbackOnNullProbe = installer.resolveRouteStrategy(autoPolicy, null)
            assertFalse(fallbackOnNullProbe.allowsQcom)
            assertTrue(fallbackOnNullProbe.allowsRife)
            assertFalse(fallbackOnNullProbe.bypassFrameBudget)
        }

        // qcom library probe returns false on linkage errors from isolated classloader namespaces
        run {
            assertTrue(FrameGpuCapabilityProbe.probeQcomMotionLibraryWith { })
            assertFalse(FrameGpuCapabilityProbe.probeQcomMotionLibraryWith {
                throw UnsatisfiedLinkError("dlopen failed: libmeguviz_qcom_motion.so not found")
            })
            assertFalse(FrameGpuCapabilityProbe.probeQcomMotionLibraryWith {
                throw ExceptionInInitializerError(UnsatisfiedLinkError("isolated namespace"))
            })
            assertFalse(FrameGpuCapabilityProbe.probeQcomMotionLibraryWith {
                throw NoClassDefFoundError("Could not initialize class io.github.jichuo1.meguviz.media.QcomMotionNative")
            })
            assertFalse(FrameGpuCapabilityProbe.probeQcomMotionLibraryWith {
                throw ClassNotFoundException("io.github.jichuo1.meguviz.media.QcomMotionNative")
            })
        }
    }

    @Test
    fun verifyQcomBridgeFallbackAndEglPresentationTime() {
        // openQcomBackend latches off qcom without pbo retry on missing matcher or linkage error
        run {
            FrameQcomBridge.resetQcomUnavailableForTest()
            try {
                val attempts = mutableListOf<Pair<Boolean, Boolean>>()
                val failure = assertThrows(IllegalStateException::class.java) {
                    FrameQcomBridge.openQcomBackend(1920, 1080, tryAhb = true) { _, _, asyncReadback, useAhb ->
                        attempts += asyncReadback to useAhb
                        throw IllegalStateException(FrameQcomBridge.VULKAN_QCOM_UNAVAILABLE_MESSAGE)
                    }
                }
                assertEquals(FrameQcomBridge.VULKAN_QCOM_UNAVAILABLE_MESSAGE, failure.message)
                assertEquals(listOf(false to true), attempts)
                assertTrue(FrameQcomBridge.isQcomUnavailable())

                assertThrows(IllegalStateException::class.java) {
                    FrameQcomBridge.openQcomBackend(1920, 1080, tryAhb = true) { _, _, asyncReadback, useAhb ->
                        attempts += asyncReadback to useAhb
                        FakeWindowBackend()
                    }
                }
                assertEquals(1, attempts.size)
            } finally {
                FrameQcomBridge.resetQcomUnavailableForTest()
            }

            try {
                val attempts = mutableListOf<Pair<Boolean, Boolean>>()
                assertThrows(ExceptionInInitializerError::class.java) {
                    FrameQcomBridge.openQcomBackend(1920, 1080, tryAhb = true) { _, _, asyncReadback, useAhb ->
                        attempts += asyncReadback to useAhb
                        throw ExceptionInInitializerError(UnsatisfiedLinkError("isolated namespace"))
                    }
                }
                assertEquals(listOf(false to true), attempts)
                assertTrue(FrameQcomBridge.isQcomUnavailable())
            } finally {
                FrameQcomBridge.resetQcomUnavailableForTest()
            }
        }

        // openQcomBackend falls back to async pbo only on compact luma AHB preflight failure
        run {
            FrameQcomBridge.resetQcomUnavailableForTest()
            try {
                val attempts = mutableListOf<Pair<Boolean, Boolean>>()
                val pboBackend = FakeWindowBackend()
                val (opened, usedAhb) = FrameQcomBridge.openQcomBackend(1920, 1080, tryAhb = true) { _, _, asyncReadback, useAhb ->
                    attempts += asyncReadback to useAhb
                    if (useAhb) throw IllegalStateException(FrameQcomBridge.COMPACT_LUMA_AHB_PREFLIGHT_FAILED_MESSAGE)
                    pboBackend
                }
                assertSame(pboBackend, opened)
                assertFalse(usedAhb)
                assertEquals(listOf(false to true, true to false), attempts)
                assertFalse(FrameQcomBridge.isQcomUnavailable())
            } finally {
                FrameQcomBridge.resetQcomUnavailableForTest()
            }
        }

        // FrameQcomEgl presentation time tracker handles unsupported extension and rejections non-fatally
        run {
            assertTrue(FrameQcomEgl.supportsPresentationTime("EGL_KHR_image EGL_ANDROID_presentation_time EGL_KHR_fence_sync"))
            assertFalse(FrameQcomEgl.supportsPresentationTime("EGL_KHR_image EGL_KHR_fence_sync"))
            assertTrue(FrameQcomEgl.supportsPresentationTime(null))

            val unsupported = FrameQcomEgl.PresentationTimeTracker()
            unsupported.configure("EGL_KHR_image")
            var invoked = false
            assertFalse(unsupported.apply { invoked = true; true })
            assertFalse(invoked)
            assertEquals(1L, unsupported.rejectedCount)

            val supported = FrameQcomEgl.PresentationTimeTracker()
            supported.configure("EGL_ANDROID_presentation_time")
            assertTrue(supported.apply { true })
            assertEquals(0L, supported.rejectedCount)
            assertFalse(supported.apply { false })
            assertEquals(1L, supported.rejectedCount)
            assertFalse(supported.apply { throw IllegalStateException("surface abandoned") })
            assertEquals(2L, supported.rejectedCount)
        }
    }
}
