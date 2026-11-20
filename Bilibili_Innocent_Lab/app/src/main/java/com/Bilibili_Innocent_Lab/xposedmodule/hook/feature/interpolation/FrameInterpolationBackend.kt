package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing.FrameNativeLoader
import io.github.jichuo1.meguviz.media.MediaNative
import io.github.jichuo1.meguviz.media.AndroidMediaSession
import io.github.jichuo1.meguviz.memory.android.ManagedMemoryDomain
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/** Optional backend only; no model is bundled and no player is intercepted by this class. */
internal class FrameInterpolationBackend private constructor(private var handle: Long, private val maxPixels: Long) : AutoCloseable {
    enum class Backend(val nativeValue: Int) { CPU_RESEARCH(0), VULKAN(1) }
    enum class Status(val nativeValue: Int) {
        OK(0), INVALID_ARGUMENT(1), UNSUPPORTED_BACKEND(2), GPU_UNAVAILABLE(3), MODEL_TOO_LARGE(4),
        INVALID_MODEL(5), MODEL_LOAD_FAILED(6), NOT_LOADED(7), BUSY(8), ALLOCATION_FAILED(9),
        INFERENCE_FAILED(10), INVALID_OUTPUT(11), NATIVE_UNAVAILABLE(-1);

        companion object {
            fun fromNative(value: Int): Status = entries.firstOrNull { it.nativeValue == value } ?: INFERENCE_FAILED
        }
    }

    data class OpenResult(val status: Status, val engine: FrameInterpolationBackend? = null)

    // 预热登记只属于这个原生引擎，旧队列收尾不影响重新创建的引擎。
    private val warmSizes = ConcurrentHashMap.newKeySet<Pair<Int, Int>>()
    fun claimWarmup(size: Pair<Int, Int>): Boolean = warmSizes.add(size)
    fun finishWarmup(size: Pair<Int, Int>, status: Status) {
        if (status != Status.OK) warmSizes.remove(size)
    }

    /** The caller owns buffers and serializes their mutation on a bounded background worker. */
    @Synchronized fun process(
        left: ByteBuffer, right: ByteBuffer, output: ByteBuffer,
        width: Int, height: Int, timestep: Float,
        leftStride: Int = width * 3, rightStride: Int = width * 3, outputStride: Int = width * 3,
        /** 帧 ID ≥ 0 时引擎复用该帧已上传的显存张量；同一 ID 的像素在其存续期内必须不变。 */
        leftId: Long = -1L, rightId: Long = -1L,
        /** 自适应光流模式下本次的光流比例 4/6/8；0 按像素数自动选择。 */
        flowScale: Int = 0
    ): Status {
        requireWorker()
        if (handle == 0L) return Status.NOT_LOADED
        if (!timestep.isFinite() || timestep !in 0f..1f || output.isReadOnly ||
            !planeValid(left, width, height, leftStride, maxPixels) || !planeValid(right, width, height, rightStride, maxPixels) ||
            !planeValid(output, width, height, outputStride, maxPixels) || flowScale !in setOf(0, 4, 6, 8)) return Status.INVALID_ARGUMENT
        return Status.fromNative(
            MediaNative.frameProcess(handle, left, right, output, width, height,
                leftStride, rightStride, outputStride, timestep, leftId, rightId, flowScale)
        )
    }

    /** 只在预热和能力分档读取回执。端点复制或实际光流档不符均不能证明目标档有足够吞吐。 */
    @Synchronized fun probeInterpolation(left: ByteBuffer, right: ByteBuffer, output: ByteBuffer,
                                        width: Int, height: Int, flowScale: Int,
                                        leftId: Long = -1L, rightId: Long = -1L): Status {
        val status = process(left, right, output, width, height, 0.5f,
            leftId = leftId, rightId = rightId, flowScale = flowScale)
        if (status != Status.OK) return status
        return if (MediaNative.frameLastFrameKind(handle) == FRAME_INFERRED &&
            MediaNative.frameLastEffectiveFlowScale(handle) == flowScale) Status.OK else Status.INVALID_OUTPUT
    }

    /** 最近一次插帧中运动置信度不足的像素比例（千分比，抽样）；用于把转场帧换成最近原帧。 */
    @Synchronized fun unreliablePermille(): Int =
        if (handle == 0L) 0 else MediaNative.frameLastUnreliablePermille(handle)

    /** 最近一次插帧两帧颜色分布差异（千分比）：切镜明显，摇镜很小。 */
    @Synchronized fun histogramPermille(): Int = if (handle == 0L) 0 else MediaNative.frameLastHistogramPermille(handle)

    /** 最近一次插帧中因局部转场换成最近原帧的分块数（16×9 网格）。 */
    @Synchronized fun patchedTiles(): Int = if (handle == 0L) 0 else MediaNative.frameLastPatchedTiles(handle)

    /** 运动置信度保护默认开启；仅供对照测试关闭。 */
    @Synchronized fun setMotionGuard(enabled: Boolean) {
        if (handle != 0L) MediaNative.frameSetMotionGuard(handle, enabled)
    }

    /** Last successful interpolation stage durations in ns: preprocess, inference (incl. transfers), postprocess. */
    @Synchronized fun stageNanos(): LongArray =
        if (handle == 0L) LongArray(3) else MediaNative.frameLastStageNanos(handle)

    /** 当前计划与最近帧的实际执行选项，供离线画质对照读取。 */
    @Synchronized fun executionInfo(): String? = if (handle == 0L) null else MediaNative.frameExecutionInfo(handle)

    @Synchronized fun deviceLost(): Boolean =
        handle != 0L && MediaNative.frameDeviceLossStatus(handle) == MGC_DEVICE_LOST

    /** Creates a model-backed RGBA8 AHB session on the calling EGL owner. */
    @Synchronized fun openWindowSession(width: Int, height: Int, sourceWidth: Int, sourceHeight: Int, path: Int): AndroidMediaSession {
        requireWorker()
        check(handle != 0L && width.toLong() * height <= maxPixels)
        return AndroidMediaSession(handle, width, height, sourceWidth, sourceHeight, 8, path, false, null)
    }

    /** CPU time of Media's own inference worker for the last completed request, when observed. */
    @Synchronized fun nativeWorkerCpuNanos(): Long? =
        if (handle == 0L) null else MediaNative.frameLastWorkerCpuNanos(handle).takeIf { it >= 0L }

    /** Schedule on the engine worker after revoking presentation epochs. */
    @Synchronized override fun close() {
        requireWorker()
        val previous = handle
        handle = 0
        if (previous != 0L) MediaNative.frameClose(previous)
    }

    companion object {
        const val MAX_PIXELS = 1920L * 1080L
        private const val FRAME_INFERRED = 1
        private const val MGC_DEVICE_LOST = -11

        /**
         * Research caller supplies the exact reviewed files. No network, download or model discovery.
         * MeguvizMedia selects the supported fp16 precision. flowScale 2, 4 or 8 estimates motion at 1/flowScale
         * resolution and warps at full resolution; gpuIo converts RGB8 on the GPU. flowScale > 1 and gpuIo
         * are Vulkan only. All are research options, off by default.
         */
        fun openResearchModel(directory: File, backend: Backend, threads: Int = 1,
                              fp16: Boolean = false, flowScale: Int = 1, gpuIo: Boolean = false,
                              protectStaticDetails: Boolean = false, maxPixels: Long = MAX_PIXELS,
                              fusedWarp: Boolean = gpuIo && flowScale > 1,
                              /** Research opt-in: a small 4K latency gain has higher measured CPU/PSS cost. */
                              rawGpuInput: Boolean = false,
                              /** flowScale 4 下，像素数超过 1920×1080 的帧改用 1/8 光流（光流输入约 480×270）。 */
                              adaptiveFlow: Boolean = false): OpenResult {
            requireWorker()
            if (threads !in 1..4 || flowScale !in setOf(1, 2, 4, 8) || protectStaticDetails && !gpuIo ||
                maxPixels !in 1L..(4096L * 2160L) || fusedWarp && (!gpuIo || flowScale == 1) || rawGpuInput && !fusedWarp || adaptiveFlow && flowScale != 4)
                return OpenResult(Status.INVALID_ARGUMENT)
            if (!FrameInterpolationNative.available) return OpenResult(Status.NATIVE_UNAVAILABLE)
            val flags = (if (fp16) 1 else 0) or (if (flowScale == 2 || flowScale == 8) 2 else 0) or (if (flowScale == 4 || flowScale == 8) 4 else 0) or
                (if (gpuIo) 8 else 0) or (if (protectStaticDetails) 16 else 0) or (if (fusedWarp) 32 else 0) or
                (if (rawGpuInput) 64 else 0) or (if (adaptiveFlow) 128 else 0)
            var opened = OpenResult(Status.INVALID_MODEL)
            try {
                val root = directory.canonicalFile
                require(root.isDirectory)
                val paramFile = modelFile(root, FrameModelSpec.PARAM)
                val weightsFile = modelFile(root, FrameModelSpec.WEIGHTS)
                val weightsOffset = FramePreparationMemory.aligned(FrameModelSpec.PARAM.size)
                FramePreparationMemory.withStorage(weightsOffset + FrameModelSpec.WEIGHTS.size) { storage ->
                    val param = FramePreparationMemory.region(storage, 0, FrameModelSpec.PARAM.size)
                    val weights = FramePreparationMemory.region(storage, weightsOffset, FrameModelSpec.WEIGHTS.size)
                    readVerified(paramFile, FrameModelSpec.PARAM, param)
                    readVerified(weightsFile, FrameModelSpec.WEIGHTS, weights)
                    opened = openVerifiedModel(backend, threads, maxPixels, flags, param, weights)
                }
            } catch (failure: Throwable) {
                // Lease/owner release can fail after frameLoad has succeeded.
                runCatching { opened.engine?.close() }
                return OpenResult(when (failure) {
                    is OutOfMemoryError, is ManagedMemoryDomain.ManagedException -> Status.ALLOCATION_FAILED
                    is LinkageError -> Status.NATIVE_UNAVAILABLE
                    else -> Status.INVALID_MODEL
                })
            }
            return opened
        }

        private fun openVerifiedModel(backend: Backend, threads: Int, maxPixels: Long, flags: Int,
                                      param: ByteBuffer, weights: ByteBuffer): OpenResult {
            val created = runCatching {
                MediaNative.frameCreate(backend.nativeValue, 0, threads, maxPixels)
            }
                .getOrElse { return OpenResult(Status.NATIVE_UNAVAILABLE) }
            if (created.size != 2 || created[0] != 0L || created[1] <= 0L)
                return OpenResult(if (created.firstOrNull() == 0L) Status.ALLOCATION_FAILED
                    else Status.fromNative(created.firstOrNull()?.toInt() ?: -1))
            val handle = created[1]
            if (flags != 0) {
                val configured = runCatching {
                    Status.fromNative(MediaNative.frameConfigure(handle, flags))
                }.getOrElse { Status.NATIVE_UNAVAILABLE }
                if (configured != Status.OK) {
                    runCatching { MediaNative.frameClose(handle) }
                    return OpenResult(configured)
                }
            }
            val loaded = runCatching {
                Status.fromNative(MediaNative.frameLoad(handle, param, weights))
            }.getOrElse { Status.MODEL_LOAD_FAILED }
            if (loaded != Status.OK) {
                runCatching { MediaNative.frameClose(handle) }
                return OpenResult(loaded)
            }
            return try {
                OpenResult(Status.OK, FrameInterpolationBackend(handle, maxPixels))
            } catch (failure: Throwable) {
                // Transfer ownership only after both JVM owner/result allocations succeed.
                runCatching { MediaNative.frameClose(handle) }
                if (failure is OutOfMemoryError) OpenResult(Status.ALLOCATION_FAILED) else throw failure
            }
        }

        private fun modelFile(root: File, part: FrameModelSpec.Part): File =
            File(root, part.fileName).canonicalFile.also { file ->
                require(file.parentFile == root && file.isFile && file.length() == part.size.toLong())
            }

        private fun readVerified(file: File, part: FrameModelSpec.Part, buffer: ByteBuffer) {
            require(file.inputStream().use { input ->
                FrameModelSpec.matches(part, input) { bytes, count -> buffer.put(bytes, 0, count) }
            })
            buffer.flip()
        }

        private fun planeValid(buffer: ByteBuffer, width: Int, height: Int, stride: Int, maxPixels: Long): Boolean {
            if (!buffer.isDirect || buffer.position() != 0 || width <= 0 || height <= 0 ||
                width.toLong() * height > maxPixels || stride.toLong() < width.toLong() * 3) return false
            return (height - 1L) * stride + width.toLong() * 3 <= buffer.limit().toLong()
        }

        private fun requireWorker() {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Frame inference requires a background worker" }
        }
    }
}

internal object FrameInterpolationNative {
    val status: FrameNativeLoader.Status get() = FrameNativeLoader.status()
    val available: Boolean get() = status == FrameNativeLoader.Status.READY

    /** Supply framework module APK + actual host codeCacheDir on a bounded background worker. */
    fun prepare(moduleApk: File, hostCodeCache: File): FrameNativeLoader.Status =
        FrameNativeLoader.prepare(moduleApk, hostCodeCache)
}
