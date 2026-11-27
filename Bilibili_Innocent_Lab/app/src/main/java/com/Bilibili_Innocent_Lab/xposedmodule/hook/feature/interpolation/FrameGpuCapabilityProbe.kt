package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing.FrameNativeLoader

/** Bounded GPU capability snapshot derived from headless Vulkan physical-device queries. */
internal enum class GpuArchitecture(val vendorId: Int) {
    QUALCOMM_ADRENO(0x5143),
    ARM_MALI(0x13B5),
    SAMSUNG_XCLIPSE_RDNA(0x1002),
    IMAGINATION_POWERVR(0x1010),
    HUAWEI_MALEOON(0x19E5),
    GENERIC_VULKAN(0),
    UNAVAILABLE(0);

    companion object {
        private const val SAMSUNG_VENDOR_ID = 0x144D

        fun fromVendorId(vendorId: Int, vulkanCompute: Boolean): GpuArchitecture = when {
            !vulkanCompute -> UNAVAILABLE
            vendorId == QUALCOMM_ADRENO.vendorId -> QUALCOMM_ADRENO
            vendorId == ARM_MALI.vendorId -> ARM_MALI
            vendorId == SAMSUNG_XCLIPSE_RDNA.vendorId || vendorId == SAMSUNG_VENDOR_ID -> SAMSUNG_XCLIPSE_RDNA
            vendorId == IMAGINATION_POWERVR.vendorId -> IMAGINATION_POWERVR
            vendorId == HUAWEI_MALEOON.vendorId -> HUAWEI_MALEOON
            else -> GENERIC_VULKAN
        }
    }
}

internal data class FrameGpuCapabilities(
    val vulkanAvailable: Boolean,
    val vulkanCompute: Boolean,
    val qcomBlockMatch: Boolean,
    val qcomLibraryReady: Boolean,
    val qcomAhbZeroCopy: Boolean,
    val vulkanAhbZeroCopy: Boolean,
    val vulkanSyncFd: Boolean,
    val fp16: Boolean,
    val subgroupOps: Boolean,
    val subgroupSize: Int,
    val cooperativeMatrix: Boolean,
    val integerDotProduct: Boolean,
    val architecture: GpuArchitecture,
    val supportedRuntimeAbi: Boolean
) {
    val qcomMotionSupported: Boolean
        get() = qcomBlockMatch && qcomLibraryReady && supportedRuntimeAbi

    val rifeVulkanSupported: Boolean
        get() = vulkanCompute && supportedRuntimeAbi
}

internal object FrameGpuCapabilityProbe {
    const val FLAG_VULKAN_AVAILABLE = 1L shl 0
    const val FLAG_VULKAN_COMPUTE = 1L shl 1
    const val FLAG_QCOM_BLOCK_MATCH = 1L shl 2
    const val FLAG_QCOM_AHB = 1L shl 3
    const val FLAG_VULKAN_AHB = 1L shl 4
    const val FLAG_VULKAN_SYNC_FD = 1L shl 5
    const val FLAG_SHADER_FP16 = 1L shl 6
    const val FLAG_SUBGROUP_OPS = 1L shl 7
    const val FLAG_COOP_MATRIX = 1L shl 8
    const val FLAG_DOT_PRODUCT = 1L shl 9

    @Volatile private var cachedResult: FrameGpuCapabilities? = null

    fun cached(): FrameGpuCapabilities? = cachedResult

    /** Runs native Vulkan physical-device enumeration on a background worker and caches the result. */
    fun probe(): FrameGpuCapabilities {
        cachedResult?.let { return it }
        check(Looper.myLooper() != Looper.getMainLooper()) { "GPU capability probe requires a background worker" }
        synchronized(this) {
            cachedResult?.let { return it }
            val raw = FrameWindowGeometry.rawGpuCapabilities()
            val runtimeAbiSupported = FrameInterpolationNative.status != FrameNativeLoader.Status.UNSUPPORTED_ABI
            val qcomLibraryReady = runtimeAbiSupported &&
                (raw and FLAG_QCOM_BLOCK_MATCH) != 0L &&
                probeQcomMotionLibrary()
            return unpack(raw, qcomLibraryReady = qcomLibraryReady, supportedRuntimeAbi = runtimeAbiSupported)
                .also { cachedResult = it }
        }
    }

    fun unpack(
        raw: Long,
        qcomLibraryReady: Boolean,
        supportedRuntimeAbi: Boolean
    ): FrameGpuCapabilities {
        val vulkanAvailable = (raw and FLAG_VULKAN_AVAILABLE) != 0L
        val vulkanCompute = (raw and FLAG_VULKAN_COMPUTE) != 0L
        val qcomBlockMatch = vulkanCompute && (raw and FLAG_QCOM_BLOCK_MATCH) != 0L
        val subgroupSize = ((raw ushr 16) and 0xFFL).toInt()
        val vendorId = (raw ushr 32).toInt()
        return FrameGpuCapabilities(
            vulkanAvailable = vulkanAvailable,
            vulkanCompute = vulkanCompute,
            qcomBlockMatch = qcomBlockMatch,
            qcomLibraryReady = qcomLibraryReady,
            qcomAhbZeroCopy = qcomBlockMatch && (raw and FLAG_QCOM_AHB) != 0L,
            vulkanAhbZeroCopy = vulkanCompute && (raw and FLAG_VULKAN_AHB) != 0L,
            vulkanSyncFd = vulkanCompute && (raw and FLAG_VULKAN_SYNC_FD) != 0L,
            fp16 = vulkanCompute && (raw and FLAG_SHADER_FP16) != 0L,
            subgroupOps = vulkanCompute && (raw and FLAG_SUBGROUP_OPS) != 0L,
            subgroupSize = subgroupSize,
            cooperativeMatrix = vulkanCompute && (raw and FLAG_COOP_MATRIX) != 0L,
            integerDotProduct = vulkanCompute && (raw and FLAG_DOT_PRODUCT) != 0L,
            architecture = GpuArchitecture.fromVendorId(vendorId, vulkanCompute),
            supportedRuntimeAbi = supportedRuntimeAbi
        )
    }

    private fun probeQcomMotionLibrary(): Boolean = probeQcomMotionLibraryWith {
        Class.forName("io.github.jichuo1.meguviz.media.GlesMotionWarp")
        // Verify QcomMotionNative actually initializes in its classloader namespace (internal System.loadLibrary);
        // ModuleNativeLoader.load(...) alone can succeed via APK path fallback and cause a false positive.
        val qcomNative = Class.forName(
            "io.github.jichuo1.meguviz.media.QcomMotionNative",
            true,
            FrameGpuCapabilityProbe::class.java.classLoader
        )
        qcomNative.getDeclaredMethod("ensureLoaded").apply { isAccessible = true }.invoke(null)
    }

    internal fun probeQcomMotionLibraryWith(loader: () -> Unit): Boolean = try {
        loader()
        true
    } catch (_: LinkageError) {
        false
    } catch (_: Throwable) {
        false
    }
}
