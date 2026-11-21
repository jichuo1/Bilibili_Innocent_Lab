package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.ModernBackdropBlur
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.ModernMaterialPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.ModernPalette
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
import org.junit.Assert.*
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before

class ModernMaterialPolicyTest {
    fun backgroundBudgetIsBoundedForPhonesTabletsAndExtremeAspectRatios() {
        for ((width, height) in listOf(1 to 1, 1080 to 2400, 1440 to 3200, 3840 to 2160,
            1 to Int.MAX_VALUE, Int.MAX_VALUE to 1, Int.MAX_VALUE to Int.MAX_VALUE)) {
            val (w, h) = ModernMaterialPolicy.sampleSize(width, height)
            assertTrue(w > 0 && h > 0)
            assertTrue(w.toLong() * h <= ModernMaterialPolicy.MAX_BACKDROP_PIXELS)
            assertTrue(w <= width && h <= height)
        }
        assertTrue(runCatching { ModernMaterialPolicy.sampleSize(0, 100) }.isFailure)
    }

    fun trueBlurPreservesConstantImagesAndDoesNotMutateTheSource() {
        val source = IntArray(13 * 7) { 0xFF253648.toInt() }
        val before = source.copyOf()
        val output = ModernBackdropBlur.blur(source, 13, 7, 3)
        assertArrayEquals(before, output)
        assertArrayEquals(before, source)
        assertNotSame(source, output)
    }

    /**
     * 零分配热路径与原 API 必须逐位同结果（2026-09-22 性能整改）。
     *
     * `blurInto` 复用调用方缓冲、`pass` 手工内联了窗口进出——原写法用捕获可变局部变量的
     * 局部 fun，Kotlin 会把累加器装箱成 `Ref.IntRef` 挪到堆上，真机实测 102×102 采样图
     * 一次 2.0–4.6ms，是柔光皮肤 UI 线程掉帧的主因。这条用例钉住"只快不变"。
     */
    fun theZeroAllocationBlurMatchesTheReferenceBitForBit() {
        val width = 23
        val height = 17
        val source = IntArray(width * height) { i ->
            (0xFF shl 24) or ((i * 37 and 255) shl 16) or ((i * 11 and 255) shl 8) or (i * 97 and 255)
        }
        listOf(1, 3, 7, 12).forEach { radius ->
            val reference = ModernBackdropBlur.blur(source, width, height, radius)
            val working = source.copyOf()
            val scratch = IntArray(source.size)
            val fast = ModernBackdropBlur.blurInto(working, scratch, width, height, radius)
            assertArrayEquals("radius=$radius 必须逐位一致", reference, fast)
        }
        // 缓冲尺寸不匹配必须直接拒绝，而不是越界或静默算错。
        assertTrue(
            runCatching {
                ModernBackdropBlur.blurInto(IntArray(6), IntArray(5), 3, 2, 1)
            }.isFailure
        )
    }

    /** 节流间隔要落在"看不出滞后、又确实省下逐帧采样"的区间里。 */
    fun theLiveSampleIntervalStaysInTheImperceptibleRange() {
        assertTrue(ModernMaterialPolicy.LIVE_SAMPLE_MIN_INTERVAL_MS in 16L..48L)
    }

    private fun source(relative: String): String {
        val candidates = sequenceOf(
            java.io.File("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/$relative"),
            java.io.File("app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/$relative")
        )
        return candidates.firstOrNull(java.io.File::isFile)?.readText()
            ?: error("cannot locate $relative from ${java.io.File(".").absolutePath}")
    }

    /**
     * 显式动画期**不得冻结**透镜采样（2026-09-23 用户报告）。
     *
     * 曾为躲 UI 线程尖刺在手风琴动画期整段不采、静默 96ms 后补采——用户看到的是顶栏/
     * 底栏透出的背景等动画播完才瞬间跳变。位移通知必须与滚动走同一条"设脏"路径。
     */
    fun explicitMotionKeepsTheLensLiveInsteadOfFreezingIt() {
        val sampler = source("ui/skin/material/LiveBackdropSampler.kt")
        assertFalse("不得再有动画期静默窗口", sampler.contains("motionUntilNanos"))
        assertFalse("不得再有单独的动画期冻结入口", sampler.contains("fun invalidateForMotion"))
        val renderer = source("ui/skin/material/FrostedMaterialRenderer.kt")
        val onPosition = renderer.after("private fun onPositionChanged(")
            .substringBefore("fun releaseMemory()", "MISSING")
        assertTrue(onPosition != "MISSING")
        assertTrue("显式动画与滚动都只是把透镜设脏", onPosition.contains("live.invalidate()"))
        assertTrue("透镜采集的离屏软件回放不得覆盖'上次硬件绘制位置'（滚动通知在 pre-draw 之后才发）",
            renderer.contains("if (view != null && canvas.isHardwareAccelerated) renderer?.register(view)"))
    }

    /**
     * 所有逐像素工作必须留在后台线程（2026-09-23 真机打点）。
     *
     * 一次采样处理 5 个表面，模糊 360–900µs、透镜重映射 250–1060µs，合计 7–10.7ms 压在
     * UI 线程——这就是当初想用冻结躲开的尖刺。挪走它们后底栏仍剩 3–4.4ms 的软件光栅化
     * （5 层半透明卡片的填充率），所以主线程只允许**录制**绘制指令，光栅化也在后台回放。
     */
    fun perPixelLensWorkNeverRunsOnTheUiThread() {
        val sampler = source("ui/skin/material/LiveBackdropSampler.kt")
        val capture = sampler.after("private fun capture(")
            .substringBefore("private fun onBatchDone(", "MISSING")
        assertTrue(capture != "MISSING")
        assertTrue("主线程只录制内容层的绘制指令", capture.contains("picture.beginRecording("))
        for (heavy in listOf("eraseColor", "drawPicture", "getPixels", "blurInto",
            "LensRefractionPolicy.remap", "premultiply", "illuminate")) {
            assertFalse("主线程采集阶段不得调用 $heavy", capture.contains(heavy))
        }
        val process = sampler.after("fun process(job: LensJob)")
            .substringBefore("fun close()", "MISSING")
        assertTrue(process != "MISSING")
        assertTrue("后台作业必须标注 @WorkerThread",
            sampler.before("fun process(job: LensJob)").trimEnd().endsWith("@WorkerThread"))
        for (heavy in listOf("drawPicture", "getPixels", "blurInto", "LensRefractionPolicy.remap",
            "premultiply", "illuminate", "unpremultiply")) {
            assertTrue("后台作业必须包含 $heavy", process.contains(heavy))
        }
        val release = sampler.after("fun release()").substringBefore("sampleWidth = 0", "MISSING")
        assertTrue(release != "MISSING")
        assertFalse("后台可能正往 sample 里回放：释放时 recycle 会立刻释放原生像素，就是释放后使用",
            release.contains("sample?.recycle()"))
        assertTrue("同一时刻至多一批在飞，在飞期间主线程不碰作业缓冲",
            sampler.contains("if (inFlight) return"))
        val done = sampler.after("private fun onBatchDone(")
            .substringBefore("private fun fail()", "MISSING")
        assertTrue(done != "MISSING")
        assertTrue("过期批次（释放/关闭后回来的）必须整批丢弃",
            done.contains("token != batchToken"))
        assertTrue("缓冲已重建的单个结果必须丢弃",
            done.contains("entry.generation != job.generation"))
    }

    fun blurActuallySpreadsAnImpulseAndReducesHighFrequencyContrast() {
        val source = IntArray(25 * 25) { 0xFF000000.toInt() }
        source[12 * 25 + 12] = 0xFFFFFFFF.toInt()
        val output = ModernBackdropBlur.blur(source, 25, 25, 2)
        assertTrue((output[12 * 25 + 12] and 255) in 1..254)
        assertTrue((output[12 * 25 + 13] and 255) > 0)
        assertEquals(0xFF000000.toInt(), output[0])
        assertTrue(output.all { it ushr 24 == 255 })
        val stripes = IntArray(21 * 11) { if (it % 21 % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val soft = ModernBackdropBlur.blur(stripes, 21, 11, 3)
        val centerRow = (5 * 21 + 6..5 * 21 + 14).map { soft[it] and 255 }
        assertTrue(centerRow.max() - centerRow.min() < 32)
    }

    fun degenerateBlurIsSafeAndInvalidBuffersAreRejected() {
        val pixel = intArrayOf(0xFF998877.toInt())
        assertArrayEquals(pixel, ModernBackdropBlur.blur(pixel, 1, 1, 18))
        assertTrue(runCatching { ModernBackdropBlur.blur(pixel, 2, 1, 2) }.isFailure)
        assertTrue(runCatching { ModernBackdropBlur.blur(pixel, 1, 1, 0) }.isFailure)
    }

    fun surfacesAreNeutralWhileAccentAndSemanticColorsArePreserved() {
        val accents = MonetColors(0xFFAA22EE.toInt(), -1, 0xFF008866.toInt(), 0xFFAA6600.toInt(), 1, 2, 3)
        for (dark in listOf(false, true)) {
            val palette = ModernPalette.from(accents, dark)
            assertEquals(accents.primary, palette.primary)
            assertEquals(accents.secondary, palette.secondary)
            assertEquals(accents.tertiary, palette.tertiary)
            assertEquals(accents.onPrimary, palette.onPrimary)
            for (color in listOf(palette.surface, palette.surfaceVariant, palette.background)) {
                val channels = listOf((color ushr 16) and 255, (color ushr 8) and 255, color and 255)
                assertTrue(channels.max() - channels.min() <= 8)
                assertTrue(if (dark) channels.max() < 52 else channels.min() >= 240)
            }
        }
    }

    fun roleHierarchyKeepsModalsReadableAndFloatingBarsLighterThanCards() {
        for (dark in listOf(false, true)) {
            val card = ModernMaterialPolicy.surface(SurfaceRole.CARD, dark)
            val modal = ModernMaterialPolicy.surface(SurfaceRole.MODAL, dark)
            val floating = ModernMaterialPolicy.surface(SurfaceRole.FLOATING, dark)
            assertTrue(modal.tintAlpha > card.tintAlpha)
            assertTrue(floating.tintAlpha < card.tintAlpha)
            SurfaceRole.entries.forEach { role ->
                val style = ModernMaterialPolicy.surface(role, dark)
                assertTrue(style.tintAlpha in 1..255)
                if (role == SurfaceRole.TOP_BAR) {
                    assertEquals(0, style.upperEdgeAlpha)
                    assertEquals(0, style.lowerEdgeAlpha)
                } else assertTrue(style.upperEdgeAlpha > style.lowerEdgeAlpha)
            }
        }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (ModernMaterialPolicyTest) -> Unit) {
            val s = ModernMaterialPolicyTest()
            block(s)
        }
        runIsolated { s -> s.backgroundBudgetIsBoundedForPhonesTabletsAndExtremeAspectRatios() }
        runIsolated { s -> s.trueBlurPreservesConstantImagesAndDoesNotMutateTheSource() }
        runIsolated { s -> s.theZeroAllocationBlurMatchesTheReferenceBitForBit() }
        runIsolated { s -> s.theLiveSampleIntervalStaysInTheImperceptibleRange() }
        runIsolated { s -> s.explicitMotionKeepsTheLensLiveInsteadOfFreezingIt() }
        runIsolated { s -> s.perPixelLensWorkNeverRunsOnTheUiThread() }
        runIsolated { s -> s.blurActuallySpreadsAnImpulseAndReducesHighFrequencyContrast() }
        runIsolated { s -> s.degenerateBlurIsSafeAndInvalidBuffersAreRejected() }
        runIsolated { s -> s.surfacesAreNeutralWhileAccentAndSemanticColorsArePreserved() }
        runIsolated { s -> s.roleHierarchyKeepsModalsReadableAndFloatingBarsLighterThanCards() }
    }
}
