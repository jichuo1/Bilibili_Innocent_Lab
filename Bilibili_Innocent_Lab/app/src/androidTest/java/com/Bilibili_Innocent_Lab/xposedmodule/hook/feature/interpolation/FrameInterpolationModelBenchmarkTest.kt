package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing.FrameNativeLoader
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import android.os.Build
import android.os.Debug
import android.os.PowerManager

/**
 * 真实 RIFE v4.6 推理时延基准，用于 INFERENCE_WITHIN_BUDGET 条件。模型不随包、不入库：
 * 只有经 run-as 手动放入目标包私有 files/rife-v4.6/ 时运行，否则跳过。
 */
@RunWith(AndroidJUnit4::class)
class FrameInterpolationModelBenchmarkTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val modelDir: File? get() = context.filesDir.resolve("rife-v4.6")

    private fun open(backend: FrameInterpolationBackend.Backend, threads: Int,
                     fp16: Boolean = false, flowScale: Int = 1, gpuIo: Boolean = false,
                     protectStaticDetails: Boolean = false, maxPixels: Long = 1920L * 1080L,
                     fusedWarp: Boolean = gpuIo && flowScale > 1, rawGpuInput: Boolean = false): FrameInterpolationBackend {
        val dir = modelDir
        assumeTrue("model not pushed", dir != null && File(dir, "flownet.bin").isFile)
        assertEquals(FrameNativeLoader.Status.READY,
            FrameInterpolationNative.prepare(File(context.applicationInfo.sourceDir), context.codeCacheDir))
        val opened = FrameInterpolationBackend.openResearchModel(dir!!, backend, threads, fp16, flowScale, gpuIo,
            protectStaticDetails, maxPixels, fusedWarp, rawGpuInput)
        assertEquals("open $backend", FrameInterpolationBackend.Status.OK, opened.status)
        return opened.engine!!
    }

    /** 斜向渐变 + 方块，右帧整体平移 shift 像素，给光流真实运动。 */
    private fun frame(width: Int, height: Int, shift: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(width * height * 3)
        for (y in 0 until height) for (x in 0 until width) {
            val sx = x - shift
            val box = (sx / 64 + y / 64) % 2 == 0
            buffer.put(((sx * 255 / width) and 0xff).toByte())
            buffer.put(((y * 255 / height) and 0xff).toByte())
            buffer.put((if (box) 220 else 30).toByte())
        }
        buffer.flip()
        return buffer
    }

    private class Measured(val times: List<Double>, val stagesMs: List<Double>, val output: ByteArray)

    private fun measure(engine: FrameInterpolationBackend, width: Int, height: Int, runs: Int): Measured {
        val left = frame(width, height, 0)
        val right = frame(width, height, 16)
        val out = ByteBuffer.allocateDirect(width * height * 3)
        val stages = DoubleArray(3)
        repeat(2) { assertEquals(FrameInterpolationBackend.Status.OK, engine.process(left, right, out, width, height, 0.5f)) }
        val times = List(runs) {
            val began = System.nanoTime()
            val status = engine.process(left, right, out, width, height, 0.5f)
            val ms = (System.nanoTime() - began) / 1e6
            assertEquals(FrameInterpolationBackend.Status.OK, status)
            engine.stageNanos().forEachIndexed { i, ns -> stages[i] += ns / 1e6 / runs }
            ms
        }.sorted()
        // 中间帧应介于两输入之间：不能等于任一输入。
        assertFalse(out == left)
        assertFalse(out == right)
        return Measured(times, stages.toList(), ByteArray(out.capacity()).also { out.duplicate().get(it) })
    }

    private fun psnr(reference: ByteArray, candidate: ByteArray): Double {
        var sum = 0.0
        for (i in reference.indices) { val d = (reference[i].toInt() and 0xff) - (candidate[i].toInt() and 0xff); sum += d * d }
        val mse = sum / reference.size
        return if (mse == 0.0) Double.POSITIVE_INFINITY else 10 * kotlin.math.log10(255.0 * 255.0 / mse)
    }

    private fun report(label: String, measured: Measured, reference: Measured? = null) {
        val stages = measured.stagesMs.joinToString("/") { "%.1f".format(it) }
        val quality = reference?.let { " psnr-vs-fp32-full=%.1fdB".format(psnr(it.output, measured.output)) } ?: ""
        report("$label stages(prep/infer/post)=${stages}ms$quality", measured.times)
    }

    private fun report(label: String, times: List<Double>) {
        val median = times[times.size / 2]
        val p90 = times[(times.size * 9 / 10).coerceAtMost(times.size - 1)]
        val fits = listOf(30, 60, 90, 120).filter { p90 <= 1000.0 / it }
        val line = "$label median=%.1fms p90=%.1fms min=%.1fms within-period-at=%s".format(median, p90, times.first(), fits)
        Log.i(TAG, line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("bench", line) })
    }

    private data class Variant(val fp16: Boolean, val flowScale: Int, val gpuIo: Boolean) {
        override fun toString() =
            "${if (fp16) "fp16" else "fp32"} flow-1/$flowScale ${if (gpuIo) "gpu-io" else "cpu-io"}"
    }

    /** 精度 × 光流分辨率 × RGB8 转换位置；质量以同分辨率 fp32 全分辨率 CPU 转换输出为参照。 */
    @Test fun vulkanInferenceLatencyAcrossResolutions() {
        val sizes = listOf(640 to 360, 960 to 540, 1280 to 720, 1920 to 1080)
        val reference = HashMap<Int, Measured>()
        val variants = listOf(Variant(false, 1, false), Variant(true, 1, true), Variant(true, 2, false),
            Variant(true, 2, true), Variant(true, 4, false), Variant(true, 4, true))
        for (variant in variants) {
            val engine = open(FrameInterpolationBackend.Backend.VULKAN, 1, variant.fp16, variant.flowScale, variant.gpuIo)
            try {
                for ((w, h) in sizes) {
                    val measured = measure(engine, w, h, if (w >= 1280) 8 else 12)
                    val label = "VULKAN $variant ${w}x$h"
                    if (variant == variants.first()) { reference[w] = measured; report(label, measured) }
                    else report(label, measured, reference[w])
                }
            } finally { engine.close() }
        }
    }

    /** 30→60 的持续负载：每 33.3 ms 一次 1080p 插帧共 60 s，按 10 s 窗口报告时延与温控状态。 */
    @Test fun sustained1080pAtThirtyInterpolationsPerSecond() {
        val engine = open(FrameInterpolationBackend.Backend.VULKAN, 1, fp16 = true, flowScale = 4, gpuIo = true)
        val power = context.getSystemService(android.os.PowerManager::class.java)
        try {
            val (w, h) = 1920 to 1080
            val left = frame(w, h, 0)
            val right = frame(w, h, 16)
            val out = ByteBuffer.allocateDirect(w * h * 3)
            repeat(3) { engine.process(left, right, out, w, h, 0.5f) }
            val periodNs = 1_000_000_000L / 30
            val start = System.nanoTime()
            var missed = 0
            for (window in 0 until 6) {
                val times = ArrayList<Double>()
                for (i in 0 until 300) {
                    val due = start + (window * 300L + i) * periodNs
                    while (System.nanoTime() < due) Thread.sleep(0, 200_000)
                    val began = System.nanoTime()
                    assertEquals(FrameInterpolationBackend.Status.OK, engine.process(left, right, out, w, h, 0.5f))
                    val ms = (System.nanoTime() - began) / 1e6
                    if (ms > periodNs / 1e6) missed++
                    times += ms
                }
                times.sort()
                report("SUSTAINED 1080p fp16 flow-1/4 gpu-io t=${(window + 1) * 10}s thermal=${power.currentThermalStatus}", times)
            }
            report("SUSTAINED 1080p summary missed-30fps-slots=$missed/1800", listOf(missed.toDouble()))
        } finally { engine.close() }
    }

    /** 滑动帧序列 (i, i+1)：按帧 ID 复用显存张量应与不缓存时逐字节一致，并减少每次插帧的耗时。 */
    @Test fun frameCacheIsIdenticalAndFaster() {
        val engine = open(FrameInterpolationBackend.Backend.VULKAN, 1, fp16 = true, flowScale = 4, gpuIo = true)
        try {
            val (w, h) = 1280 to 720
            val frames = List(12) { frame(w, h, it * 16) }
            val cachedOut = ByteBuffer.allocateDirect(w * h * 3)
            val plainOut = ByteBuffer.allocateDirect(w * h * 3)
            repeat(2) { engine.process(frames[0], frames[1], plainOut, w, h, 0.5f) }
            val plain = ArrayList<Double>()
            val cached = ArrayList<Double>()
            for (round in 0 until 3) for (i in 0 until frames.size - 1) {
                var began = System.nanoTime()
                assertEquals(FrameInterpolationBackend.Status.OK, engine.process(frames[i], frames[i + 1], plainOut, w, h, 0.5f))
                plain += (System.nanoTime() - began) / 1e6
                val base = round * 100L
                began = System.nanoTime()
                assertEquals(FrameInterpolationBackend.Status.OK, engine.process(frames[i], frames[i + 1], cachedOut, w, h, 0.5f,
                    leftId = base + i, rightId = base + i + 1))
                if (i > 0) cached += (System.nanoTime() - began) / 1e6
                assertTrue("cached output differs at $round/$i", plainOut == cachedOut)
            }
            plain.sort(); cached.sort()
            report("CACHE 720p fp16 flow-1/4 gpu-io uncached", plain)
            report("CACHE 720p fp16 flow-1/4 gpu-io cached", cached)
        } finally { engine.close() }
    }

    @Test fun cpuInferenceLatencyAtLowResolution() {
        val engine = open(FrameInterpolationBackend.Backend.CPU_RESEARCH, 4)
        try {
            for ((w, h) in listOf(640 to 360, 960 to 540)) report("CPU4 ${w}x$h", measure(engine, w, h, 4))
        } finally { engine.close() }
    }

    /** RGB24 loads cross uint and row boundaries; a cached image must also retain its source stride. */
    @Test fun fusedRgbCachePreservesPaddedRows() {
        val w=641; val h=361; val row=w*3
        val fp16 = InstrumentationRegistry.getArguments().getString("fusionFp16", "true").toBooleanStrict()
        val left=frame(w,h,0); val right=frame(w,h,8)
        val phases=listOf(.25f,.5f,.75f)
        fun compactPixels(engine: FrameInterpolationBackend): Map<Float, ByteArray> =
            phases.associateWith { alpha ->
                val out=ByteBuffer.allocateDirect(row*h)
                assertEquals(FrameInterpolationBackend.Status.OK,engine.process(left,right,out,w,h,alpha))
                ByteArray(row*h).also { out.duplicate().get(it) }
            }
        val compareRawModes = InstrumentationRegistry.getArguments().getString("fusionCompareRawModes", "false").toBooleanStrict()
        var standardPixels: Map<Float, ByteArray>? = null
        fun padded(input:ByteBuffer,stride:Int):ByteBuffer = ByteBuffer.allocateDirect(stride*h).also { output ->
            for(y in 0 until h) for(x in 0 until stride)
                output.put(y*stride+x,if(x<row) input.get(y*row+x) else 0x5a.toByte())
        }
        for(rawGpuInput in listOf(false,true)) {
            val engine=open(FrameInterpolationBackend.Backend.VULKAN,1,fp16,4,true,true,rawGpuInput=rawGpuInput)
            try {
                // 行布局只与同一着色器的无缓存紧凑输入比较。显式参数可重跑普通与 raw 着色器的严格对照。
                val references = if (rawGpuInput && compareRawModes) checkNotNull(standardPixels) else compactPixels(engine)
                if (!rawGpuInput && compareRawModes) standardPixels = references
                for((lp,rp) in listOf(0 to 0,5 to 9,11 to 1)) for(alpha in phases) {
                    val output=padded(left,row+7)
                    assertEquals(FrameInterpolationBackend.Status.OK,engine.process(padded(left,row+lp),padded(right,row+rp),
                        output,w,h,alpha,row+lp,row+rp,row+7,leftId=71,rightId=72))
                    val actual=ByteArray(row*h)
                    var maximum=0
                    for(y in 0 until h) {
                        for(x in 0 until row) {
                            val index=y*row+x
                            actual[index]=output.get(y*(row+7)+x)
                            maximum=maxOf(maximum,kotlin.math.abs((actual[index].toInt() and 255)-
                                (references.getValue(alpha)[index].toInt() and 255)))
                        }
                        for(x in row until row+7) assertEquals(0x5a.toByte(),output.get(y*(row+7)+x))
                    }
                    assertTrue("RGB24 fp16=$fp16 raw=$rawGpuInput stride $lp/$rp alpha=$alpha max=$maximum",maximum<=1)
                    assertTrue(psnr(references.getValue(alpha),actual)>=60.0)
                }
            } finally { engine.close() }
        }
    }

    /** 固定棋盘向右平移 8 像素，1/4、1/2、3/4 槽位的整数位移真值分别为 2、4、6 像素。 */
    @Test fun fusedFp16QualityAgainstReferenceAndTranslation() {
        val width = 641
        val height = 361
        val phases = listOf(.25f, .5f, .75f)
        val left = frame(width, height, 0)
        val right = frame(width, height, 8)
        val truth = phases.associateWith { phase ->
            ByteArray(width * height * 3).also { frame(width, height, (8 * phase).toInt()).get(it) }
        }
        val reference = mutableMapOf<Float, ByteArray>()
        data class Variant(val name: String, val fp16: Boolean, val fused: Boolean, val guard: Boolean, val raw: Boolean = false)
        val variants = listOf(
            Variant("fp32-separate", false, false, false),
            Variant("fp16-separate", true, false, false),
            Variant("fp16-fused", true, true, false),
            Variant("fp16-fused-protected", true, true, true),
            Variant("fp16-fused-raw-protected", true, true, true, raw = true)
        )
        fun errors(expected: ByteArray, actual: ByteArray, inset: Int): JSONObject {
            var absolute = 0L
            var squared = 0L
            var maximum = 0
            for (y in inset until height - inset) {
                val start = 3 * (y * width + inset)
                val end = 3 * ((y + 1) * width - inset)
                for (offset in start until end) {
                    val diff = kotlin.math.abs((actual[offset].toInt() and 255) - (expected[offset].toInt() and 255))
                    absolute += diff
                    squared += diff * diff
                    maximum = maxOf(maximum, diff)
                }
            }
            val samples = 3L * (width - 2 * inset) * (height - 2 * inset)
            return JSONObject().put("mae", absolute.toDouble() / samples).put("max", maximum)
                .put("psnrDb", if (squared == 0L) JSONObject.NULL else 10 * kotlin.math.log10(65025.0 * samples / squared))
        }
        for (variant in variants) {
            open(FrameInterpolationBackend.Backend.VULKAN, 1, variant.fp16, 4, true, true,
                fusedWarp = variant.fused, rawGpuInput = variant.raw).use { engine ->
                engine.setMotionGuard(variant.guard)
                val output = ByteBuffer.allocateDirect(width * height * 3)
                for (phase in phases) {
                    assertEquals(FrameInterpolationBackend.Status.OK,
                        engine.process(left, right, output, width, height, phase))
                    val bytes = ByteArray(output.capacity()).also { output.duplicate().get(it) }
                    if (!variant.fp16) reference[phase] = bytes
                    val execution = JSONObject(checkNotNull(engine.executionInfo()))
                    assertEquals(1, execution.getInt("frame_kind"))
                    val resolved = reference.getValue(phase)
                    val known = truth.getValue(phase)
                    val result = JSONObject().put("variant", variant.name).put("phase", phase.toDouble())
                        .put("fp32", errors(resolved, bytes, 0)).put("fp32Center", errors(resolved, bytes, 32))
                        .put("translation", errors(known, bytes, 0)).put("translationCenter", errors(known, bytes, 32))
                        .put("patchedTiles", engine.patchedTiles()).put("unreliablePermille", engine.unreliablePermille())
                        .put("execution", execution)
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                        putString("translation", result.toString())
                    })
                }
            }
        }
    }

    /** Diagnostic, outside performance windows: distinguish Java input retention from GPU pool high water. */
    @Test fun modelMemoryRetentionAcrossResolutions() {
        val rawGpuInput=InstrumentationRegistry.getArguments().getString("rawGpuInput") == "true"
        fun reportMemory(stage:String,w:Int,h:Int) {
            val memory=Debug.MemoryInfo()
            Debug.getMemoryInfo(memory)
            val record=JSONObject().put("stage",stage).put("width",w).put("height",h)
                .put("pssKb",memory.totalPss).put("dalvikPssKb",memory.dalvikPss)
                .put("nativePssKb",memory.nativePss).put("otherPssKb",memory.otherPss)
                .put("nativeAllocatedKb",Debug.getNativeHeapAllocatedSize()/1024)
                .put("memoryStats",JSONObject(memory.memoryStats)).put("rawGpuInput",rawGpuInput)
            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {
                putString("modelMemory",record.toString())
            })
        }
        fun exercise(w:Int,h:Int) {
            val engine=open(FrameInterpolationBackend.Backend.VULKAN,1,true,4,true,true,
                maxPixels=maxOf(1920L*1080,w.toLong()*h),rawGpuInput=rawGpuInput)
            try {
                val left=frame(w,h,0);val right=frame(w,h,16)
                val output=ByteBuffer.allocateDirect(w*h*3)
                repeat(5) { assertEquals(FrameInterpolationBackend.Status.OK,
                    engine.process(left,right,output,w,h,.5f,leftId=10,rightId=11)) }
                reportMemory("active",w,h)
            } finally { engine.close() }
        }
        for((w,h) in listOf(640 to 360,1280 to 720,1920 to 1080,3840 to 2160,640 to 360)) {
            reportMemory("before",w,h)
            exercise(w,h)
            // Deliberate diagnostic GC only. Normal benchmark and playback never force GC.
            repeat(2) { System.gc();System.runFinalization();Thread.sleep(200) }
            reportMemory("closedAfterGc",w,h)
        }
    }

    /** 白色细笔画在移动背景前保持原像素；字幕更换时不得把旧字贴回。 */
    @Test fun stationaryCaptionsArePreservedWithoutLockingChangedText() {
        val (w, h) = 320 to 180
        fun caption(shift: Int, textShift: Int): ByteBuffer = frame(w, h, shift).also { b ->
            for (y in 120 until 160) for (x in 50 until 270) for (c in 0..2) {
                val ink = y in 132..145 && (x - textShift) % 12 in 0..2 && x in 70..250
                b.put((y * w + x) * 3 + c, (if (ink) 255 else 0).toByte())
            }
        }
        val left = caption(0, 0)
        val right = caption(40, 0)
        val changed = caption(40, 6)
        fun run(engine: FrameInterpolationBackend, input: ByteBuffer, alpha: Float): ByteBuffer {
            val output = ByteBuffer.allocateDirect(w * h * 3)
            assertEquals(FrameInterpolationBackend.Status.OK, engine.process(left, input, output, w, h, alpha))
            return output
        }
        // 覆盖全部验证档的子帧相位，包含 30→120 的 1/4、3/4 与 15→165 的 1/11…10/11。
        val phases = listOf(2, 3, 4, 5, 6, 11).flatMap { denominator ->
            (1 until denominator).map { it.toFloat() / denominator }
        }.distinct().sorted()
        // 先保存参考再关闭引擎，避免与进程级播放引擎一起超过两个原生实例的硬上限。
        val references = open(FrameInterpolationBackend.Backend.VULKAN, 1, true, 4, true, false).let { engine ->
            try { phases.associateWith { alpha -> run(engine, right, alpha) to run(engine, changed, alpha) } }
            finally { engine.close() }
        }
        val protectedEngine = open(FrameInterpolationBackend.Backend.VULKAN, 1, true, 4, true, true)
        try {
            for (alpha in phases) {
                val (plain, changedPlain) = references.getValue(alpha)
                val protected = run(protectedEngine, right, alpha)
                var plainError = 0L
                var protectedError = 0L
                for (y in 134..143) for (x in 74..246) if (x % 12 in 0..4 || x % 12 in 10..11) for (c in 0..2) {
                    val i = (y * w + x) * 3 + c
                    val expected = left.get(i).toInt() and 255
                    plainError += kotlin.math.abs((plain.get(i).toInt() and 255) - expected)
                    protectedError += kotlin.math.abs((protected.get(i).toInt() and 255) - expected)
                }
                assertEquals("stationary caption alpha=$alpha", 0L, protectedError)
                assertTrue("fixture must expose caption distortion alpha=$alpha", plainError > protectedError)
                val changedProtected = run(protectedEngine, changed, alpha)
                for (y in 134..143) for (x in 74..246) if (x % 12 in 0..2) for (c in 0..2) {
                    val i = (y * w + x) * 3 + c
                    assertEquals("changed text alpha=$alpha $x/$y", changedPlain.get(i), changedProtected.get(i))
                }
                val result = JSONObject().put("alpha", alpha.toDouble()).put("width", w).put("height", h)
                    .put("plainAbsoluteErrorSum", plainError).put("protectedAbsoluteErrorSum", protectedError)
                    .put("changedTextPreserved", true)
                Log.i(TAG, "CAPTION $result")
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                    putString("caption", result.toString())
                })
            }
        } finally { protectedEngine.close() }
    }

    @Test fun detailProtectionLatencyAt720p() {
        for (protect in listOf(false, true, true, false)) {
            val engine = open(FrameInterpolationBackend.Backend.VULKAN, 1, true, 4, true, protect)
            try { report("DETAIL 720p protect=$protect", measure(engine, 1280, 720, 16)) }
            finally { engine.close() }
        }
    }

    /** Same model/scale/precision, alternating GPU implementations, including RGB packing tails. */
    @Test fun fusedWarpQualityAndLatency() {
        val scale = InstrumentationRegistry.getArguments().getString("flowScale", "4").toInt()
        require(scale == 2 || scale == 4)
        val rawGpuInput=InstrumentationRegistry.getArguments().getString("rawGpuInput") == "true"
        // FP32 两条路径使用相同精度的坐标。传 fusionFp16=true 可以重跑 FP16 的严格跨实现对照。
        val fp16=InstrumentationRegistry.getArguments().getString("fusionFp16","false").toBooleanStrict()
        val selectedWidth = InstrumentationRegistry.getArguments().getString("fusionWidth")?.toInt()
        for ((w,h) in listOf(641 to 361, 1280 to 720, 1920 to 1080, 3840 to 2160).filter { selectedWidth == null || it.first == selectedWidth }) {
            val inputs = listOf(frame(w,h,0), frame(w,h,maxOf(4,w/80)))
            val references = HashMap<Float,ByteArray>()
            for (fused in listOf(false,true,false,true)) {
                val engine = open(FrameInterpolationBackend.Backend.VULKAN,1,fp16,scale,true,true,
                    maxPixels=maxOf(1920L*1080,w.toLong()*h),fusedWarp=fused,rawGpuInput=fused && rawGpuInput)
                try {
                    // 比较 GPU 实现时，统一关闭会主动替换图像内容的运动保护。
                    engine.setMotionGuard(false)
                    val times = ArrayList<Long>()
                    val out=ByteBuffer.allocateDirect(w*h*3)
                    var worstMax=0
                    var worstPsnr=Double.POSITIVE_INFINITY
                    for (i in 0 until 15) {
                        val alpha=listOf(.25f,.5f,.75f)[i%3]
                        val start=System.nanoTime()
                        assertEquals(FrameInterpolationBackend.Status.OK,engine.process(inputs[0],inputs[1],out,w,h,alpha,
                            leftId=10,rightId=11))
                        if(i>=3)times+=System.nanoTime()-start
                        val bytes=ByteArray(out.capacity()).also{out.duplicate().get(it)}
                        if(!fused)references[alpha]=bytes
                        else {
                            val ref=references.getValue(alpha)
                            if(i==3 && InstrumentationRegistry.getArguments().getString("dumpFusion") == "true") {
                                for((name,data) in listOf("reference" to ref,"candidate" to bytes)) {
                                    val bitmap=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888)
                                    val pixels=IntArray(w*h) { p -> android.graphics.Color.rgb(data[p*3].toInt() and 255,
                                        data[p*3+1].toInt() and 255,data[p*3+2].toInt() and 255) }
                                    bitmap.setPixels(pixels,0,w,0,0,w,h)
                                    File(context.cacheDir,"fusion-$name.png").outputStream().use {
                                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                                    }
                                    bitmap.recycle()
                                }
                            }
                            var max=0
                            for(j in bytes.indices)max=maxOf(max,kotlin.math.abs((bytes[j].toInt() and 255)-(ref[j].toInt() and 255)))
                            worstMax=maxOf(worstMax,max);worstPsnr=minOf(worstPsnr,psnr(ref,bytes))
                        }
                    }
                    times.sort()
                    val record=JSONObject().put("width",w).put("height",h).put("fused",fused).put("fp16Requested",fp16)
                        .put("flowScale", scale)
                        .put("rawGpuInput",fused && rawGpuInput)
                        .put("p50Ms",times[times.size/2]/1e6).put("p90Ms",times[(times.size*9/10).coerceAtMost(times.size-1)]/1e6)
                        .put("maxByteError",worstMax).put("psnrDb",if(worstPsnr.isFinite())worstPsnr else JSONObject.NULL)
                        .put("pssKb",Debug.getPss())
                    InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("fusion",record.toString())})
                    if(fused)assertTrue("fusion quality $record",worstPsnr>=60.0 && worstMax<=1)
                } finally{engine.close()}
            }
        }
    }

    /** 全档位 × 分辨率的独立 GPU 预算测量。显式 4K 研究引擎不改变正式捕获、账本或媒体准入上限。 */
    @Test fun allRatesAndResolutionsIncluding4k() {
        val sizes = listOf(640 to 360, 854 to 480, 960 to 540, 1280 to 720, 1920 to 1080, 2560 to 1440, 3840 to 2160)
        val profiles = listOf(15 to 15, 30 to 30, 15 to 45, 25 to 50, 30 to 60, 30 to 120,
            60 to 120, 24 to 144, 15 to 165, 37 to 185)
        val power = context.getSystemService(PowerManager::class.java)
        val args = InstrumentationRegistry.getArguments()
        val rawGpuInput=args.getString("rawGpuInput") == "true"
        val requestedWidth = args.getString("matrixWidth")?.toInt()
        val count = args.getString("samples", "32").toInt().coerceIn(16, 128)
        val outputFile = context.filesDir.resolve("frame-rate-resolution-matrix.jsonl")
        var identityBase = 1_000_000L
        for ((w, h) in sizes.filter { requestedWidth == null || it.first == requestedWidth }) {
            val engine = open(FrameInterpolationBackend.Backend.VULKAN, 1, true, 4, true, true,
                maxPixels = maxOf(1920L * 1080L, w.toLong() * h),rawGpuInput=rawGpuInput)
            try {
                val inputs = List(3) { frame(w, h, it * maxOf(4, w / 80)) }
                val output = ByteBuffer.allocateDirect(w * h * 3)
                if (w.toLong() * h > 1920L * 1080L) {
                    val production = open(FrameInterpolationBackend.Backend.VULKAN, 1, true, 4, true, true)
                    try {
                        assertEquals("default pixel limit must remain closed", FrameInterpolationBackend.Status.INVALID_ARGUMENT,
                            production.process(inputs[0], inputs[1], output, w, h, .5f))
                        assertFalse("production ledger must reject oversized frames", FrameBufferLedger(3).configure(w, h))
                    } finally { production.close() }
                }
                repeat(3) { assertEquals(FrameInterpolationBackend.Status.OK, engine.process(inputs[0], inputs[1], output, w, h, .5f)) }
                for ((source, target) in profiles) {
                    val samples = ArrayList<Long>()
                    val cpu = ArrayList<Long>()
                    val nativeCpu = ArrayList<Long>()
                    val stages = LongArray(3)
                    var originals = 0
                    var slot = 0L
                    var failure: FrameInterpolationBackend.Status? = null
                    val pssStart = Debug.getPss()
                    val began = System.nanoTime()
                    while (samples.size < count) {
                        val scaled = slot * source
                        val pair = scaled / target
                        val phase = scaled % target
                        slot++
                        if (phase == 0L && target != source) { originals++; continue }
                        val alpha = phase.toFloat() / target
                        val first = inputs[(pair % 3).toInt()]
                        val second = inputs[((pair + 1) % 3).toInt()]
                        val cpuStart = Debug.threadCpuTimeNanos()
                        val start = System.nanoTime()
                        val status = engine.process(first, second, output, w, h, alpha,
                            leftId = identityBase + pair, rightId = identityBase + pair + 1)
                        samples += System.nanoTime() - start
                        cpu += Debug.threadCpuTimeNanos() - cpuStart
                        if (status != FrameInterpolationBackend.Status.OK) { failure = status; break }
                        if (phase != 0L) {
                            engine.nativeWorkerCpuNanos()?.let(nativeCpu::add)
                            engine.stageNanos().forEachIndexed { i, value -> stages[i] += value }
                        }
                    }
                    identityBase += 1_000_000L
                    samples.sort()
                    fun q(f: Double) = samples[(kotlin.math.ceil(samples.size * f).toInt() - 1).coerceIn(samples.indices)] / 1e6
                    val required = target - source
                    val avgNs = samples.average()
                    val result = JSONObject().put("width", w).put("height", h).put("sourceFps", source).put("targetFps", target)
                        .put("stage", if (required == 0) "original frame copy only" else "raw model inference; no capture/presentation")
                        .put("samples", samples.size).put("originalSlotsSkipped", originals).put("rawGpuInput",rawGpuInput)
                        .put("p50Ms", q(.5)).put("p90Ms", q(.9)).put("p99Ms", q(.99)).put("maxMs", q(1.0))
                        .put("nativeWorkerCpuSamples", nativeCpu.size)
                        .put("nativeWorkerCpuMeanMs", if (nativeCpu.isEmpty()) JSONObject.NULL else nativeCpu.average() / 1e6)
                        .put("cpuMeanMs", cpu.average() / 1e6).put("wallElapsedMs", (System.nanoTime() - began) / 1e6)
                        .put("requiredInferencesPerSecond", required).put("estimatedWorkerUtilization", avgNs * required / 1e9)
                        .put("rawP90Fits", failure == null && (required == 0 || q(.9) <= 1000.0 / required))
                        .put("status", failure?.name ?: "OK").put("pssStartKb", pssStart).put("pssEndKb", Debug.getPss())
                        .put("nativeHeapKb", Debug.getNativeHeapAllocatedSize() / 1024)
                        .put("captureSizeAdmitted", w.toLong() * h <= 1280L * 720)
                        .put("defaultBackendSizeAdmitted", w.toLong() * h <= 1920L * 1080)
                        .put("averagePrepMs", stages[0].toDouble() / samples.size / 1e6)
                        .put("averageGpuAndTransfersMs", stages[1].toDouble() / samples.size / 1e6)
                        .put("averagePostMs", stages[2].toDouble() / samples.size / 1e6)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) result.put("thermalStatus", power.currentThermalStatus)
                    outputFile.appendText(result.toString() + "\n")
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("matrix", result.toString()) })
                    assertNull("inference failed at ${w}x$h $source->$target", failure)
                    // 不能把原生推理吞吐的预算通过声称为完整管线达标；正式准入单独保留并记录。
                }
            } finally { engine.close() }
            System.gc()
        }
    }

    /**
     * 宿主处理分辨率选择依据：按宿主稳态（滚动帧对、每帧只上传一次并按 ID 复用）测 1/4 与 1/8 光流在
     * 720p～4K 的单次补帧耗时，并以同尺寸 1/4 光流为参照给出 1/8 的 PSNR。可用 -e sizes 2560x1440,3840x2160 限定。
     */
    @Test fun processingResolutionThroughput() {
        val selected = InstrumentationRegistry.getArguments().getString("sizes")?.split(',')?.map {
            val (w, h) = it.split('x'); w.toInt() to h.toInt() }
        val sizes = selected ?: listOf(1280 to 720, 1920 to 1080, 2560 to 1440, 3840 to 2160)
        for ((w, h) in sizes) {
            val frames = List(4) { frame(w, h, it * 12) }
            val outputs = HashMap<Int, ByteArray>()
            for (scale in listOf(4, 8)) {
                val engine = open(FrameInterpolationBackend.Backend.VULKAN, 1, fp16 = true, flowScale = scale, gpuIo = true,
                    protectStaticDetails = true, maxPixels = maxOf(1920L * 1080L, w.toLong() * h))
                try {
                    val out = ByteBuffer.allocateDirect(w * h * 3)
                    fun step(i: Long): Double {
                        val began = System.nanoTime()
                        assertEquals(FrameInterpolationBackend.Status.OK, engine.process(frames[(i % 4).toInt()],
                            frames[((i + 1) % 4).toInt()], out, w, h, 0.5f, leftId = i, rightId = i + 1))
                        return (System.nanoTime() - began) / 1e6
                    }
                    repeat(4) { step(it.toLong()) }
                    val times = (4L until 64L).map(::step).sorted()
                    assertEquals(FrameInterpolationBackend.Status.OK,
                        engine.process(frames[0], frames[1], out, w, h, 0.5f))
                    outputs[scale] = ByteArray(out.capacity()).also { out.duplicate().get(it) }
                    val quality = if (scale == 8) " psnr-vs-flow4=%.1fdB".format(psnr(outputs.getValue(4), outputs.getValue(8))) else ""
                    report("PROCESSING ${w}x$h fp16 flow-1/$scale cached$quality", times)
                } finally { engine.close() }
            }
        }
    }

    /**
     * 对真值的画质：右帧为整体平移 2d，理想中间帧恰为平移 d。比较 1/4、1/8 与自适应光流对真值的 PSNR，
     * 并以两帧直接平均（不做运动补偿）为下限基线；自适应须与对应固定比例逐字节一致。
     */
    /**
     * 不重复的值噪声纹理（两个八度，格距随分辨率缩放），整体水平平移 shift 像素。棋盘格有周期，
     * 平移接近半周期时运动有歧义，不能作为真值。
     */
    private fun scene(width: Int, height: Int, shift: Int, seed: Int = 0): ByteArray {
        fun hash(x: Int, y: Int, c: Int): Int {
            var v = x * 374761393 + y * 668265263 + c * 1442695041 + seed * 951274213
            v = (v xor (v ushr 13)) * 1274126177
            return (v xor (v ushr 16)) and 0xff
        }
        fun noise(fx: Double, fy: Double, c: Int): Double {
            val x0 = kotlin.math.floor(fx).toInt(); val y0 = kotlin.math.floor(fy).toInt()
            val tx = fx - x0; val ty = fy - y0
            val a = hash(x0, y0, c) * (1 - tx) + hash(x0 + 1, y0, c) * tx
            val b = hash(x0, y0 + 1, c) * (1 - tx) + hash(x0 + 1, y0 + 1, c) * tx
            return a * (1 - ty) + b * ty
        }
        val cell = width / 24.0
        val out = ByteArray(width * height * 3)
        var i = 0
        for (y in 0 until height) for (x in 0 until width) {
            val sx = x - shift
            for (c in 0 until 3) {
                val v = 0.65 * noise(sx / cell, y / cell, c) + 0.35 * noise(sx * 4 / cell, y * 4 / cell, c + 3)
                out[i++] = v.toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    @Test fun flowScaleQualityAgainstTruth() {
        val selected = InstrumentationRegistry.getArguments().getString("sizes")?.split(',')?.map {
            val (w, h) = it.split('x'); w.toInt() to h.toInt() }
        val sizes = selected ?: listOf(1280 to 720, 1920 to 1080, 2560 to 1440, 3840 to 2160)
        val engines = listOf("flow4" to Triple(4, false, false), "flow8" to Triple(8, false, false),
            "adaptive" to Triple(4, true, false))
        for ((w, h) in sizes) {
            val d = (w / 80) and 1.inv()
            val left = ByteBuffer.allocateDirect(w * h * 3).put(scene(w, h, 0)).also { it.flip() }
            val right = ByteBuffer.allocateDirect(w * h * 3).put(scene(w, h, 2 * d)).also { it.flip() }
            val truth = scene(w, h, d)
            val blend = ByteArray(w * h * 3).also { out ->
                for (i in out.indices) out[i] = (((left.get(i).toInt() and 0xff) + (right.get(i).toInt() and 0xff) + 1) / 2).toByte()
            }
            val outputs = LinkedHashMap<String, ByteArray>()
            for ((name, config) in engines) {
                val engine = FrameInterpolationBackend.openResearchModel(modelDir!!.also {
                    assumeTrue("model not pushed", File(it, "flownet.bin").isFile)
                    assertEquals(FrameNativeLoader.Status.READY,
                        FrameInterpolationNative.prepare(File(context.applicationInfo.sourceDir), context.codeCacheDir))
                }, FrameInterpolationBackend.Backend.VULKAN, 1, true, config.first, true, true,
                    maxOf(1920L * 1080L, w.toLong() * h), adaptiveFlow = config.second)
                assertEquals("open $name", FrameInterpolationBackend.Status.OK, engine.status)
                try {
                    val out = ByteBuffer.allocateDirect(w * h * 3)
                    val times = List(8) {
                        val began = System.nanoTime()
                        assertEquals(FrameInterpolationBackend.Status.OK, engine.engine!!.process(left, right, out, w, h, 0.5f))
                        (System.nanoTime() - began) / 1e6
                    }.drop(2).sorted()
                    outputs[name] = ByteArray(out.capacity()).also { out.duplicate().get(it) }
                    report("TRUTH ${w}x$h $name d=$d psnr-vs-truth=%.2fdB blend-baseline=%.2fdB".format(
                        psnr(truth, outputs.getValue(name)), psnr(truth, blend)), times)
                } finally { engine.engine!!.close() }
            }
            val expected = if (w.toLong() * h > 1920L * 1080L) "flow8" else "flow4"
            assertArrayEquals("adaptive ${w}x$h", outputs.getValue(expected), outputs.getValue("adaptive"))
        }
    }

    /**
     * 1080p 档位：同一自适应引擎逐次指定 1/4、1/6、1/8 光流。宿主稳态耗时（滚动帧、前一帧命中缓存）与
     * 对真值中间帧的 PSNR；横屏与竖屏各测一次。
     */
    @Test fun flowTiersAt1080p() {
        val model = modelDir!!
        assumeTrue("model not pushed", File(model, "flownet.bin").isFile)
        assertEquals(FrameNativeLoader.Status.READY,
            FrameInterpolationNative.prepare(File(context.applicationInfo.sourceDir), context.codeCacheDir))
        val opened = FrameInterpolationBackend.openResearchModel(model, FrameInterpolationBackend.Backend.VULKAN, 1,
            true, 4, true, true, 2560L * 1440L, adaptiveFlow = true)
        assertEquals(FrameInterpolationBackend.Status.OK, opened.status)
        val engine = opened.engine!!
        try {
            for ((w, h) in listOf(1920 to 1080, 1080 to 1920)) {
                val d = (maxOf(w, h) / 80) and 1.inv()
                val scenes = List(5) { ByteBuffer.allocateDirect(w * h * 3).put(scene(w, h, it * d)).also { b -> b.flip() } }
                val truth = scene(w, h, d)
                val out = ByteBuffer.allocateDirect(w * h * 3)
                var id = 1_000L
                for (scale in listOf(4, 6, 8)) {
                    fun step(i: Int): Double {
                        val began = System.nanoTime()
                        assertEquals(FrameInterpolationBackend.Status.OK, engine.process(scenes[i % 4], scenes[(i + 1) % 4],
                            out, w, h, 0.5f, leftId = id + i, rightId = id + i + 1, flowScale = scale))
                        return (System.nanoTime() - began) / 1e6
                    }
                    repeat(4) { step(it) }
                    val times = (4 until 64).map(::step).sorted()
                    id += 1_000L
                    assertEquals(FrameInterpolationBackend.Status.OK,
                        engine.process(scenes[0], scenes[2], out, w, h, 0.5f, flowScale = scale))
                    val quality = psnr(truth, ByteArray(out.capacity()).also { out.duplicate().get(it) })
                    report("TIER ${w}x$h flow-1/$scale psnr-vs-truth=%.2fdB".format(quality), times)
                }
            }
        } finally { engine.close() }
    }

    /**
     * 转场保护标定（1080p，1/6 光流）：保护开/关各跑一次，记录对真值的 PSNR 与不可靠像素千分比。
     * 正常运动不得误判为转场且画质不降；整屏切镜必须达到转场阈值（宿主据此换成最近原帧）。
     */
    @Test fun transitionGuard() {
        val model = modelDir!!
        assumeTrue("model not pushed", File(model, "flownet.bin").isFile)
        assertEquals(FrameNativeLoader.Status.READY,
            FrameInterpolationNative.prepare(File(context.applicationInfo.sourceDir), context.codeCacheDir))
        val opened = FrameInterpolationBackend.openResearchModel(model, FrameInterpolationBackend.Backend.VULKAN, 1,
            true, 4, true, true, 2560L * 1440L, adaptiveFlow = true)
        assertEquals(FrameInterpolationBackend.Status.OK, opened.status)
        val engine = opened.engine!!
        val (w, h) = 1920 to 1080
        val n = w * h * 3
        fun mix(a: ByteArray, b: ByteArray, t: Double) = ByteArray(n) { i ->
            ((a[i].toInt() and 0xff) * (1 - t) + (b[i].toInt() and 0xff) * t + 0.5).toInt().toByte() }
        fun brighten(a: ByteArray, amount: Int) = ByteArray(n) { i -> ((a[i].toInt() and 0xff) + amount).coerceIn(0, 255).toByte() }
        val a0 = scene(w, h, 0); val b0 = scene(w, h, 0, seed = 7)
        val d = 24
        val halfCut = scene(w, h, 0).also { out -> val other = scene(w, h, 0, seed = 9)
            for (y in 0 until h) System.arraycopy(other, y * w * 3, out, y * w * 3, w * 3 / 2) }
        // 局部转场：左上四分之一（分屏）与右下约 1/9 的画中画换成另一场景，其余静止。
        fun region(x0: Int, y0: Int, x1: Int, y1: Int) = scene(w, h, 0).also { out -> val other = scene(w, h, 0, seed = 11)
            for (y in y0 until y1) System.arraycopy(other, (y * w + x0) * 3, out, (y * w + x0) * 3, (x1 - x0) * 3) }
        val quarter = intArrayOf(0, 0, w / 2, h / 2)
        val pip = intArrayOf(w * 2 / 3, h * 2 / 3, w, h)
        val quarterCut = region(quarter[0], quarter[1], quarter[2], quarter[3])
        val pipCut = region(pip[0], pip[1], pip[2], pip[3])
        fun regionPsnr(a: ByteArray, b: ByteArray, r: IntArray): Double {
            var sum = 0.0; var count = 0
            for (y in r[1] until r[3]) for (x in r[0] * 3 until r[2] * 3) {
                val i = y * w * 3 + x; val diff = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff); sum += diff * diff; count++ }
            val mse = sum / count
            return if (mse == 0.0) 99.0 else 10 * kotlin.math.log10(255.0 * 255.0 / mse)
        }
        // name, left, right, truth (null: either original is acceptable)
        val cases = listOf(
            Triple("motion", a0 to scene(w, h, 2 * d), scene(w, h, d)),
            Triple("hard-cut", a0 to b0, null),
            Triple("dissolve", mix(a0, b0, 0.4) to mix(a0, b0, 0.6), mix(a0, b0, 0.5)),
            Triple("flash", a0 to brighten(a0, 90), brighten(a0, 45)),
            Triple("over-range", a0 to scene(w, h, 400), scene(w, h, 200)),
            Triple("half-cut", a0 to halfCut, null),
            Triple("quarter-cut", a0 to quarterCut, null),
            Triple("pip-cut", a0 to pipCut, null))
        val regions = mapOf("quarter-cut" to quarter, "pip-cut" to pip)
        val out = ByteBuffer.allocateDirect(n)
        try {
            val results = HashMap<String, Pair<Int, Double>>()
            val patchedTiles = HashMap<String, Int>()
            for (guard in listOf(false, true)) {
                engine.setMotionGuard(guard)
                for ((name, pair, truth) in cases) {
                    val left = ByteBuffer.allocateDirect(n).put(pair.first).also { it.flip() }
                    val right = ByteBuffer.allocateDirect(n).put(pair.second).also { it.flip() }
                    assertEquals(FrameInterpolationBackend.Status.OK, engine.process(left, right, out, w, h, 0.5f, flowScale = 6))
                    val permille = engine.unreliablePermille()
                    val tiles = engine.patchedTiles()
                    val output = ByteArray(n).also { out.duplicate().get(it) }
                    // 局部转场：变化区域内与最近原帧（左帧）的 PSNR；整帧与两侧原帧中较近者的 PSNR。
                    val quality = regions[name]?.let { regionPsnr(pair.first, output, it) } ?: truth?.let { psnr(it, output) }
                        ?: maxOf(psnr(pair.first, output), psnr(pair.second, output))
                    if (guard) patchedTiles[name] = tiles
                    val blend = truth?.let { psnr(it, mix(pair.first, pair.second, 0.5)) } ?: 0.0
                    if (guard) results[name] = permille to quality
                    report("GUARD ${if (guard) "on " else "off"} $name unreliable=$permille‰ tiles=$tiles psnr=%.2fdB blend=%.2fdB".format(quality, blend),
                        listOf(0.0))
                }
            }
            assertTrue("motion misjudged as transition: ${results["motion"]}",
                results.getValue("motion").first < FrameInterpolationEngineHolder.TRANSITION_PERMILLE)
            assertEquals("motion must not patch tiles", 0, patchedTiles.getValue("motion"))
            assertEquals("flash must not patch tiles", 0, patchedTiles.getValue("flash"))
            for (name in regions.keys) assertTrue("$name region not protected: ${results[name]}", results.getValue(name).second >= 40.0)
            assertTrue("hard cut not detected: ${results["hard-cut"]}",
                results.getValue("hard-cut").first >= FrameInterpolationEngineHolder.TRANSITION_PERMILLE)
        } finally { engine.close() }
    }

    /**
     * 快速运动物体（静止背景上平移的纹理方块）：对真值中间帧，在物体轨迹附近区域计 PSNR（光晕与撕裂都在这里）。
     * 比较 1/4、1/6 光流在候选向量选择关/开下的画质与耗时；开启后不得劣于关闭。
     */
    @Test fun fastObjectBoundaries() {
        val model = modelDir!!
        assumeTrue("model not pushed", File(model, "flownet.bin").isFile)
        assertEquals(FrameNativeLoader.Status.READY,
            FrameInterpolationNative.prepare(File(context.applicationInfo.sourceDir), context.codeCacheDir))
        val opened = FrameInterpolationBackend.openResearchModel(model, FrameInterpolationBackend.Backend.VULKAN, 1,
            true, 4, true, true, 2560L * 1440L, adaptiveFlow = true)
        assertEquals(FrameInterpolationBackend.Status.OK, opened.status)
        val engine = opened.engine!!
        val (w, h) = 1920 to 1080
        val n = w * h * 3
        val background = scene(w, h, 0)
        val texture = scene(w, h, 0, seed = 5)
        // 两个物体：大方块水平快移，小方块竖直快移。
        data class Obj(val size: Int, val x: Int, val y: Int, val dx: Int, val dy: Int)
        val objects = listOf(Obj(240, 500, 300, 96, 0), Obj(140, 1300, 500, 0, 72))
        fun frame(t: Double) = background.copyOf().also { out ->
            for (o in objects) {
                val ox = (o.x + o.dx * t).toInt(); val oy = (o.y + o.dy * t).toInt()
                for (y in 0 until o.size) System.arraycopy(texture, ((o.y + y) * w + o.x) * 3, out, ((oy + y) * w + ox) * 3, o.size * 3)
            }
        }
        val left = ByteBuffer.allocateDirect(n).put(frame(0.0)).also { it.flip() }
        val right = ByteBuffer.allocateDirect(n).put(frame(1.0)).also { it.flip() }
        val truth = frame(0.5)
        fun regionPsnr(out: ByteArray): Double {
            var sum = 0.0; var count = 0L
            for (o in objects) {
                val x0 = (o.x - 48).coerceAtLeast(0); val x1 = (o.x + o.dx + o.size + 48).coerceAtMost(w)
                val y0 = (o.y - 48).coerceAtLeast(0); val y1 = (o.y + o.dy + o.size + 48).coerceAtMost(h)
                for (y in y0 until y1) for (x in x0 * 3 until x1 * 3) {
                    val i = y * w * 3 + x; val d = (truth[i].toInt() and 0xff) - (out[i].toInt() and 0xff); sum += d * d; count++ }
            }
            return 10 * kotlin.math.log10(255.0 * 255.0 / (sum / count).coerceAtLeast(1e-9))
        }
        val out = ByteBuffer.allocateDirect(n)
        val results = HashMap<String, Double>()
        try {
            for (scale in listOf(4, 6, 8)) {
                val times = List(8) {
                    val began = System.nanoTime()
                    assertEquals(FrameInterpolationBackend.Status.OK, engine.process(left, right, out, w, h, 0.5f, flowScale = scale))
                    (System.nanoTime() - began) / 1e6
                }.drop(2).sorted()
                val output = ByteArray(n).also { out.duplicate().get(it) }
                val region = regionPsnr(output)
                results["$scale"] = region
                report("FAST flow-1/$scale region=%.2fdB frame=%.2fdB".format(region, psnr(truth, output)), times)
            }
            engine.setMotionGuard(false)
            assertEquals(FrameInterpolationBackend.Status.OK, engine.process(left, right, out, w, h, 0.5f, flowScale = 6))
            report("FAST flow-1/6 guard=off region=%.2fdB".format(regionPsnr(ByteArray(n).also { out.duplicate().get(it) })), listOf(0.0))
            engine.setMotionGuard(true)
            val full = FrameInterpolationBackend.openResearchModel(model, FrameInterpolationBackend.Backend.VULKAN, 1,
                true, 1, true, true, 2560L * 1440L, fusedWarp = false)
            if (full.status == FrameInterpolationBackend.Status.OK) try {
                assertEquals(FrameInterpolationBackend.Status.OK, full.engine!!.process(left, right, out, w, h, 0.5f))
                val output = ByteArray(n).also { out.duplicate().get(it) }
                report("FAST full-res flow (model reference) region=%.2fdB frame=%.2fdB".format(regionPsnr(output), psnr(truth, output)), listOf(0.0))
                // 误差位置：物体内部 vs 物体周围（遮挡/露出带）。
                var inner = 0.0; var innerN = 0L; var ring = 0.0; var ringN = 0L
                val o = objects[0]; val cx = o.x + o.dx / 2
                for (y in o.y - 40 until o.y + o.size + 40) for (x in cx - 120 - 40 until cx + 120 + 40) {
                    val inside = x in cx until cx + o.size - 0 && y in o.y until o.y + o.size
                    for (c in 0 until 3) { val i = (y * w + x) * 3 + c
                        val d = ((truth[i].toInt() and 0xff) - (output[i].toInt() and 0xff)).toDouble()
                        if (inside) { inner += d * d; innerN++ } else { ring += d * d; ringN++ } }
                }
                report("FAST full-res inner-mse=%.1f ring-mse=%.1f".format(inner / innerN, ring / ringN), listOf(0.0))
            } finally { full.engine!!.close() }
        } finally { engine.setMotionGuard(true); engine.close() }
        assertTrue("finer flow must not be worse: $results", results.getValue("4") >= results.getValue("8") - 0.5)
    }

    /**
     * 动画细线（宿主真机抓帧所见：快速平移的角色头顶呆毛在补帧中消失）：纯色背景上带 4 px 描边的圆形「头部」
     * 与其上方 4 px 粗的细线圆环一起平移 2d。对真值中间帧，在细线圆环区域与整个头部区域计 PSNR。
     */
    @Test fun thinLinesOnFlatBackground() {
        val model = modelDir!!
        assumeTrue("model not pushed", File(model, "flownet.bin").isFile)
        assertEquals(FrameNativeLoader.Status.READY,
            FrameInterpolationNative.prepare(File(context.applicationInfo.sourceDir), context.codeCacheDir))
        val opened = FrameInterpolationBackend.openResearchModel(model, FrameInterpolationBackend.Backend.VULKAN, 1,
            true, 4, true, true, 2560L * 1440L, adaptiveFlow = true)
        assertEquals(FrameInterpolationBackend.Status.OK, opened.status)
        val engine = opened.engine!!
        val (w, h) = 1920 to 1080
        val n = w * h * 3
        val cy = 620; val r = 220; val loopCy = cy - r - 40; val loopR = 34
        fun draw(cx: Int) = ByteArray(n).also { out ->
            for (y in 0 until h) for (x in 0 until w) {
                val i = (y * w + x) * 3
                val dh = kotlin.math.hypot((x - cx).toDouble(), (y - cy).toDouble())
                val dl = kotlin.math.hypot((x - cx - 10).toDouble(), (y - loopCy).toDouble())
                val rgb = when {
                    kotlin.math.abs(dl - loopR) <= 2.0 -> intArrayOf(40, 50, 90)          // 细线呆毛
                    dh <= r - 4 -> intArrayOf(70, 100, 190)                                // 头发
                    dh <= r -> intArrayOf(30, 30, 60)                                      // 描边
                    else -> intArrayOf(104, 72, 150)                                       // 纯色背景
                }
                out[i] = rgb[0].toByte(); out[i + 1] = rgb[1].toByte(); out[i + 2] = rgb[2].toByte()
            }
        }
        val start = 900; val d = 30
        val left = ByteBuffer.allocateDirect(n).put(draw(start)).also { it.flip() }
        val right = ByteBuffer.allocateDirect(n).put(draw(start + 2 * d)).also { it.flip() }
        val truth = draw(start + d)
        fun regionPsnr(out: ByteArray, x0: Int, y0: Int, x1: Int, y1: Int): Double {
            var sum = 0.0; var count = 0L
            for (y in y0 until y1) for (x in x0 * 3 until x1 * 3) {
                val i = y * w * 3 + x; val e = (truth[i].toInt() and 0xff) - (out[i].toInt() and 0xff); sum += e * e; count++ }
            return 10 * kotlin.math.log10(255.0 * 255.0 / (sum / count).coerceAtLeast(1e-9))
        }
        val loop = intArrayOf(start + 10 - loopR - 20, loopCy - loopR - 20, start + 2 * d + 10 + loopR + 20, loopCy + loopR + 20)
        val head = intArrayOf(start - r - 20, cy - r - 20, start + 2 * d + r + 20, cy + r + 20)
        val out = ByteBuffer.allocateDirect(n)
        val results = HashMap<String, Double>()
        try {
            for (scale in listOf(4, 6, 8)) {
                val times = List(6) {
                    val began = System.nanoTime()
                    assertEquals(FrameInterpolationBackend.Status.OK, engine.process(left, right, out, w, h, 0.5f, flowScale = scale))
                    (System.nanoTime() - began) / 1e6
                }.drop(2).sorted()
                val output = ByteArray(n).also { out.duplicate().get(it) }
                val loopPsnr = regionPsnr(output, loop[0], loop[1], loop[2], loop[3])
                results["$scale"] = loopPsnr
                report("THIN flow-1/$scale loop=%.2fdB head=%.2fdB".format(loopPsnr,
                    regionPsnr(output, head[0], head[1], head[2], head[3])), times)
            }
        } finally { engine.close() }
        // 细线依赖光流分辨率：1/4 必须明显优于 1/6（宿主据此在能力允许时为 1080p 选 1/4）。
        assertTrue("thin lines need finer flow: $results", results.getValue("4") >= results.getValue("6") + 1.0)
    }

    /**
     * 回放真机抓帧（files/replay/NN-a.ppm、NN-b.ppm，手动放入、不入库）：报告不可靠比例、回填分块、颜色分布差异
     * 与宿主的整帧换原帧判定，用于在真实画面上标定转场保护。无数据时跳过。
     */
    @Test fun replayCapturedPairs() {
        val dir = context.filesDir.resolve("replay")
        val pairs = dir.listFiles { f -> f.name.endsWith("-a.ppm") }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no captured pairs", pairs.isNotEmpty())
        val model = modelDir!!
        assertEquals(FrameNativeLoader.Status.READY,
            FrameInterpolationNative.prepare(File(context.applicationInfo.sourceDir), context.codeCacheDir))
        val opened = FrameInterpolationBackend.openResearchModel(model, FrameInterpolationBackend.Backend.VULKAN, 1,
            true, 4, true, true, 2560L * 1440L, adaptiveFlow = true)
        val engine = opened.engine!!
        fun read(file: File): Triple<Int, Int, ByteArray> {
            val bytes = file.readBytes()
            var offset = 0; val fields = ArrayList<String>()
            while (fields.size < 4) {
                while (bytes[offset].toInt().toChar().isWhitespace()) offset++
                val start = offset
                while (!bytes[offset].toInt().toChar().isWhitespace()) offset++
                fields += String(bytes, start, offset - start)
            }
            offset++
            return Triple(fields[1].toInt(), fields[2].toInt(), bytes.copyOfRange(offset, bytes.size))
        }
        try {
            for (a in pairs) {
                val (w, h, left) = read(a)
                val right = read(File(a.path.replace("-a.ppm", "-b.ppm"))).third
                val out = ByteBuffer.allocateDirect(w * h * 3)
                assertEquals(FrameInterpolationBackend.Status.OK, engine.process(
                    ByteBuffer.allocateDirect(left.size).put(left).also { it.flip() },
                    ByteBuffer.allocateDirect(right.size).put(right).also { it.flip() }, out, w, h, 0.5f))
                val unreliable = engine.unreliablePermille(); val histogram = engine.histogramPermille()
                val swap = unreliable >= FrameInterpolationEngineHolder.TRANSITION_PERMILLE ||
                    unreliable >= FrameInterpolationEngineHolder.CUT_PERMILLE && histogram >= FrameInterpolationEngineHolder.CUT_HISTOGRAM_PERMILLE
                report("REPLAY ${a.name.removeSuffix("-a.ppm")} ${w}x$h unreliable=$unreliable‰ tiles=${engine.patchedTiles()} " +
                    "histogram=$histogram‰ swap=$swap", listOf(0.0))
            }
        } finally { engine.close() }
    }

    /**
     * 分析屏幕录像（files/replay/rec.mp4，手动放入、不入库）：逐帧解码亮度平面，计算相邻帧的平均差异。
     * 运动画面中差异接近 0 的帧即重复帧（掉帧感）；输出每帧差异序列供定位。无录像时跳过。
     */
    @Test fun analyzeScreenRecording() {
        val file = context.filesDir.resolve("replay/rec.mp4")
        assumeTrue("no recording", file.isFile)
        val extractor = android.media.MediaExtractor().apply { setDataSource(file.path) }
        val track = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("video/") }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val decoder = android.media.MediaCodec.createDecoderByType(format.getString(android.media.MediaFormat.KEY_MIME)!!)
        decoder.configure(format, null, null, 0)
        decoder.start()
        val info = android.media.MediaCodec.BufferInfo()
        var previous: IntArray? = null
        val diffs = ArrayList<Pair<Long, Double>>()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) decoder.dequeueInputBuffer(5_000).takeIf { it >= 0 }?.let { index ->
                    val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                    if (size < 0) { decoder.queueInputBuffer(index, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                }
                val out = decoder.dequeueOutputBuffer(info, 5_000)
                if (out < 0) continue
                if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                val image = decoder.getOutputImage(out)!!
                val y = image.planes[0]
                val w = image.width; val h = image.height
                // 中央 80% 区域、每 4 像素取样，避开状态栏/水印。
                val samples = IntArray(((h * 8 / 10) / 4) * ((w * 8 / 10) / 4))
                var k = 0
                for (row in h / 10 until h * 9 / 10 step 4) for (col in w / 10 until w * 9 / 10 step 4) {
                    if (k < samples.size) samples[k++] = y.buffer.get(row * y.rowStride + col * y.pixelStride).toInt() and 0xff
                }
                image.close()
                decoder.releaseOutputBuffer(out, false)
                previous?.let { p -> diffs += info.presentationTimeUs to samples.indices.sumOf { kotlin.math.abs(samples[it] - p[it]) }.toDouble() / samples.size }
                previous = samples
            }
        } finally { decoder.stop(); decoder.release(); extractor.release() }
        val text = diffs.joinToString("\n") { (t, d) -> "%.3f %.3f".format(t / 1e6, d) }
        context.filesDir.resolve("replay/rec-diffs.txt").writeText(text)
        report("RECORDING frames=${diffs.size + 1} repeats(<0.15)=${diffs.count { it.second < 0.15 }}", listOf(0.0))
    }

    private companion object { const val TAG = "FrameBench" }
}
